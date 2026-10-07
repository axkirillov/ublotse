# lunibib

A faster, cleaner frontend for the catalogue of the **Universitätsbibliothek Leipzig**
(UBL). The long-term goal is an Android app; this repo starts with the prototype — a plain
HTML/JS page on `localhost` backed by a small Node proxy.

The official catalogue at `katalog.ub.uni-leipzig.de` is slow because of its *frontend*
architecture, not because the data is hard to reach. lunibib bypasses the VuFind theme and
talks to the library's open **SRU** endpoint directly.

```
browser  ──/api/search──▶  server.js (proxy + parse + cache)  ──SRU──▶  sru.finc.info/de15
```

## Run it

Requires Node 18+ (uses the built-in `fetch`). No dependencies, no install step.

```sh
cd lunibib
npm start          # or: node server.js
```

Then open <http://127.0.0.1:5173>.

Environment overrides: `PORT`, `HOST`, `SRU_BASE`, `SRU_DATABASE`.

## Why there is a backend at all

`sru.finc.info` does not send `Access-Control-Allow-Origin`, so a browser cannot call it
directly. A ~250-line, zero-dependency Node server fills that gap and, more importantly, is
where the interesting work happens:

- **Parse once, serve JSON.** The SRU returns whole catalogue records; the UI needs a dozen
  fields. The proxy normalises records, formats and holdings so the client stays dumb.
- **Cache.** The index is refreshed daily, so responses are cached in memory for 30 minutes.
  The UI shows whether a result came from SRU or from the cache.
- **Rate-limit discipline.** finc limits automated scripts to 100 records per query; the
  server clamps `maximumRecords` accordingly.

## The one finding that shaped everything

The SRU endpoint offers four record schemas (`operation=explain`): `marcxml`, `turbomarc`,
`dc` and `solr`. We ask for **`solr`**, because it is the only one that carries:

| Field | Why it matters |
| --- | --- |
| `id` (`0-…`, `ai-…`) | the VuFind record id → **exact permalinks** |
| `author2` + `author2_role` | authors with roles — **absent from `marcxml`** |
| `format` | normalised types (Book, CD, Serial, …) for the badge and facets |
| `topic_facet`, `genre_facet` | subjects |
| `branch_de15`, `callnumber_de15` | holdings: branch and call number |
| `url`, `isbn`, `issn`, `oclc_num` | access and identifiers |

Two traps worth recording:

- finc's `marcxml` looks authoritative but **drops all author fields**. A `dc.creator=goethe`
  query returns records whose MARC body has no `100`/`700` at all. The `dc` schema has the
  authors but almost nothing else; `solr` has both.
- Empty MARC fields are serialised as **self-closing** tags (`<controlfield tag="001"/>`,
  `<subfield code="a"/>`). A naive lazy regex then swallows the next field. (This bit an
  earlier MARCXML-based version of this parser; the Solr parser sidesteps it.)

## What the prototype does

- Live, debounced search over the de15 SRU index; no page reloads.
- Field selector: all fields, title, author, subject, publisher, ISBN, ISSN, full text.
- Result cards: type badges, title, clickable authors (with roles), imprint, holdings
  (branch + call number), clickable subject chips, ISBN/ISSN/OCLC, cover thumbnail.
- Exact permalinks to the record in the official catalogue.
- Client-side facets (type, decade, language) and sorting over the loaded results.
- “Cite” dialog with a formatted citation and BibTeX, copy to clipboard.
- In-memory response cache; the UI shows whether a result came from SRU or the cache.
- Shareable URL state (`?q=…&index=…`).
- Keyboard: `/` focuses search, `Enter` searches, `Esc` clears.

## SRU notes (verified against the live endpoint)

| What | Result |
| --- | --- |
| Base | `https://sru.finc.info/de15` |
| Schema used | `recordSchema=solr` (also `marcxml`, `dc`, `turbomarc`) |
| Indexes | `cql.serverChoice`, `cql.anywhere`, `dc.title`, `dc.creator`, `dc.subject`, `dc.publisher`, `dc.date`, `dc.identifier` (ISBN), `bath.issn`, `bath.corporateName`, `bath.conferenceName` |
| Paging | `startRecord` + `maximumRecords` (≤ 100) works |
| Sorting | **not supported** (`sortKeys` → diagnostic 80) |
| Date ranges | **not supported** (`dc.date>=…` errors); `dc.date=YYYY` is unreliable |
| Facets | not offered over SRU — the sidebar is computed client-side over loaded results |
| VuFind REST | `/api/v1/search` is currently behind an anti-bot cookie and a maintenance page — hence SRU |

Because SRU exposes no facet index, the sidebar filters the loaded page of results. Clicking
an author or subject, in contrast, runs a real server-side search.

Known data quirks: authors are still missing for some records even in `solr`; ISBN-13 values
that live only in MARC `024` are not found by the `dc.identifier` index (ISBN-10s from `020`
are); `publishDate` can be a range like `2010-`, so the year is taken from `publishDateSort`
first.

## Roadmap

- Server-side facets (would need the Solr endpoint directly, if it can be reached politely).
- Availability: the Solr holdings are a snapshot; live item status needs the ILS (Libero).
- Android client against this same `/api/search` contract.
- Persist the cache (SQLite) and warm it for popular queries.
- Harden the Solr-document parser against schema drift; fall back to `dc` if needed.

## Layout

```
lunibib/
├── server.js        # zero-dep HTTP server: static files + /api/search proxy
├── public/
│   ├── index.html   # markup
│   ├── app.js       # frontend (plain ES modules)
│   └── style.css
└── package.json
```

## Data source

SRU endpoint operated by finc / UBL: <https://sru.finc.info/de15?operation=explain&version=1.1>.
The index is updated daily from the local library system (Libero). Be polite: this prototype
caches aggressively and never requests more than 100 records at once.
