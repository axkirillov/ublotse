package de.axkirillov.ublotse;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * The wishlist's SQL and the two pieces of logic that must stay byte-identical to the browser
 * frontend: the record key and the ISO timestamp format. Kept free of Android imports so a plain
 * JVM test can run the very same statements through the {@code sqlite3} CLI.
 */
public final class WishlistSql {

  private WishlistSql() {}

  public static final String TABLE = "wishlist";
  public static final String CREATE_TABLE =
      "CREATE TABLE IF NOT EXISTS wishlist (\n"
          + "    client_id  TEXT NOT NULL,\n"
          + "    record_id  TEXT NOT NULL,\n"
          + "    record     TEXT NOT NULL,\n"
          + "    note       TEXT NOT NULL DEFAULT '',\n"
          + "    created_at TEXT NOT NULL,\n"
          + "    PRIMARY KEY (client_id, record_id)\n"
          + "  )";
  public static final String CREATE_INDEX =
      "CREATE INDEX IF NOT EXISTS wishlist_by_client ON wishlist (client_id, created_at DESC)";

  public static final String LIST =
      "SELECT record_id, record, note, created_at FROM wishlist"
          + " WHERE client_id = ? ORDER BY created_at DESC, record_id";
  public static final String COUNT = "SELECT COUNT(*) AS count FROM wishlist WHERE client_id = ?";
  public static final String SAVE =
      "INSERT INTO wishlist (client_id, record_id, record, note, created_at)"
          + " VALUES (?, ?, ?, ?, ?)"
          + " ON CONFLICT(client_id, record_id) DO UPDATE SET record = excluded.record,"
          + " note = excluded.note";
  public static final String REMOVE =
      "DELETE FROM wishlist WHERE client_id = ? AND record_id = ?";

  /**
   * Stable identity for a saved record — the port of {@code wishlistKey}. Kept byte-for-byte
   * identical in the frontend so a card can tell whether it is already on the list.
   */
  public static String keyOf(Map<String, Object> record) {
    if (record == null) return "t:";
    Object id = record.get("id");
    if (id instanceof String && !((String) id).isEmpty()) return (String) id;

    List<String> parts = new ArrayList<>();
    Object title = record.get("title");
    if (title instanceof String && !((String) title).isEmpty()) parts.add((String) title);
    Object year = record.get("year");
    if (year instanceof String && !((String) year).isEmpty()) parts.add((String) year);
    Object isbn = record.get("isbn");
    if (isbn instanceof List && !((List<?>) isbn).isEmpty()) {
      Object first = ((List<?>) isbn).get(0);
      if (first != null && !String.valueOf(first).isEmpty()) parts.add(String.valueOf(first));
    }
    StringBuilder joined = new StringBuilder();
    for (String part : parts) {
      if (joined.length() > 0) joined.append('~');
      joined.append(part);
    }
    return "t:" + joined;
  }

  /** `new Date().toISOString()`: always exactly three fractional digits, always UTC. */
  public static String nowIso() {
    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    return format.format(new Date());
  }

  /** One row as the API presents it, or null when a corrupted row cannot be parsed. */
  public static Map<String, Object> item(String key, String recordJson, String note, String createdAt) {
    Object record;
    try {
      record = Json.parse(recordJson);
    } catch (IllegalArgumentException broken) {
      return null;
    }
    return Json.obj("key", key, "note", note, "addedAt", createdAt, "record", record);
  }
}
