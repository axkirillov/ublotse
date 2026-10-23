#!/usr/bin/env node
/**
 * UBLotse — a faster frontend for the Leipzig University Library catalogue.
 *
 * This is the prototype backend: a zero-dependency Node HTTP server that
 *
 *   1. serves the static frontend from ./public
 *   2. proxies search requests to the finc SRU endpoint (sru.finc.info)
 *   3. turns the retrieved records into a small, clean JSON shape
 *   4. caches responses in memory
 *   5. proxies book covers around finc's cookie gate
 *   6. stores a per-client wishlist in SQLite (node:sqlite, no dependencies)
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
import { mkdirSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createAccount } from './account.js';

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
const USER_AGENT = 'ublotse-prototype/0.1 (+https://github.com/axkirillov/ublotse)';

// Covers live on the catalogue host but behind finc's `finc_open=1` cookie gate,
// which a cross-origin <img> cannot satisfy — see the cover proxy below.
const COVER_SERVICE = `${CATALOGUE_BASE}/Cover/Show`;
const OPENLIBRARY_COVER = 'https://covers.openlibrary.org/b/isbn';
const COVER_SIZES = new Set(['small', 'medium', 'large']);
const COVER_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const COVER_FAILURE_TTL_MS = 10 * 60 * 1000;
const COVER_CACHE_MAX_ENTRIES = 800;

// The wishlist lives in SQLite so the same API can back the Android app later.
const DB_PATH = process.env.UBLOTSE_DB || path.join(__dirname, 'data', 'ublotse.db');
const CLIENT_ID_RE = /^[A-Za-z0-9_-]{6,64}$/;
const DEFAULT_CLIENT_ID = 'local';
const MAX_BODY_BYTES = 64 * 1024;

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

// Catch-all indexes that get relevance anchoring (see anchorCatchAll).
const CATCH_ALL_INDEXES = new Set([INDEXES.serverChoice, INDEXES.anywhere]);

// Words that carry no weight as a title or author anchor. Dropping them makes
// the anchor clauses narrower without making them any less of a subset of the
// broad query.
const ANCHOR_STOPWORDS = new Set([
  'and', 'or', 'of', 'the', 'a', 'an', 'in', 'on', 'for', 'to', 'with', 'by',
  'und', 'oder', 'der', 'die', 'das', 'den', 'dem', 'des', 'ein', 'eine', 'einer',
  'van', 'von', 'de', 'la', 'le', 'les', 'el', 'los', 'il', 'lo', 'y', 'e',
]);

// Anchoring emits two clauses per adjacent word pair plus one per ordered
// title/creator pair, so it is only applied to the short queries people
// actually type — and is bounded by both a token count and a byte budget.
const MAX_ANCHOR_TOKENS = 6;
const MAX_ANCHOR_LENGTH = 3000;

function escapeCqlValue(value) {
  return value.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
}

/** The plain "every term matches somewhere in the record" query. */
function broadCql(idx, tokens) {
  return tokens.map((t) => `${idx}="${escapeCqlValue(t)}"`).join(' AND ');
}

/**
 * Anchor a catch-all query on title/creator pairs.
 *
 * `cql.serverChoice` carries no usable relevance ranking. "Lolita Nabokov"
 * returns hundreds of records *about* the novel and buries the novel itself;
 * "Progress and Poverty Henry George" ranks Henry George's own book 35th, behind
 * thirty reviews and journal articles. A fielded `dc.title` / `dc.creator`
 * clause, by contrast, is scored the way the catalogue's own frontend scores it.
 * OR-ing those into the broad query keeps recall (and the reported total)
 * intact while floating records where one term is the title and another the
 * author to the top — whichever order they were typed in.
 *
 * Two shapes of anchor are emitted:
 *
 *   - `dc.title="t" AND dc.creator="u"`, for every ordered pair of content
 *     words — this is what finds "Lolita Nabokov";
 *   - `dc.creator="U, T"`, a *catalogue-form name phrase* built from every
 *     adjacent pair, e.g. “…Henry George” -> `dc.creator="George, Henry"`.
 *     Single tokens are worthless here (`dc.creator="George"` matches ~694k
 *     records) whereas the inverted phrase is highly selective. `dc.creator`
 *     only answers to that inverted form: `dc.creator="Henry George"` matches
 *     nothing at all.
 *
 * Every anchor is narrowed by the remaining words through the catch-all index,
 * and the plain broad query is always OR-ed in last so the total never
 * collapses. The name phrases come first: they are the more selective of the
 * two, and the byte budget must not spend itself on the weaker pairs.
 */
function anchorCatchAll(idx, tokens) {
  const content = tokens.filter((t) => !ANCHOR_STOPWORDS.has(t.toLowerCase()));
  if (content.length < 2 || content.length > MAX_ANCHOR_TOKENS) return broadCql(idx, tokens);

  const term = (t) => `${idx}="${escapeCqlValue(t)}"`;
  const restOf = (skip) =>
    content
      .filter((_, k) => !skip.includes(k))
      .map(term)
      .join(' AND ');
  const anchored = [];
  let used = 0;
  const add = (clause) => {
    if (used + clause.length > MAX_ANCHOR_LENGTH) return false;
    anchored.push(clause);
    used += clause.length + 4; // + ' OR '
    return true;
  };

  // Catalogue-form name phrases from adjacent words, in both orders, so that
  // "Henry George" and "George Henry" both reach `dc.creator="George, Henry"`.
  for (let k = 0; k + 1 < content.length; k += 1) {
    const rest = restOf([k, k + 1]);
    if (!rest) continue; // a bare creator phrase is far too broad to anchor on
    for (const phrase of [`${content[k + 1]}, ${content[k]}`, `${content[k]}, ${content[k + 1]}`]) {
      if (!add(`(dc.creator="${escapeCqlValue(phrase)}" AND ${rest})`)) break;
    }
  }

  // A title word plus an author word, for every ordered pair.
  for (let i = 0; i < content.length; i += 1) {
    for (let j = 0; j < content.length; j += 1) {
      if (i === j) continue;
      const rest = restOf([i, j]);
      const clause =
        `(dc.title="${escapeCqlValue(content[i])}" AND dc.creator="${escapeCqlValue(content[j])}"` +
        `${rest ? ` AND ${rest}` : ''})`;
      if (!add(clause)) return `(${anchored.join(' OR ')}) OR (${broadCql(idx, tokens)})`;
    }
  }

  return `(${anchored.join(' OR ')}) OR (${broadCql(idx, tokens)})`;
}

/**
 * Turn a user query into CQL.
 *
 * The de15 server has no usable sort and no date ranges, but it does accept
 * boolean CQL. We AND the individual words the way VuFind's default search
 * does: "grundlagen physik" -> serverChoice="grundlagen" AND serverChoice="physik".
 * Catch-all queries are additionally anchored on title/creator pairs so the
 * work itself outranks commentary about it (see `anchorCatchAll`).
 *
 * `raw` passes a hand-written CQL query straight through (advanced mode) and
 * `anchors: false` returns the plain broad query — used for display, so the
 * result bar does not show the scaffolding.
 */
function buildCql({ index, q, raw, anchors = true }) {
  if (raw) return raw.trim();

  const idx = INDEXES[index] || INDEXES.serverChoice;
  const query = q.trim();
  if (!query) return '';

  if (SINGLE_TERM_INDEXES.has(index)) {
    return `${idx}="${escapeCqlValue(query)}"`;
  }

  // Punctuation around a word is never meaningful to CQL and does real damage
  // inside an anchor (`dc.creator="Henry, George,"`), so trim it off here.
  const tokens = query
    .split(/\s+/)
    .map((t) => t.replace(/^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$/gu, ''))
    .filter(Boolean);
  if (anchors && CATCH_ALL_INDEXES.has(idx)) return anchorCatchAll(idx, tokens);
  return broadCql(idx, tokens);
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

/** Map one VuFind Solr document onto UBLotse's record shape. */
function mapSolrDoc(doc) {
  const arr = (name) => doc[name] || [];
  const str = (name) => doc[name] || '';

  // VuFind splits names in two: `author` is the work's own author, `author2`
  // holds translators, editors, performers and the like — but audiovisual and
  // some ebook records carry every name in `author2` with a primary author in
  // neither, and `author`-less records are common. Reading only `author2` (as
  // this did) silently dropped the author of every ordinary monograph, so
  // Henry George's "Progress and Poverty" showed up with no author at all.
  // Roles live in parallel arrays that are not always the same length as the
  // names, so every name is paired with its role defensively.
  const byName = new Map();
  const addNames = (names, roles, primary) => {
    names.forEach((name, i) => {
      const role = roles[i] || '';
      const candidate = {
        name,
        role,
        // "aut" is the default, not a distinguishing role; marking it would
        // print "(Verfasser)" after every card's first author.
        roleLabel: role === 'aut' || primary ? '' : ROLE_LABELS[role] || '',
      };
      const existing = byName.get(name);
      if (!existing || (!existing.roleLabel && candidate.roleLabel)) byName.set(name, candidate);
    });
  };
  addNames(arr('author'), arr('author_role'), true);
  addNames(arr('author2'), arr('author2_role'), false);
  addNames(arr('author_corporate'), arr('author_corporate_role'), false);
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
    headers: { 'User-Agent': USER_AGENT },
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
    author: countBy(records, (r) => r.authors.map((a) => a.name)),
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
// Cover proxy
// ---------------------------------------------------------------------------

// The catalogue's cover service answers a cookie-less browser with an HTML page
// that sets `finc_open=1` and reloads (HTTP 419), so a cross-origin <img> never
// gets an image. Server-side we can send that cookie, so covers are fetched
// here, de-duplicated in memory and re-served from our own origin.
const coverCache = new Map();

async function fetchCover(isbn, size) {
  const cacheKey = `${isbn}|${size}`;
  const cached = coverCache.get(cacheKey);
  if (cached && cached.expires > Date.now()) return cached;

  let result = { ok: false };
  const sources = [
    `${COVER_SERVICE}?isbn=${isbn}&size=${size}`,
    `${OPENLIBRARY_COVER}/${isbn}-M.jpg?default=false`, // fills the gaps UBL leaves
  ];

  for (const source of sources) {
    try {
      const response = await fetch(source, {
        redirect: 'follow',
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
        headers: { Cookie: 'finc_open=1', 'User-Agent': USER_AGENT },
      });
      const type = response.headers.get('content-type') || '';
      const body = Buffer.from(await response.arrayBuffer());
      // "No cover" is a 410-byte 67×79 GIF with HTTP 200, not an error status.
      const placeholder = type === 'image/gif' && body.byteLength < 2048;
      if (response.ok && type.startsWith('image/') && !placeholder && body.byteLength) {
        result = { ok: true, type, body };
        break;
      }
    } catch {
      // Off to the next source; a failed cover must never break a search.
    }
  }

  if (coverCache.size >= COVER_CACHE_MAX_ENTRIES) coverCache.delete(coverCache.keys().next().value);
  coverCache.set(cacheKey, {
    ...result,
    expires: Date.now() + (result.ok ? COVER_TTL_MS : COVER_FAILURE_TTL_MS),
  });
  return result;
}

async function handleCover(url, res) {
  const isbn = (url.searchParams.get('isbn') || '').replace(/[^0-9Xx]/g, '');
  if (!looksLikeIsbn(isbn)) {
    sendText(res, 400, 'Ungültige ISBN');
    return;
  }

  const requestedSize = url.searchParams.get('size');
  const cover = await fetchCover(isbn, COVER_SIZES.has(requestedSize) ? requestedSize : 'medium');
  if (!cover.ok) {
    sendText(res, 404, 'Kein Cover');
    return;
  }

  res.writeHead(200, {
    'Content-Type': cover.type,
    'Content-Length': cover.body.byteLength,
    'Cache-Control': 'public, max-age=604800, immutable',
    ...CORS_HEADERS,
  });
  res.end(cover.body);
}

// ---------------------------------------------------------------------------
// Wishlist (SQLite)
// ---------------------------------------------------------------------------

mkdirSync(path.dirname(DB_PATH), { recursive: true });
const db = new DatabaseSync(DB_PATH);
db.exec(`
  CREATE TABLE IF NOT EXISTS wishlist (
    client_id  TEXT NOT NULL,
    record_id  TEXT NOT NULL,
    record     TEXT NOT NULL,
    note       TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL,
    PRIMARY KEY (client_id, record_id)
  );
  CREATE INDEX IF NOT EXISTS wishlist_by_client ON wishlist (client_id, created_at DESC);
`);

const wishlistQuery = {
  list: db.prepare(
    'SELECT record_id, record, note, created_at FROM wishlist WHERE client_id = ? ORDER BY created_at DESC, record_id',
  ),
  count: db.prepare('SELECT COUNT(*) AS count FROM wishlist WHERE client_id = ?'),
  save: db.prepare(`
    INSERT INTO wishlist (client_id, record_id, record, note, created_at)
    VALUES (?, ?, ?, ?, ?)
    ON CONFLICT(client_id, record_id) DO UPDATE SET record = excluded.record, note = excluded.note
  `),
  remove: db.prepare('DELETE FROM wishlist WHERE client_id = ? AND record_id = ?'),
};

// Stable identity for a saved record. Kept byte-for-byte identical in the
// frontend so a card can tell whether it is already on the list.
function wishlistKey(record) {
  if (record?.id) return record.id;
  return `t:${[record?.title, record?.year, record?.isbn?.[0]].filter(Boolean).join('~')}`;
}

// No accounts yet: the client (browser or Android app) mints an id once and
// sends it along. Everything falls back to a shared local list for plain curl.
function clientIdOf(url, req) {
  const candidate = String(req.headers['x-client-id'] || url.searchParams.get('client') || '');
  return CLIENT_ID_RE.test(candidate) ? candidate : DEFAULT_CLIENT_ID;
}

function handleWishlistGet(url, req, res) {
  const clientId = clientIdOf(url, req);
  const items = [];
  for (const row of wishlistQuery.list.all(clientId)) {
    try {
      items.push({ key: row.record_id, note: row.note, addedAt: row.created_at, record: JSON.parse(row.record) });
    } catch {
      // A corrupted row should not take down the whole list.
    }
  }
  sendJson(res, 200, { clientId, count: items.length, items });
}

async function handleWishlistAdd(url, req, res) {
  const clientId = clientIdOf(url, req);
  const body = await readJsonBody(req);
  const record = body?.record;
  if (!record || typeof record !== 'object' || Array.isArray(record) || !record.title) {
    sendJson(res, 400, { error: 'Erwartet: { "record": { "title": … } }' });
    return;
  }

  const snapshot = { ...record };
  delete snapshot._uid; // client-only DOM handle
  const key = wishlistKey(snapshot);
  wishlistQuery.save.run(clientId, key, JSON.stringify(snapshot), String(body.note || ''), new Date().toISOString());
  sendJson(res, 200, { saved: true, key, count: wishlistQuery.count.get(clientId).count });
}

function handleWishlistRemove(url, req, res, rawKey) {
  const clientId = clientIdOf(url, req);
  const key = decodeURIComponent(rawKey);
  const { changes } = wishlistQuery.remove.run(clientId, key);
  sendJson(res, 200, { removed: changes > 0, key, count: wishlistQuery.count.get(clientId).count });
}

// ---------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------

const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, X-Client-Id',
  'Access-Control-Max-Age': '86400',
};

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
    ...CORS_HEADERS,
  });
  res.end(body);
}

function sendText(res, status, text) {
  res.writeHead(status, {
    'Content-Type': 'text/plain; charset=utf-8',
    'Content-Length': Buffer.byteLength(text),
    'Cache-Control': 'public, max-age=86400',
    ...CORS_HEADERS,
  });
  res.end(text);
}

function readJsonBody(req, limit = MAX_BODY_BYTES) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > limit) {
        reject(new Error('Request body too large'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (!chunks.length) {
        resolve(null);
        return;
      }
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch {
        reject(new Error('Invalid JSON body'));
      }
    });
    req.on('error', reject);
  });
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
  const cqlDisplay = buildCql({ index, q, raw, anchors: false });
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
    cqlDisplay,
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

// "Mein Konto" lives in its own module: the SRU index has no per-user data,
// so it logs into the catalogue's VuFind account and parses the loans table.
const account = createAccount({
  catalogueBase: process.env.CATALOGUE_BASE || 'https://katalog.ub.uni-leipzig.de',
  sendJson,
  readJsonBody,
  clientIdOf,
});

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

  try {
    if (req.method === 'OPTIONS') {
      res.writeHead(204, CORS_HEADERS);
      res.end();
      return;
    }

    if (url.pathname === '/api/search') {
      if (req.method !== 'GET') {
        sendJson(res, 405, { error: 'Method not allowed' });
        return;
      }
      await handleSearch(url, res);
      return;
    }

    if (url.pathname === '/api/cover') {
      if (req.method !== 'GET') {
        sendJson(res, 405, { error: 'Method not allowed' });
        return;
      }
      await handleCover(url, res);
      return;
    }

    if (url.pathname === '/api/wishlist') {
      if (req.method === 'GET') {
        handleWishlistGet(url, req, res);
        return;
      }
      if (req.method === 'POST') {
        await handleWishlistAdd(url, req, res);
        return;
      }
      sendJson(res, 405, { error: 'Method not allowed' });
      return;
    }

    if (url.pathname.startsWith('/api/wishlist/')) {
      if (req.method === 'DELETE') {
        handleWishlistRemove(url, req, res, url.pathname.slice('/api/wishlist/'.length));
        return;
      }
      sendJson(res, 405, { error: 'Method not allowed' });
      return;
    }

    if (url.pathname === '/api/account' || url.pathname.startsWith('/api/account/')) {
      await account.handle(url, req, res);
      return;
    }

    if (url.pathname === '/api/health') {
      sendJson(res, 200, {
        ok: true,
        upstream: SRU_BASE,
        database: SRU_DATABASE,
        schema: 'solr',
        wishlist: { backend: 'sqlite', path: DB_PATH },
      });
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
    console.log(`UBLotse  →  http://${HOST}:${PORT}`);
    console.log(`upstream →  ${SRU_BASE}/${SRU_DATABASE} (SRU 1.1, solr schema)`);
    console.log(`wishlist →  ${DB_PATH}`);
  });
}

export {
  buildCql,
  anchorCatchAll,
  decodeEntities,
  parseSolrDoc,
  mapSolrDoc,
  parseSruResponse,
  computeFacets,
  looksLikeIsbn,
  wishlistKey,
};
