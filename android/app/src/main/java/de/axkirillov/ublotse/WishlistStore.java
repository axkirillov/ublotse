package de.axkirillov.ublotse;

import java.util.List;
import java.util.Map;

/**
 * Storage behind {@code /api/wishlist}. On the device this is SQLite; in tests it can be anything.
 * The client id is a parameter rather than a field so that the browser's contract — one store,
 * many clients — survives into the app unchanged.
 */
public interface WishlistStore {

  /** Newest first, exactly as the API returns them. */
  List<Map<String, Object>> list(String clientId);

  long count(String clientId);

  /** Upsert. `recordJson` is the whole-record snapshot, with any client-only fields removed. */
  void save(String clientId, String key, String recordJson, String note, String createdAt);

  boolean remove(String clientId, String key);

  /** Where the rows live, for {@code /api/health}. */
  String describe();
}
