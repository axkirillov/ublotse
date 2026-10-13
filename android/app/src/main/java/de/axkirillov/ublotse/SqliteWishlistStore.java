package de.axkirillov.ublotse;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The wishlist on the device: the same table the Node server creates, so a future sync endpoint
 * has nothing to reconcile. The SQL itself lives in {@link WishlistSql}, free of Android imports,
 * which is what lets the statements be tested against the {@code sqlite3} CLI.
 */
public final class SqliteWishlistStore extends SQLiteOpenHelper implements WishlistStore {

  private static final String DATABASE = "ublotse.db";
  private static final int VERSION = 1;

  private final Context context;

  public SqliteWishlistStore(Context context) {
    super(context, DATABASE, null, VERSION);
    this.context = context.getApplicationContext();
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(WishlistSql.CREATE_TABLE);
    db.execSQL(WishlistSql.CREATE_INDEX);
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int from, int to) {
    // Both statements are IF NOT EXISTS; a prototype does not need migrations yet.
    onCreate(db);
  }

  @Override
  public List<Map<String, Object>> list(String clientId) {
    List<Map<String, Object>> rows = new ArrayList<>();
    Cursor cursor = getReadableDatabase().rawQuery(WishlistSql.LIST, new String[] {clientId});
    try {
      while (cursor.moveToNext()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("record_id", cursor.getString(0));
        row.put("record", cursor.getString(1));
        row.put("note", cursor.getString(2));
        row.put("created_at", cursor.getString(3));
        rows.add(row);
      }
    } finally {
      cursor.close();
    }
    return rows;
  }

  @Override
  public long count(String clientId) {
    Cursor cursor = getReadableDatabase().rawQuery(WishlistSql.COUNT, new String[] {clientId});
    try {
      return cursor.moveToFirst() ? cursor.getLong(0) : 0;
    } finally {
      cursor.close();
    }
  }

  @Override
  public void save(String clientId, String key, String recordJson, String note, String createdAt) {
    ContentValues values = new ContentValues();
    values.put("client_id", clientId);
    values.put("record_id", key);
    values.put("record", recordJson);
    values.put("note", note);
    values.put("created_at", createdAt);
    getWritableDatabase()
        .insertWithOnConflict(
            WishlistSql.TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
  }

  @Override
  public boolean remove(String clientId, String key) {
    int removed =
        getWritableDatabase()
            .delete(WishlistSql.TABLE, "client_id = ? AND record_id = ?", new String[] {clientId, key});
    return removed > 0;
  }

  @Override
  public String describe() {
    return context.getDatabasePath(DATABASE).getAbsolutePath();
  }
}
