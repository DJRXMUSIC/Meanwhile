package app.meanwhile.bridge;

import static app.meanwhile.bridge.TestNotifications.EV365;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.provider.Settings.Secure;
import android.speech.RecognizerIntent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import app.meanwhile.bridge.core.AppPages;
import app.meanwhile.bridge.core.Reading;
import app.meanwhile.bridge.core.TimestampSource;

/** The Meanwhile screen: the packaged pages, the WebView's rules, and the native functions. */
@RunWith(RobolectricTestRunner.class)
public class MainActivityTest {

    private static final String SITE = AppPages.HOME;

    private Application app;
    private ActivityController<MainActivity> controller;

    @Before
    public void setUp() {
        Bridge.resetForTests();
        app = RuntimeEnvironment.getApplication();
        EventLog.clear(app);
    }

    @After
    public void tearDown() {
        if (controller != null) controller.pause().stop().destroy();
        Bridge.resetForTests();
    }

    private void grantNotificationAccess() {
        Secure.putString(app.getContentResolver(), "enabled_notification_listeners",
                new ComponentName(app, EversenseListenerService.class).flattenToString());
    }

    /** Starts the screen as an already set-up install would see it. */
    private MainActivity startWithSite() {
        grantNotificationAccess();
        return start(new Intent(app, MainActivity.class));
    }

    private MainActivity start(Intent intent) {
        controller = Robolectric.buildActivity(MainActivity.class, intent).setup();
        shadowOf(Looper.getMainLooper()).idle();
        return controller.get();
    }

    private static WebViewClient client(MainActivity a) {
        return shadowOf(a.web()).getWebViewClient();
    }

    private static MainActivity.Native nativeOf(MainActivity a) {
        return (MainActivity.Native) shadowOf(a.web()).getJavascriptInterface(MainActivity.JS_NAME);
    }

    /** As the WebView reports a page starting to load. */
    private static void pageStarted(MainActivity a, String url) {
        client(a).onPageStarted(a.web(), url, null);
    }

    private static WebResourceRequest request(String url, boolean mainFrame) {
        return new WebResourceRequest() {
            @Override
            public Uri getUrl() {
                return Uri.parse(url);
            }

            @Override
            public boolean isForMainFrame() {
                return mainFrame;
            }

            @Override
            public boolean isRedirect() {
                return false;
            }

            @Override
            public boolean hasGesture() {
                return true;
            }

            @Override
            public String getMethod() {
                return "GET";
            }

            @Override
            public Map<String, String> getRequestHeaders() {
                return Collections.emptyMap();
            }
        };
    }

    private static String allText(View v) {
        if (v instanceof TextView) return ((TextView) v).getText() + "\n";
        final StringBuilder sb = new StringBuilder();
        if (v instanceof ViewGroup) {
            final ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (g.getChildAt(i).getVisibility() == View.VISIBLE) sb.append(allText(g.getChildAt(i)));
            }
        }
        return sb.toString();
    }

    private static <T extends View> T find(View v, Class<T> type, String text) {
        if (type.isInstance(v) && (text == null || text.contentEquals(((TextView) v).getText()))) return type.cast(v);
        if (v instanceof ViewGroup) {
            final ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                final T hit = find(g.getChildAt(i), type, text);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static String screen(Activity a) {
        return allText(a.findViewById(android.R.id.content));
    }

    private static void tap(Activity a, String button) {
        final Button b = find(a.findViewById(android.R.id.content), Button.class, button);
        assertNotNull("no button '" + button + "' in: " + screen(a), b);
        b.performClick();
        shadowOf(Looper.getMainLooper()).idle();
    }

    // ---------------------------------------------------------------- opening

    @Test
    public void firstRunOpensMeanwhileAndTheBridgeSetup() {
        final MainActivity a = start(new Intent(app, MainActivity.class));
        // no address to enter: Meanwhile is inside the app
        assertEquals(AppPages.HOME, shadowOf(a.web()).getLastLoadedUrl());
        // notification access is off on a fresh install: the bridge's setup screen comes up
        final Intent next = shadowOf(a).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(StatusActivity.class.getName(), next.getComponent().getClassName());
        assertTrue(Bridge.get(app).server.isRunning());
    }

    @Test
    public void setUpInstallGoesStraightToMeanwhile() {
        final MainActivity a = startWithSite();
        assertNull("no setup screen once access is granted", shadowOf(a).getNextStartedActivity());
        assertEquals(AppPages.HOME, shadowOf(a.web()).getLastLoadedUrl());
        assertTrue(a.web().getSettings().getJavaScriptEnabled());
        assertTrue(a.web().getSettings().getDomStorageEnabled());
        assertFalse(a.web().getSettings().getAllowFileAccess());
        assertTrue(a.web().getSettings().getUserAgentString().contains("EverBridge/"));
        assertNotNull(nativeOf(a));
    }

    // ---------------------------------------------------------------- the packaged pages

    private static String body(WebResourceResponse r) throws java.io.IOException {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        int k;
        while ((k = r.getData().read(buf)) > 0) out.write(buf, 0, k);
        return out.toString("UTF-8");
    }

    @Test
    public void pagesComeFromInsideTheApp() throws Exception {
        final MainActivity a = startWithSite();
        WebResourceResponse r = a.serve(AppPages.HOME);
        assertEquals(200, r.getStatusCode());
        assertEquals("text/html", r.getMimeType());
        assertEquals("utf-8", r.getEncoding());
        final String home = body(r);
        assertTrue(home, home.contains("<html"));
        assertTrue(home, home.contains("/_next/static/"));

        // every script and stylesheet the home page names is in the app
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"(/_next/static/[^\"?\\\\]+)\"").matcher(home);
        int found = 0;
        while (m.find()) {
            final WebResourceResponse part = a.serve(AppPages.ORIGIN + m.group(1));
            assertEquals(m.group(1), 200, part.getStatusCode());
            found++;
        }
        assertTrue("only " + found + " parts", found >= 3);

        for (String page : new String[]{"settings/", "profile/", "settings", "settings/index.txt?_rsc=1"}) {
            r = a.serve(AppPages.HOME + page);
            assertEquals(page, 200, r.getStatusCode());
        }
        assertEquals("text/plain", a.serve(AppPages.HOME + "settings/index.txt").getMimeType());

        // nothing else
        assertEquals(404, a.serve(AppPages.HOME + "no-such-page/").getStatusCode());
        assertEquals(404, a.serve(AppPages.HOME + "../../shared_prefs/bridge_settings.xml").getStatusCode());
        assertEquals(404, a.serve(AppPages.HOME + "%2e%2e/%2e%2e/databases/readings.db").getStatusCode());
        assertNull("other sites are fetched normally", a.serve("https://my-site.netlify.app/api/sync"));
        assertNull(a.serve("http://127.0.0.1:17590/sgv.json"));
        assertEquals("malformed, but the app's", 404, a.serve(AppPages.ORIGIN + "/_next/x.css\\").getStatusCode());
    }

    // ---------------------------------------------------------------- staying on the site

    @Test
    public void linksOffTheSiteOpenInTheBrowser() {
        final MainActivity a = startWithSite();
        assertFalse(client(a).shouldOverrideUrlLoading(a.web(), request(SITE + "settings/", true)));
        assertNull(shadowOf(a).getNextStartedActivity());

        assertTrue(client(a).shouldOverrideUrlLoading(a.web(), request("https://example.org/page", true)));
        final Intent view = shadowOf(a).getNextStartedActivity();
        assertEquals(Intent.ACTION_VIEW, view.getAction());
        assertEquals("https://example.org/page", view.getDataString());

        // a look-alike host is not the app
        assertTrue(client(a).shouldOverrideUrlLoading(a.web(), request("https://" + AppPages.HOST + ".evil.example/", true)));
        // the Meanwhile server is a website too
        assertTrue(client(a).shouldOverrideUrlLoading(a.web(), request("https://my-site.netlify.app/", true)));
        // sub-resources are not navigations
        assertFalse(client(a).shouldOverrideUrlLoading(a.web(), request("https://fonts.example/x.woff", false)));
    }

    // ---------------------------------------------------------------- readings for the page

    @Test
    public void pageReadsTheSameReadingsTheEndpointServes() throws Exception {
        final long now = System.currentTimeMillis();
        final Bridge b = Bridge.get(app);
        b.store.insert(new Reading(now - 600_000, 118, TimestampSource.NOTIFICATION_WHEN, now, now, now, EV365, ""));
        b.store.insert(new Reading(now - 300_000, 124, TimestampSource.NOTIFICATION_WHEN, now, now, now, EV365, ""));
        b.store.insert(new Reading(now - 1_000, 131, TimestampSource.NOTIFICATION_WHEN, now, now, now, EV365, ""));

        final MainActivity a = startWithSite();
        pageStarted(a, SITE);
        final String json = nativeOf(a).sgv(2);
        assertEquals(b.routes.sgv(2), json);
        final JSONArray arr = new JSONArray(json);
        assertEquals(2, arr.length());
        assertEquals(131, arr.getJSONObject(0).getInt("sgv"));
        assertEquals(7, arr.getJSONObject(0).getInt("delta")); // change since the previous reading
        assertEquals(now - 1_000, arr.getJSONObject(0).getLong("date"));
        assertEquals(1, a.nativeReads.get());

        // silly counts are clamped, not errors
        assertEquals(1, new JSONArray(nativeOf(a).sgv(0)).length());
        assertEquals(3, new JSONArray(nativeOf(a).sgv(1_000_000)).length());
    }

    @Test
    public void nativeFunctionsOnlyWorkOnTheSite() {
        final long now = System.currentTimeMillis();
        Bridge.get(app).store.insert(new Reading(now, 131, TimestampSource.POST_TIME, 0, now, now, EV365, ""));
        final MainActivity a = startWithSite();

        // before any page has started, and on any other page: nothing
        assertEquals("[]", nativeOf(a).sgv(5));
        pageStarted(a, "https://evil.example/");
        assertEquals("[]", nativeOf(a).sgv(5));
        assertNull(nativeOf(a).saveBegin("x.json"));
        assertFalse(nativeOf(a).copyText("secret"));
        nativeOf(a).openStatus();
        nativeOf(a).startSpeech();
        // the Alerts tab's functions too: nothing read, nothing changed, nothing opened
        assertNull(nativeOf(a).alertsState());
        assertEquals("not allowed", nativeOf(a).alertsSaveRule("{\"uuid\":\"x\",\"threshold\":80}"));
        assertEquals("not allowed", nativeOf(a).alertsSaveSettings("{}"));
        assertEquals("not allowed", nativeOf(a).alertsTest("{\"uuid\":\"x\",\"threshold\":80}"));
        nativeOf(a).alertsPickSound("phone", "");
        nativeOf(a).alertsOpenPermission("overlay");
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(shadowOf(a).getNextStartedActivity());
        assertEquals(0, a.nativeReads.get());

        pageStarted(a, SITE + "settings");
        assertEquals(1, new JSONArray(parse(nativeOf(a).sgv(5))).length());

        // an in-page navigation elsewhere turns it off again
        client(a).doUpdateVisitedHistory(a.web(), "https://evil.example/", false);
        assertEquals("[]", nativeOf(a).sgv(5));
    }

    private static java.util.List<Object> parse(String json) {
        try {
            final JSONArray arr = new JSONArray(json);
            final java.util.List<Object> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) out.add(arr.get(i));
            return out;
        } catch (org.json.JSONException e) {
            throw new AssertionError(json, e);
        }
    }

    // ---------------------------------------------------------------- server address

    @Test
    public void serverAddressIsSavedAsJustTheSite() {
        final MainActivity a = startWithSite();
        final MainActivity.Native n = nativeOf(a);
        pageStarted(a, SITE + "settings/");
        assertEquals("", n.apiBase());
        assertEquals("https://my-site.netlify.app", n.setApiBase("  My-Site.netlify.app/settings/  "));
        assertEquals("https://my-site.netlify.app", n.apiBase());
        assertEquals("https://my-site.netlify.app", new Settings(app).apiBase());
        // unusable: refused, and the saved one is kept
        assertNull(n.setApiBase("not an address"));
        assertNull(n.setApiBase("http://my-site.netlify.app"));
        assertNull(n.setApiBase(null));
        assertEquals("https://my-site.netlify.app", n.apiBase());
        // empty clears it
        assertEquals("", n.setApiBase(""));
        assertEquals("", new Settings(app).apiBase());

        // and nothing from anywhere but the app's pages
        n.setApiBase("https://my-site.netlify.app");
        pageStarted(a, "https://evil.example/");
        assertEquals("", n.apiBase());
        assertNull(n.setApiBase("https://evil.example"));
        assertEquals("https://my-site.netlify.app", new Settings(app).apiBase());
    }

    // ---------------------------------------------------------------- backup, clipboard, status

    @Test
    public void backupIsSavedAsAFile() {
        final MainActivity a = startWithSite();
        pageStarted(a, SITE);
        final MainActivity.Native n = nativeOf(a);
        // in pieces, with a two-unit character (an emoji) cut across two of them
        final String id = n.saveBegin("meanwhile-2026-11-16.json");
        assertNotNull(id);
        assertTrue(n.saveChunk(id, "{\"note\":\"pizza \uD83C"));
        assertTrue(n.saveChunk(id, "\uDF55 née\"}"));
        final String where = n.saveEnd(id);
        assertNotNull(where);
        assertTrue(where, where.contains("meanwhile-2026-11-16.json"));
        assertTrue(EventLog.readAll(app).contains("[EXPORT] saved"));
        assertEquals("{\"note\":\"pizza \uD83C\uDF55 née\"}", savedText(where));

        // nothing is left in the cache, and a finished or unknown id does nothing
        final java.io.File[] left = new java.io.File(app.getCacheDir(), "saves").listFiles();
        assertEquals(0, left == null ? 0 : left.length);
        assertFalse(n.saveChunk(id, "more"));
        assertNull(n.saveEnd(id));
        assertFalse(n.saveChunk("nope", "x"));

        // a name can't climb out of the folder
        final String oddId = n.saveBegin("../../evil dir/x.json");
        assertTrue(n.saveChunk(oddId, "{}"));
        final String odd = n.saveEnd(oddId);
        assertNotNull(odd);
        assertFalse(odd, odd.contains(".."));
        assertFalse(odd, odd.contains(" "));
        assertEquals("file", LogExport.safeName(""));
        assertEquals("a_b.json", LogExport.safeName("a/b.json"));
        assertEquals("_hidden", LogExport.safeName("..hidden"));
        assertEquals("____evil_dir_x.json", LogExport.safeName("../../evil dir/x.json"));
        assertEquals("meanwhile-2026-11-16.json", LogExport.safeName("meanwhile-2026-11-16.json"));
    }

    /** Reads back a saved file, wherever {@link LogExport} put it. */
    private String savedText(String where) {
        try {
            final java.io.File direct = new java.io.File(where);
            if (direct.isFile()) {
                return new String(java.nio.file.Files.readAllBytes(direct.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            }
            final String name = where.substring(where.lastIndexOf('/') + 1);
            try (android.database.Cursor c = app.getContentResolver().query(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{android.provider.MediaStore.MediaColumns._ID},
                    android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=?", new String[]{name}, null)) {
                assertTrue("saved file not found: " + where, c != null && c.moveToFirst());
                final Uri uri = android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0));
                try (java.io.InputStream in = app.getContentResolver().openInputStream(uri)) {
                    final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    final byte[] buf = new byte[4096];
                    int k;
                    while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
                    return out.toString("UTF-8");
                }
            }
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void copyAndStatusScreen() {
        final MainActivity a = startWithSite();
        pageStarted(a, SITE);
        assertTrue(nativeOf(a).copyText("abc123"));
        assertEquals("abc123", app.getSystemService(ClipboardManager.class).getPrimaryClip().getItemAt(0).getText().toString());

        nativeOf(a).openStatus();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(StatusActivity.class.getName(), shadowOf(a).getNextStartedActivity().getComponent().getClassName());
    }

    @Test
    public void alertsTabFunctionsOnTheSite() throws Exception {
        final MainActivity a = startWithSite();
        pageStarted(a, SITE + "alerts/");
        final org.json.JSONObject st = new org.json.JSONObject(nativeOf(a).alertsState());
        assertTrue(st.getJSONObject("config").getJSONArray("alerts").length() >= 1);
        nativeOf(a).alertsPickSound("phone", "");
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(android.media.RingtoneManager.ACTION_RINGTONE_PICKER, shadowOf(a).getNextStartedActivity().getAction());
        nativeOf(a).alertsPickSound("file", "");
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, shadowOf(a).getNextStartedActivity().getAction());
        nativeOf(a).alertsOpenPermission("overlay");
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, shadowOf(a).getNextStartedActivity().getAction());
        nativeOf(a).alertsOpenPermission("anything else");
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(shadowOf(a).getNextStartedActivity());
    }

    // ---------------------------------------------------------------- voice and file picker

    @Test
    public void voiceInputComesBackToThePage() {
        final MainActivity a = startWithSite();
        pageStarted(a, SITE);
        nativeOf(a).startSpeech();
        shadowOf(Looper.getMainLooper()).idle();
        final ShadowActivity.IntentForResult listen = shadowOf(a).getNextStartedActivityForResult();
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, listen.intent.getAction());
        assertEquals(MainActivity.REQ_SPEECH, listen.requestCode);

        shadowOf(a).receiveResult(listen.intent, Activity.RESULT_OK, new Intent().putStringArrayListExtra(
                RecognizerIntent.EXTRA_RESULTS, new ArrayList<>(Arrays.asList("I'm at \"180\" eating now", "other guess"))));
        assertEquals("window.__everbridgeSpeech&&window.__everbridgeSpeech(\"I'm at \\\"180\\\" eating now\",null)",
                shadowOf(a.web()).getLastEvaluatedJavascript());

        // cancelled: the page is still told, so its mic button stops "listening"
        nativeOf(a).startSpeech();
        shadowOf(Looper.getMainLooper()).idle();
        final ShadowActivity.IntentForResult again = shadowOf(a).getNextStartedActivityForResult();
        shadowOf(a).receiveResult(again.intent, Activity.RESULT_CANCELED, null);
        assertEquals("window.__everbridgeSpeech&&window.__everbridgeSpeech(null,null)",
                shadowOf(a.web()).getLastEvaluatedJavascript());
    }

    @Test
    public void restoreFromFilePicksAFile() {
        final MainActivity a = startWithSite();
        final Uri[][] got = new Uri[1][];
        final boolean[] called = new boolean[1];
        final ValueCallback<Uri[]> cb = v -> {
            got[0] = v;
            called[0] = true;
        };
        assertTrue(shadowOf(a.web()).getWebChromeClient().onShowFileChooser(a.web(), cb, null));
        final ShadowActivity.IntentForResult pick = shadowOf(a).getNextStartedActivityForResult();
        assertEquals(Intent.ACTION_GET_CONTENT, pick.intent.getAction());
        final Uri file = Uri.parse("content://downloads/meanwhile-2026-11-16.json");
        shadowOf(a).receiveResult(pick.intent, Activity.RESULT_OK, new Intent().setData(file));
        assertTrue(called[0]);
        assertEquals(1, got[0].length);
        assertEquals(file, got[0][0]);

        // backing out of the picker answers the page with "nothing", or it could never ask again
        called[0] = false;
        shadowOf(a.web()).getWebChromeClient().onShowFileChooser(a.web(), cb, null);
        final ShadowActivity.IntentForResult pick2 = shadowOf(a).getNextStartedActivityForResult();
        shadowOf(a).receiveResult(pick2.intent, Activity.RESULT_CANCELED, null);
        assertTrue(called[0]);
        assertNull(got[0]);
    }

    // ---------------------------------------------------------------- failures

    @Test
    public void pageThatWontLoadLeavesAWayBack() {
        final MainActivity a = startWithSite();
        // (the SDK can't build a WebResourceError; this is what onReceivedError calls for a page)
        a.pageFailed("net::ERR_NAME_NOT_RESOLVED");
        String s = screen(a);
        assertTrue(s, s.contains("Meanwhile didn't open"));
        assertTrue(s, s.contains("net::ERR_NAME_NOT_RESOLVED"));
        assertTrue(s, s.contains("Readings are still being collected"));
        assertEquals(View.GONE, a.web().getVisibility());

        tap(a, "Eversense connection and setup");
        assertEquals(StatusActivity.class.getName(), shadowOf(a).getNextStartedActivity().getComponent().getClassName());

        tap(a, "Try again");
        assertEquals(View.VISIBLE, a.web().getVisibility());

        // a failed image or script is not a failed page
        client(a).onReceivedError(a.web(), request(SITE + "icon.png", false), null);
        assertFalse(screen(a), screen(a).contains("Meanwhile didn't open"));

        client(a).onReceivedHttpError(a.web(), request(SITE, true),
                new WebResourceResponse("text/html", "utf-8", 404, "Not Found", null, null));
        s = screen(a);
        assertTrue(s, s.contains("Error 404"));
        tap(a, "Try again");
        assertEquals(View.VISIBLE, a.web().getVisibility());
        assertEquals(SITE, shadowOf(a.web()).getLastLoadedUrl());
        assertFalse(screen(a), screen(a).contains("Try again"));
    }

    private static RenderProcessGoneDetail crash() {
        return new RenderProcessGoneDetail() {
            @Override
            public boolean didCrash() {
                return true;
            }

            @Override
            public int rendererPriorityAtExit() {
                return 0;
            }
        };
    }

    @Test
    public void crashedPageIsReplacedWithoutTakingTheBridgeDown() {
        final MainActivity a = startWithSite();
        final WebView first = a.web();
        // true = handled; false would let Android kill the whole app, bridge included
        assertTrue(client(a).onRenderProcessGone(first, crash()));
        assertNotNull(a.web());
        assertNotSame(first, a.web());
        assertEquals(SITE, shadowOf(a.web()).getLastLoadedUrl());
        assertTrue(Bridge.get(app).server.isRunning());
        assertTrue(EventLog.readAll(app).contains("page renderer gone"));

        // a page that keeps crashing stops being reloaded
        for (int i = 0; i < 3; i++) assertTrue(client(a).onRenderProcessGone(a.web(), crash()));
        assertNull(a.web());
        assertTrue(screen(a), screen(a).contains("Meanwhile keeps stopping"));
        tap(a, "Try again");
        assertNotNull(a.web());
    }

    // ---------------------------------------------------------------- automation

    @Test
    public void automationCanOnlyPointAtThisDeviceAndNeverSaves() {
        grantNotificationAccess();
        new Settings(app).setApiBase("https://my-site.netlify.app");
        // another app can't send the page's data to a server of its choosing
        MainActivity a = start(new Intent(app, MainActivity.class)
                .putExtra(MainActivity.EXTRA_ACTION, "web_check").putExtra(MainActivity.EXTRA_API, "https://evil.example/"));
        pageStarted(a, SITE);
        assertEquals("https://my-site.netlify.app", nativeOf(a).apiBase());
        controller.pause().stop().destroy();

        a = start(new Intent(app, MainActivity.class).putExtra(MainActivity.EXTRA_ACTION, "web_check")
                .putExtra(MainActivity.EXTRA_API, "http://localhost:3000/").putExtra(MainActivity.EXTRA_PAGE, "settings/"));
        pageStarted(a, SITE + "settings/");
        assertEquals("http://localhost:3000", nativeOf(a).apiBase());
        assertEquals(SITE + "settings/", shadowOf(a.web()).getLastLoadedUrl());
        assertEquals("https://my-site.netlify.app", new Settings(app).apiBase());
        controller.pause().stop().destroy();

        // without the web_check action the extras are ignored, and only known pages open
        a = start(new Intent(app, MainActivity.class).putExtra(MainActivity.EXTRA_API, "http://localhost:3000")
                .putExtra(MainActivity.EXTRA_PAGE, "settings/"));
        pageStarted(a, SITE);
        assertEquals("https://my-site.netlify.app", nativeOf(a).apiBase());
        assertEquals(SITE, shadowOf(a.web()).getLastLoadedUrl());
        controller.pause().stop().destroy();
        a = start(new Intent(app, MainActivity.class).putExtra(MainActivity.EXTRA_ACTION, "web_check")
                .putExtra(MainActivity.EXTRA_PAGE, "../../evil"));
        assertEquals(SITE, shadowOf(a.web()).getLastLoadedUrl());
        assertSame(a, controller.get());
        // only harmless buttons can be pressed
        assertEquals(new java.util.HashSet<>(Arrays.asList("Download backup", "Sync now", "Open glucose alerts", "Restore from file")), MainActivity.CLICKABLE);
    }

    @Test
    public void backLeavesTheAppWhenThePageHasNoHistory() {
        final MainActivity a = startWithSite();
        a.onBackPressed(); // nothing to go back to: leaves the app
        assertTrue(a.isFinishing());
    }
}
