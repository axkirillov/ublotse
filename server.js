#!/usr/bin/env node
/**
 * lunibib — a faster frontend for the Leipzig University Library catalogue.
 *
 * This is the prototype backend: a zero-dependency Node HTTP server that
 *
 *   1. serves the static frontend from ./public
 *   2. proxies search requests to the finc SRU endpoint (sru.finc.info)
 *   3. turns the retrieved records into a small, clean JSON shape
 *   4. caches responses in memory
 *
 * Why a proxy at all?  sru.finc.info does not send CORS headers, so a browser
 * cannot call it directly. The proxy is also the natural home for parsing,
 * caching and for enforcing the finc rate limit (<= 100 records per query).
 *
 * Which record schema?  The endpoint offers `marcxml`, `turbomarc`, `dc` and
 * `solr` (see `operation=explain`). We ask for `solr`, the VuFind index
 * document, because it is the only one that carries
 *
 *   - the VuFind record id (`0-…` / `ai-…`), i.e. exact permalinks,
 *   - authors (`author2` + roles), which finc's MARCXML export omits,
 *   - normalised formats, subjects and holdings (branch + call number).
 *
 * Run:  node server.js   (or: npm start)
 */

import http from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = path.join(__dirname, 'public');

const PORT = Number(process.env.PORT || 5173);
const HOST = process.env.HOST || '127.0.0.1';

const SRU_BASE = process.env.SRU_BASE || 'https://sru.finc.info';
const SRU_DATABASE = process.env.SRU_DATABASE || 'de15';

const MAX_RECORDS = 100; // finc: automated scripts are limited to 100 records/query
const DEFAULT_LIMIT = 20;
const REQUEST_TIMEOUT_MS = 12_000;
const CACHE_TTL_MS = 30 * 60 * 1000; // the SRU index is refreshed daily
const CACHE_MAX_ENTRIES = 500;

const CATALOGUE_BASE = 'https://katalog.ub.uni-leipzig.de';

// ---------------------------------------------------------------------------
// Query building
// ---------------------------------------------------------------------------

// The indexes the de15 SRU actually exposes (see operation=explain).
const INDEXES = {
  serverChoice: 'cql.serverChoice', // all metadata fields
  anywhere: 'cql.anywhere', // all metadata fields + full texts
  title: 'dc.title',
  creator: 'dc.creator',
  subject: 'dc.subject',
  publisher: 'dc.publisher',
  date: 'dc.date',
  identifier: 'dc.identifier', // ISBN
  issn: 'bath.issn',
  corporateName: 'bath.corporateName',
  conferenceName: 'bath.conferenceName',
};

const SINGLE_TERM_INDEXES = new Set(['identifier', 'issn']);

function escapeCqlValue(value) {
  return value.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
}

/**
 * Turn a user query into CQL.
 *
 * The de15 server has no usable sort and no date ranges, but it does accept
 * boolean CQL. We AND the individual words the way VuFind's default search
 * does: "grundlagen physik" -> serverChoice="grundlagen" AND serverChoice="physik".
 *
 * `raw` passes a hand-written CQL query straight through (advanced mode).
 */
function buildCql({ index, q, raw }) {
  if (raw) return raw.trim();

  const idx = INDEXES[index] || INDEXES.serverChoice;
  const query = q.trim();
  if (!query) return '';

  if (SINGLE_TERM_INDEXES.has(index)) {
    return `${idx}="${escapeCqlValue(query)}"`;
  }

  const tokens = query.split(/\s+/).filter(Boolean);
  return tokens.map((t) => `${idx}="${escapeCqlValue(t)}"`).join(' AND ');
}

// ---------------------------------------------------------------------------
// XML helpers
// ---------------------------------------------------------------------------

const NAMED_ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };

function decodeEntities(input) {
  return input.replace(/&(#x?[0-9a-fA-F]+|[a-zA-Z]+);/g, (match, entity) => {
    if (entity[0] === '#') {
      const hex = entity[1] === 'x' || entity[1] === 'X';
      const code = parseInt(hex ? entity.slice(2) : entity.slice(1), hex ? 16 : 10);
      return Number.isFinite(code) ? String.fromCodePoint(code) : match;
    }
    return NAMED_ENTITIES[entity] ?? match;
  });
}

/** Collapse whitespace and decode entities — the one normalisation applied to all values. */
const clean = (value) => decodeEntities(String(value).replace(/\s+/g, ' ').trim());

// ---------------------------------------------------------------------------
// Solr-document parsing
// ---------------------------------------------------------------------------

const DOC_RE = /<doc>([\s\S]*?)<\/doc>/g;
const SOLR_FIELD_RE = /<(str|arr|int|long|bool|float|double|date) name="([^"]+)"([^>]*?)(?:\/>|>([\s\S]*?)<\/\1>)/g;
const SOLR_STR_RE = /<str>([\s\S]*?)<\/str>/g;

/** Parse one `<doc>` into a plain object of scalar or array values. */
function parseSolrDoc(xml) {
  const doc = {};
  for (const [, kind, name, , inner] of xml.matchAll(SOLR_FIELD_RE)) {
    if (name === 'fullrecord' || name === '_version_') continue; // never needed, can be large
    const value = inner ?? '';
    if (kind === 'arr') {
      doc[name] = [...value.matchAll(SOLR_STR_RE)].map((m) => clean(m[1])).filter(Boolean);
    } else if (kind === 'str' || kind === 'date') {
      doc[name] = clean(value);
    } else {
      doc[name] = value.trim();
    }
  }
  return doc;
}

// VuFind's format vocabulary, in German. Unknown values pass through unchanged.
const FORMAT_LABELS = {
  Book: 'Buch',
  'E-Book': 'E-Book',
  eBook: 'E-Book',
  Textbook: 'Lehrbuch',
  Serial: 'Zeitschrift',
  Journal: 'Zeitschrift',
  'Journal, E-Journal': 'Zeitschrift',
  Article: 'Aufsatz',
  ElectronicArticle: 'Online-Artikel',
  MonographSeries: 'Schriftenreihe',
  SpokenWord: 'Hörbuch',
  Audio: 'Audio',
  CD: 'CD',
  DVD: 'DVD',
  Video: 'Video',
  Map: 'Karte',
  Score: 'Noten',
  MusicalScore: 'Noten',
  Thesis: 'Hochschulschrift',
  Database: 'Datenbank',
  Website: 'Website',
  Conference: 'Konferenz',
  Reference: 'Nachschlagewerk',
  Software: 'Software',
  Image: 'Bild',
  Manuscript: 'Manuskript',
};

// MARC relator codes that are worth showing next to a name. `oth` is noise.
const ROLE_LABELS = {
  aut: 'Verfasser',
  edt: 'Hrsg.',
  trl: 'Übers.',
  ill: 'Ill.',
  prf: 'Sprecher',
  drt: 'Regie',
  cmp: 'Komp.',
  cnd: 'Dirigent',
  act: 'Darsteller',
  com: 'Komp.',
  arr: 'Bearb.',
  ctb: 'Mitarb.',
  mus: 'Musik',
};

function looksLikeIsbn(value) {
  return /^(97[89])?\d{9}[\dX]$/i.test(value.replace(/[-\s]/g, ''));
}

function yearOf(doc) {
  const sorted = doc.publishDateSort || '';
  if (/^\d{4}$/.test(sorted)) return sorted;
  for (const value of doc.publishDate || []) {
    const match = value.match(/\d{4}/);
    if (match) return match[0];
  }
  return '';
}

function catalogueSearchUrl({ title, authors }) {
  const params = new URLSearchParams({
    lookfor: [title, authors[0]?.name?.split(',')[0]].filter(Boolean).join(' '),
    type: 'AllFields',
  });
  return `${CATALOGUE_BASE}/Search/Results?${params}`;
}

/** Map one VuFind Solr document onto lunibib's record shape. */
function mapSolrDoc(doc) {
  const arr = (name) => doc[name] || [];
  const str = (name) => doc[name] || '';

  const roles = arr('author2_role');
  const byName = new Map();
  arr('author2').forEach((name, i) => {
    const role = roles[i] || '';
    const candidate = { name, role, roleLabel: ROLE_LABELS[role] || '' };
    const existing = byName.get(name);
    if (!existing || (!existing.roleLabel && candidate.roleLabel)) byName.set(name, candidate);
  });
  const authors = [...byName.values()];

  const formats = [...new Set(arr('format').map((f) => FORMAT_LABELS[f] || f))];
  const urls = [...new Set(arr('url'))];

  const isbn = [...new Set([...arr('isbn'), ...arr('marc024a_ct_mv').filter(looksLikeIsbn)])];

  const branches = arr('branch_de15');
  const callnumbers = [...new Set(arr('callnumber_de15'))];
  const online = urls.length > 0 || arr('facet_avail').includes('Free');
  const availability =
    branches.length || callnumbers.length || online
      ? { branch: branches[0] || '', callnumbers: callnumbers.slice(0, 4), online }
      : null;

  const id = str('id');
  const title = str('title') || str('title_short') || 'Ohne Titel';
  const part = str('title_part_str');

  const record = {
    id,
    title,
    subtitle: part && part !== title ? part : '',
    titleFull: str('title_full'),
    authors,
    place: arr('publishPlace').join(', '),
    publisher: arr('publisher').join(', '),
    year: yearOf(doc),
    imprint: str('imprint'),
    isbn,
    issn: arr('issn'),
    oclc: arr('oclc_num'),
    subjects: [...new Set([...arr('topic_facet'), ...arr('topic')])].slice(0, 8),
    genres: arr('genre_facet'),
    languages: arr('language'),
    formats,
    type: formats[0] || 'Medium',
    physical: arr('physical').join('; '),
    series: arr('series2'),
    summary: arr('footnote').join(' '),
    urls,
    availability,
    parentTitle: str('hierarchy_top_title'),
    source: 'solr',
  };

  record.recordUrl = id ? `${CATALOGUE_BASE}/Record/${encodeURIComponent(id)}` : catalogueSearchUrl(record);
  return record;
}

// ---------------------------------------------------------------------------
// SRU request + response parsing
// ---------------------------------------------------------------------------

async function sruSearch({ cql, startRecord, maximumRecords }) {
  const params = new URLSearchParams({
    operation: 'searchRetrieve',
    version: '1.1',
    query: cql,
    startRecord: String(startRecord),
    maximumRecords: String(maximumRecords),
    recordSchema: 'solr',
  });
  const url = `${SRU_BASE}/${SRU_DATABASE}?${params}`;

  const response = await fetch(url, {
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    headers: { 'User-Agent': 'lunibib-prototype/0.1 (+https://github.com/axkirillov/lunibib)' },
  });
  if (!response.ok) throw new Error(`SRU responded with HTTP ${response.status}`);
  return response.text();
}

function parseSruResponse(xml) {
  const diagnostic = xml.match(/<diag:message>([\s\S]*?)<\/diag:message>/);
  if (diagnostic) throw new Error(clean(diagnostic[1]));

  const total = Number(xml.match(/<zs:numberOfRecords>(\d+)/)?.[1] || 0);
  const nextRecordPosition = Number(xml.match(/<zs:nextRecordPosition>(\d+)/)?.[1] || 0);

  const records = [];
  for (const match of xml.matchAll(DOC_RE)) {
    try {
      records.push(mapSolrDoc(parseSolrDoc(match[1])));
    } catch {
      // A single malformed record should never take down the whole page.
    }
  }

  return { total, nextRecordPosition, records };
}

// ---------------------------------------------------------------------------
// Facets (also computed client-side over the loaded page; kept here for API users)
// ---------------------------------------------------------------------------

function countBy(records, valuesOf) {
  const counts = new Map();
  for (const record of records) {
    for (const value of valuesOf(record)) {
      if (value) counts.set(value, (counts.get(value) || 0) + 1);
    }
  }
  return [...counts]
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0], 'de'))
    .slice(0, 12)
    .map(([value, count]) => ({ value, count }));
}

function computeFacets(records) {
  return {
    type: countBy(records, (r) => [r.type]),
    language: countBy(records, (r) => r.languages),
    decade: countBy(records, (r) => (r.year ? [`${r.year.slice(0, 3)}0`] : [])),
    author: countBy(records, (r) => r.authors.slice(0, 1).map((a) => a.name)),
  };
}

// ---------------------------------------------------------------------------
// In-memory cache
// ---------------------------------------------------------------------------

const cache = new Map();

function cacheGet(key) {
  const entry = cache.get(key);
  if (!entry) return null;
  if (entry.expires < Date.now()) {
    cache.delete(key);
    return null;
  }
  return entry.value;
}

function cacheSet(key, value) {
  if (cache.size >= CACHE_MAX_ENTRIES) cache.delete(cache.keys().next().value);
  cache.set(key, { expires: Date.now() + CACHE_TTL_MS, value });
}

// ---------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.json': 'application/json; charset=utf-8',
  '.ico': 'image/x-icon',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
};

async function serveStatic(pathname, res) {
  const relative = pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, '');
  const filePath = path.resolve(PUBLIC_DIR, relative);

  if (filePath !== PUBLIC_DIR && !filePath.startsWith(PUBLIC_DIR + path.sep)) {
    res.writeHead(403).end('Forbidden');
    return;
  }

  try {
    const data = await readFile(filePath);
    res.writeHead(200, {
      'Content-Type': MIME_TYPES[path.extname(filePath)] || 'application/octet-stream',
      'Cache-Control': 'no-cache',
    });
    res.end(data);
  } catch {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Not found');
  }
}

// ---------------------------------------------------------------------------
// /api/search
// ---------------------------------------------------------------------------

async function handleSearch(url, res) {
  const q = (url.searchParams.get('q') || '').trim();
  const index = url.searchParams.get('index') || 'serverChoice';
  const raw = url.searchParams.get('raw') || '';
  const page = Math.max(1, Number(url.searchParams.get('page') || 1) || 1);
  const limit = Math.min(
    MAX_RECORDS,
    Math.max(1, Number(url.searchParams.get('limit') || DEFAULT_LIMIT) || DEFAULT_LIMIT),
  );

  if (!q && !raw) {
    sendJson(res, 400, { error: 'Missing query (q)' });
    return;
  }

  const cql = buildCql({ index, q, raw });
  const startRecord = (page - 1) * limit + 1;
  const cacheKey = `${cql}|${startRecord}|${limit}`;

  const cached = cacheGet(cacheKey);
  if (cached) {
    sendJson(res, 200, { ...cached, cached: true });
    return;
  }

  const startedAt = performance.now();
  const xml = await sruSearch({ cql, startRecord, maximumRecords: limit });
  const parsed = parseSruResponse(xml);
  const tookMs = Math.round(performance.now() - startedAt);

  const payload = {
    query: q,
    index,
    cql,
    source: 'sru:solr',
    total: parsed.total,
    page,
    limit,
    startRecord,
    nextRecordPosition: parsed.nextRecordPosition,
    hasMore: parsed.nextRecordPosition > 0,
    tookMs,
    facets: computeFacets(parsed.records),
    records: parsed.records,
  };

  cacheSet(cacheKey, payload);
  sendJson(res, 200, { ...payload, cached: false });
}

// ---------------------------------------------------------------------------
// Server
// ---------------------------------------------------------------------------

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

  try {
    if (url.pathname === '/api/search') {
      if (req.method !== 'GET') {
        sendJson(res, 405, { error: 'Method not allowed' });
        return;
      }
      await handleSearch(url, res);
      return;
    }

    if (url.pathname === '/api/health') {
      sendJson(res, 200, { ok: true, upstream: SRU_BASE, database: SRU_DATABASE, schema: 'solr' });
      return;
    }

    await serveStatic(url.pathname, res);
  } catch (error) {
    if (res.headersSent) {
      res.end();
      return;
    }
    sendJson(res, 502, { error: error.message || 'Upstream request failed' });
  }
});

const isMain = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;

if (isMain) {
  server.listen(PORT, HOST, () => {
    console.log(`lunibib  →  http://${HOST}:${PORT}`);
    console.log(`upstream →  ${SRU_BASE}/${SRU_DATABASE} (SRU 1.1, solr schema)`);
  });
}

export {
  buildCql,
  decodeEntities,
  parseSolrDoc,
  mapSolrDoc,
  parseSruResponse,
  computeFacets,
  looksLikeIsbn,
};
