package de.axkirillov.ublotse;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A minimal SRU 1.1 client for finc — one request, one parsed page. Ported from {@code
 * sruSearch}/{@code parseSruResponse} in {@code server.js}.
 */
public class Sru {

  public static final String DEFAULT_BASE = "https://sru.finc.info";
  public static final String DEFAULT_DATABASE = "de15";
  public static final String USER_AGENT = "ublotse-android/0.1 (+https://github.com/axkirillov/ublotse)";
  public static final int DEFAULT_TIMEOUT_MS = 12000;

  private static final Pattern DIAGNOSTIC_RE =
      Pattern.compile("<diag:message>([\\s\\S]*?)</diag:message>");
  private static final Pattern TOTAL_RE = Pattern.compile("<zs:numberOfRecords>(\\d+)");
  private static final Pattern NEXT_RE = Pattern.compile("<zs:nextRecordPosition>(\\d+)");

  public static final class Page {
    public final long total;
    public final long nextRecordPosition;
    public final List<Map<String, Object>> records;

    Page(long total, long nextRecordPosition, List<Map<String, Object>> records) {
      this.total = total;
      this.nextRecordPosition = nextRecordPosition;
      this.records = records;
    }
  }

  private final String base;
  private final String database;
  private final int timeoutMs;

  public Sru() {
    this(DEFAULT_BASE, DEFAULT_DATABASE, DEFAULT_TIMEOUT_MS);
  }

  public Sru(String base, String database, int timeoutMs) {
    this.base = base;
    this.database = database;
    this.timeoutMs = timeoutMs;
  }

  public String base() {
    return base;
  }

  public String database() {
    return database;
  }

  /** Ask the catalogue for one page. Always the `solr` record schema. */
  public Page search(String cql, long startRecord, int maximumRecords) throws IOException {
    List<String> pairs = new ArrayList<>();
    pairs.add("operation");
    pairs.add("searchRetrieve");
    pairs.add("version");
    pairs.add("1.1");
    pairs.add("query");
    pairs.add(cql);
    pairs.add("startRecord");
    pairs.add(String.valueOf(startRecord));
    pairs.add("maximumRecords");
    pairs.add(String.valueOf(maximumRecords));
    pairs.add("recordSchema");
    pairs.add("solr");

    StringBuilder url = new StringBuilder(base).append('/').append(database).append('?');
    for (int i = 0; i < pairs.size(); i += 2) {
      if (i > 0) url.append('&');
      url.append(pairs.get(i)).append('=').append(formEncode(pairs.get(i + 1)));
    }

    Http.Response response =
        Http.get(url.toString(), Http.headers("User-Agent", USER_AGENT), timeoutMs);
    if (response.status != 200) {
      throw new IOException("SRU responded with HTTP " + response.status);
    }
    return parse(new String(response.body, "UTF-8"));
  }

  /** `application/x-www-form-urlencoded`, the same escaping `URLSearchParams` uses. */
  private static String formEncode(String value) {
    try {
      return URLEncoder.encode(value, "UTF-8");
    } catch (UnsupportedEncodingException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static Page parse(String xml) throws IOException {
    Matcher diagnostic = DIAGNOSTIC_RE.matcher(xml);
    if (diagnostic.find()) throw new IOException(Solr.clean(diagnostic.group(1)));

    long total = number(TOTAL_RE, xml);
    long next = number(NEXT_RE, xml);

    List<Map<String, Object>> records = new ArrayList<>();
    for (String doc : Solr.splitDocs(xml)) {
      try {
        records.add(Solr.mapSolrDoc(Solr.parseSolrDoc(doc)));
      } catch (RuntimeException ignored) {
        // A single malformed record should never take down the whole page.
      }
    }
    return new Page(total, next, Collections.unmodifiableList(records));
  }

  private static long number(Pattern pattern, String xml) {
    Matcher matcher = pattern.matcher(xml);
    if (!matcher.find()) return 0;
    try {
      return Long.parseLong(matcher.group(1));
    } catch (NumberFormatException unexpected) {
      return 0;
    }
  }

  static Map<String, String> queryParams(String cql, long startRecord, int maximumRecords) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("operation", "searchRetrieve");
    params.put("version", "1.1");
    params.put("query", cql);
    params.put("startRecord", String.valueOf(startRecord));
    params.put("maximumRecords", String.valueOf(maximumRecords));
    params.put("recordSchema", "solr");
    return params;
  }
}
