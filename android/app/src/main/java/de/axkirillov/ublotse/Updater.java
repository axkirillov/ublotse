package de.axkirillov.ublotse;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Updating without a store: ask GitHub for the latest release, offer it, download the APK and hand
 * it to the system installer.
 *
 * <p>There is no Play Store listing, so nothing else would ever update this app — the alternative
 * is telling people to sideload a file every time. The check runs once a day on launch and on
 * demand from the footer of the page, where the frontend finds {@link Bridge} as {@code
 * window.Ublotse}.
 *
 * <p>Both halves of the permission story are Android's, not ours: since API 26 a request to
 * install an unknown app needs {@code REQUEST_INSTALL_PACKAGES} in the manifest *and* the user's
 * consent in Settings, and since API 24 the installer will not accept a {@code file://} URI. So
 * the APK is fetched into the cache, shared through {@link UpdateFilesProvider}, and the user is
 * taken to the one settings screen that unblocks it.
 */
public final class Updater {

  private static final String PREFS = "ublotse-updates";
  private static final String LAST_CHECK = "last_check";
  private static final String SKIPPED = "skipped_version";
  private static final String PENDING = "pending_install";

  private static final String RELEASE_API = "https://api.github.com/repos/axkirillov/ublotse/releases/latest";
  private static final String RELEASE_PAGE = "https://github.com/axkirillov/ublotse/releases";

  private static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;
  private static final int CHECK_TIMEOUT_MS = 15000;
  private static final int DOWNLOAD_TIMEOUT_MS = 60000;

  private Updater() {}

  // -------------------------------------------------------------------------
  // Checking
  // -------------------------------------------------------------------------

  /** Once a day, quietly. A failed check is not worth a dialog; the footer button retries. */
  public static void checkOnLaunch(Activity activity) {
    SharedPreferences preferences = preferences(activity);
    long now = System.currentTimeMillis();
    if (now - preferences.getLong(LAST_CHECK, 0) < CHECK_INTERVAL_MS) return;
    // Recorded before the request: being offline should not mean a check on every launch.
    preferences.edit().putLong(LAST_CHECK, now).apply();
    check(activity, false);
  }

  /** {@code manual} is the difference between a dialog and a toast: silence is only polite unsolicited. */
  public static void check(final Activity activity, final boolean manual) {
    final String installed = installedVersion(activity);
    new Thread(
            new Runnable() {
              @Override
              public void run() {
                Update.Release release = null;
                String failure = null;
                try {
                  Http.Response response =
                      Http.get(
                          RELEASE_API,
                          Http.headers(
                              "User-Agent", "UBLotse/" + installed,
                              "Accept", "application/vnd.github+json"),
                          CHECK_TIMEOUT_MS);
                  release = Update.parse(response.text());
                } catch (IOException offline) {
                  failure = offline.getMessage();
                }
                final Update.Release found = release;
                final String problem = failure;
                activity.runOnUiThread(
                    new Runnable() {
                      @Override
                      public void run() {
                        report(activity, installed, found, problem, manual);
                      }
                    });
              }
            },
            "ublotse-update-check")
        .start();
  }

  private static void report(Activity activity, String installed, Update.Release release, String failure, boolean manual) {
    if (activity.isFinishing() || activity.isDestroyed()) return;
    if (release == null) {
      if (manual) {
        toast(activity, failure == null ? "Keine Release-Informationen gefunden." : "Update-Prüfung fehlgeschlagen.");
      }
      return;
    }
    if (!Update.isNewer(release.version, installed)) {
      if (manual) toast(activity, "UBLotse " + installed + " ist aktuell.");
      return;
    }
    // A version the user waved away is not offered again on its own.
    if (!manual && release.version.equals(preferences(activity).getString(SKIPPED, ""))) return;
    offer(activity, installed, release);
  }

  private static void offer(final Activity activity, String installed, final Update.Release release) {
    new AlertDialog.Builder(activity)
        .setTitle("Update verfügbar")
        .setMessage(
            "UBLotse "
                + release.version
                + " ist verfügbar. Installiert ist "
                + installed
                + ".\n\nDie Merkliste bleibt beim Aktualisieren erhalten.")
        .setPositiveButton(
            "Installieren",
            new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface dialog, int which) {
                download(activity, release);
              }
            })
        .setNeutralButton(
            "Überspringen",
            new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface dialog, int which) {
                preferences(activity).edit().putString(SKIPPED, release.version).apply();
              }
            })
        .setNegativeButton("Später", null)
        .show();
  }

  // -------------------------------------------------------------------------
  // Downloading and installing
  // -------------------------------------------------------------------------

  private static void download(final Activity activity, final Update.Release release) {
    // A spinner rather than a progress bar — one small file, and ProgressDialog is deprecated.
    final ProgressBar spinner = new ProgressBar(activity);
    spinner.setIndeterminate(true);
    final AtomicBoolean cancelled = new AtomicBoolean(false);
    final AlertDialog progress =
        new AlertDialog.Builder(activity)
            .setTitle("Update wird geladen")
            .setMessage("UBLotse " + release.version)
            .setView(padded(activity, spinner))
            .setNegativeButton(
                "Abbrechen",
                new DialogInterface.OnClickListener() {
                  @Override
                  public void onClick(DialogInterface dialog, int which) {
                    cancelled.set(true);
                  }
                })
            .create();
    progress.show();

    new Thread(
            new Runnable() {
              @Override
              public void run() {
                final File target = new File(UpdateFilesProvider.directory(activity), release.fileName());
                String failure = null;
                try {
                  byte[] apk =
                      Http.get(
                              release.apkUrl,
                              Http.headers("User-Agent", "UBLotse", "Accept", "application/octet-stream"),
                              DOWNLOAD_TIMEOUT_MS)
                          .body;
                  // The API told us how big the asset is; a short answer is a broken answer.
                  if (release.apkSize > 0 && apk.length != release.apkSize) {
                    throw new IOException("unvollständiger Download (" + apk.length + " von " + release.apkSize + " Bytes)");
                  }
                  if (!cancelled.get()) {
                    target.getParentFile().mkdirs();
                    FileOutputStream out = new FileOutputStream(target);
                    try {
                      out.write(apk);
                    } finally {
                      out.close();
                    }
                  }
                } catch (IOException failed) {
                  failure = failed.getMessage();
                }
                final String problem = failure;
                activity.runOnUiThread(
                    new Runnable() {
                      @Override
                      public void run() {
                        progress.dismiss();
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        if (cancelled.get()) return;
                        if (problem != null) {
                          toast(activity, "Download fehlgeschlagen: " + problem);
                          return;
                        }
                        install(activity, target);
                      }
                    });
              }
            },
            "ublotse-update-download")
        .start();
  }

  /** {@code setView} gives the view no breathing room of its own. */
  private static View padded(Context context, View view) {
    FrameLayout frame = new FrameLayout(context);
    int pad = Math.round(24 * context.getResources().getDisplayMetrics().density);
    frame.setPadding(pad, pad, pad, pad);
    frame.addView(
        view,
        new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    return frame;
  }

  /**
   * Install, or explain the one setting that stands in the way. From API 26 the installer is
   * simply a no-op until the user grants it, so asking first is the difference between a flow and
   * a dead button.
   */
  private static void install(final Activity activity, final File apk) {
    if (!canInstall(activity)) {
      preferences(activity).edit().putString(PENDING, apk.getAbsolutePath()).apply();
      new AlertDialog.Builder(activity)
          .setTitle("Installation erlauben")
          .setMessage(
              "Android muss UBLotse einmal erlauben, Apps zu installieren. Danach geht es hier "
                  + "automatisch weiter.")
          .setPositiveButton(
              "Einstellungen",
              new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                  try {
                    activity.startActivity(
                        new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName())));
                  } catch (RuntimeException noSettings) {
                    // Some builds hide that screen; the release page is the fallback.
                    openExternally(activity, RELEASE_PAGE);
                  }
                }
              })
          .setNegativeButton(
              "Abbrechen",
              new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                  preferences(activity).edit().remove(PENDING).apply();
                }
              })
          .show();
      return;
    }
    fire(activity, apk);
  }

  /** Called from {@code onResume}: the user may have just granted the permission we sent them for. */
  public static void resumePendingInstall(Activity activity) {
    SharedPreferences preferences = preferences(activity);
    String path = preferences.getString(PENDING, null);
    if (path == null || !canInstall(activity)) return;
    preferences.edit().remove(PENDING).apply();
    File apk = new File(path);
    if (apk.isFile()) fire(activity, apk);
  }

  private static boolean canInstall(Context context) {
    return Build.VERSION.SDK_INT < 26 || context.getPackageManager().canRequestPackageInstalls();
  }

  private static void fire(Activity activity, File apk) {
    Intent intent = new Intent(Intent.ACTION_VIEW);
    intent.setDataAndType(UpdateFilesProvider.uriFor(apk), "application/vnd.android.package-archive");
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    try {
      activity.startActivity(intent);
    } catch (RuntimeException noInstaller) {
      toast(activity, "Kein Installer gefunden.");
    }
  }

  private static void openExternally(Activity activity, String url) {
    try {
      activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (RuntimeException nothingHandlesIt) {
      toast(activity, "Kein Browser gefunden.");
    }
  }

  // -------------------------------------------------------------------------
  // The frontend's side of it
  // -------------------------------------------------------------------------

  /**
   * Exposed to the page as {@code window.Ublotse} — present in the app, absent in a browser, which
   * is how the footer decides whether to offer a check at all. The WebView only ever loads the
   * loopback origin (everything else is handed to the system browser), so only our own page can
   * reach this.
   */
  public static final class Bridge {

    private final Activity activity;

    public Bridge(Activity activity) {
      this.activity = activity;
    }

    /** The installed version, so the footer can say which one is running. */
    @JavascriptInterface
    public String version() {
      return installedVersion(activity);
    }

    /** Called from the page; the bridge thread is not the UI thread. */
    @JavascriptInterface
    public void checkForUpdates() {
      activity.runOnUiThread(
          new Runnable() {
            @Override
            public void run() {
              check(activity, true);
            }
          });
    }
  }

  // -------------------------------------------------------------------------

  public static String installedVersion(Context context) {
    try {
      PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
      return info.versionName == null ? "0" : info.versionName;
    } catch (PackageManager.NameNotFoundException impossible) {
      return "0";
    }
  }

  private static SharedPreferences preferences(Context context) {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  private static void toast(Context context, String message) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show();
  }
}
