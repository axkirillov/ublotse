/**
 * UBLotse — frontend.
 *
 * Plain ES modules, no framework. Talks only to the local /api/search proxy,
 * which in turn talks to the finc SRU endpoint. Results are fetched page by
 * page; facets and sorting are computed in the browser over what is loaded.
 */

const els = {
  form: document.getElementById('search-form'),
  query: document.getElementById('query'),
  index: document.getElementById('index'),
  clear: document.getElementById('clear'),
  results: document.getElementById('results'),
  resultbar: document.getElementById('resultbar'),
  facets: document.getElementById('facets'),
  more: document.getElementById('more'),
  toast: document.getElementById('toast'),
  dialog: document.getElementById('cite-dialog'),
  citeTitle: document.getElementById('cite-title'),
  citeCitation: document.getElementById('cite-citation'),
  citeBibtex: document.getElementById('cite-bibtex'),
  themeToggle: document.getElementById('theme-toggle'),
  wishToggle: document.getElementById('wishlist-toggle'),
  accountToggle: document.getElementById('account-toggle'),
  wishCount: document.getElementById('wishlist-count'),
  update: document.getElementById('app-update'),
  appVersion: document.getElementById('app-version'),
  updateCheck: document.getElementById('update-check'),
};

// ------------------------------------------------------------------- theme

const THEME_KEY = 'ublotse:theme';
const darkQuery = window.matchMedia('(prefers-color-scheme: dark)');
// Must agree with the facet breakpoint in style.css.
const narrowQuery = window.matchMedia('(max-width: 860px)');

/** The chosen mode, or whatever the operating system prefers. */
function effectiveTheme() {
  return document.documentElement.dataset.theme || (darkQuery.matches ? 'dark' : 'light');
}

function applyTheme(theme) {
  if (theme) document.documentElement.dataset.theme = theme;
  else delete document.documentElement.dataset.theme;

  const current = effectiveTheme();
  els.themeToggle.setAttribute('aria-pressed', String(current === 'dark'));
  els.themeToggle.title =
    current === 'dark' ? 'Zum hellen Farbmodus wechseln' : 'Zum dunklen Farbmodus wechseln';
}

// ------------------------------------------------------------------- icons

// Line icons drawn from primitives, in the style of the catalogue's own set.
const ICON_ATTRS =
  'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"';

const ICONS = {
  book: '<path d="M6.5 4.5A1.5 1.5 0 0 1 8 3h9a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H8a1.5 1.5 0 0 1-1.5-1.5z"/><path d="M9.5 3v18"/>',
  document:
    '<path d="M6.5 3.5h7l4 4v13h-11z"/><path d="M13.5 3.5v4h4"/><path d="M9.5 12h5M9.5 15h5M9.5 18h3"/>',
  globe:
    '<circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17"/><path d="M12 3.5c2.2 2.4 3.3 5.4 3.3 8.5s-1.1 6.1-3.3 8.5c-2.2-2.4-3.3-5.4-3.3-8.5S9.8 5.9 12 3.5z"/>',
  journal: '<rect x="4.5" y="4.5" width="15" height="15" rx="1"/><path d="M9 4.5v15M4.5 9h15"/>',
  thesis:
    '<path d="M12 4.5 21 9l-9 4.5L3 9z"/><path d="M7 11v4.4c0 1.4 2.2 2.6 5 2.6s5-1.2 5-2.6V11"/>',
  audio: '<circle cx="12" cy="12" r="8.5"/><circle cx="12" cy="12" r="2.4"/>',
  video:
    '<circle cx="12" cy="12" r="8.5"/><path d="M10.5 8.8 16 12l-5.5 3.2z" fill="currentColor" stroke="none"/>',
  map: '<path d="M9 4.5 3.5 7v12.5L9 17l6 2.5 5.5-2.5V4.5L15 7z"/><path d="M9 4.5V17M15 7v12.5"/>',
  score: '<path d="M10 17.6V6l8-1.8v11.4"/><circle cx="8" cy="17.6" r="2.1"/><circle cx="16" cy="15.6" r="2.1"/>',
  software: '<rect x="3.5" y="4.5" width="17" height="12" rx="1"/><path d="M8 20.5h8"/>',
  image:
    '<rect x="4" y="5" width="16" height="14" rx="1"/><circle cx="9" cy="10" r="1.6"/><path d="M5.5 17.5 10 13l3.5 3.5L16 14l3 3"/>',
  manuscript:
    '<path d="M5 5.5c2.4-1.6 4.8-1.6 7 0 2.2-1.6 4.6-1.6 7 0v13c-2.4-1.6-4.8-1.6-7 0-2.2-1.6-4.6-1.6-7 0z"/><path d="M12 5.5v13"/>',
};

const FORMAT_ICONS = {
  Buch: 'book',
  Lehrbuch: 'book',
  'E-Book': 'book',
  Schriftenreihe: 'book',
  Nachschlagewerk: 'book',
  Medium: 'book',
  Aufsatz: 'document',
  'Online-Artikel': 'document',
  Konferenz: 'document',
  Hochschulschrift: 'thesis',
  Zeitschrift: 'journal',
  Karte: 'map',
  Noten: 'score',
  Hörbuch: 'audio',
  Audio: 'audio',
  CD: 'audio',
  DVD: 'video',
  Video: 'video',
  Datenbank: 'software',
  Software: 'software',
  Website: 'globe',
  Bild: 'image',
  Manuskript: 'manuscript',
};

const CHECK =
  '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12,2A10,10 0 0,1 22,12A10,10 0 0,1 12,22A10,10 0 0,1 2,12A10,10 0 0,1 12,2M11,16.5L18,9.5L16.59,8.09L11,13.67L7.91,10.58L6.5,12L11,16.5Z"/></svg>';

const BOOKMARK_OUTLINE =
  'M17,18L12,15.82L7,18V5H17M17,3H7A2,2 0 0,0 5,5V21L12,18L19,21V5C19,3.89 18.1,3 17,3Z';
const BOOKMARK_FILLED = 'M17,3H7A2,2 0 0,0 5,5V21L12,18L19,21V5C19,3.89 18.1,3 17,3Z';

function iconFor(record) {
  const labels = record.formats.length ? record.formats : [record.type];
  let name = labels.map((label) => FORMAT_ICONS[label]).find(Boolean);
  if (!name && record.availability?.online) name = 'globe';
  return `<svg ${ICON_ATTRS}>${ICONS[name || 'book']}</svg>`;
}

const INDEX_LABELS = {
  serverChoice: 'Alle Felder',
  title: 'Titel',
  creator: 'Verfasser',
  subject: 'Schlagwort',
  publisher: 'Verlag',
  identifier: 'ISBN',
  issn: 'ISSN',
  anywhere: 'Volltext',
};

const PAGE_SIZE = 20;
const LIVE_SEARCH_MIN_CHARS = 3;
const LIVE_SEARCH_DEBOUNCE_MS = 450;

const FACET_GROUPS = [
  { key: 'type', label: 'Typ' },
  { key: 'author', label: 'Verfasser' },
  { key: 'decade', label: 'Jahrzehnt' },
  { key: 'language', label: 'Sprache' },
];

const SORTS = {
  relevance: { label: 'Relevanz', compare: null },
  'year-desc': { label: 'Jahr (neu → alt)', compare: (a, b) => (Number(b.year) || 0) - (Number(a.year) || 0) },
  'year-asc': { label: 'Jahr (alt → neu)', compare: (a, b) => (Number(a.year) || 9999) - (Number(b.year) || 9999) },
  title: { label: 'Titel (A–Z)', compare: (a, b) => a.title.localeCompare(b.title, 'de') },
  author: { label: 'Verfasser (A–Z)', compare: (a, b) => (a.authors[0]?.name || 'zzz').localeCompare(b.authors[0]?.name || 'zzz', 'de') },
};

const state = {
  q: '',
  index: 'serverChoice',
  page: 0,
  records: [],
  byUid: new Map(),
  total: 0,
  tookMs: 0,
  cached: false,
  hasMore: false,
  cql: '',
  loading: false,
  controller: null,
  filters: emptyFilters(),
  collapsed: new Set(),
  sort: 'relevance',
  citeRecord: null,
  uidSeq: 0,
  view: 'search',
  clientId: '',
  wishlist: new Map(),
  account: { loggedIn: false, username: '', loans: [], fetchedAt: '', loading: false, error: '' },
};

function emptyFilters() {
  return { type: new Set(), author: new Set(), decade: new Set(), language: new Set() };
}

// ---------------------------------------------------------------- utilities

const esc = (value) =>
  String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

function debounce(fn, wait) {
  let timer;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), wait);
  };
}

function toast(message) {
  els.toast.textContent = message;
  els.toast.classList.add('show');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => els.toast.classList.remove('show'), 2200);
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    const area = document.createElement('textarea');
    area.value = text;
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.append(area);
    area.select();
    const ok = document.execCommand('copy');
    area.remove();
    return ok;
  }
}

// ---------------------------------------------------------------- wishlist

const CLIENT_KEY = 'ublotse:client';

/** Stable identity for a saved record — mirrors `wishlistKey` in server.js. */
function wishlistKey(record) {
  if (record?.id) return record.id;
  return `t:${[record?.title, record?.year, record?.isbn?.[0]].filter(Boolean).join('~')}`;
}

/** One id per browser, minted once; the Android app will do the same per install. */
function loadClientId() {
  try {
    const existing = localStorage.getItem(CLIENT_KEY);
    if (existing) return existing;
    const id = crypto.randomUUID();
    localStorage.setItem(CLIENT_KEY, id);
    return id;
  } catch {
    return 'local';
  }
}

async function wishlistRequest(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { 'Content-Type': 'application/json', 'X-Client-Id': state.clientId, ...(options.headers || {}) },
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`);
  return data;
}

async function loadWishlist() {
  try {
    const data = await wishlistRequest('/api/wishlist');
    state.wishlist = new Map(data.items.map((item) => [item.key, item]));
  } catch (error) {
    toast(`Merkliste nicht erreichbar: ${error.message}`);
  }
}

/** Add or remove optimistically, then reconcile with the server. */
async function toggleWish(record) {
  const key = wishlistKey(record);
  const existing = state.wishlist.get(key);

  if (existing) {
    state.wishlist.delete(key);
    render();
    try {
      await wishlistRequest(`/api/wishlist/${encodeURIComponent(key)}`, { method: 'DELETE' });
      toast('Aus der Merkliste entfernt');
    } catch (error) {
      state.wishlist.set(key, existing);
      render();
      toast(`Entfernen fehlgeschlagen: ${error.message}`);
    }
    return;
  }

  state.wishlist.set(key, { key, note: '', addedAt: new Date().toISOString(), record: { ...record } });
  render();
  try {
    await wishlistRequest('/api/wishlist', { method: 'POST', body: JSON.stringify({ record }) });
    toast('Zur Merkliste hinzugefügt');
  } catch (error) {
    state.wishlist.delete(key);
    render();
    toast(`Merken fehlgeschlagen: ${error.message}`);
  }
}

/** Assign each record a handle so cards can be re-rendered without losing identity. */
function registerRecords(records) {
  for (const record of records) {
    if (!record._uid) record._uid = `u${(state.uidSeq += 1)}`;
    state.byUid.set(record._uid, record);
  }
  return records;
}

async function openWishlist() {
  if (state.controller) state.controller.abort();
  state.view = 'wishlist';
  syncUrl();
  render();
  await loadWishlist();
  render();
}


// ------------------------------------------------------------------ account

const ACCOUNT_EMPTY = { loggedIn: false, username: '', loans: [], fetchedAt: '', loading: false, error: '' };

async function accountRequest(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { 'Content-Type': 'application/json', 'X-Client-Id': state.clientId, ...(options.headers || {}) },
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(data.error || 'HTTP ' + response.status);
    error.status = response.status;
    throw error;
  }
  return data;
}

async function openAccount() {
  if (state.controller) state.controller.abort();
  state.view = 'account';
  syncUrl();
  render();
  if (state.account.loggedIn) await refreshAccount();
}

async function refreshAccount() {
  const account = state.account;
  account.loading = true;
  account.error = '';
  render();
  try {
    const data = await accountRequest('/api/account/loans');
    account.loggedIn = true;
    account.username = data.username || account.username;
    account.loans = data.items || [];
    account.fetchedAt = data.fetchedAt || '';
  } catch (error) {
    if (error.status === 401) {
      account.loggedIn = false;
      account.loans = [];
    }
    account.error = error.message;
  } finally {
    account.loading = false;
    render();
  }
}

async function submitLogin(form) {
  const username = form.elements.username.value.trim();
  const password = form.elements.password.value;
  const account = state.account;
  account.loading = true;
  account.error = '';
  render();
  try {
    const data = await accountRequest('/api/account/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    });
    account.loggedIn = true;
    account.username = data.username || username;
    account.loans = data.items || [];
    account.fetchedAt = data.fetchedAt || '';
    toast('Angemeldet — Ausleihen geladen');
  } catch (error) {
    account.error = error.message;
  } finally {
    account.loading = false;
    render();
  }
}

async function logoutAccount() {
  try {
    await accountRequest('/api/account/logout', { method: 'POST' });
  } catch {
    // The local session is dropped either way.
  }
  state.account = { ...ACCOUNT_EMPTY };
  render();
  toast('Abgemeldet');
}

async function showDiagnose(button) {
  button.disabled = true;
  toast('Frage den Katalog …');
  try {
    const data = await accountRequest('/api/account/diagnose');
    els.results.innerHTML =
      '<div class="state"><p>Das hat der Katalog geantwortet:</p>' +
      '<pre class="diagnose" id="diagnose-text">' +
      esc(data.report || '') +
      '</pre>' +
      '<button type="button" class="btn-primary" data-account-action="copy-diagnose">Kopieren</button> ' +
      '<button type="button" class="btn-plain small" data-account-action="refresh">Zurück</button>' +
      '</div>';
    const text = document.getElementById('diagnose-text');
    button = els.results.querySelector('[data-account-action="copy-diagnose"]');
    if (text && button) button.dataset.report = text.textContent;
  } catch (error) {
    toast('Diagnose fehlgeschlagen: ' + error.message);
    render();
  }
}

async function copyDiagnose(button) {
  const report = button.dataset.report || '';
  try {
    await navigator.clipboard.writeText(report);
    toast('Kopiert');
  } catch {
    // Older WebViews have no clipboard API: select the <pre> so a long-press can copy it.
    const text = document.getElementById('diagnose-text');
    if (text) {
      const range = document.createRange();
      range.selectNodeContents(text);
      const selection = window.getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
      toast('Text markiert — zum Kopieren gedrückt halten');
    }
  }
}

async function renewLoan(button) {
  button.disabled = true;
  try {
    const data = await accountRequest('/api/account/renew', {
      method: 'POST',
      body: JSON.stringify({ href: button.dataset.href, renewId: button.dataset.renewId }),
    });
    state.account.loans = data.items || [];
    toast(data.message || 'Ausleihe verlängert');
    render();
  } catch (error) {
    button.disabled = false;
    toast('Verlängern fehlgeschlagen: ' + error.message);
  }
}

function dueState(loan) {
  if (!loan.dueISO) return { text: loan.due ? 'Fällig: ' + loan.due : 'Fälligkeit unbekannt', cls: '' };
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const due = new Date(loan.dueISO + 'T00:00:00');
  const days = Math.round((due - today) / 86400000);
  if (Number.isNaN(days)) return { text: 'Fällig: ' + loan.due, cls: '' };
  if (days < 0) return { text: 'Überfällig seit ' + Math.abs(days) + (Math.abs(days) === 1 ? ' Tag' : ' Tagen'), cls: 'overdue' };
  if (days === 0) return { text: 'Heute fällig', cls: 'due-soon' };
  if (days <= 3) return { text: 'In ' + days + (days === 1 ? ' Tag' : ' Tagen') + ' fällig', cls: 'due-soon' };
  return { text: 'Fällig am ' + loan.due, cls: '' };
}

function loanHtml(loan) {
  const due = dueState(loan);
  // VuFind either links to a renewal or puts the item's renewal id in the page's own form; either
  // way the renewal is the catalogue's business, so only the ids cross the API.
  const renew = loan.renewId || loan.renewHref
    ? '<button type="button" class="btn-plain small" data-account-action="renew" data-href="' +
      esc(loan.renewHref || '') +
      '" data-renew-id="' +
      esc(loan.renewId || '') +
      '">Verlängern</button>'
    : '';
  const href = loan.recordHref ? 'https://katalog.ub.uni-leipzig.de' + loan.recordHref : '';
  const title = href
    ? '<a href="' + esc(href) + '" target="_blank" rel="noopener">' + esc(loan.title) + '</a>'
    : esc(loan.title);

  return '<article class="loan ' + due.cls + '">' +
    '<div class="loan-main"><h2>' + title + '</h2>' +
    (loan.author ? '<p class="loan-author">' + esc(loan.author) + '</p>' : '') +
    '</div>' +
    '<div class="loan-side"><span class="loan-due ' + due.cls + '">' + esc(due.text) + '</span>' + renew + '</div>' +
    '</article>';
}

function renderAccount() {
  const account = state.account;
  els.facets.innerHTML = '';
  els.more.hidden = true;

  if (!account.loggedIn) {
    els.resultbar.innerHTML = '<span class="bits"><span>Mein Konto</span></span>';
    els.results.innerHTML =
      '<section class="account-intro">' +
      '<h1>Mein Konto</h1>' +
      '<p>Melden Sie sich mit Ihrer Bibliotheksausweisnummer an, um Ihre ausgeliehenen Bücher zu sehen — mit Fristen und Verlängerung.</p>' +
      '<form class="account-form" id="account-form" autocomplete="on">' +
      '<label>Ausweisnummer<input name="username" type="text" autocomplete="username" inputmode="numeric" required /></label>' +
      '<label>Passwort<input name="password" type="password" autocomplete="current-password" required /></label>' +
      '<button type="submit" class="btn-primary"' + (account.loading ? ' disabled' : '') + '>' + (account.loading ? 'Anmelden …' : 'Anmelden') + '</button>' +
      '</form>' +
      (account.error ? '<p class="account-error" role="alert">' + esc(account.error) + '</p>' : '') +
      '<p class="account-note">Die Zugangsdaten gehen nur an den Katalog und werden nicht gespeichert. Die Sitzung endet nach 30 Minuten Untätigkeit.</p>' +
      '</section>';
    return;
  }

  const loans = account.loans;
  const action = account.loading ? ' disabled' : '';
  els.resultbar.innerHTML =
    '<span class="bits"><span><strong>' + loans.length + '</strong> ' + (loans.length === 1 ? 'Ausleihe' : 'Ausleihen') + '</span>' +
    (account.username ? '<span class="account-user">' + esc(account.username) + '</span>' : '') + '</span>' +
    '<span class="spacer"></span>' +
    '<button type="button" class="btn-plain small" data-account-action="refresh"' + action + '>Aktualisieren</button>' +
    '<button type="button" class="btn-plain small" data-account-action="logout">Abmelden</button>';

  if (account.error) {
    els.results.innerHTML = '<div class="state">' + esc(account.error) + '</div>';
    return;
  }
  if (account.loading && !loans.length) {
    els.results.innerHTML = '<div class="state">Ausleihen werden geladen …</div>';
    return;
  }
  if (!loans.length) {
    // An empty list is either a real empty account or a page the parser did not understand, and
    // only the catalogue knows which. The diagnostic says what it actually sent.
    els.results.innerHTML =
      '<div class="state">Zurzeit ist nichts ausgeliehen.' +
      '<p class="account-note">Wenn im Katalog Ausleihen stehen, ' +
      '<button type="button" class="btn-plain small" data-account-action="diagnose">zeig, was der Katalog geliefert hat</button>.</p>' +
      '</div>';
    return;
  }

  els.results.innerHTML = loans.map(loanHtml).join('');
}

// ------------------------------------------------------------------ search

async function runSearch({ append = false } = {}) {
  const q = els.query.value.trim();
  const index = els.index.value;

  if (!q) {
    showWelcome();
    return;
  }

  state.view = 'search';

  if (state.controller) state.controller.abort();
  const controller = new AbortController();
  state.controller = controller;

  if (!append) {
    state.page = 0;
    state.records = [];
    state.byUid = new Map();
    state.filters = emptyFilters();
    state.total = 0;
    renderSkeletons();
  }

  state.q = q;
  state.index = index;
  state.loading = true;
  els.more.disabled = true;
  els.more.textContent = append ? 'Lädt …' : 'Mehr laden';

  const params = new URLSearchParams({
    q,
    index,
    page: String(state.page + 1),
    limit: String(PAGE_SIZE),
  });

  try {
    const response = await fetch(`/api/search?${params}`, { signal: controller.signal });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`);

    registerRecords(data.records);
    state.records = append ? [...state.records, ...data.records] : data.records;

    state.page = data.page;
    state.total = data.total;
    state.tookMs = data.tookMs;
    state.cached = data.cached;
    state.hasMore = data.hasMore;
    state.cql = data.cqlDisplay || data.cql;

    syncUrl();
    render();
  } catch (error) {
    if (error.name === 'AbortError') return;
    renderError(error.message);
  } finally {
    if (state.controller === controller) state.controller = null;
    state.loading = false;
    els.more.disabled = false;
    els.more.textContent = 'Mehr laden';
  }
}

// ------------------------------------------------------------------- facets

function facetCounts(records) {
  const add = (map, value) => {
    if (value) map.set(value, (map.get(value) || 0) + 1);
  };
  const toList = (map) =>
    [...map].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0], 'de')).slice(0, 10).map(([value, count]) => ({ value, count }));

  const type = new Map();
  const author = new Map();
  const decade = new Map();
  const language = new Map();

  for (const record of records) {
    add(type, record.type);
    for (const name of record.authors.map((a) => a.name)) add(author, name);
    if (record.year) add(decade, `${record.year.slice(0, 3)}0`);
    for (const lang of record.languages) add(language, lang);
  }

  return {
    type: toList(type),
    author: toList(author),
    decade: toList(decade),
    language: toList(language),
  };
}

function activeFilterCount() {
  return Object.values(state.filters).reduce((sum, set) => sum + set.size, 0);
}

function visibleRecords() {
  const { type, author, decade, language } = state.filters;
  const anyFilter = type.size || author.size || decade.size || language.size;

  let records = anyFilter
    ? state.records.filter((record) => {
        const recordDecade = record.year ? `${record.year.slice(0, 3)}0` : '';
        const typeOk = !type.size || type.has(record.type);
        const authorOk = !author.size || record.authors.some((a) => author.has(a.name));
        const decadeOk = !decade.size || decade.has(recordDecade);
        const languageOk = !language.size || record.languages.some((l) => language.has(l));
        return typeOk && authorOk && decadeOk && languageOk;
      })
    : [...state.records];

  const compare = SORTS[state.sort]?.compare;
  if (compare) records.sort(compare);
  return records;
}

// ------------------------------------------------------------------ render

function render() {
  if (state.view === 'wishlist') renderWishlist();
  else if (state.view === 'account') renderAccount();
  else {
    renderBar();
    renderFacets();
    renderResults();
  }
  renderWishBadge();
  els.accountToggle.setAttribute('aria-pressed', String(state.view === 'account'));
}

function renderWishBadge() {
  const count = state.wishlist.size;
  els.wishCount.textContent = String(count);
  els.wishCount.hidden = count === 0;
  els.wishToggle.setAttribute('aria-pressed', String(state.view === 'wishlist'));
}

function renderBar() {
  const visible = visibleRecords().length;
  const loaded = state.records.length;
  const filters = activeFilterCount();

  const bits = [];
  if (state.total) {
    bits.push(`<span><strong>${state.total.toLocaleString('de-DE')}</strong> Treffer</span>`);
  }
  if (filters) {
    bits.push(`<span>${visible} von ${loaded} geladenen sichtbar</span>`);
  } else if (loaded && state.total > loaded) {
    bits.push(`<span>${loaded} geladen</span>`);
  }
  if (state.tookMs) {
    bits.push(`<span title="${state.cached ? 'aus dem Cache' : 'frisch von SRU'}">${state.tookMs} ms${state.cached ? ' · Cache' : ''}</span>`);
  }

  const sortSelect = `
    <label class="sort-label">Sortierung
      <select id="sort">
        ${Object.entries(SORTS)
          .map(([key, { label }]) => `<option value="${key}"${key === state.sort ? ' selected' : ''}>${esc(label)}</option>`)
          .join('')}
      </select>
    </label>`;

  els.resultbar.innerHTML = `
    <span class="bits">${bits.join(' · ')}</span>
    <span class="spacer"></span>
    <span class="cql" title="${esc(state.cql)}">${esc(state.cql)}</span>
    ${sortSelect}
  `;
}

// On phones the panel sits above the results, so its groups start closed; left
// open, four groups of ten would push the first hit off screen.
let facetDefaultsApplied = false;

function renderFacets() {
  if (!state.records.length) {
    els.facets.innerHTML = '';
    return;
  }

  if (!facetDefaultsApplied) {
    facetDefaultsApplied = true;
    if (narrowQuery.matches) for (const { key } of FACET_GROUPS) state.collapsed.add(key);
  }

  const counts = facetCounts(state.records);
  const groups = FACET_GROUPS.filter(({ key }) => counts[key].length).map(({ key, label }) => {
    const collapsed = state.collapsed.has(key);
    const items = counts[key]
      .map(({ value, count }) => {
        const active = state.filters[key].has(value);
        return `
          <li>
            <button type="button" class="facet" data-facet="${key}" data-value="${esc(value)}"
              title="${esc(value)}" aria-pressed="${active}">
              <span class="mark" aria-hidden="true">${active ? '−' : '+'}</span>
              <span class="text">${esc(value)}</span>
              <span class="count">${count.toLocaleString('de-DE')}</span>
            </button>
          </li>`;
      })
      .join('');
    return `
      <div class="facet-group${collapsed ? ' collapsed' : ''}" data-group="${key}">
        <h3>
          <button type="button" class="facet-toggle" data-toggle="${key}" aria-expanded="${!collapsed}">
            <span>${esc(label)}</span>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7.41,8.58L12,13.17L16.59,8.58L18,10L12,16L6,10L7.41,8.58Z" /></svg>
          </button>
        </h3>
        <ul class="facet-list">${items}</ul>
      </div>`;
  });

  const reset = activeFilterCount()
    ? `<button type="button" class="facet-reset" id="facet-reset">Filter zurücksetzen</button>`
    : '';

  els.facets.innerHTML = `
    ${groups.join('')}
    ${reset}
    <p class="facet-note">Filter und Sortierung beziehen sich auf die geladenen Treffer.</p>
  `;
}

function renderResults() {
  const records = visibleRecords();

  if (!records.length) {
    els.results.innerHTML = `<div class="state">Keine Treffer für die aktuellen Filter.</div>`;
    els.more.hidden = true;
    return;
  }

  els.results.innerHTML = records.map(cardHtml).join('');
  els.more.hidden = !state.hasMore;
  attachCoverFallbacks();
}

function renderWishlist() {
  const items = [...state.wishlist.values()].sort((a, b) => String(b.addedAt).localeCompare(String(a.addedAt)));
  const records = registerRecords(items.map((item) => item.record));

  els.facets.innerHTML = '';
  els.more.hidden = true;
  els.resultbar.innerHTML = `<span class="bits"><span><strong>${items.length}</strong> gemerkt</span></span><span class="spacer"></span>`;

  if (!records.length) {
    els.results.innerHTML = `<div class="state">Die Merkliste ist leer. Mit <strong>Merken</strong> sammeln Sie Treffer für später — auch über Sitzungen hinweg.</div>`;
    return;
  }

  els.results.innerHTML = records.map(cardHtml).join('');
  attachCoverFallbacks();
}

function cardHtml(record) {
  const authors = record.authors.length
    ? `<p class="authors">von ${record.authors
        .slice(0, 6)
        .map(
          (author) =>
            `<button type="button" class="author" data-search="${esc(author.name)}" data-index="creator">${esc(author.name)}</button>${
              author.roleLabel ? ` <span class="role">(${esc(author.roleLabel)})</span>` : ''
            }`,
        )
        .join(', ')}${record.authors.length > 6 ? ' u. a.' : ''}</p>`
    : '';

  const imprint = record.imprint
    ? `<p class="imprint">${esc(record.imprint)}</p>`
    : (() => {
        const parts = [];
        if (record.place || record.publisher) parts.push([record.place, record.publisher].filter(Boolean).join(' : '));
        if (record.year) parts.push(record.year);
        return parts.length ? `<p class="imprint">${esc(parts.join(', '))}</p>` : '';
      })();

  const parent = record.parentTitle ? `<p class="parent">in: ${esc(record.parentTitle)}</p>` : '';

  const holdings = (() => {
    const availability = record.availability;
    if (!availability) return '';
    const parts = [];
    if (availability.callnumbers.length) {
      parts.push(
        `<span class="holdings-label">Signatur:</span> <strong>${esc(availability.callnumbers.join(', '))}</strong>`,
      );
    }
    if (availability.branch) {
      parts.push(`<span class="holdings-label">Standort:</span> <strong>${esc(availability.branch)}</strong>`);
    }
    return parts.length ? `<p class="holdings">${parts.join(' ')}</p>` : '';
  })();

  const formats = (record.formats.length ? record.formats : [record.type]).slice(0, 2);
  const badges = [
    ...formats.map((format) => `<li><span class="badge">${esc(format)}</span></li>`),
    record.availability?.online
      ? `<li><span class="badge badge-ok">${CHECK}<span>Online verfügbar</span></span></li>`
      : '',
  ].join('');

  const subjects = record.subjects
    .slice(0, 6)
    .map(
      (subject) =>
        `<li><button type="button" class="chip" data-search="${esc(subject)}" data-index="subject">${esc(subject)}</button></li>`,
    )
    .join('');

  const chips = badges || subjects ? `<ul class="chips">${badges}${subjects}</ul>` : '';

  const ids = [
    ...record.isbn.map((v) => `ISBN ${v}`),
    ...record.issn.map((v) => `ISSN ${v}`),
    ...record.oclc.map((v) => `OCLC ${v}`),
  ];
  const idsHtml = ids.length ? `<p class="ids">${esc(ids.join('  ·  '))}</p>` : '';

  const online = record.urls[0];

  const cover = record.isbn.length
    ? `<img class="cover" alt="" loading="lazy" decoding="async" src="/api/cover?isbn=${encodeURIComponent(record.isbn[0])}&size=medium">`
    : '';

  const saved = state.wishlist.has(wishlistKey(record));
  const wish = `<button type="button" class="wish${saved ? ' is-saved' : ''}" data-wish="${record._uid}" aria-pressed="${saved}"
    title="${saved ? 'Aus der Merkliste entfernen' : 'Zur Merkliste hinzufügen'}">
    <svg viewBox="0 0 24 24" aria-hidden="true"><path d="${saved ? BOOKMARK_FILLED : BOOKMARK_OUTLINE}" /></svg>
    <span>${saved ? 'Gemerkt' : 'Merken'}</span>
  </button>`;

  return `
    <article class="card" data-uid="${record._uid}">
      <div class="format-icon">${iconFor(record)}</div>
      <div class="card-main">
        <h3><a href="${esc(record.recordUrl)}" target="_blank" rel="noopener">${esc(record.title)}</a></h3>
        ${record.subtitle ? `<p class="subtitle">${esc(record.subtitle)}</p>` : ''}
        ${authors}
        ${parent}
        ${imprint}
        ${holdings}
        ${chips}
        ${idsHtml}
        <div class="actions">
          <a href="${esc(record.recordUrl)}" target="_blank" rel="noopener">Im Katalog</a>
          ${wish}
          <button type="button" data-cite="${record._uid}">Zitieren</button>
          ${online ? `<a href="${esc(online)}" target="_blank" rel="noopener">Online-Zugang</a>` : ''}
        </div>
      </div>
      ${cover}
    </article>`;
}

/** Hide cover placeholders (the cover service returns a 1px gif when it has nothing). */
function attachCoverFallbacks() {
  for (const img of els.results.querySelectorAll('img.cover')) {
    const drop = () => img.remove();
    img.addEventListener('error', drop);
    img.addEventListener('load', () => {
      if (img.naturalWidth <= 4 || img.naturalHeight <= 4) drop();
    });
  }
}

function renderSkeletons() {
  els.resultbar.innerHTML = '';
  els.facets.innerHTML = '';
  els.results.innerHTML = Array.from(
    { length: 4 },
    () => `<div class="skeleton"><div></div><div></div><div></div></div>`,
  ).join('');
  els.more.hidden = true;
}

function renderError(message) {
  els.resultbar.innerHTML = '';
  els.facets.innerHTML = '';
  els.more.hidden = true;
  els.results.innerHTML = `<div class="state error"><strong>Die Abfrage ist fehlgeschlagen.</strong><br>${esc(message)}</div>`;
}

function showWelcome() {
  state.view = 'search';
  state.q = '';
  state.records = [];
  state.total = 0;
  state.cql = '';
  state.page = 0;
  state.hasMore = false;
  state.filters = emptyFilters();
  els.resultbar.innerHTML = '';
  els.facets.innerHTML = '';
  els.more.hidden = true;
  els.results.innerHTML = `
    <div class="welcome">
      <h1>Universitätsbibliothek Leipzig (UBL)</h1>
      <h2>Finden statt warten.</h2>
      <p>UBLotse fragt den Leipziger Katalog direkt über die offene SRU-Schnittstelle ab —
      ohne die Theme-Skripte, die die offizielle Oberfläche langsam machen.</p>
      <ul class="welcome-tips">
        <li><kbd>/</kbd> fokussiert das Suchfeld</li>
        <li><kbd>Enter</kbd> sucht, <kbd>Esc</kbd> leert</li>
        <li>Verfasser und Schlagwörter sind anklickbar</li>
      </ul>
    </div>`;
  syncUrl();
}

// ----------------------------------------------------------------- citation

function citationFor(record) {
  const authors = record.authors.map((author) => author.name).join('; ');
  const title = [record.title, record.subtitle].filter(Boolean).join(': ');
  const place = [record.place, record.publisher].filter(Boolean).join(': ');
  const parts = [];
  if (authors) parts.push(/[.!?]$/.test(authors) ? authors : `${authors}.`);
  if (record.year) parts.push(`(${record.year}).`);
  if (title) parts.push(`${title}.`);
  if (place) parts.push(/[.!?]$/.test(place) ? place : `${place}.`);
  return parts.join(' ');
}

function bibtexFor(record) {
  const type =
    record.type === 'Aufsatz' || record.type === 'Online-Artikel'
      ? 'article'
      : record.type === 'Hochschulschrift'
        ? 'phdthesis'
        : record.type === 'Zeitschrift'
          ? 'misc'
          : 'book';

  const names = record.authors.map((author) => author.name);
  const surname = names[0]?.split(',')[0]?.replace(/[^\p{L}\p{N}]/gu, '') || 'ublotse';
  const key = `${surname}${record.year || ''}`;

  const fields = [];
  const push = (name, value) => {
    if (value) fields.push(`  ${name} = {${value}},`);
  };
  push('author', names.join(' and '));
  push('title', [record.title, record.subtitle].filter(Boolean).join(': '));
  push('year', record.year);
  push('publisher', record.publisher);
  push('address', record.place);
  push('isbn', record.isbn[0]);
  push('issn', record.issn[0]);
  push('series', record.series.join('; '));

  return `@${type}{${key},\n${fields.join('\n')}\n}`;
}

function openCitation(record) {
  state.citeRecord = record;
  els.citeTitle.textContent = record.title ? `Zitieren — ${record.title}` : 'Zitieren';
  els.citeCitation.textContent = citationFor(record);
  els.citeBibtex.textContent = bibtexFor(record);
  els.dialog.showModal();
}

// ------------------------------------------------------------------- events

els.form.addEventListener('submit', (event) => {
  event.preventDefault();
  runSearch();
});

els.query.addEventListener('input', () => {
  const value = els.query.value.trim();
  if (!value) {
    if (state.controller) state.controller.abort();
    showWelcome();
    return;
  }
  if (value.length >= LIVE_SEARCH_MIN_CHARS) liveSearch();
});

els.index.addEventListener('change', () => {
  if (els.query.value.trim()) runSearch();
});

els.clear.addEventListener('click', () => {
  els.query.value = '';
  if (state.controller) state.controller.abort();
  showWelcome();
  els.query.focus();
});

els.more.addEventListener('click', () => runSearch({ append: true }));

els.resultbar.addEventListener('change', (event) => {
  if (event.target.id === 'sort') {
    state.sort = event.target.value;
    render();
  }
});

els.facets.addEventListener('click', (event) => {
  const toggle = event.target.closest('[data-toggle]');
  if (toggle) {
    const key = toggle.dataset.toggle;
    if (state.collapsed.has(key)) state.collapsed.delete(key);
    else state.collapsed.add(key);
    const collapsed = state.collapsed.has(key);
    toggle.closest('.facet-group').classList.toggle('collapsed', collapsed);
    toggle.setAttribute('aria-expanded', String(!collapsed));
    return;
  }

  const reset = event.target.closest('#facet-reset');
  if (reset) {
    state.filters = emptyFilters();
    render();
    return;
  }
  const button = event.target.closest('.facet');
  if (!button) return;

  const { facet, value } = button.dataset;
  const set = state.filters[facet];
  if (set.has(value)) set.delete(value);
  else set.add(value);
  render();
});

els.results.addEventListener('click', (event) => {
  const accountButton = event.target.closest('[data-account-action]');
  if (accountButton) {
    const action = accountButton.dataset.accountAction;
    if (action === 'logout') logoutAccount();
    else if (action === 'refresh') refreshAccount();
    else if (action === 'renew') renewLoan(accountButton);
    else if (action === 'diagnose') showDiagnose(accountButton);
    else if (action === 'copy-diagnose') copyDiagnose(accountButton);
    return;
  }

  const wishButton = event.target.closest('[data-wish]');
  if (wishButton) {
    const record = state.byUid.get(wishButton.dataset.wish);
    if (record) toggleWish(record);
    return;
  }

  const searchButton = event.target.closest('[data-search]');
  if (searchButton) {
    els.query.value = searchButton.dataset.search;
    els.index.value = searchButton.dataset.index;
    runSearch();
    window.scrollTo({ top: 0, behavior: 'smooth' });
    return;
  }

  const citeButton = event.target.closest('[data-cite]');
  if (citeButton) {
    const record = state.byUid.get(citeButton.dataset.cite);
    if (record) openCitation(record);
  }
});

els.dialog.addEventListener('click', async (event) => {
  const copyButton = event.target.closest('[data-copy]');
  if (!copyButton || !state.citeRecord) return;
  const text = copyButton.dataset.copy === 'bibtex' ? bibtexFor(state.citeRecord) : citationFor(state.citeRecord);
  if (await copyText(text)) toast(copyButton.dataset.copy === 'bibtex' ? 'BibTeX kopiert' : 'Zitat kopiert');
});

document.addEventListener('submit', (event) => {
  if (event.target.id !== 'account-form') return;
  event.preventDefault();
  submitLogin(event.target);
});

els.accountToggle.addEventListener('click', () => {
  if (state.view === 'account') {
    state.view = 'search';
    syncUrl();
    render();
    if (!state.records.length) showWelcome();
  } else {
    openAccount();
  }
});

els.wishToggle.addEventListener('click', () => {
  if (state.view === 'wishlist') {
    state.view = 'search';
    syncUrl();
    render();
  } else {
    openWishlist();
  }
});

els.themeToggle.addEventListener('click', () => {
  const next = effectiveTheme() === 'dark' ? 'light' : 'dark';
  try {
    localStorage.setItem(THEME_KEY, next);
  } catch {
    /* private mode — the choice just will not stick */
  }
  applyTheme(next);
});

darkQuery.addEventListener('change', () => {
  if (!document.documentElement.dataset.theme) applyTheme(null);
});

document.addEventListener('keydown', (event) => {
  const typing = /^(INPUT|SELECT|TEXTAREA)$/.test(document.activeElement?.tagName || '');
  if (event.key === '/' && !typing) {
    event.preventDefault();
    els.query.focus();
    els.query.select();
  }
  if (event.key === 'Escape' && document.activeElement === els.query && els.query.value) {
    els.query.value = '';
    showWelcome();
  }
});

window.addEventListener('popstate', () => {
  const params = new URLSearchParams(location.search);
  els.query.value = params.get('q') || '';
  els.index.value = params.get('index') || 'serverChoice';
  if (params.get('view') === 'wishlist') {
    openWishlist();
    return;
  }
  if (els.query.value) runSearch();
  else showWelcome();
});

const liveSearch = debounce(() => runSearch(), LIVE_SEARCH_DEBOUNCE_MS);

function syncUrl() {
  const params = new URLSearchParams();
  if (state.view === 'wishlist') params.set('view', 'wishlist');
  if (state.q) params.set('q', state.q);
  if (state.index && state.index !== 'serverChoice') params.set('index', state.index);
  const query = params.toString();
  history.replaceState(null, '', query ? `?${query}` : location.pathname);
}

// ----------------------------------------------------------------- updates

// The Android app injects window.Ublotse; a plain browser has no way to install anything, so the
// footer entry stays hidden there. Probing for the bridge rather than for the platform keeps this
// page the same file in both.
const appBridge = window.Ublotse;

if (appBridge && typeof appBridge.checkForUpdates === 'function') {
  const version = typeof appBridge.version === 'function' ? appBridge.version() : '';
  els.update.hidden = false;
  els.appVersion.textContent = version ? `UBLotse ${version} · ` : '';
  els.updateCheck.addEventListener('click', () => appBridge.checkForUpdates());
}

// --------------------------------------------------------------------- init

(function init() {
  applyTheme(document.documentElement.dataset.theme || null);
  state.clientId = loadClientId();

  const params = new URLSearchParams(location.search);
  if (params.get('view') === 'wishlist') {
    openWishlist();
    return;
  }

  loadWishlist().then(() => {
    renderWishBadge();
    if (state.records.length) render(); // fill in the “Merken” states once loaded
  });

  const q = params.get('q');
  if (q) {
    els.query.value = q;
    els.index.value = params.get('index') || 'serverChoice';
    runSearch();
  } else {
    showWelcome();
  }
})();
