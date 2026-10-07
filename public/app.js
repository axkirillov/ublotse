/**
 * lunibib — frontend.
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
};

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
  sort: 'relevance',
  citeRecord: null,
};

function emptyFilters() {
  return { type: new Set(), decade: new Set(), language: new Set() };
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

// ------------------------------------------------------------------ search

async function runSearch({ append = false } = {}) {
  const q = els.query.value.trim();
  const index = els.index.value;

  if (!q) {
    showWelcome();
    return;
  }

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

    for (const record of data.records) record._uid = crypto.randomUUID();
    for (const record of data.records) state.byUid.set(record._uid, record);
    state.records = append ? [...state.records, ...data.records] : data.records;

    state.page = data.page;
    state.total = data.total;
    state.tookMs = data.tookMs;
    state.cached = data.cached;
    state.hasMore = data.hasMore;
    state.cql = data.cql;

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
  const decade = new Map();
  const language = new Map();

  for (const record of records) {
    add(type, record.type);
    if (record.year) add(decade, `${record.year.slice(0, 3)}0`);
    for (const lang of record.languages) add(language, lang);
  }

  return { type: toList(type), decade: toList(decade), language: toList(language) };
}

function activeFilterCount() {
  return Object.values(state.filters).reduce((sum, set) => sum + set.size, 0);
}

function visibleRecords() {
  const { type, decade, language } = state.filters;
  const anyFilter = type.size || decade.size || language.size;

  let records = anyFilter
    ? state.records.filter((record) => {
        const recordDecade = record.year ? `${record.year.slice(0, 3)}0` : '';
        const typeOk = !type.size || type.has(record.type);
        const decadeOk = !decade.size || decade.has(recordDecade);
        const languageOk = !language.size || record.languages.some((l) => language.has(l));
        return typeOk && decadeOk && languageOk;
      })
    : [...state.records];

  const compare = SORTS[state.sort]?.compare;
  if (compare) records.sort(compare);
  return records;
}

// ------------------------------------------------------------------ render

function render() {
  renderBar();
  renderFacets();
  renderResults();
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

function renderFacets() {
  if (!state.records.length) {
    els.facets.innerHTML = '';
    return;
  }

  const counts = facetCounts(state.records);
  const groups = FACET_GROUPS.filter(({ key }) => counts[key].length).map(({ key, label }) => {
    const items = counts[key]
      .map(
        ({ value, count }) => `
          <li>
            <button type="button" class="facet" data-facet="${key}" data-value="${esc(value)}"
              aria-pressed="${state.filters[key].has(value)}">
              <span>${esc(value)}</span><span class="count">${count}</span>
            </button>
          </li>`,
      )
      .join('');
    return `<div class="facet-group"><h3>${esc(label)}</h3><ul class="facet-list">${items}</ul></div>`;
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

function cardHtml(record) {
  const authors = record.authors.length
    ? `<p class="authors">${record.authors
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
    if (availability.branch) parts.push(availability.branch);
    if (availability.callnumbers.length) parts.push(availability.callnumbers.join(', '));
    if (!parts.length && availability.online) parts.push('Online verfügbar');
    return parts.length ? `<p class="holdings">${esc(parts.join(' · '))}</p>` : '';
  })();

  const subjects = record.subjects.length
    ? `<ul class="chips">${record.subjects
        .slice(0, 6)
        .map(
          (subject) =>
            `<li><button type="button" class="chip" data-search="${esc(subject)}" data-index="subject">${esc(subject)}</button></li>`,
        )
        .join('')}</ul>`
    : '';

  const ids = [
    ...record.isbn.map((v) => `ISBN ${v}`),
    ...record.issn.map((v) => `ISSN ${v}`),
    ...record.oclc.map((v) => `OCLC ${v}`),
  ];
  const idsHtml = ids.length ? `<p class="ids">${esc(ids.join('  ·  '))}</p>` : '';

  const badges = (record.formats.length ? record.formats : [record.type])
    .slice(0, 2)
    .map((format) => `<span class="badge">${esc(format)}</span>`)
    .join('');

  const online = record.urls[0];

  const cover = record.isbn.length
    ? `<img class="cover" alt="" loading="lazy" src="https://katalog.ub.uni-leipzig.de/Cover/Show?isbn=${encodeURIComponent(record.isbn[0])}&size=medium">`
    : '';

  return `
    <article class="card" data-uid="${record._uid}">
      ${cover}
      <div class="card-main">
        <div class="card-top">${badges}${record.year ? `<span class="year">${esc(record.year)}</span>` : ''}</div>
        <h3><a href="${esc(record.recordUrl)}" target="_blank" rel="noopener">${esc(record.title)}</a></h3>
        ${record.subtitle ? `<p class="subtitle">${esc(record.subtitle)}</p>` : ''}
        ${authors}
        ${parent}
        ${imprint}
        ${holdings}
        ${subjects}
        ${idsHtml}
        <div class="actions">
          <a href="${esc(record.recordUrl)}" target="_blank" rel="noopener">Im Katalog</a>
          <button type="button" data-cite="${record._uid}">Zitieren</button>
          ${online ? `<a href="${esc(online)}" target="_blank" rel="noopener">Online-Zugang</a>` : ''}
        </div>
      </div>
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
      <h1>Finden statt warten.</h1>
      <p>lunibib fragt den Leipziger Katalog direkt über die offene SRU-Schnittstelle ab —
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
  const surname = names[0]?.split(',')[0]?.replace(/[^\p{L}\p{N}]/gu, '') || 'lunibib';
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
  if (els.query.value) runSearch();
  else showWelcome();
});

const liveSearch = debounce(() => runSearch(), LIVE_SEARCH_DEBOUNCE_MS);

function syncUrl() {
  const params = new URLSearchParams();
  if (state.q) params.set('q', state.q);
  if (state.index && state.index !== 'serverChoice') params.set('index', state.index);
  const query = params.toString();
  history.replaceState(null, '', query ? `?${query}` : location.pathname);
}

// --------------------------------------------------------------------- init

(function init() {
  const params = new URLSearchParams(location.search);
  const q = params.get('q');
  if (q) {
    els.query.value = q;
    els.index.value = params.get('index') || 'serverChoice';
    runSearch();
  } else {
    showWelcome();
  }
})();
