package de.axkirillov.ublotse;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A bounded map whose entries expire — the Node server's `cache` and `coverCache` rolled into
 * one class. Insertion order is the eviction order, matching JavaScript's `Map`.
 */
public final class TtlCache<V> {

  private static final class Entry<V> {
    final V value;
    final long expires;

    Entry(V value, long expires) {
      this.value = value;
      this.expires = expires;
    }
  }

  private final LinkedHashMap<String, Entry<V>> entries = new LinkedHashMap<>();
  private final int maxEntries;
  private final long defaultTtlMs;

  public TtlCache(int maxEntries, long defaultTtlMs) {
    this.maxEntries = maxEntries;
    this.defaultTtlMs = defaultTtlMs;
  }

  public synchronized V get(String key) {
    Entry<V> entry = entries.get(key);
    if (entry == null) return null;
    if (entry.expires < System.currentTimeMillis()) {
      entries.remove(key);
      return null;
    }
    return entry.value;
  }

  public synchronized void put(String key, V value) {
    put(key, value, defaultTtlMs);
  }

  public synchronized void put(String key, V value, long ttlMs) {
    if (ttlMs <= 0) {
      entries.remove(key);
      return;
    }
    while (entries.size() >= maxEntries) {
      Iterator<String> oldest = entries.keySet().iterator();
      if (!oldest.hasNext()) break;
      oldest.next();
      oldest.remove();
    }
    entries.put(key, new Entry<>(value, System.currentTimeMillis() + ttlMs));
  }

  public synchronized int size() {
    return entries.size();
  }

  public synchronized void clear() {
    entries.clear();
  }
}
