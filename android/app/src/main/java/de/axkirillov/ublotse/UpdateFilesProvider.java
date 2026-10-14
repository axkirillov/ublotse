package de.axkirillov.ublotse;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Hands the downloaded APK to the system installer.
 *
 * <p>{@code targetSdk 34} forbids {@code file://} URIs in an {@code ACTION_VIEW} intent, and the
 * usual fix — AndroidX's {@code FileProvider} — would drag a dependency into an app that has none.
 * This is the whole of it: one read-only directory, never exported, reachable only through the
 * read grant attached to the install intent.
 */
public final class UpdateFilesProvider extends ContentProvider {

  static final String AUTHORITY = "de.axkirillov.ublotse.updates";

  /** Where the updater keeps downloads; the provider serves exactly this directory. */
  static File directory(Context context) {
    return new File(context.getCacheDir(), "updates");
  }

  static Uri uriFor(File file) {
    return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(file.getName()).build();
  }

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public String getType(Uri uri) {
    return "application/vnd.android.package-archive";
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    String name = uri.getLastPathSegment();
    if (name == null || name.contains("..") || name.contains("/") || !name.endsWith(".apk")) {
      throw new FileNotFoundException("not an update: " + uri);
    }
    if (mode == null || !mode.startsWith("r")) throw new FileNotFoundException("read-only: " + uri);
    return ParcelFileDescriptor.open(new File(directory(getContext()), name), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  // Nothing writes through the provider; these exist because ContentProvider is abstract.

  @Override
  public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
    return null;
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    return null;
  }

  @Override
  public int delete(Uri uri, String selection, String[] selectionArgs) {
    return 0;
  }

  @Override
  public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
    return 0;
  }
}
