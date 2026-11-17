package app.meanwhile.bridge;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.text.InputType;
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
import android.widget.EditText;
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

import app.meanwhile.bridge.core.BridgeRoutes;
import app.meanwhile.bridge.core.WebOrigin;

/**
 * What the app icon opens: Meanwhile, in a WebView, with the bridge running underneath in
 * the same process.
 *
 * The page is the Meanwhile site (its address is entered once and kept on the phone). On
 * that site only, the page gets {@code window.EverBridgeNative}: the readings straight from
 * the bridge's store (no network hop), saving a backup file, voice input, the clipboard and
 * a way to open the bridge's {@link StatusActivity status screen}. Links to anywhere else
 * open in the browser.
 *
 * The WebView must never take the bridge down with it: a crashed page renderer is replaced
 * here instead of being left to kill the process, which is also the notification listener.
 *
 * Automation (used by the emulator test): {@code --es action web_check} logs what the page
 * shows; with it, {@code --es url http://localhost:PORT} loads a site served on this
 * device for that run only.
 */
public class MainActivity extends Activity {

    static final String EXTRA_ACTION = "action";
    static final String EXTRA_URL = "url";
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
    /** The address form or an error message, shown instead of the page; null otherwise. */
    private View panel;
    /** The site being shown. Its origin is what {@link #trusted} is measured against. */
    private String baseUrl;
    /** True while the page in the WebView is on {@link #baseUrl}'s origin. */
    private volatile boolean trusted;
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

        final String testUrl = automationUrl(getIntent());
        final String url = testUrl != null ? testUrl : bridge.settings.webUrl();
        if (url.isEmpty()) showAddressForm();
        else open(url);

        // Setup first: without notification access there are no readings to show.
        if (savedInstanceState == null && testUrl == null && !bridge.hasNotificationAccess()) {
            startActivity(new Intent(this, StatusActivity.class));
        }
        handleAutomation(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
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

    /** Shows {@code url}, which becomes the site the native functions are offered to. */
    void open(String url) {
        baseUrl = url;
        removePanel();
        if (web == null) web = createWeb();
        web.setVisibility(View.VISIBLE);
        trusted = false; // until the page has started loading from the right place
        web.loadUrl(url);
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
            trusted = WebOrigin.sameOrigin(baseUrl, url);
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            trusted = WebOrigin.sameOrigin(baseUrl, url);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) return false;
            final String url = request.getUrl().toString();
            if (WebOrigin.sameOrigin(baseUrl, url)) return false;
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
            if (request.isForMainFrame()) {
                showError("Can't reach Meanwhile", String.valueOf(error.getDescription()));
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (request.isForMainFrame() && response.getStatusCode() >= 400) {
                showError("Meanwhile didn't open", "The site answered with error " + response.getStatusCode()
                        + ". Check the address is right.");
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
            else if (baseUrl != null) open(baseUrl);
            return true;
        }
    }

    private final class Chrome extends WebChromeClient {
        // "Restore from file" in Meanwhile's settings
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
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

        @JavascriptInterface
        public void openStatus() {
            if (trusted) runOnUiThread(() -> startActivity(new Intent(MainActivity.this, StatusActivity.class)));
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
            if (cb == null) return;
            final Uri picked = resultCode == RESULT_OK && data != null ? data.getData() : null;
            cb.onReceiveValue(picked == null ? null : new Uri[]{picked});
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

    /** Asks for the Meanwhile site's address: on first run, and from "Change Meanwhile address". */
    void showAddressForm() {
        final String current = bridge.settings.webUrl();
        final LinearLayout p = newPanel("Meanwhile address",
                "Paste the web address you open Meanwhile with. It starts with https:// "
                        + "and you only need to do this once.");
        final EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setSingleLine(true);
        field.setHint("https://");
        field.setText(current);
        field.setTextColor(INK);
        field.setHintTextColor(MUTED);
        p.addView(field, marginTop(16));
        final TextView problem = label("", 14, Color.rgb(239, 152, 0));
        p.addView(problem, marginTop(4));
        p.addView(button("Open Meanwhile", v -> {
            final String url = WebOrigin.normalize(field.getText().toString());
            if (url == null) {
                problem.setText("That isn't a web address this app can open. It should look like "
                        + "https://your-site.netlify.app");
                return;
            }
            bridge.settings.setWebUrl(url);
            EventLog.log(this, "WEB", "Meanwhile address set");
            open(url);
        }), marginTop(12));
        if (!current.isEmpty()) {
            p.addView(button("Cancel", v -> {
                removePanel();
                if (web != null) web.setVisibility(View.VISIBLE);
                else open(current);
            }), marginTop(8));
        }
        p.addView(button("EverBridge status and setup", v -> startActivity(new Intent(this, StatusActivity.class))),
                marginTop(8));
    }

    /** Shown instead of a page that failed to load, so the bridge's screens stay reachable. */
    private void showError(String title, String detail) {
        EventLog.log(this, "WEB", title + ": " + detail);
        final LinearLayout p = newPanel(title, detail + "\n\nReadings are still being collected.");
        p.addView(button("Try again", v -> {
            if (baseUrl != null) open(baseUrl);
        }), marginTop(16));
        p.addView(button("EverBridge status and setup", v -> startActivity(new Intent(this, StatusActivity.class))),
                marginTop(8));
        p.addView(button("Change Meanwhile address", v -> showAddressForm()), marginTop(8));
    }

    // ------------------------------------------------------------------ automation

    private void handleAutomation(Intent intent) {
        final String action = intent != null ? intent.getStringExtra(EXTRA_ACTION) : null;
        if ("change_address".equals(action)) showAddressForm();
        else if ("web_check".equals(action)) webCheck(0);
    }

    /**
     * An address to load for this run only, from a {@code web_check} intent. Only a site
     * served on this device itself is accepted, so no other app can point this window (and
     * the readings it offers) at a site of its choosing.
     */
    private static String automationUrl(Intent intent) {
        if (intent == null || !"web_check".equals(intent.getStringExtra(EXTRA_ACTION))) return null;
        final String url = WebOrigin.normalize(intent.getStringExtra(EXTRA_URL));
        return url != null && WebOrigin.isLoopbackUrl(url) ? url : null;
    }

    /** Logs the page's text once the reading tile has filled in (or after 90 s regardless). */
    private void webCheck(int attempt) {
        if (web == null || isFinishing()) return;
        web.evaluateJavascript("(function(){return document.body?document.body.innerText:''})()", text -> {
            final String t = text == null ? "" : text;
            if (t.contains("next in") || t.contains("due now") || attempt >= 90) {
                EventLog.log(this, "WEBCHECK", "native_reads=" + nativeReads.get() + " url=" + (web != null ? web.getUrl() : null)
                        + " text=" + (t.length() > 600 ? t.substring(0, 600) : t));
            } else {
                handler.postDelayed(() -> webCheck(attempt + 1), 1_000);
            }
        });
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
