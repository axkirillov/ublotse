package de.axkirillov.ublotse;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The VuFind Solr document parser and record mapper — a port of the
 * {@code server.js} equivalents, kept shape-compatible so the shipped frontend
 * cannot tell the app's backend from the original Node one.
 */
public final class Solr {

  private Solr() {}

  public static final String CATALOGUE_BASE = "https://katalog.ub.uni-leipzig.de";

  private static final Pattern DOC_RE = Pattern.compile("<doc>([\\s\\S]*?)</doc>");
  private static final Pattern SOLR_FIELD_RE =
      Pattern.compile(
          "<(str|arr|int|long|bool|float|double|date) name=\"([^\"]+)\"([^>]*?)(?:/>|>([\\s\\S]*?)</\\1>)");
  private static final Pattern SOLR_STR_RE = Pattern.compile("<str>([\\s\\S]*?)</str>");
  private static final Pattern YEAR_RE = Pattern.compile("\\d{4}");
  private static final Pattern ENTITY_RE = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);");

  // VuFind's format vocabulary, in German. Unknown values pass through unchanged.
  private static final Map<String, String> FORMAT_LABELS;

  // MARC relator codes worth showing next to a name. `oth` is noise.
  private static final Map<String, String> ROLE_LABELS;

  private static final Map<String, String> NAMED_ENTITIES;

  static {
    Map<String, String> entities = new LinkedHashMap<>();
    entities.put("amp", "&");
    entities.put("lt", "<");
    entities.put("gt", ">");
    entities.put("quot", "\"");
    entities.put("apos", "'");
    NAMED_ENTITIES = Collections.unmodifiableMap(entities);

    Map<String, String> formats = new LinkedHashMap<>();
    formats.put("Book", "Buch");
    formats.put("E-Book", "E-Book");
    formats.put("eBook", "E-Book");
    formats.put("Textbook", "Lehrbuch");
    formats.put("Serial", "Zeitschrift");
    formats.put("Journal", "Zeitschrift");
    formats.put("Journal, E-Journal", "Zeitschrift");
    formats.put("Article", "Aufsatz");
    formats.put("ElectronicArticle", "Online-Artikel");
    formats.put("MonographSeries", "Schriftenreihe");
    formats.put("SpokenWord", "Hörbuch");
    formats.put("Audio", "Audio");
    formats.put("CD", "CD");
    formats.put("DVD", "DVD");
    formats.put("Video", "Video");
    formats.put("Map", "Karte");
    formats.put("Score", "Noten");
    formats.put("MusicalScore", "Noten");
    formats.put("Thesis", "Hochschulschrift");
    formats.put("Database", "Datenbank");
    formats.put("Website", "Website");
    formats.put("Conference", "Konferenz");
    formats.put("Reference", "Nachschlagewerk");
    formats.put("Software", "Software");
    formats.put("Image", "Bild");
    formats.put("Manuscript", "Manuskript");
    FORMAT_LABELS = Collections.unmodifiableMap(formats);

    Map<String, String> roles = new LinkedHashMap<>();
    roles.put("aut", "Verfasser");
    roles.put("edt", "Hrsg.");
    roles.put("trl", "Übers.");
    roles.put("ill", "Ill.");
    roles.put("prf", "Sprecher");
    roles.put("drt", "Regie");
    roles.put("cmp", "Komp.");
    roles.put("cnd", "Dirigent");
    roles.put("act", "Darsteller");
    roles.put("com", "Komp.");
    roles.put("arr", "Bearb.");
    roles.put("ctb", "Mitarb.");
    roles.put("mus", "Musik");
    ROLE_LABELS = Collections.unmodifiableMap(roles);
  }

  // -------------------------------------------------------------------------
  // XML helpers
  // -------------------------------------------------------------------------

  public static String decodeEntities(String input) {
    Matcher matcher = ENTITY_RE.matcher(input);
    StringBuffer out = new StringBuffer(input.length());
    while (matcher.find()) {
      String entity = matcher.group(1);
      String replacement = matcher.group(0);
      if (entity.charAt(0) == '#') {
        boolean hex = entity.length() > 1 && (entity.charAt(1) == 'x' || entity.charAt(1) == 'X');
        try {
          int code = Integer.parseInt(hex ? entity.substring(2) : entity.substring(1), hex ? 16 : 10);
          if (code >= 0 && code <= 0x10FFFF) replacement = new String(Character.toChars(code));
        } catch (NumberFormatException ignored) {
          // Unparseable numeric entity: leave it verbatim, as the server does.
        }
      } else {
        String named = NAMED_ENTITIES.get(entity);
        if (named != null) replacement = named;
      }
      matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  /** Collapse whitespace and decode entities — the one normalisation applied to all values. */
  public static String clean(String value) {
    return decodeEntities(Js.collapse(value == null ? "" : value));
  }

  // -------------------------------------------------------------------------
  // Solr-document parsing
  // -------------------------------------------------------------------------

  /** Parse one {@code <doc>} into a map of scalar or list values. */
  public static Map<String, Object> parseSolrDoc(String xml) {
    Map<String, Object> doc = new LinkedHashMap<>();
    Matcher field = SOLR_FIELD_RE.matcher(xml);
    while (field.find()) {
      String kind = field.group(1);
      String name = field.group(2);
      if ("fullrecord".equals(name) || "_version_".equals(name)) continue; // never needed
      String inner = field.group(4) == null ? "" : field.group(4);

      if ("arr".equals(kind)) {
        List<Object> values = new ArrayList<>();
        Matcher str = SOLR_STR_RE.matcher(inner);
        while (str.find()) {
          String value = clean(str.group(1));
          if (!value.isEmpty()) values.add(value);
        }
        doc.put(name, values);
      } else if ("str".equals(kind) || "date".equals(kind)) {
        doc.put(name, clean(inner));
      } else {
        doc.put(name, inner.trim());
      }
    }
    return doc;
  }

  // -------------------------------------------------------------------------
  // Record mapping
  // -------------------------------------------------------------------------

  public static boolean looksLikeIsbn(String value) {
    return value.replaceAll("[-\\s]", "").matches("(?i)^(97[89])?\\d{9}[\\dX]$");
  }

  private static List<String> arr(Map<String, Object> doc, String name) {
    Object value = doc.get(name);
    if (!(value instanceof List)) return new ArrayList<>();
    List<?> list = (List<?>) value;
    List<String> out = new ArrayList<>(list.size());
    for (Object item : list) out.add(String.valueOf(item));
    return out;
  }

  /** Only a real {@code <str>} counts; a list here never happens in practice. */
  private static String str(Map<String, Object> doc, String name) {
    Object value = doc.get(name);
    return value instanceof String ? (String) value : "";
  }

  private static String join(List<String> values, String separator) {
    StringBuilder out = new StringBuilder();
    for (String value : values) {
      if (out.length() > 0) out.append(separator);
      out.append(value);
    }
    return out.toString();
  }

  /** Deduplicating, order-preserving — JavaScript's {@code [...new Set(a)]}. */
  private static <T> List<T> unique(List<T> values) {
    return new ArrayList<>(new LinkedHashSet<>(values));
  }

  public static String yearOf(Map<String, Object> doc) {
    String sorted = str(doc, "publishDateSort");
    if (sorted.matches("^\\d{4}$")) return sorted;    Object published = doc.get("publishDate");
    List<String> values = new ArrayList<>();
    if (published instanceof List) {
      for (Object item : (List<?>) published) values.add(String.valueOf(item));
    } else if (published instanceof String) {
      values.add((String) published);
    }
    for (String value : values) {
      Matcher match = YEAR_RE.matcher(value);
      if (match.find()) return match.group();
    }
    return "";
  }

  private static String catalogueSearchUrl(String title, List<Object> authors) {
    String surname = "";
    if (!authors.isEmpty() && authors.get(0) instanceof Map) {
      Object name = ((Map<?, ?>) authors.get(0)).get("name");
      if (name != null) surname = String.valueOf(name).split(",", -1)[0];
    }
    List<String> parts = new ArrayList<>();
    if (!title.isEmpty()) parts.add(title);
    if (!surname.isEmpty()) parts.add(surname);
    try {
      return CATALOGUE_BASE
          + "/Search/Results?lookfor="
          + URLEncoder.encode(join(parts, " "), "UTF-8")
          + "&type=AllFields";
    } catch (UnsupportedEncodingException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** Escapes exactly like JavaScript's {@code encodeURIComponent}. */
  private static String encodeUriComponent(String value) {
    byte[] bytes;
    try {
      bytes = value.getBytes("UTF-8");
    } catch (UnsupportedEncodingException impossible) {
      throw new IllegalStateException(impossible);
    }
    StringBuilder out = new StringBuilder(bytes.length);
    for (byte b : bytes) {
      int c = b & 0xFF;
      boolean safe =
          (c >= 'A' && c <= 'Z')
              || (c >= 'a' && c <= 'z')
              || (c >= '0' && c <= '9')
              || c == '-'
              || c == '_'
              || c == '.'
              || c == '!'
              || c == '~'
              || c == '*'
              || c == '\''
              || c == '('
              || c == ')';
      if (safe) out.append((char) c);
      else out.append('%').append(String.format("%02X", c));
    }
    return out.toString();
  }

  /** Map one VuFind Solr document onto UBLotse's record shape. */
  public static Map<String, Object> mapSolrDoc(Map<String, Object> doc) {
    // VuFind splits names in two: `author` is the work's own author, `author2`
    // holds translators, editors, performers and the like — but audiovisual and
    // some ebook records carry every name in `author2` with a primary author in
    // neither, and `author`-less records are common. Roles live in parallel
    // arrays that are not always the same length as the names, so every name is
    // paired with its role defensively.
    Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
    addNames(byName, arr(doc, "author"), arr(doc, "author_role"), true);
    addNames(byName, arr(doc, "author2"), arr(doc, "author2_role"), false);
    addNames(byName, arr(doc, "author_corporate"), arr(doc, "author_corporate_role"), false);
    List<Object> authors = new ArrayList<Object>(byName.values());

    List<String> formats = new ArrayList<>();
    for (String format : arr(doc, "format")) {
      formats.add(FORMAT_LABELS.containsKey(format) ? FORMAT_LABELS.get(format) : format);
    }
    formats = unique(formats);

    List<String> urls = unique(arr(doc, "url"));

    List<String> isbn = new ArrayList<>(arr(doc, "isbn"));
    for (String candidate : arr(doc, "marc024a_ct_mv")) {
      if (looksLikeIsbn(candidate)) isbn.add(candidate);
    }
    isbn = unique(isbn);

    List<String> branches = arr(doc, "branch_de15");
    List<String> callnumbers = unique(arr(doc, "callnumber_de15"));
    boolean online = !urls.isEmpty() || arr(doc, "facet_avail").contains("Free");
    Map<String, Object> availability = null;
    if (!branches.isEmpty() || !callnumbers.isEmpty() || online) {
      List<Object> shown = new ArrayList<>();
      shown.addAll(callnumbers.subList(0, Math.min(4, callnumbers.size())));
      availability =
          Json.obj(
              "branch", branches.isEmpty() ? "" : branches.get(0),
              "callnumbers", shown,
              "online", online);
    }

    String id = str(doc, "id");
    String title = str(doc, "title");
    if (title.isEmpty()) title = str(doc, "title_short");
    if (title.isEmpty()) title = "Ohne Titel";
    String part = str(doc, "title_part_str");

    List<String> subjects = new ArrayList<>(arr(doc, "topic_facet"));
    subjects.addAll(arr(doc, "topic"));
    subjects = unique(subjects);
    List<Object> subjectList = new ArrayList<>();
    subjectList.addAll(subjects.subList(0, Math.min(8, subjects.size())));

    Map<String, Object> record = new LinkedHashMap<>();
    record.put("id", id);
    record.put("title", title);
    record.put("subtitle", !part.isEmpty() && !part.equals(title) ? part : "");
    record.put("titleFull", str(doc, "title_full"));
    record.put("authors", authors);
    record.put("place", join(arr(doc, "publishPlace"), ", "));
    record.put("publisher", join(arr(doc, "publisher"), ", "));
    record.put("year", yearOf(doc));
    record.put("imprint", str(doc, "imprint"));
    record.put("isbn", isbn);
    record.put("issn", arr(doc, "issn"));
    record.put("oclc", arr(doc, "oclc_num"));
    record.put("subjects", subjectList);
    record.put("genres", arr(doc, "genre_facet"));
    record.put("languages", arr(doc, "language"));
    record.put("formats", formats);
    record.put("type", formats.isEmpty() ? "Medium" : formats.get(0));
    record.put("physical", join(arr(doc, "physical"), "; "));
    record.put("series", arr(doc, "series2"));
    record.put("summary", join(arr(doc, "footnote"), " "));
    record.put("urls", urls);
    record.put("availability", availability);
    record.put("parentTitle", str(doc, "hierarchy_top_title"));
    record.put("source", "solr");
    record.put(
        "recordUrl",
        id.isEmpty()
            ? catalogueSearchUrl(title, authors)
            : CATALOGUE_BASE + "/Record/" + encodeUriComponent(id));
    return record;
  }

  private static void addNames(
      Map<String, Map<String, Object>> byName, List<String> names, List<String> roles, boolean primary) {
    for (int i = 0; i < names.size(); i += 1) {
      String name = names.get(i);
      String role = i < roles.size() ? roles.get(i) : "";
      // "aut" is the default, not a distinguishing role; marking it would print
      // "(Verfasser)" after every card's first author.
      String roleLabel = "";
      if (!"aut".equals(role) && !primary && ROLE_LABELS.containsKey(role)) {
        roleLabel = ROLE_LABELS.get(role);
      }
      Map<String, Object> existing = byName.get(name);
      boolean upgrade = existing != null && isEmpty(existing.get("roleLabel")) && !roleLabel.isEmpty();
      // Re-putting keeps the original insertion order, so the primary author stays first.
      if (existing == null || upgrade) {
        byName.put(name, Json.obj("name", name, "role", role, "roleLabel", roleLabel));
      }
    }
  }

  private static boolean isEmpty(Object value) {
    return value == null || String.valueOf(value).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Facets
  // -------------------------------------------------------------------------

  private interface Values {
    List<String> of(Map<String, Object> record);
  }

  private static List<Map<String, Object>> countBy(List<Map<String, Object>> records, Values values) {
    final Map<String, Integer> counts = new LinkedHashMap<>();
    for (Map<String, Object> record : records) {
      for (String value : values.of(record)) {
        if (value == null || value.isEmpty()) continue;
        Integer current = counts.get(value);
        counts.put(value, current == null ? 1 : current + 1);
      }
    }
    List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
    // A fresh collator per call: Collator is not thread-safe and this runs on
    // every search from whichever HTTP worker thread happens to pick it up.
    final Collator german = Collator.getInstance(Locale.GERMAN);
    Collections.sort(
        entries,
        (a, b) -> {
          int byCount = b.getValue() - a.getValue();
          return byCount != 0 ? byCount : german.compare(a.getKey(), b.getKey());
        });
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : entries) {
      if (out.size() >= 12) break;
      out.add(Json.obj("value", entry.getKey(), "count", (long) entry.getValue().intValue()));
    }
    return out;
  }

  private static List<String> one(String value) {
    return value == null || value.isEmpty()
        ? Collections.<String>emptyList()
        : Collections.singletonList(value);
  }

  private static List<String> strings(Object value) {
    List<String> out = new ArrayList<>();
    if (value instanceof List) {
      for (Object item : (List<?>) value) out.add(String.valueOf(item));
    } else if (value instanceof String) {
      out.add((String) value);
    }
    return out;
  }

  private static String text(Map<String, Object> record, String key) {
    Object value = record.get(key);
    return value instanceof String ? (String) value : "";
  }

  public static Map<String, Object> computeFacets(List<Map<String, Object>> records) {
    Map<String, Object> facets = new LinkedHashMap<>();
    facets.put("type", countBy(records, r -> one(text(r, "type"))));
    facets.put("language", countBy(records, r -> strings(r.get("languages"))));
    facets.put(
        "decade",
        countBy(records, r -> {
          String year = text(r, "year");
          return year.length() >= 3 ? one(year.substring(0, 3) + "0") : Collections.<String>emptyList();
        }));
    facets.put(
        "author",
        countBy(records, r -> {
          List<String> names = new ArrayList<>();
          Object authors = r.get("authors");
          if (authors instanceof List) {
            for (Object author : (List<?>) authors) {
              if (author instanceof Map) names.add(String.valueOf(((Map<?, ?>) author).get("name")));
            }
          }
          return names;
        }));
    return facets;
  }

  /** Splits a whole SRU document into its {@code <doc>} elements. */
  static List<String> splitDocs(String xml) {
    List<String> docs = new ArrayList<>();
    Matcher matcher = DOC_RE.matcher(xml);
    while (matcher.find()) docs.add(matcher.group(1));
    return docs;
  }
}
