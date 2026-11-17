package app.meanwhile.bridge;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import app.meanwhile.bridge.core.AppPages;
import app.meanwhile.bridge.core.BridgeRoutes;
import app.meanwhile.bridge.core.WebOrigin;

/**
 * What the app icon opens: Meanwhile, with the bridge running underneath in the same
 * process.
 *
 * Meanwhile's pages are packaged inside the app (assets/web, built from this repository
 * by {@code npm run build:app}) and served here from {@link AppPages#HOME}, an address
 * Android reserves for exactly that. Nothing is downloaded to show them, so the app opens
 * with no internet. Its data (IndexedDB) lives in the app's own storage.
 *
 * Those pages, and only those, get {@code window.EverBridgeNative}: the readings straight
 * from the bridge's store (no network hop), saving a backup file, voice input, the
 * clipboard, the bridge's {@link StatusActivity status screen}, and the address of the
 * Meanwhile server that AI advice and cloud backup go to. Links anywhere else open in
 * the browser.
 *
 * The page must never take the bridge down with it: a crashed page renderer is replaced
 * here instead of being left to kill the process, which is also the notification listener.
 *
 * Automation (used by the emulator test), each logging what the page shows:
 * {@code --es action web_check} on the home page; with {@code --es page settings/
 * --es click "Download backup"} (or "Sync now") that button is pressed first, and
 * {@code --es until <text>} waits for that text. {@code --es api http://localhost:PORT}
 * points AI and sync at a server on this device for that run only (never saved).
 */
public class MainActivity extends Activity {

    static final String EXTRA_ACTION = "action";
    static final String EXTRA_PAGE = "page";
    static final String EXTRA_CLICK = "click";
    static final String EXTRA_UNTIL = "until";
    static final String EXTRA_API = "api";
    /** The only buttons automation may press: both harmless to press any time. */
    static final java.util.Set<String> CLICKABLE = new java.util.HashSet<>(
            java.util.Arrays.asList("Download backup", "Sync now", "Open glucose alerts", "Restore from file"));
    static final String JS_NAME = "EverBridgeNative";
    static final int REQ_FILE = 1;
    static final int REQ_SPEECH = 2;

    private static final int BACKGROUND = Color.rgb(10, 10, 12);
    private static final int INK = Color.rgb(236, 236, 240);
    private static final int MUTED = Color.rgb(150, 150, 160);
    /** More renderer crashes than this within {@link #CRASH_WINDOW_MS} stops the reloading. */
    private static final int MAX_CRASHES = 3;
    private static final long CRASH_WINDOW_MS = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Bridge bridge;
    private FrameLayout root;
    private WebView web;
    /** An error message shown instead of the page; null otherwise. */
    private View panel;
    /** True while the page in the WebView is one of the app's own pages. */
    private volatile boolean trusted;
    /** Server address for this run only, from automation; null normally. */
    private volatile String apiOverride;
    private ValueCallback<Uri[]> fileCallback;
    private int crashes;
    private long firstCrashAt;
    final AtomicInteger nativeReads = new AtomicInteger();
    /** Files the page is in the middle of saving, by id. */
    private final Map<String, Save> saves = new HashMap<>();

    private static final class Save {
        final String name;
        final File tmp;
        final Writer out;

        Save(String name, File tmp, Writer out) {
            this.name = name;
            this.tmp = tmp;
            this.out = out;
        }
    }

    private static void deleteAll(File dir) {
        final File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bridge = Bridge.get(this);
        bridge.ensureServer();
        BridgeService.start(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            StatusActivity.applyInsets(v, insets);
            return insets;
        });
        setContentView(root);

        final Intent intent = getIntent();
        final boolean automated = intent != null && intent.getStringExtra(EXTRA_ACTION) != null;
        apiOverride = automationApi(intent);
        if (!hasPages()) {
            showError("This copy of the app is incomplete", "It was built without Meanwhile's pages. "
                    + "Install the APK from the bridge-latest release.");
        } else {
            open(AppPages.HOME + startPage(intent));
        }

        // Setup first: without notification access there are no readings to show.
        if (savedInstanceState == null && !automated && !bridge.hasNotificationAccess()) {
            startActivity(new Intent(this, StatusActivity.class));
        }
        handleAutomation(intent);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (web != null && intent.getStringExtra(EXTRA_ACTION) == null && "alerts/".equals(startPage(intent))) {
            open(AppPages.HOME + "alerts/");
        }
        handleAutomation(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onPause() {
        // also tells the page it is hidden, so it stops polling until it is back
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        destroyWeb();
        synchronized (saves) { // a save the page never finished
            for (Save s : saves.values()) {
                try {
                    s.out.close();
                } catch (IOException ignored) {
                }
                //noinspection ResultOfMethodCallIgnored
                s.tmp.delete();
            }
            saves.clear();
        }
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (panel == null && web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    // ------------------------------------------------------------------ the page

    /** Shows one of the app's pages. */
    void open(String url) {
        removePanel();
        if (web == null) web = createWeb();
        web.setVisibility(View.VISIBLE);
        trusted = false; // until the page has started loading from the right place
        web.loadUrl(url);
    }

    private boolean hasPages() {
        try (java.io.InputStream in = getAssets().open(AppPages.ROOT + "/index.html")) {
            return in.read() >= 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** The packaged file for one of the app's pages, or null to let the request through. */
    WebResourceResponse serve(String url) {
        // anything on the app's address is answered here, even an address too malformed to
        // match a page: it must never go out to the network
        if (!AppPages.isAppUrl(url) && (url == null || !url.startsWith(AppPages.ORIGIN + "/"))) return null;
        for (String asset : AppPages.candidates(url)) {
            final WebResourceResponse r = asset(asset, 200, "OK");
            if (r != null) return r;
        }
        final WebResourceResponse missing = asset(AppPages.NOT_FOUND, 404, "Not Found");
        return missing != null ? missing
                : new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, new java.io.ByteArrayInputStream(new byte[0]));
    }

    private WebResourceResponse asset(String path, int status, String reason) {
        final java.io.InputStream in;
        try {
            in = getAssets().open(path);
        } catch (IOException e) {
            return null; // not there (or a folder)
        }
        final String mime = AppPages.mimeType(path);
        final Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-cache");
        return new WebResourceResponse(mime, AppPages.encoding(mime), status, reason, headers, in);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWeb() {
        final WebView w = new WebView(this);
        w.setBackgroundColor(BACKGROUND);
        final WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true); // localStorage; IndexedDB holds the app's data
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setSupportMultipleWindows(false);
        s.setTextZoom(100);
        s.setUserAgentString(s.getUserAgentString() + " EverBridge/" + bridge.versionName());
        w.setWebViewClient(new Client());
        w.setWebChromeClient(new Chrome());
        w.addJavascriptInterface(new Native(), JS_NAME);
        root.addView(w, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return w;
    }

    private void destroyWeb() {
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
        if (web == null) return;
        root.removeView(web);
        web.destroy();
        web = null;
        trusted = false;
    }

    WebView web() {
        return web;
    }

    private final class Client extends WebViewClient {

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            trusted = AppPages.isAppUrl(url);
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            trusted = AppPages.isAppUrl(url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            pagesFinished++;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return serve(request.getUrl().toString());
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) return false;
            final String url = request.getUrl().toString();
            if (AppPages.isAppUrl(url)) return false;
            // anything that isn't Meanwhile goes to the browser, not into this window
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
            } catch (ActivityNotFoundException | SecurityException e) {
                EventLog.log(MainActivity.this, "WEB", "no app can open " + url);
            }
            return true;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) pageFailed(String.valueOf(error.getDescription()));
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (request.isForMainFrame() && response.getStatusCode() >= 400) {
                showError("Meanwhile didn't open", "Error " + response.getStatusCode() + " opening "
                        + request.getUrl().getPath() + ".");
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // Returning true keeps the app (and so the bridge) alive; the dead WebView
            // must not be used again.
            final long now = SystemClock.elapsedRealtime();
            if (now - firstCrashAt > CRASH_WINDOW_MS) {
                firstCrashAt = now;
                crashes = 0;
            }
            crashes++;
            EventLog.log(MainActivity.this, "WEB", "page renderer gone (crashed=" + detail.didCrash()
                    + ", " + crashes + " in the last minute)");
            destroyWeb();
            if (isFinishing() || isDestroyed()) return true;
            if (crashes > MAX_CRASHES) showError("Meanwhile keeps stopping", "The page crashed several times in a row.");
            else open(AppPages.HOME);
            return true;
        }
    }

    private final class Chrome extends WebChromeClient {
        /** The page's errors and warnings go into the event log (and so the saved log file). */
        @Override
        public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
            if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR
                    || m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.WARNING) {
                final String text = m.message() == null ? "" : m.message();
                EventLog.log(MainActivity.this, "PAGE", m.messageLevel() + ": "
                        + (text.length() > 500 ? text.substring(0, 500) : text) + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
            }
            return false;
        }

        // "Restore from file" in Meanwhile's settings
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            final File staged = automationPick;
            automationPick = null;
            EventLog.log(MainActivity.this, "WEB", "file picker opened by the page");
            if (staged != null) {
                // the emulator test: the file adb staged, instead of the picker it can't operate
                callback.onReceiveValue(new Uri[]{Uri.fromFile(staged)});
                return true;
            }
            fileCallback = callback;
            final Intent pick = new Intent(Intent.ACTION_GET_CONTENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*"); // backups are .json, which file pickers often don't type as JSON
            try {
                startActivityForResult(pick, REQ_FILE);
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                callback.onReceiveValue(null);
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ native functions

    /**
     * {@code window.EverBridgeNative} in the page. Called on a WebView thread, not the main
     * thread. Every method does nothing unless the page is the Meanwhile site.
     */
    final class Native {

        /** The newest {@code count} readings, as {@code /sgv.json} would return them; null on failure. */
        @JavascriptInterface
        public String sgv(int count) {
            if (!trusted) return "[]";
            try {
                final String json = bridge.routes.sgv(Math.max(1, Math.min(BridgeRoutes.MAX_COUNT, count)));
                nativeReads.incrementAndGet();
                return json;
            } catch (RuntimeException e) {
                EventLog.log(MainActivity.this, "WEB", "reading the store for the page failed: " + e);
                return null; // the page then asks the HTTP endpoint instead
            }
        }

        // Saving a text file (Meanwhile's backup) to Downloads/EverBridge. It arrives in
        // pieces and is assembled on disk: a backup can be tens of megabytes.

        /** Starts a file. Returns an id for {@link #saveChunk} and {@link #saveEnd}, or null. */
        @JavascriptInterface
        public String saveBegin(String name) {
            if (!trusted) return null;
            try {
                synchronized (saves) {
                    final File dir = new File(getCacheDir(), "saves");
                    if (saves.isEmpty()) deleteAll(dir); // leftovers from a save that never finished
                    if (!dir.isDirectory() && !dir.mkdirs()) return null;
                    final String id = UUID.randomUUID().toString();
                    final File tmp = new File(dir, id);
                    saves.put(id, new Save(name, tmp,
                            new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)));
                    return id;
                }
            } catch (IOException | RuntimeException e) {
                EventLog.log(MainActivity.this, "WEB", "starting to save " + name + " failed: " + e);
                return null;
            }
        }

        @JavascriptInterface
        public boolean saveChunk(String id, String text) {
            final Save s;
            synchronized (saves) {
                s = saves.get(id);
            }
            if (!trusted || s == null || text == null) return false;
            try {
                s.out.write(text);
                return true;
            } catch (IOException e) {
                EventLog.log(MainActivity.this, "WEB", "saving " + s.name + " failed: " + e);
                return false;
            }
        }

        /** Finishes the file. Returns where it was saved (for display), or null on failure. */
        @JavascriptInterface
        public String saveEnd(String id) {
            final Save s;
            synchronized (saves) {
                s = saves.remove(id);
            }
            if (s == null) return null;
            try {
                s.out.close();
                if (!trusted) return null;
                final String mime = s.name != null && s.name.endsWith(".json") ? "application/json" : "text/plain";
                return LogExport.saveFile(MainActivity.this, s.name, mime, s.tmp).where;
            } catch (IOException | RuntimeException e) {
                EventLog.log(MainActivity.this, "WEB", "saving " + s.name + " failed: " + e);
                return null;
            } finally {
                //noinspection ResultOfMethodCallIgnored
                s.tmp.delete();
            }
        }

        @JavascriptInterface
        public boolean copyText(String text) {
            if (!trusted || text == null) return false;
            try {
                final ClipboardManager cm = getSystemService(ClipboardManager.class);
                if (cm == null) return false;
                cm.setPrimaryClip(ClipData.newPlainText("Meanwhile", text));
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }

        /** Voice input. The result arrives in the page as {@code __everbridgeSpeech(text, error)}. */
        @JavascriptInterface
        public void startSpeech() {
            if (trusted) runOnUiThread(MainActivity.this::startSpeech);
        }

        /** Address of the Meanwhile server for AI advice and cloud backup; "" if not set. */
        @JavascriptInterface
        public String apiBase() {
            if (!trusted) return "";
            final String o = apiOverride;
            return o != null ? o : bridge.settings.apiBase();
        }

        /** Saves it; "" clears it. Returns the cleaned-up address, or null if it isn't usable. */
        @JavascriptInterface
        public String setApiBase(String url) {
            if (!trusted || url == null) return null;
            final String trimmed = url.trim();
            final String clean = trimmed.isEmpty() ? "" : WebOrigin.normalize(trimmed);
            if (clean == null) return null;
            // just the site: "https://x.netlify.app/settings" means https://x.netlify.app
            final String site = clean.isEmpty() ? "" : clean.substring(0, clean.indexOf('/', clean.indexOf("//") + 2));
            bridge.settings.setApiBase(site);
            EventLog.log(MainActivity.this, "WEB", site.isEmpty() ? "server address cleared" : "server address set");
            return site;
        }

        @JavascriptInterface
        public void openStatus() {
            if (trusted) runOnUiThread(() -> startActivity(new Intent(MainActivity.this, StatusActivity.class)));
        }

        // The Alerts tab. Reads return JSON; changes return null or what's wrong, and when they
        // have been applied the page hears window.__alertsChanged().

        @JavascriptInterface
        public String alertsState() {
            return trusted ? AlertsBridge.state(MainActivity.this) : null;
        }

        @JavascriptInterface
        public String alertsSaveRule(String json) {
            return trusted ? AlertsBridge.saveRule(MainActivity.this, json, MainActivity.this::alertsChanged) : "not allowed";
        }

        @JavascriptInterface
        public void alertsRemoveRule(String uuid) {
            if (trusted) AlertsBridge.removeRule(MainActivity.this, uuid, MainActivity.this::alertsChanged);
        }

        @JavascriptInterface
        public String alertsSaveSettings(String json) {
            return trusted ? AlertsBridge.saveSettings(MainActivity.this, json, MainActivity.this::alertsChanged) : "not allowed";
        }

        @JavascriptInterface
        public void alertsSnooze(int minutes) {
            if (trusted) AlertsBridge.snooze(MainActivity.this, minutes, MainActivity.this::alertsChanged);
        }

        @JavascriptInterface
        public void alertsUnsnooze() {
            if (trusted) AlertsBridge.unsnooze(MainActivity.this, MainActivity.this::alertsChanged);
        }

        @JavascriptInterface
        public void alertsSetActive(String uuid, boolean on) {
            if (trusted) AlertsBridge.setActive(MainActivity.this, uuid, on, MainActivity.this::alertsChanged);
        }

        @JavascriptInterface
        public void alertsDisable(String which, int minutes) {
            if (trusted) AlertsBridge.disable(MainActivity.this, which, minutes, MainActivity.this::alertsChanged);
        }

        @JavascriptInterface
        public void alertsPreSnooze(String uuid, int minutes) {
            if (trusted) AlertsBridge.preSnooze(MainActivity.this, uuid, minutes, MainActivity.this::alertsChanged);
        }

        @JavascriptInterface
        public String alertsTest(String json) {
            return trusted ? AlertsBridge.test(MainActivity.this, json) : "not allowed";
        }

        @JavascriptInterface
        public void alertsStopTest() {
            if (trusted) Alerts.stopTest(MainActivity.this);
        }

        @JavascriptInterface
        public String alertsSoundInfo(String uri) {
            return trusted ? AlertsBridge.soundInfo(MainActivity.this, uri == null || uri.isEmpty() ? null : uri) : null;
        }

        /** "phone": Android's sound list; "file": a sound file. The choice arrives as window.__alertsSoundPicked(uri). */
        @JavascriptInterface
        public void alertsPickSound(String kind, String current) {
            if (trusted) runOnUiThread(() -> pickSound(kind, current));
        }

        /** "overlay", "full_screen", "dnd" or "notifications": opens where Android allows it. */
        @JavascriptInterface
        public void alertsOpenPermission(String which) {
            if (trusted) runOnUiThread(() -> openPermission(which));
        }
    }

    static final int REQ_PHONE_SOUND = 3;
    static final int REQ_SOUND_FILE = 4;

    private void alertsChanged() {
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript("window.__alertsChanged&&window.__alertsChanged()", null);
        });
    }

    private void pickSound(String kind, String current) {
        final Intent i;
        final int req;
        if ("phone".equals(kind)) {
            i = new Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_ALL)
                    .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Alert tone")
                    .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                    .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);
            if (AlertSound.isPhoneSound(current)) {
                i.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(current));
            }
            req = REQ_PHONE_SOUND;
        } else if ("file".equals(kind)) {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*");
            req = REQ_SOUND_FILE;
        } else {
            return;
        }
        try {
            startActivityForResult(i, req);
        } catch (ActivityNotFoundException e) {
            soundPicked(null);
        }
    }

    private void soundPicked(String uri) {
        if (web == null) return;
        web.evaluateJavascript("window.__alertsSoundPicked&&window.__alertsSoundPicked("
                + (uri == null ? "null" : JSONObject.quote(uri)) + ")", null);
    }

    private void openPermission(String which) {
        final Intent i;
        switch (which == null ? "" : which) {
            case "overlay":
                i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                break;
            case "full_screen":
                if (Build.VERSION.SDK_INT < 34) return;
                i = new Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:" + getPackageName()));
                break;
            case "dnd":
                i = new Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                break;
            case "notifications":
                i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
                break;
            default:
                return;
        }
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            EventLog.log(this, "WEB", "can't open the setting for " + which);
        }
    }

    private void startSpeech() {
        final Intent listen = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        try {
            startActivityForResult(listen, REQ_SPEECH);
        } catch (ActivityNotFoundException | SecurityException e) {
            speechResult(null, "Voice input isn't available on this phone");
        }
    }

    /** Hands the page what was heard: (text, null), (null, error), or (null, null) if cancelled. */
    @SuppressWarnings("deprecation")
    private static Uri legacyPickedRingtone(Intent data) {
        return data.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
    }

    private void speechResult(String text, String error) {
        if (web == null) return;
        web.evaluateJavascript("window.__everbridgeSpeech&&window.__everbridgeSpeech("
                + (text == null ? "null" : JSONObject.quote(text)) + ","
                + (error == null ? "null" : JSONObject.quote(error)) + ")", null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE) {
            final ValueCallback<Uri[]> cb = fileCallback;
            fileCallback = null;
            if (cb == null) {
                EventLog.log(this, "WEB", "file picker returned, but the page was no longer waiting for it");
                return;
            }
            final Uri picked = resultCode == RESULT_OK && data != null ? data.getData() : null;
            EventLog.log(this, "WEB", picked == null ? "file picker: nothing chosen" : "file picker: a file was chosen");
            cb.onReceiveValue(picked == null ? null : new Uri[]{picked});
        } else if (requestCode == REQ_PHONE_SOUND) {
            Uri picked = null;
            if (resultCode == RESULT_OK && data != null) {
                picked = Build.VERSION.SDK_INT >= 33
                        ? data.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri.class)
                        : legacyPickedRingtone(data);
            }
            soundPicked(picked == null ? null : picked.toString());
        } else if (requestCode == REQ_SOUND_FILE) {
            final Uri picked = resultCode == RESULT_OK && data != null ? data.getData() : null;
            if (picked != null) {
                try {
                    getContentResolver().takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException e) {
                    EventLog.log(this, "ALERT", "no lasting access to " + picked + ": " + e.getMessage());
                }
            }
            soundPicked(picked == null ? null : picked.toString());
        } else if (requestCode == REQ_SPEECH) {
            String heard = null;
            if (resultCode == RESULT_OK && data != null) {
                final ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (results != null && !results.isEmpty()) heard = results.get(0);
            }
            speechResult(heard, null);
        }
    }

    // ------------------------------------------------------------------ panels

    private void removePanel() {
        if (panel != null) root.removeView(panel);
        panel = null;
    }

    private LinearLayout newPanel(String title, String message) {
        removePanel();
        if (web != null) web.setVisibility(View.GONE);
        final LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        final int pad = dp(24);
        p.setPadding(pad, pad, pad, pad);
        p.setBackgroundColor(BACKGROUND);
        p.addView(label(title, 22, INK));
        p.addView(label(message, 15, MUTED), marginTop(8));
        root.addView(p, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panel = p;
        return p;
    }

    /** A page failed to load (and not just one of its images or scripts). */
    void pageFailed(String description) {
        showError("Meanwhile didn't open", description);
    }

    /** Shown instead of a page that failed to load, so the bridge's screens stay reachable. */
    private void showError(String title, String detail) {
        EventLog.log(this, "WEB", title + ": " + detail);
        final LinearLayout p = newPanel(title, detail + "\n\nReadings are still being collected.");
        p.addView(button("Try again", v -> open(AppPages.HOME)), marginTop(16));
        p.addView(button("Eversense connection and setup", v -> startActivity(new Intent(this, StatusActivity.class))),
                marginTop(8));
    }

    // ------------------------------------------------------------------ automation

    /** The emulator test: a file to give the page's next file picker (adb only). */
    private File automationPick;

    private void handleAutomation(Intent intent) {
        if (intent == null || !"web_check".equals(intent.getStringExtra(EXTRA_ACTION))) return;
        final String pick = intent.getStringExtra("pick");
        if (pick != null) {
            automationPick = Automation.fromAdb(this, intent.getStringExtra("token")) ? Automation.stagedFile(this, pick) : null;
            EventLog.log(this, "WEB", automationPick != null ? "automation: next file picked is " + pick
                    : "automation: file " + pick + " refused");
        }
        final String api = automationApi(intent);
        if (api != null) apiOverride = api;
        final String page = automationPage(intent);
        final int loadsBefore = pagesFinished;
        final boolean opening = web != null && !page.isEmpty();
        if (opening) open(AppPages.HOME + page);
        final String click = intent.getStringExtra(EXTRA_CLICK);
        final String until = intent.getStringExtra(EXTRA_UNTIL);
        final String c = click != null && CLICKABLE.contains(click) ? click : null;
        final String id = String.valueOf(System.nanoTime());
        // a page it opened: check that page once it has loaded, not the one it replaces
        if (opening) afterLoad(loadsBefore, 0, () -> webCheck(c, until, 0, id));
        else webCheck(c, until, 0, id);
    }

    /** Page loads finished so far (automation waits for the one it started). */
    private int pagesFinished;

    private void afterLoad(int loadsBefore, int waited, Runnable then) {
        if (pagesFinished > loadsBefore || waited >= 60) {
            then.run();
            return;
        }
        handler.postDelayed(() -> afterLoad(loadsBefore, waited + 1, then), 1_000);
    }

    /** A page of the app to open, from automation: "" (home), "settings/", "profile/" or "alerts/". */
    private static String automationPage(Intent intent) {
        if (intent == null || !"web_check".equals(intent.getStringExtra(EXTRA_ACTION))) return "";
        final String page = intent.getStringExtra(EXTRA_PAGE);
        return "settings/".equals(page) || "profile/".equals(page) || "alerts/".equals(page) ? page : "";
    }

    /** The page to start on: the automation's, or the Alerts tab from the app icon's shortcut. */
    private static String startPage(Intent intent) {
        if (intent != null && intent.getStringExtra(EXTRA_ACTION) == null && "alerts/".equals(intent.getStringExtra(EXTRA_PAGE))) {
            return "alerts/";
        }
        return automationPage(intent);
    }

    /**
     * A server address for this run only, from automation. Only a server on this device
     * itself is accepted, so no other app can send the page's data somewhere else.
     */
    private static String automationApi(Intent intent) {
        if (intent == null || !"web_check".equals(intent.getStringExtra(EXTRA_ACTION))) return null;
        final String url = WebOrigin.normalize(intent.getStringExtra(EXTRA_API));
        if (url == null || !WebOrigin.isLoopbackUrl(url)) return null;
        return url.substring(0, url.indexOf('/', url.indexOf("//") + 2));
    }

    /**
     * Once the page has loaded: presses {@code click} if given (once), then logs the page's
     * text when it contains {@code until} (by default, the reading tile's countdown), or
     * after 90 s regardless.
     */
    private void webCheck(String click, String until, int attempt, String checkId) {
        webCheck(click, until, attempt, new boolean[]{false});
    }

    /**
     * Presses {@code click} (once) with a real tap on the button, as a finger would: a script
     * click isn't a user gesture, and the page's file picker only opens for one.
     */
    private void webCheck(String click, String until, int attempt, boolean[] tapped) {
        if (web == null || isFinishing()) return;
        final boolean findButton = click != null && !tapped[0];
        final String script = "(function(){"
                + (findButton ? "var b=[].slice.call(document.querySelectorAll('button'))"
                + ".filter(function(x){return x.textContent.trim()===" + JSONObject.quote(click) + "})[0];"
                + "if(b){b.scrollIntoView({block:'center'});var r=b.getBoundingClientRect();"
                + "return 'TAP '+(r.left+r.width/2)+' '+(r.top+r.height/2);}" : "")
                + "return document.body?document.body.innerText:''})()";
        web.evaluateJavascript(script, text -> {
            final String t = text == null ? "" : text;
            if (findButton && t.startsWith("\"TAP ")) {
                final String[] xy = t.substring(5, t.length() - 1).split(" ");
                tap(Float.parseFloat(xy[0]), Float.parseFloat(xy[1]));
                tapped[0] = true;
                handler.postDelayed(() -> webCheck(click, until, attempt + 1, tapped), 1_000);
                return;
            }
            final boolean done = until != null ? t.contains(until) : (t.contains("next in") || t.contains("due now"));
            if (done || attempt >= 90) {
                // the line that matched first (it can be far down a long page), then the start of the page
                String match = "";
                if (until != null) {
                    final int at = t.indexOf(until);
                    if (at >= 0) {
                        final int end = t.indexOf("\\n", at);
                        match = " match=" + t.substring(at, end < 0 ? Math.min(t.length(), at + 200) : Math.min(end, at + 200));
                    }
                }
                EventLog.log(this, "WEBCHECK", "native_reads=" + nativeReads.get() + " url=" + (web != null ? web.getUrl() : null)
                        + (click != null ? " tapped=" + tapped[0] : "") + match
                        + " text=" + (t.length() > 3000 ? t.substring(0, 3000) : t));
            } else {
                handler.postDelayed(() -> webCheck(click, until, attempt + 1, tapped), 1_000);
            }
        });
    }

    /** A finger tap on the page at CSS pixel (x, y). */
    private void tap(float cssX, float cssY) {
        if (web == null) return;
        final float d = getResources().getDisplayMetrics().density;
        final float x = cssX * d;
        final float y = cssY * d;
        final long down = android.os.SystemClock.uptimeMillis();
        final android.view.MotionEvent press = android.view.MotionEvent.obtain(down, down,
                android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        web.dispatchTouchEvent(press);
        press.recycle();
        handler.postDelayed(() -> {
            if (web == null) return;
            final android.view.MotionEvent lift = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(),
                    android.view.MotionEvent.ACTION_UP, x, y, 0);
            web.dispatchTouchEvent(lift);
            lift.recycle();
        }, 80);
    }

    // ------------------------------------------------------------------ view helpers

    private TextView label(String s, int sp, int color) {
        final TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        final Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams marginTop(int dpTop) {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(dpTop);
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
