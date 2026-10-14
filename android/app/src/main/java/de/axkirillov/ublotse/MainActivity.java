package de.axkirillov.ublotse;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * The whole app: start a loopback server, point a WebView at it, hand every outbound link to the
 * system browser, and offer released updates from inside the app. The frontend in {@code
 * assets/public} is the very same directory the Node prototype serves — no build step, no rewriting
 * of paths.
 */
public class MainActivity extends Activity {

  private static final String PREFS = "ublotse";
  private static final String CLIENT_KEY = "client_id";

  private LocalServer server;
  private WebView webView;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);

    UblotseApi api =
        new UblotseApi(
            new Sru(),
            new Covers(new File(getCacheDir(), "covers")),
            new SqliteWishlistStore(this));

    // The browser mints its own id and sends it in X-Client-Id; on the device that is ignored in
    // favour of one id per install, so clearing the WebView's data cannot orphan the wishlist.
    final String installId = installId();

    LocalServer local =
        new LocalServer(
            new LocalServer.ApiHandler() {
              @Override
              public WebResponse handle(
                  String method,
                  String path,
                  java.util.Map<String, String> query,
                  byte[] body,
                  java.util.Map<String, String> headers) {
                return api.handle(method, path, query, body, installId);
              }
            },
            new AssetResourceSource(this));

    int port;
    try {
      port = local.start();
    } catch (IOException refused) {
      setContentView(errorView("Der lokale Server konnte nicht starten: " + refused.getMessage()));
      return;
    }
    server = local;

    webView = new WebView(this);
    WebSettings settings = webView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true); // the frontend keeps its theme, and its client id, here
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setSupportMultipleWindows(true); // every catalogue link is target="_blank"

    if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
      WebView.setWebContentsDebuggingEnabled(true);
    }

    webView.setWebViewClient(
        new WebViewClient() {
          @Override
          public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (isLoopback(uri)) return false; // ours: stay in the WebView
            openExternally(uri);
            return true;
          }
        });

    // target="_blank" never reaches shouldOverrideUrlLoading: Chromium asks for a second window
    // instead. Give it a headless one, catch its first navigation, and send that to the browser.
    webView.setWebChromeClient(
        new WebChromeClient() {
          @Override
          public boolean onCreateWindow(
              WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            final WebView probe = new WebView(MainActivity.this);
            probe.setWebViewClient(
                new WebViewClient() {
                  @Override
                  public boolean shouldOverrideUrlLoading(WebView spare, WebResourceRequest request) {
                    openExternally(request.getUrl());
                    final WebView doomed = spare;
                    spare.post(
                        new Runnable() {
                          @Override
                          public void run() {
                            doomed.destroy();
                          }
                        });
                    return true;
                  }
                });
            ((WebView.WebViewTransport) resultMsg.obj).setWebView(probe);
            resultMsg.sendToTarget();
            return true;
          }
        });

    setContentView(webView);

    // The page finds this as window.Ublotse and uses it for the “Nach Updates suchen” entry in the
    // footer; a browser has no such object, so the entry stays hidden there.
    webView.addJavascriptInterface(new Updater.Bridge(this), "Ublotse");

    webView.loadUrl("http://127.0.0.1:" + port + "/");

    // At most once a day, and never a dialog unless there is something to install.
    Updater.checkOnLaunch(this);
  }

  private static boolean isLoopback(Uri uri) {
    String host = uri.getHost();
    return "127.0.0.1".equals(host) || "localhost".equals(host);
  }

  private void openExternally(Uri uri) {
    if (uri == null) return;
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, uri));
    } catch (ActivityNotFoundException nothingHandlesIt) {
      // A device with no browser is a device where this simply does nothing.
    }
  }

  /** One id per install, stable across restarts and across cleared WebView data. */
  private String installId() {
    SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
    String existing = preferences.getString(CLIENT_KEY, null);
    if (existing != null && !existing.isEmpty()) return existing;
    String minted = UUID.randomUUID().toString();
    preferences.edit().putString(CLIENT_KEY, minted).apply();
    return minted;
  }

  private TextView errorView(String message) {
    TextView view = new TextView(this);
    view.setText(message);
    view.setPadding(48, 96, 48, 48);
    setContentView(view, new ViewGroup.LayoutParams(-1, -1));
    return view;
  }

  @Override
  protected void onResume() {
    super.onResume();
    // Returning from the “install unknown apps” settings screen is how a pending update proceeds.
    Updater.resumePendingInstall(this);
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
      webView.goBack();
      return true;
    }
    return super.onKeyDown(keyCode, event);
  }

  @Override
  protected void onDestroy() {
    if (webView != null) {
      webView.destroy();
      webView = null;
    }
    if (server != null) {
      server.stop();
      server = null;
    }
    super.onDestroy();
  }
}
