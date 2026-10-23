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
  const match = /(\d{1,2})\.(\d{1,2})\.(\d{4})/.exec(String(value));
  if (!match) return null;
  const [, day, month, year] = match;
  return year + '-' + month.padStart(2, '0') + '-' + day.padStart(2, '0');
}

function firstDate(text) {
  const match = /\b\d{1,2}\.\d{1,2}\.\d{4}\b/.exec(String(text));
  return match ? match[0] : null;
}

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
 * Parse the VuFind "Checked Out" page. The theme renders one table with a
 * title link per row and date columns; header names differ between VuFind
 * versions, so the column lookup accepts English and German labels and falls
 * back to the first date found in the row.
 */
function parseLoans(html) {
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

    const renewMatch = /<a\b[^>]*href="([^"]*(?:renew|Renew|verlaengern|verl(?:ä|&auml;)ngern)[^"]*)"[^>]*>/i.exec(row);
    const renewHref = renewMatch ? decodeEntities(renewMatch[1]) : null;

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
    });
  }

  return rowsOut;
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
    let body = options.form ? new URLSearchParams(options.form).toString() : undefined;

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

    if (pathname === '/api/account/renew' && req.method === 'POST') {
      if (!session.loggedIn) {
        sendJson(res, 401, { error: 'Nicht angemeldet.', loggedIn: false });
        return;
      }
      const body = await readJsonBody(req);
      const target = safeRenewTarget(String(body?.href || ''));
      if (!target) {
        sendJson(res, 400, { error: 'Ungültiger Verlängerungslink.' });
        return;
      }
      const result = await request(session, target);
      const renewed = /verl(?:ä|&auml;)ngert|renewed|success/i.test(result.html);
      const loans = await loadLoans(session);
      sendJson(res, 200, { renewed, message: renewed ? 'Ausleihe verlängert.' : null, ...loans });
      return;
    }

    sendJson(res, 404, { error: 'Unbekannter Konto-Endpunkt.' });
  }

  return { handle };
}

export { parseLoans, germanToIso };
