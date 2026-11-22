package com.ghibli.calendar;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Shows YOUR existing calendar website, unchanged, and shares its data with the widget. */
public class MainActivity extends FileActivity {
    private static final String SITE = BuildConfig.SITE_URL;
    private static final String CHANNEL = "gf_reminders";
    private static final int REQ_NOTIF = 900;

    private static java.lang.ref.WeakReference<MainActivity> live;

    private WebView web;
    private ScriptHandler scriptHandler;
    private String fallbackJs;
    private String origin;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        live = new java.lang.ref.WeakReference<>(this);
        Uri site = Uri.parse(SITE);
        origin = site.getScheme() + "://" + site.getAuthority();

        web = new WebView(this);
        web.setBackgroundColor(getColor(R.color.paper));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        web.addJavascriptInterface(new Bridge(), "GFNative");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (origin.equals(u.getScheme() + "://" + u.getAuthority())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                if (fallbackJs != null && url != null && url.startsWith(origin)) v.evaluateJavascript(fallbackJs, null);
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (!r.isForMainFrame()) return;
                String u = r.getUrl().toString();
                // Offline: the site's own cache has the home page but not "?day=…" variants.
                if (u.startsWith(SITE) && u.contains("?")) { v.loadUrl(SITE); return; }
                v.loadDataWithBaseURL(null, OFFLINE_HTML, "text/html", "utf-8", null);
            }
        });

        createChannel();
        loadSite(dayOf(getIntent()), pageOf(getIntent()));
        firstRunPrompt();
    }

    private String dayOf(Intent i) {
        String d = i == null ? null : i.getStringExtra("day");
        return (d != null && d.matches("\\d{4}-\\d{2}-\\d{2}")) ? d : null;
    }

    /** Only plain file names such as "sync-setup.html" are accepted. */
    private String pageOf(Intent i) {
        String p = i == null ? null : i.getStringExtra("page");
        return (p != null && p.matches("[A-Za-z0-9_-]+\\.html")) ? p : null;
    }

    private void loadSite(String day) { loadSite(day, null); }

    private void loadSite(String day, String page) {
        installScript();
        String url = SITE;
        if (page != null) url = SITE + page;
        else if (day != null) url = SITE + "index.html?day=" + day;
        web.loadUrl(url);
    }

    private void installScript() {
        String js = readAsset("gf-bridge.js");
        String raw = StateStore.raw(this);
        String lit = raw == null ? "null"
                : JSONObject.quote(raw).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        js = js.replace("__GF_STATE__", lit);
        StateStore.markWebSeen(this);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            if (scriptHandler != null) scriptHandler.remove();
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(web, js, Collections.singleton(origin));
            fallbackJs = null;
        } else {
            fallbackJs = js; // older WebView: best effort
        }
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return new String(o.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }

    private void firstRunPrompt() {
        SharedPreferences p = getSharedPreferences("ghibli_app", MODE_PRIVATE);
        if (StateStore.hasState(this) || p.getBoolean("asked_import", false)) return;
        p.edit().putBoolean("asked_import", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Bring your existing calendar?")
                .setMessage("If you already use Ghibli Calendar in Chrome, save a backup there first (see the README), then choose that file here. Otherwise start fresh.")
                .setPositiveButton("Choose backup file", (d, w) -> pickImport())
                .setNegativeButton("Start fresh", null)
                .show();
    }

    @Override
    void onDataReplaced() { loadSite(null); }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        loadSite(dayOf(i), pageOf(i));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The widget may have changed your data while the app was in the background.
        if (StateStore.hasState(this) && StateStore.webIsStale(this)) refreshFromStore();
    }

    private void refreshFromStore() {
        if (web == null) return;
        installScript();
        String u = web.getUrl();
        if (u != null && u.startsWith(origin)) web.reload(); else web.loadUrl(SITE);
    }

    /**
     * Called after the widget / board / a notification button changed the data.
     * If the website page is still alive in the background, refresh it right away so that
     * it can never write an out-of-date copy of your calendar back.
     */
    static void reloadIfStale(Context c) {
        final MainActivity a = live == null ? null : live.get();
        if (a == null || !StateStore.hasState(c) || !StateStore.webIsStale(c)) return;
        a.runOnUiThread(new Runnable() { @Override public void run() { a.refreshFromStore(); } });
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (live != null && live.get() == this) live = null;
        if (web != null) { web.removeJavascriptInterface("GFNative"); web.destroy(); }
        super.onDestroy();
    }

    // ---------- notifications ----------
    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private String permissionState() {
        boolean on = getSystemService(NotificationManager.class).areNotificationsEnabled();
        if (on) return "granted";
        return getSharedPreferences("ghibli_app", MODE_PRIVATE).getBoolean("asked_notif", false) ? "denied" : "default";
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_NOTIF) {
            getSharedPreferences("ghibli_app", MODE_PRIVATE).edit().putBoolean("asked_notif", true).apply();
            web.evaluateJavascript("window.__gfPerm&&window.__gfPerm('" + permissionState() + "')", null);
        }
    }

    private void postNotification(String title, String body, String day, String tag, String actionsJson) {
        int id = (tag == null || tag.isEmpty() ? title : tag).hashCode();
        Intent open = new Intent(this, MainActivity.class).putExtra("day", day)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .setData(Uri.parse("ghibli://notif/" + id));
        Notification.Builder nb = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_leaf)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setColor(getColor(R.color.sage))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(this, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        try {
            JSONArray arr = new JSONArray(actionsJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String a = o.optString("a");
                if (a.startsWith("mark-done:")) {
                    Intent ai = new Intent(this, ActionActivity.class).putExtra("op", "toggle")
                            .putExtra("id", a.substring("mark-done:".length()))
                            .setData(Uri.parse("ghibli://done/" + a));
                    PendingIntent pi = PendingIntent.getActivity(this, id + 1, ai, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    nb.addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_leaf), o.optString("t", "Mark done"), pi).build());
                }
            }
        } catch (Exception ignored) { }
        getSystemService(NotificationManager.class).notify(id, nb.build());
    }

    /** Methods the website (inside this app) can call. */
    private final class Bridge {
        @JavascriptInterface
        public void saveState(String json) {
            if (json == null || json.length() < 2) return;
            if (StateStore.writeFromWeb(MainActivity.this, json)) {
                Widgets.refreshAllDebounced(MainActivity.this);
            }
        }

        @JavascriptInterface
        public String permission() { return permissionState(); }

        @JavascriptInterface
        public void requestPermission() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                } else {
                    web.evaluateJavascript("window.__gfPerm&&window.__gfPerm('" + permissionState() + "')", null);
                }
            });
        }

        @JavascriptInterface
        public void showNotification(String title, String body, String day, String tag, String actionsJson) {
            if (!"granted".equals(permissionState())) return;
            postNotification(title, body, day, tag, actionsJson);
        }

        @JavascriptInterface
        public void retry() { runOnUiThread(() -> loadSite(null)); }
    }

    private static final String OFFLINE_HTML =
            "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>"
            + "body{margin:0;min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;"
            + "font-family:Georgia,serif;background:#F5F1E8;color:#3A362E;text-align:center;padding:24px}"
            + "p{color:#6B6458;max-width:300px}button{margin-top:18px;border:0;border-radius:999px;background:#7A8D6B;color:#fff;"
            + "font-size:16px;padding:12px 26px}"
            + "@media(prefers-color-scheme:dark){body{background:#1B211C;color:#EAE6D9}p{color:#C9C2B0}}"
            + "</style></head><body><div style='font-size:44px'>🌱</div><h2>The forest is out of reach</h2>"
            + "<p>Couldn’t load the calendar. Your widget still works offline. Connect once to finish loading the app.</p>"
            + "<button onclick='GFNative.retry()'>Try again</button></body></html>";
}
