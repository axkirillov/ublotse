package de.axkirillov.ublotse;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code /api/*} contract, ported route for route from {@code server.js} so the shipped
 * frontend cannot tell the two apart. Deliberately Android-free: everything here runs on a plain
 * JVM, which is the only way to test it without an emulator.
 */
public class UblotseApi {

  public static final int MAX_RECORDS = 100; // finc: automated scripts are limited to 100 records/query
  public static final int DEFAULT_LIMIT = 20;
  public static final long CACHE_TTL_MS = 30L * 60 * 1000; // the SRU index is refreshed daily
  public static final int CACHE_MAX_ENTRIES = 500;
  public static final int MAX_BODY_BYTES = 64 * 1024;

  private static final Set<String> COVER_SIZES =
      new HashSet<>(Arrays.asList("small", "medium", "large"));

  private final Sru sru;
  private final Covers covers;
  private final WishlistStore wishlist;
  private final Account account;
  private final TtlCache<Map<String, Object>> searchCache =
      new TtlCache<>(CACHE_MAX_ENTRIES, CACHE_TTL_MS);

  public UblotseApi(Sru sru, Covers covers, WishlistStore wishlist) {
    this(sru, covers, wishlist, new Account());
  }

  public UblotseApi(Sru sru, Covers covers, WishlistStore wishlist, Account account) {
    this.sru = sru;
    this.covers = covers;
    this.wishlist = wishlist;
    this.account = account;
  }

  /**
   * Route one request. {@code path} arrives percent-encoded, as the request line gave it, and
   * {@code clientId} is resolved by the caller — the app substitutes a per-install id for the
   * browser's per-profile one. Returns null when the path is not an API path at all, letting the
   * caller fall through to the bundled frontend.
   */
  public WebResponse handle(
      String method, String path, Map<String, String> query, byte[] body, String clientId) {
    if (!path.startsWith("/api/")) return null;

    try {
      if ("OPTIONS".equals(method)) return WebResponse.empty(204);

      if ("/api/search".equals(path)) {
        if (!"GET".equals(method)) return methodNotAllowed();
        return search(query);
      }
      if ("/api/cover".equals(path)) {
        if (!"GET".equals(method)) return methodNotAllowed();
        return cover(query);
      }
      if ("/api/wishlist".equals(path)) {
        if ("GET".equals(method)) return wishlistGet(clientId);
        if ("POST".equals(method)) return wishlistAdd(clientId, body);
        return methodNotAllowed();
      }
      if (path.startsWith("/api/wishlist/")) {
        if (!"DELETE".equals(method)) return methodNotAllowed();
        return wishlistRemove(clientId, path.substring("/api/wishlist/".length()));
      }
      // "Mein Konto" is its own module: the SRU index has no per-user data, so it logs into the
      // catalogue's VuFind account instead of the search index.
      if ("/api/account".equals(path) || path.startsWith("/api/account/")) {
        return account.handle(method, path, body, clientId);
      }
      if ("/api/health".equals(path)) {
        return WebResponse.json(
            200,
            Json.obj(
                "ok",
                true,
                "upstream",
                sru.base(),
                "database",
                sru.database(),
                "schema",
                "solr",
                "wishlist",
                Json.obj("backend", "sqlite", "path", wishlist.describe())));
      }
      return WebResponse.json(404, Json.obj("error", "Not found"));
    } catch (Exception error) {
      String message = error.getMessage();
      return WebResponse.json(502, Json.obj("error", message == null ? "Upstream request failed" : message));
    }
  }

  private static WebResponse methodNotAllowed() {
    return WebResponse.json(405, Json.obj("error", "Method not allowed"));
  }

  // -------------------------------------------------------------------------
  // /api/search
  // -------------------------------------------------------------------------

  private WebResponse search(Map<String, String> query) throws IOException {
    String q = Js.trim(opt(query, "q", ""));
    String index = opt(query, "index", "serverChoice");
    String raw = opt(query, "raw", "");
    long page = positive(opt(query, "page", ""), 1);
    long limit = Math.min(MAX_RECORDS, positive(opt(query, "limit", ""), DEFAULT_LIMIT));

    if (q.isEmpty() && raw.isEmpty()) {
      return WebResponse.json(400, Json.obj("error", "Missing query (q)"));
    }

    String cql = Cql.build(index, q, raw, true);
    String cqlDisplay = Cql.build(index, q, raw, false);
    long startRecord = (page - 1) * limit + 1;
    String cacheKey = cql + "|" + startRecord + "|" + limit;

    Map<String, Object> cached = searchCache.get(cacheKey);
    if (cached != null) return WebResponse.json(200, withCached(cached, true));

    long startedAt = System.nanoTime();
    Sru.Page parsed = sru.search(cql, startRecord, (int) limit);
    long tookMs = Math.round((System.nanoTime() - startedAt) / 1000000.0);

    Map<String, Object> payload =
        Json.obj(
            "query",
            q,
            "index",
            index,
            "cql",
            cql,
            "cqlDisplay",
            cqlDisplay,
            "source",
            "sru:solr",
            "total",
            parsed.total,
            "page",
            page,
            "limit",
            limit,
            "startRecord",
            startRecord,
            "nextRecordPosition",
            parsed.nextRecordPosition,
            "hasMore",
            parsed.nextRecordPosition > 0,
            "tookMs",
            tookMs,
            "facets",
            Solr.computeFacets(parsed.records),
            "records",
            new ArrayList<Object>(parsed.records));

    searchCache.put(cacheKey, payload);
    return WebResponse.json(200, withCached(payload, false));
  }

  private static Map<String, Object> withCached(Map<String, Object> payload, boolean cached) {
    Map<String, Object> copy = new LinkedHashMap<>(payload);
    copy.put("cached", cached);
    return copy;
  }

  // -------------------------------------------------------------------------
  // /api/cover
  // -------------------------------------------------------------------------

  private WebResponse cover(Map<String, String> query) {
    String isbn = opt(query, "isbn", "").replaceAll("[^0-9Xx]", "");
    if (!Solr.looksLikeIsbn(isbn)) return WebResponse.text(400, "Ungültige ISBN");

    String requested = opt(query, "size", "");
    Covers.Cover cover = covers.get(isbn, COVER_SIZES.contains(requested) ? requested : "medium");
    if (!cover.ok) return WebResponse.text(404, "Kein Cover");

    return WebResponse.bytes(200, cover.type, cover.body, "public, max-age=604800, immutable");
  }

  // -------------------------------------------------------------------------
  // /api/wishlist
  // -------------------------------------------------------------------------

  private WebResponse wishlistGet(String clientId) {
    List<Object> items = new ArrayList<>();
    for (Map<String, Object> row : wishlist.list(clientId)) {
      Map<String, Object> item =
          WishlistSql.item(
              String.valueOf(row.get("record_id")),
              String.valueOf(row.get("record")),
              String.valueOf(row.get("note")),
              String.valueOf(row.get("created_at")));
      if (item != null) items.add(item); // a corrupted row should not take down the whole list
    }
    return WebResponse.json(
        200, Json.obj("clientId", clientId, "count", (long) items.size(), "items", items));
  }

  private WebResponse wishlistAdd(String clientId, byte[] body) throws IOException {
    Object parsed;
    if (body == null || body.length == 0) {
      parsed = null;
    } else {
      try {
        parsed = Json.parse(new String(body, "UTF-8"));
      } catch (IllegalArgumentException invalid) {
        throw new IOException("Invalid JSON body");
      }
    }

    Map<String, Object> envelope = asMap(parsed);
    Map<String, Object> record = envelope == null ? null : asMap(envelope.get("record"));
    Object title = record == null ? null : record.get("title");
    if (record == null || !(title instanceof String) || ((String) title).isEmpty()) {
      return WebResponse.json(400, Json.obj("error", "Erwartet: { \"record\": { \"title\": … } }"));
    }

    Map<String, Object> snapshot = new LinkedHashMap<>(record);
    snapshot.remove("_uid"); // client-only DOM handle
    String key = WishlistSql.keyOf(snapshot);
    Object note = envelope.get("note");
    wishlist.save(
        clientId,
        key,
        Json.write(snapshot),
        note == null ? "" : String.valueOf(note),
        WishlistSql.nowIso());
    return WebResponse.json(200, Json.obj("saved", true, "key", key, "count", wishlist.count(clientId)));
  }

  private WebResponse wishlistRemove(String clientId, String rawKey) {
    String key = Http.decodeUriComponent(rawKey);
    boolean removed = wishlist.remove(clientId, key);
    return WebResponse.json(
        200, Json.obj("removed", removed, "key", key, "count", wishlist.count(clientId)));
  }

  // -------------------------------------------------------------------------
  // Parameter helpers, matching the loose `Number(x) || fallback` coercion
  // -------------------------------------------------------------------------

  private static String opt(Map<String, String> query, String key, String fallback) {
    String value = query == null ? null : query.get(key);
    return value == null ? fallback : value;
  }

  private static long positive(String raw, long fallback) {
    long value = number(raw, 0);
    if (value == 0) value = fallback;
    return Math.max(1, value);
  }

  private static long number(String raw, long fallback) {
    if (raw == null) return fallback;
    String trimmed = Js.trim(raw);
    if (trimmed.isEmpty()) return fallback;
    try {
      return Long.parseLong(trimmed);
    } catch (NumberFormatException notANumber) {
      return fallback;
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return value instanceof Map ? (Map<String, Object>) value : null;
  }

  static String utf8(byte[] bytes) throws UnsupportedEncodingException {
    return new String(bytes, "UTF-8");
  }
}
