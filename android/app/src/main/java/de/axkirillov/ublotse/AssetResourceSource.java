package de.axkirillov.ublotse;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.IOException;
import java.io.InputStream;

/** Serves the bundled frontend straight out of {@code assets/public}. */
public final class AssetResourceSource implements LocalServer.ResourceSource {

  private static final String PREFIX = "public";

  private final AssetManager assets;

  public AssetResourceSource(Context context) {
    this.assets = context.getAssets();
  }

  @Override
  public byte[] read(String path) {
    if (path == null || path.isEmpty() || path.charAt(0) != '/') return null;
    try {
      InputStream stream = assets.open(PREFIX + path);
      try {
        return Http.readAll(stream);
      } finally {
        stream.close();
      }
    } catch (IOException missing) {
      return null;
    }
  }

  @Override
  public String contentType(String path) {
    return LocalServer.contentTypeFor(path);
  }
}
