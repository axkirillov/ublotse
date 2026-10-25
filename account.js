/**
 * UBLotse — "Mein Konto": the loans of the signed-in library account.
 *
 * The SRU index carries no per-user data, so this module talks to the
 * catalogue's VuFind account instead: it fetches the login form, posts the
 * library card number and password, keeps the session cookie in memory and
 * parses the "Checked Out" table into JSON.
 *
 * Credentials are never stored. The session lives per client id and is
 * dropped after 30 idle minutes or when the client logs out.
 */

const IDLE_MS = 30 * 60 * 1000;
const MAX_HOPS = 6;

function decodeEntities(input) {
  return String(input)
    .replace(/&#x([0-9a-f]+);/gi, (_, hex) => String.fromCodePoint(parseInt(hex, 16)))
    .replace(/&#(\d+);/g, (_, num) => String.fromCodePoint(Number(num)))
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'");
}

function textOf(html) {
  return decodeEntities(
    String(html)
      .replace(/<br\s*\/?>/gi, ' ')
      .replace(/<\/(p|div|li|td|th)>/gi, ' ')
      .replace(/<[^>]*>/g, ' '),
  )
    .replace(/\s+/g, ' ')
    .trim();
}

function germanToIso(value) {
  const iso = /\b(\d{4})-(\d{2})-(\d{2})\b/.exec(String(value));
  if (iso) return iso[1] + '-' + iso[2] + '-' + iso[3];
  const match = /(\d{1,2})\.(\d{1,2})\.(\d{4})/.exec(String(value));
  if (!match) return null;
  const [, day, month, year] = match;
  return year + '-' + month.padStart(2, '0') + '-' + day.padStart(2, '0');
}

function firstDate(text) {
  const match = /\b\d{1,2}\.\d{1,2}\.\d{4}\b|\b\d{4}-\d{2}-\d{2}\b/.exec(String(text));
  return match ? match[0] : null;
}

/**
 * The renew column, in the two shapes VuFind has used: a link the browser can follow
 * (`renewMyItemsLink` drivers) or a hidden form value the checked-out page posts back
 * (`getRenewDetails` drivers, which is what this catalogue does).
 */
const RENEW_LINK_RE = /<a\b[^>]*href="([^"]*(?:renew|verlaengern|verl(?:ä|&auml;)ngern)[^"]*)"[^>]*>/i;
const RENEW_FORM_ID_RE =
  /<input\b(?=[^>]*name="(?:renewAllIDS|renewSelectedIDS|selectAllIDS)\[\]")(?=[^>]*value="([^"]*)")[^>]*>/i;
const RENEW_ID_LABEL_RE =
  /(?:R(?:ü|&uuml;|ue)ckgabedatum|Due Date|F(?:ä|&auml;|ae)llig(?: am)?|Leihfrist|Frist)\s*:?\s*(?:<[^>]*>\s*)*/i;

/** Split a row into cells, keeping the raw HTML of each. */
function cellsOf(rowHtml) {
  const cells = [];
  const re = /<(t[dh])\b[^>]*>([\s\S]*?)<\/\1>/gi;
  let match;
  while ((match = re.exec(rowHtml))) {
    cells.push({ header: match[1].toLowerCase() === 'th', html: match[2], text: textOf(match[2]) });
  }
  return cells;
}

function headerIndex(headers, pattern) {
  const index = headers.findIndex((cell) => pattern.test(cell.text));
  return index >= 0 ? index : -1;
}

/**
 * VuFind 10 renders the checked-out items as a list of records, not a table:
 *
 *   <ul class="record-list">
 *     <li id="record0-123" class="result">
 *       <div class="media-body">
 *         <h2><a href="/Record/0-123" class="title">Lolita : Roman</a>
 *             <span class="authors">von: <a href="/Author/…">Nabokov, Vladimir</a></span></h2>
 *         <strong>Rückgabedatum: 12.11.2026</strong>
 *         <input type="hidden" name="renewAllIDS[]" value="…">
 *
 * The due date has no column to be read from, so it is found by its label and
 * falls back to the first date in the item. Titles without a Solr record come
 * as a <span class="title"> instead of a link.
 */
function parseRecordList(html) {
  const source = String(html);
  const starts = [];
  const openRe = /<li\b([^>]*)>/gi;
  let open;
  while ((open = openRe.exec(source))) {
    if (!/class="[^"]*\bresult\b[^"]*"/i.test(open[1])) continue;
    starts.push({ from: openRe.lastIndex, at: open.index });
  }
  if (!starts.length) return [];

  const loans = [];
  for (let i = 0; i < starts.length; i += 1) {
    const end = i + 1 < starts.length ? starts[i + 1].at : source.length;
    const item = source.slice(starts[i].from, end);

    const anchor = /<a\b[^>]*href="([^"]*\/Record\/[^"]*)"[^>]*>([\s\S]*?)<\/a>/i.exec(item);
    const span = /<span\b[^>]*class="[^"]*\btitle\b[^"]*"[^>]*>([\s\S]*?)<\/span>/i.exec(item);
    const title = anchor ? textOf(anchor[2]) : span ? textOf(span[1]) : '';
    if (!title) continue;

    const recordHref = anchor ? decodeEntities(anchor[1]) : null;
    const recordId = recordHref ? (/(?:\/Record\/)([^?"'/]+)/.exec(recordHref) || [])[1] || null : null;

    const authors = /<span\b[^>]*class="[^"]*\bauthors\b[^"]*"[^>]*>([\s\S]*?)<\/span>/i.exec(item);
    const authorAnchor = authors ? /<a\b[^>]*>([\s\S]*?)<\/a>/i.exec(authors[1]) : null;
    const author = authorAnchor ? textOf(authorAnchor[1]) : authors ? textOf(authors[1]).replace(/^\S+:\s*/, '') : '';

    const label = RENEW_ID_LABEL_RE.exec(item);
    const due = (label ? firstDate(item.slice(label.index + label[0].length, label.index + label[0].length + 60)) : null) || firstDate(item);

    const renewLink = RENEW_LINK_RE.exec(item);
    const renewForm = RENEW_FORM_ID_RE.exec(item);

    loans.push({
      title,
      author,
      due,
      dueISO: germanToIso(due || ''),
      borrowed: null,
      recordId,
      recordHref,
      renewHref: renewLink ? decodeEntities(renewLink[1]) : null,
      renewId: renewForm ? decodeEntities(renewForm[1]) : null,
    });
  }

  return loans;
}

/**
 * Parse the VuFind "Checked Out" page. Older themes render one table with a
 * title link per row and date columns; header names differ between VuFind
 * versions, so the column lookup accepts English and German labels and falls
 * back to the first date found in the row.
 */
function parseLoans(html) {
  const listed = parseRecordList(html);
  if (listed.length) return listed;

  const tables = String(html).match(/<table\b[\s\S]*?<\/table>/gi) || [];
  const table =
    tables.find((candidate) => /f(?:ä|&auml;|ae)llig|due date|ausgeliehen|checked out|r(?:ü|&uuml;|ue)ckgabe/i.test(candidate)) ||
    tables.find((candidate) => /\/Record\//.test(candidate));

  if (!table) return [];

  const rows = table.match(/<tr\b[\s\S]*?<\/tr>/gi) || [];
  const rowsOut = [];
  let headers = [];

  for (const row of rows) {
    const cells = cellsOf(row);
    if (!cells.length) continue;
    if (cells.every((cell) => cell.header)) {
      headers = cells;
      continue;
    }
    if (cells.every((cell) => !cell.text)) continue;

    const anchor = /<a\b[^>]*href="([^"]*\/Record\/[^"]*)"[^>]*>([\s\S]*?)<\/a>/i.exec(row);
    const title = anchor ? textOf(anchor[2]) : cells.find((cell) => cell.text && !firstDate(cell.text))?.text || '';
    if (!title) continue;

    const recordHref = anchor ? decodeEntities(anchor[1]) : null;
    const recordId = recordHref ? (/(?:\/Record\/)([^?"'/]+)/.exec(recordHref) || [])[1] || null : null;

    const dueIndex = headerIndex(headers, /f(?:ä|&auml;|ae)llig|due|r(?:ü|&uuml;|ue)ckgabe/i);
    const borrowedIndex = headerIndex(headers, /ausgeliehen|borrowed|seit/i);

    let due = dueIndex >= 0 ? firstDate(cells[dueIndex]?.text) : null;
    let borrowed = borrowedIndex >= 0 ? firstDate(cells[borrowedIndex]?.text) : null;

    const dates = cells.map((cell) => firstDate(cell.text)).filter(Boolean);
    if (!due) due = dates[0] || null;
    if (!borrowed) borrowed = dates.find((value) => value !== due) || null;

    const renewMatch = RENEW_LINK_RE.exec(row);
    const renewHref = renewMatch ? decodeEntities(renewMatch[1]) : null;
    const renewForm = RENEW_FORM_ID_RE.exec(row);

    const author = cells
      .slice(1)
      .map((cell) => cell.text)
      .find((text) => text && !firstDate(text) && text.length > 2 && text !== title) || '';

    rowsOut.push({
      title,
      author,
      due,
      dueISO: germanToIso(due || ''),
      borrowed,
      recordId,
      recordHref,
      renewHref,
      renewId: renewForm ? decodeEntities(renewForm[1]) : null,
    });
  }

  return rowsOut;
}

const DATE_TEXT_RE = /\b\d{1,2}\.\d{1,2}\.\d{4}\b|\b\d{4}-\d{2}-\d{2}\b/;
const DATE_COUNT_RE = /\b\d{1,2}\.\d{1,2}\.\d{4}\b|\b\d{4}-\d{2}-\d{2}\b/g;
const VOID_ELEMENTS = new Set([
  'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source',
  'track', 'wbr',
]);

function countMatches(pattern, text) {
  const matches = String(text).match(pattern);
  return matches ? matches.length : 0;
}

function countAll(text, needle) {
  let total = 0;
  let at = String(text).indexOf(needle);
  while (at >= 0) {
    total += 1;
    at = String(text).indexOf(needle, at + needle.length);
  }
  return total;
}

function stripScripts(html) {
  return String(html)
    .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, ' ');
}

function abbreviate(text, limit) {
  const value = String(text == null ? '' : text).trim();
  return value.length <= limit ? value : value.slice(0, limit - 1) + '\u2026';
}

function classSuffix(attributes) {
  const match = /class="([^"]*)"/i.exec(String(attributes || ''));
  if (!match) return '';
  let suffix = '';
  for (const token of match[1].trim().split(/\s+/)) {
    if (!token || token.startsWith('checkbox_') || token.length > 24) continue;
    suffix += '.' + token;
  }
  return suffix;
}

function joinPath(stack, window) {
  return stack.slice(-window).join('>');
}

function elementName(element) {
  const dot = element.indexOf('.');
  return dot < 0 ? element : element.slice(0, dot);
}

/**
 * Walk the tags once, keeping a stack of open elements, and report where the dates and the record
 * links sit. This is the whole point of the diagnostic: no template can be assumed, so the report
 * says which element the due date is inside of and which element holds the record link.
 *
 * A stray closing tag pops at most itself, and void elements never stack — this describes where
 * something sits, it is not a DOM.
 */
function scan(html, dateLimit, linkLimit) {
  const dates = [];
  const links = [];
  const stack = [];
  const seenDates = new Set();
  const seenLinks = new Set();
  const page = String(html == null ? '' : html);
  const tagRe = /<(\/?)([a-zA-Z][a-zA-Z0-9-]*)([^>]*)>/g;
  let at = 0;
  let tag;
  while ((tag = tagRe.exec(page))) {
    const text = page.slice(at, tag.index);
    at = tagRe.lastIndex;

    if (text && dates.length < dateLimit && DATE_TEXT_RE.test(text) && stack.length) {
      const path = joinPath(stack, 4);
      if (!seenDates.has(path)) {
        seenDates.add(path);
        dates.push({ path, text: abbreviate(text, 70) });
      }
    }

    const name = (tag[2] || '').toLowerCase();
    if (!name) continue;
    const closing = Boolean(tag[1]);

    if (!closing && name === 'a' && links.length < linkLimit && /href="[^"]*\/Record\//i.test(tag[3])) {
      const close = page.indexOf('</a', at);
      const anchor = close > at ? page.slice(at, close) : '';
      const path = joinPath(stack, 3) + '>a';
      if (!seenLinks.has(path)) {
        seenLinks.add(path);
        links.push({ path, text: abbreviate(anchor, 70) });
      }
    }

    if (closing) {
      for (let i = stack.length - 1; i >= 0; i -= 1) {
        if (elementName(stack[i]) === name) {
          stack.length = i;
          break;
        }
      }
    } else if (!VOID_ELEMENTS.has(name)) {
      stack.push(name + classSuffix(tag[3]));
    }
  }
  return { dates, links };
}

/** One page's worth of the diagnostic report, character-for-character Account.java's. */
function pageReport(path, status, html) {
  const page = String(html == null ? '' : html);
  const lines = [];
  lines.push('=== ' + path + ' ===');
  lines.push(
    'seite    ' +
      status +
      ', ' +
      page.length +
      ' B, logout=' +
      (/\/MyResearch\/Logout/.test(page) ? 'ja' : 'nein') +
      ', ausleihen=' +
      parseLoans(page).length,
  );
  lines.push(
    'zaehler  tables=' +
      countMatches(/<table\b[\s\S]*?<\/table>/gi, page) +
      ', li.result=' +
      countMatches(/<li\b[^>]*class="[^"]*\bresult\b[^"]*"[^>]*>/gi, page) +
      ', /Record/=' +
      countAll(page, '/Record/') +
      ', daten=' +
      countMatches(DATE_COUNT_RE, page),
  );
  lines.push('text     ' + abbreviate(textOf(stripScripts(page)), 400));
  const found = scan(page, 4, 3);
  for (const one of found.dates) lines.push('datum    ' + one.path + '  "' + one.text + '"');
  for (const one of found.links) lines.push('link     ' + one.path + '  "' + one.text + '"');
  return lines.join('\n') + '\n';
}

function extractAlert(html) {
  const match = /class="[^"]*(?:alert|error|fehler)[^"]*"[^>]*>([\s\S]{0,600}?)<\//i.exec(String(html));
  const text = match ? textOf(match[1]) : '';
  return text && text.length > 3 ? text : '';
}

export function createAccount({ catalogueBase, sendJson, readJsonBody, clientIdOf }) {
  const origin = new URL(catalogueBase).origin;
  const sessions = new Map();

  function sessionFor(clientId) {
    const now = Date.now();
    for (const [id, entry] of sessions) {
      if (now - entry.touchedAt > IDLE_MS) sessions.delete(id);
    }
    let session = sessions.get(clientId);
    if (!session) {
      session = { cookies: new Map(), loggedIn: false, username: '', touchedAt: now };
      sessions.set(clientId, session);
    }
    session.touchedAt = now;
    return session;
  }

  function absorbCookies(session, response) {
    const lines = typeof response.headers.getSetCookie === 'function' ? response.headers.getSetCookie() : [];
    for (const line of lines) {
      const pair = String(line).split(';')[0];
      const eq = pair.indexOf('=');
      if (eq < 1) continue;
      const name = pair.slice(0, eq).trim();
      const value = pair.slice(eq + 1).trim();
      if (!value || /expires=Thu, 01 Jan 1970/i.test(line)) session.cookies.delete(name);
      else session.cookies.set(name, value);
    }
  }

  function cookieHeader(session) {
    const pairs = [...session.cookies].map(([name, value]) => name + '=' + value);
    return ['finc_open=1', ...pairs].join('; ');
  }

  /** Fetch a catalogue page, following redirects by hand so cookies survive. */
  async function request(session, pathOrUrl, options = {}) {
    let target = pathOrUrl.startsWith('http') ? pathOrUrl : catalogueBase.replace(/\/$/, '') + pathOrUrl;
    let method = options.method || 'GET';
    let body = undefined;
    if (options.form) {
      body = typeof options.form === 'string' ? options.form : new URLSearchParams(options.form).toString();
    }

    for (let hop = 0; hop < MAX_HOPS; hop += 1) {
      const response = await fetch(target, {
        method,
        body,
        redirect: 'manual',
        headers: {
          Cookie: cookieHeader(session),
          'User-Agent': 'UBLotse/0.2 (library catalogue frontend)',
          Accept: 'text/html,application/xhtml+xml',
          ...(body ? { 'Content-Type': 'application/x-www-form-urlencoded' } : {}),
          ...(options.headers || {}),
        },
      });
      absorbCookies(session, response);

      if ([301, 302, 303, 307, 308].includes(response.status)) {
        const location = response.headers.get('location');
        if (!location) return { status: response.status, url: target, html: '' };
        target = new URL(location, target).toString();
        if (![307, 308].includes(response.status)) {
          method = 'GET';
          body = undefined;
        }
        continue;
      }

      return { status: response.status, url: target, html: await response.text() };
    }

    throw new Error('Zu viele Weiterleitungen beim Katalog.');
  }

  function safeRenewTarget(href) {
    try {
      const target = new URL(href, catalogueBase);
      if (target.origin !== origin) return null;
      if (!target.pathname.startsWith('/MyResearch/')) return null;
      return target.toString();
    } catch {
      return null;
    }
  }

  async function loadLoans(session) {
    const page = await request(session, '/MyResearch/CheckedOut');
    if (/\/MyResearch\/UserLogin|name="loginForm"/.test(page.html) && !page.html.includes('/MyResearch/Logout')) {
      session.loggedIn = false;
      const error = new Error('Sitzung abgelaufen. Bitte erneut anmelden.');
      error.code = 'SESSION_EXPIRED';
      throw error;
    }
    return { items: parseLoans(page.html), fetchedAt: new Date().toISOString() };
  }

  async function handleLogin(req, res, clientId) {
    const session = sessionFor(clientId);
    const body = await readJsonBody(req);
    const username = String(body?.username || '').trim();
    const password = String(body?.password || '');
    if (!username || !password) {
      sendJson(res, 400, { error: 'Ausweisnummer und Passwort erforderlich.', loggedIn: false });
      return;
    }

    const home = await request(session, '/MyResearch/Home');
    const csrf = /name="csrf"\s+value="([^"]+)"/i.exec(home.html) || /value="([^"]+)"\s+name="csrf"/i.exec(home.html);
    if (!csrf) {
      sendJson(res, 502, { error: 'Das Anmeldeformular des Katalogs war nicht lesbar.', loggedIn: false });
      return;
    }

    const result = await request(session, '/MyResearch/Home', {
      method: 'POST',
      form: {
        username,
        password,
        auth_method: 'ILS',
        csrf: csrf[1],
        processLogin: 'Mein Konto',
      },
    });

    const loggedIn = /\/MyResearch\/Logout/.test(result.html);
    session.loggedIn = loggedIn;
    session.username = loggedIn ? username : '';

    if (!loggedIn) {
      const message = extractAlert(result.html) || 'Anmeldung fehlgeschlagen. Ausweisnummer oder Passwort prüfen.';
      sendJson(res, 401, { error: message, loggedIn: false });
      return;
    }

    const loans = await loadLoans(session);
    sendJson(res, 200, { loggedIn: true, username: session.username, ...loans });
  }

  async function handle(url, req, res) {
    const pathname = url.pathname;
    const clientId = clientIdOf(url, req);
    const session = sessionFor(clientId);

    if (pathname === '/api/account' && req.method === 'GET') {
      sendJson(res, 200, { loggedIn: Boolean(session.loggedIn), username: session.username || null });
      return;
    }

    if (pathname === '/api/account/login' && req.method === 'POST') {
      await handleLogin(req, res, clientId);
      return;
    }

    if (pathname === '/api/account/logout' && req.method === 'POST') {
      try {
        if (session.loggedIn || session.cookies.size) await request(session, '/MyResearch/Logout');
      } catch {
        // A failed upstream logout must not keep the local session alive.
      }
      sessions.delete(clientId);
      sendJson(res, 200, { loggedIn: false });
      return;
    }

    if (pathname === '/api/account/loans' && req.method === 'GET') {
      if (!session.loggedIn) {
        sendJson(res, 401, { error: 'Nicht angemeldet.', loggedIn: false });
        return;
      }
      try {
        const loans = await loadLoans(session);
        sendJson(res, 200, { loggedIn: true, username: session.username, ...loans });
      } catch (error) {
        sendJson(res, error.code === 'SESSION_EXPIRED' ? 401 : 502, {
          error: error.message,
          loggedIn: error.code !== 'SESSION_EXPIRED',
        });
      }
      return;
    }

    if (pathname === '/api/account/diagnose' && req.method === 'GET') {
      if (!session.loggedIn) {
        sendJson(res, 401, { error: 'Nicht angemeldet.', loggedIn: false });
        return;
      }
      let report = 'Konto-Diagnose\n';
      for (const pagePath of ['/MyResearch/CheckedOut', '/MyResearch/Home']) {
        try {
          const page = await request(session, pagePath);
          report += '\n' + pageReport(pagePath, page.status, page.html);
        } catch (error) {
          report += '\n=== ' + pagePath + ' ===\nfehler  ' + error.message + '\n';
        }
      }
      sendJson(res, 200, { report });
      return;
    }

    if (pathname === '/api/account/renew' && req.method === 'POST') {
      if (!session.loggedIn) {
        sendJson(res, 401, { error: 'Nicht angemeldet.', loggedIn: false });
        return;
      }
      const body = await readJsonBody(req);
      const renewId = String(body?.renewId || '').slice(0, 400);
      let renewed = false;
      let message = null;

      if (renewId) {
        // The common case: the checked-out page carries the item's renewal id and the
        // csrf hash its own form needs, so the renewal is a post back to that page.
        const page = await request(session, '/MyResearch/CheckedOut');
        const csrf = /name="csrf"\s+value="([^"]+)"/i.exec(page.html) || /value="([^"]+)"\s+name="csrf"/i.exec(page.html);
        if (!csrf) {
          sendJson(res, 502, { error: 'Das Verlängerungsformular des Katalogs war nicht lesbar.' });
          return;
        }
        const form = new URLSearchParams();
        form.set('csrf', csrf[1]);
        form.set('renewSelected', '1');
        form.append('renewSelectedIDS[]', renewId);
        form.append('selectAllIDS[]', renewId);
        const result = await request(session, '/MyResearch/CheckedOut', { method: 'POST', form: form.toString() });
        renewed = /class="[^"]*alert-success/i.test(result.html);
        const flash = /<div class="[^"]*alert[^"]*"[^>]*>([\s\S]{0,400}?)<\/div>/i.exec(result.html);
        message = renewed ? (flash ? textOf(flash[1]) : 'Ausleihe verlängert.') : null;
      } else {
        const target = safeRenewTarget(String(body?.href || ''));
        if (!target) {
          sendJson(res, 400, { error: 'Ungültiger Verlängerungslink.' });
          return;
        }
        const result = await request(session, target);
        renewed = /verl(?:ä|&auml;)ngert|renewed|success/i.test(result.html);
        message = renewed ? 'Ausleihe verlängert.' : null;
      }

      const loans = await loadLoans(session);
      sendJson(res, 200, { renewed, message, ...loans });
      return;
    }

    sendJson(res, 404, { error: 'Unbekannter Konto-Endpunkt.' });
  }

  return { handle };
}

export { parseLoans, germanToIso, pageReport };
