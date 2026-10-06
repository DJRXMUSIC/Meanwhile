package app.meanwhile.bridge;

import static app.meanwhile.bridge.TestNotifications.EV365;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;

/** Notification in, Nightscout JSON out over a real socket: what Meanwhile sees. */
@RunWith(RobolectricTestRunner.class)
public class BridgeHttpEndToEndTest {

    private Application app;
    private ServiceController<EversenseListenerService> listener;
    private Bridge bridge;
    private int port;

    @Before
    public void setUp() throws Exception {
        Bridge.resetForTests();
        app = RuntimeEnvironment.getApplication();
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        new Settings(app).save(port, false, "", "auto", "", true, 20);
        bridge = Bridge.get(app);
        assertTrue(bridge.ensureServer());
        listener = Robolectric.buildService(EversenseListenerService.class).create();
    }

    @After
    public void tearDown() {
        listener.destroy();
        Bridge.resetForTests();
    }

    private String get(String path) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(3000);
        assertEquals(200, c.getResponseCode());
        assertEquals("*", c.getHeaderField("Access-Control-Allow-Origin"));
        try (InputStream in = c.getInputStream()) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toString("UTF-8");
        }
    }

    @Test
    public void meanwhileSeesEachReadingWithItsExactTimestamp() throws Exception {
        final long t0 = System.currentTimeMillis() - 10 * 60_000L;
        final int[] values = {104, 109, 117};
        for (int i = 0; i < values.length; i++) {
            final long when = t0 + i * 5 * 60_000L;
            listener.get().onNotificationPosted(TestNotifications.sbn(EV365,
                    TestNotifications.customView(app, String.valueOf(values[i]), "mg/dL", when), when + 1_100));
        }

        final JSONArray a = new JSONArray(get("/sgv.json?count=144"));
        assertEquals(3, a.length());
        final JSONObject newest = a.getJSONObject(0);
        assertEquals(117, newest.getInt("sgv"));
        assertEquals(t0 + 10 * 60_000L, newest.getLong("date"));
        assertEquals(8.0, newest.getDouble("delta"), 1e-9);
        assertEquals("FortyFiveUp", newest.getString("direction"));
        assertEquals("notification_when", newest.getString("ts_source"));
        assertEquals("mgdl", newest.getString("units_hint"));
        assertEquals(104, a.getJSONObject(2).getInt("sgv"));
        assertEquals("NotComputable", a.getJSONObject(2).getString("direction"));
    }

    @Test
    public void statusReportsHealth() throws Exception {
        JSONObject s = new JSONObject(get("/status.json"));
        assertFalse(s.getBoolean("ok"));
        assertTrue(s.isNull("last_reading"));
        assertEquals(port, s.getJSONObject("server").getInt("port"));
        assertEquals(EV365, s.getJSONArray("packages").getString(0));

        bridge.setListenerConnected(true);
        final long now = System.currentTimeMillis();
        listener.get().onNotificationPosted(TestNotifications.sbn(EV365,
                TestNotifications.customView(app, "131", "mg/dL", now - 800), now));
        s = new JSONObject(get("/status.json"));
        assertTrue(s.getBoolean("ok"));
        assertEquals(131, s.getJSONObject("last_reading").getInt("sgv"));
        assertEquals("STORED", s.getJSONObject("last_capture").getString("outcome"));
    }

    @Test
    public void settingsChangeMovesTheEndpoint() throws Exception {
        final int newPort;
        try (ServerSocket s = new ServerSocket(0)) {
            newPort = s.getLocalPort();
        }
        bridge.settings.save(newPort, false, "", "auto", "", true, 20);
        assertTrue(bridge.ensureServer());
        port = newPort;
        assertEquals("[]", get("/sgv.json"));
    }
}
