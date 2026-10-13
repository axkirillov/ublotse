package de.axkirillov.ublotse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CQL query building — a line-by-line port of {@code server.js}.
 *
 * This is the one part of the app that must not drift from the server: the two
 * are expected to emit byte-identical CQL for the same input, and the JVM test
 * suite asserts exactly that against the Node implementation.
 */
public final class Cql {

  private Cql() {}

  /** The indexes the de15 SRU actually exposes (see operation=explain). */
  public static final Map<String, String> INDEXES;

  static {
    Map<String, String> indexes = new LinkedHashMap<>();
    indexes.put("serverChoice", "cql.serverChoice"); // all metadata fields
    indexes.put("anywhere", "cql.anywhere"); // all metadata fields + full texts
    indexes.put("title", "dc.title");
    indexes.put("creator", "dc.creator");
    indexes.put("subject", "dc.subject");
    indexes.put("publisher", "dc.publisher");
    indexes.put("date", "dc.date");
    indexes.put("identifier", "dc.identifier"); // ISBN
    indexes.put("issn", "bath.issn");
    indexes.put("corporateName", "bath.corporateName");
    indexes.put("conferenceName", "bath.conferenceName");
    INDEXES = Collections.unmodifiableMap(indexes);
  }

  private static final Set<String> SINGLE_TERM_INDEXES =
      new LinkedHashSet<>(Arrays.asList("identifier", "issn"));

  private static final Set<String> CATCH_ALL_INDEXES =
      new LinkedHashSet<>(Arrays.asList(INDEXES.get("serverChoice"), INDEXES.get("anywhere")));

  /**
   * Words that carry no weight as a title or author anchor. Dropping them makes
   * the anchor clauses narrower without making them any less of a subset of the
   * broad query.
   */
  private static final Set<String> ANCHOR_STOPWORDS =
      new LinkedHashSet<>(
          Arrays.asList(
              "and", "or", "of", "the", "a", "an", "in", "on", "for", "to", "with", "by",
              "und", "oder", "der", "die", "das", "den", "dem", "des", "ein", "eine", "einer",
              "van", "von", "de", "la", "le", "les", "el", "los", "il", "lo", "y", "e"));

  private static final int MAX_ANCHOR_TOKENS = 6;
  private static final int MAX_ANCHOR_LENGTH = 3000;

  /** Trims CQL-significant punctuation off both ends of a word. */
  private static final String TRIM_PUNCTUATION = "^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$";

  public static String escapeCqlValue(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  /** The plain "every term matches somewhere in the record" query. */
  public static String broadCql(String idx, List<String> tokens) {
    StringBuilder out = new StringBuilder();
    for (String token : tokens) {
      if (out.length() > 0) out.append(" AND ");
      out.append(idx).append("=\"").append(escapeCqlValue(token)).append('"');
    }
    return out.toString();
  }

  /**
   * Anchor a catch-all query on title/creator pairs — see the long comment in
   * {@code server.js} for why this exists and why the name phrases come first.
   */
  public static String anchorCatchAll(String idx, List<String> tokens) {
    List<String> content = new ArrayList<>();
    for (String token : tokens) {
      if (!ANCHOR_STOPWORDS.contains(token.toLowerCase(Locale.ROOT))) content.add(token);
    }
    if (content.size() < 2 || content.size() > MAX_ANCHOR_TOKENS) return broadCql(idx, tokens);

    List<String> anchored = new ArrayList<>();
    int[] used = {0};

    final class Budget {
      boolean add(String clause) {
        if (used[0] + clause.length() > MAX_ANCHOR_LENGTH) return false;
        anchored.add(clause);
        used[0] += clause.length() + 4; // + ' OR '
        return true;
      }
    }
    Budget budget = new Budget();

    // Catalogue-form name phrases from adjacent words, in both orders, so that
    // "Henry George" and "George Henry" both reach dc.creator="George, Henry".
    for (int k = 0; k + 1 < content.size(); k += 1) {
      String rest = restOf(idx, content, k, k + 1);
      if (rest.isEmpty()) continue; // a bare creator phrase is far too broad to anchor on
      String a = content.get(k);
      String b = content.get(k + 1);
      String[] phrases = {b + ", " + a, a + ", " + b};
      for (String phrase : phrases) {
        if (!budget.add("(dc.creator=\"" + escapeCqlValue(phrase) + "\" AND " + rest + ")")) break;
      }
    }

    // A title word plus an author word, for every ordered pair.
    for (int i = 0; i < content.size(); i += 1) {
      for (int j = 0; j < content.size(); j += 1) {
        if (i == j) continue;
        String rest = restOf(idx, content, i, j);
        String clause =
            "(dc.title=\""
                + escapeCqlValue(content.get(i))
                + "\" AND dc.creator=\""
                + escapeCqlValue(content.get(j))
                + "\""
                + (rest.isEmpty() ? "" : " AND " + rest)
                + ")";
        if (!budget.add(clause)) return joined(idx, tokens, anchored);
      }
    }

    return joined(idx, tokens, anchored);
  }

  private static String joined(String idx, List<String> tokens, List<String> anchored) {
    StringBuilder out = new StringBuilder();
    out.append('(');
    for (int i = 0; i < anchored.size(); i += 1) {
      if (i > 0) out.append(" OR ");
      out.append(anchored.get(i));
    }
    out.append(") OR (").append(broadCql(idx, tokens)).append(')');
    return out.toString();
  }

  /** All content words except the two at the given positions, AND-ed together. */
  private static String restOf(String idx, List<String> content, int skipA, int skipB) {
    StringBuilder out = new StringBuilder();
    for (int k = 0; k < content.size(); k += 1) {
      if (k == skipA || k == skipB) continue;
      if (out.length() > 0) out.append(" AND ");
      out.append(idx).append("=\"").append(escapeCqlValue(content.get(k))).append('"');
    }
    return out.toString();
  }

  public static String build(String index, String q) {
    return build(index, q, null, true);
  }

  public static String build(String index, String q, String raw, boolean anchors) {
    if (raw != null && !raw.isEmpty()) return Js.trim(raw);

    String idx = INDEXES.containsKey(index) ? INDEXES.get(index) : INDEXES.get("serverChoice");
    String query = Js.trim(q);
    if (query.isEmpty()) return "";

    if (SINGLE_TERM_INDEXES.contains(index)) {
      return idx + "=\"" + escapeCqlValue(query) + "\"";
    }

    // Punctuation around a word is never meaningful to CQL and does real damage
    // inside an anchor (dc.creator="Henry, George,"), so trim it off here.
    List<String> tokens = new ArrayList<>();
    for (String part : Js.splitWhitespace(query)) {
      String token = part.replaceAll(TRIM_PUNCTUATION, "");
      if (!token.isEmpty()) tokens.add(token);
    }

    if (anchors && CATCH_ALL_INDEXES.contains(idx)) return anchorCatchAll(idx, tokens);
    return broadCql(idx, tokens);
  }
}
