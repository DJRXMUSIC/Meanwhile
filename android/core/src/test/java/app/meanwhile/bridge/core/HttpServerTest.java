package app.meanwhile.bridge.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class HttpServerTest {

    private static final long T = 1_790_000_000_000L;

    private MemoryReadingStore store;
    private HttpServer server;

    @Before
    public void setUp() {
        store = new MemoryReadingStore();
        for (int i = 0; i < 30; i++) {
            final long ts = T + i * 300_000L;
            store.insert(new Reading(ts, 100 + i, TimestampSource.POST_TIME, 0, ts, ts, "p", ""));
        }
        server = new HttpServer(new BridgeRoutes(store, new BridgeRoutes.Status() {
            @Override
            public String statusJson() {
                return new Json().beginObject().field("ok", true).endObject().toString();
            }

            @Override
            public String unitsHint() {
                return "mgdl";
            }
        }));
        assertTrue(server.start(0, false, null));
    }

    @After
    public void tearDown() {
        server.stop();
    }

    private HttpURLConnection get(String path) throws IOException {
        final HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + server.port() + path).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(3000);
        return c;
    }

    private static String body(HttpURLConnection c) throws IOException {
        final InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    @Test
    public void sgvDefaultCountIs24NewestFirstWithCors() throws Exception {
        final HttpURLConnection c = get("/sgv.json");
        assertEquals(200, c.getResponseCode());
        assertEquals("*", c.getHeaderField("Access-Control-Allow-Origin"));
        assertEquals("no-store", c.getHeaderField("Cache-Control"));
        assertTrue(c.getContentType().startsWith("application/json"));
        final JSONArray a = new JSONArray(body(c));
        assertEquals(24, a.length());
        assertEquals(129, a.getJSONObject(0).getInt("sgv"));
        assertEquals(T + 29 * 300_000L, a.getJSONObject(0).getLong("date"));
        assertEquals("Flat", a.getJSONObject(0).getString("direction")); // +1 per 5 min
    }

    @Test
    public void countParameterAndNightscoutPaths() throws Exception {
        assertEquals(30, new JSONArray(body(get("/sgv.json?count=144"))).length());
        assertEquals(5, new JSONArray(body(get("/api/v1/entries/sgv.json?count=5"))).length());
        assertEquals(1, new JSONArray(body(get("/api/v1/entries.json?count=0"))).length());
        assertEquals(24, new JSONArray(body(get("/sgv.json?count=abc"))).length());
    }

    @Test
    public void statusAndRootAnd404() throws Exception {
        assertTrue(new JSONObject(body(get("/status.json"))).getBoolean("ok"));
        assertTrue(body(get("/")).contains("sgv.json"));
        assertEquals(404, get("/nope").getResponseCode());
    }

    @Test
    public void corsPreflightIncludesPrivateNetworkAccess() throws Exception {
        final String raw = rawRequest("OPTIONS /sgv.json HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                + "Origin: https://meanwhile.example\r\nAccess-Control-Request-Method: GET\r\n"
                + "Access-Control-Request-Private-Network: true\r\n\r\n");
        assertTrue(raw, raw.startsWith("HTTP/1.1 204"));
        assertTrue(raw.contains("Access-Control-Allow-Private-Network: true"));
        assertTrue(raw.contains("Access-Control-Allow-Origin: *"));
        assertTrue(raw.contains("Access-Control-Allow-Methods: GET, HEAD, OPTIONS"));
    }

    @Test
    public void headHasNoBodyAndPostIsRejected() throws Exception {
        final String head = rawRequest("HEAD /sgv.json HTTP/1.1\r\nHost: x\r\n\r\n");
        assertTrue(head.startsWith("HTTP/1.1 200"));
        assertTrue(head.endsWith("\r\n\r\n"));
        assertTrue(rawRequest("POST /sgv.json HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n").startsWith("HTTP/1.1 405"));
    }

    @Test
    public void garbageRequestsDoNotKillTheServer() throws Exception {
        rawRequest("\r\n");
        rawRequest("NONSENSE\r\n\r\n");
        try (Socket s = new Socket("127.0.0.1", server.port())) {
            // connect and close without sending anything
        }
        assertEquals(200, get("/sgv.json").getResponseCode());
        assertTrue(server.isRunning());
    }

    @Test
    public void handlerExceptionBecomes500() throws Exception {
        final HttpServer bad = new HttpServer(req -> {
            throw new IllegalStateException("boom");
        });
        assertTrue(bad.start(0, false, null));
        try {
            final HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + bad.port() + "/sgv.json").openConnection();
            assertEquals(500, c.getResponseCode());
            assertTrue(bad.isRunning());
        } finally {
            bad.stop();
        }
    }

    @Test
    public void portInUseIsReportedNotThrown() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            final HttpServer other = new HttpServer(req -> null);
            assertFalse(other.start(taken.getLocalPort(), false, null));
            assertNotNull(other.lastError());
            assertFalse(other.isRunning());
        }
    }

    @Test
    public void restartOnNewPortAndStop() throws Exception {
        final int first = server.port();
        assertTrue(server.start(0, false, null));
        assertTrue(server.isRunning());
        assertEquals(200, get("/sgv.json").getResponseCode());
        server.stop();
        assertFalse(server.isRunning());
        assertEquals(-1, server.port());
        assertTrue(first > 0);
    }

    @Test
    public void manyConcurrentClients() throws Exception {
        final Thread[] threads = new Thread[20];
        final int[] ok = new int[1];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                try {
                    if (get("/sgv.json?count=144").getResponseCode() == 200) {
                        synchronized (ok) {
                            ok[0]++;
                        }
                    }
                } catch (IOException ignored) {
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join(10_000);
        assertEquals(threads.length, ok[0]);
        assertTrue(server.requestsServed() >= threads.length);
    }

    @Test
    public void apiSecretOnlyRequiredForNonLoopbackClients() throws Exception {
        assertTrue(server.start(0, true, "s3cret"));
        final String sha1 = Hashes.sha1Hex("s3cret");
        assertEquals(40, sha1.length());

        final InetAddress lan = InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 1, 20});
        final InetAddress loop = InetAddress.getByName("127.0.0.1");
        assertTrue(server.isAuthorized(req(loop, Collections.<String, String>emptyMap())));
        assertFalse(server.isAuthorized(req(lan, Collections.<String, String>emptyMap())));
        assertFalse(server.isAuthorized(req(lan, Collections.singletonMap("api-secret", "wrong"))));
        assertTrue(server.isAuthorized(req(lan, Collections.singletonMap("api-secret", sha1.toUpperCase()))));

        // loopback over a real socket still works without the header
        assertEquals(200, get("/sgv.json").getResponseCode());

        assertTrue(server.start(0, true, ""));
        assertTrue(server.isAuthorized(req(lan, Collections.<String, String>emptyMap())));
    }

    @Test
    public void queryParsing() {
        final Map<String, String> q = HttpServer.parseQuery("count=5&a=b%20c&flag&bad=%zz&=x");
        assertEquals("5", q.get("count"));
        assertEquals("b c", q.get("a"));
        assertEquals("", q.get("flag"));
        assertFalse(q.containsKey("bad"));
        assertEquals(24, BridgeRoutes.parseCount(null));
        assertEquals(1000, BridgeRoutes.parseCount("5000"));
        assertEquals(1, BridgeRoutes.parseCount("-3"));
    }

    private static HttpServer.Request req(InetAddress remote, Map<String, String> headers) {
        return new HttpServer.Request("GET", "/sgv.json", new HashMap<String, String>(), headers, remote);
    }

    private String rawRequest(String request) throws IOException {
        try (Socket s = new Socket("127.0.0.1", server.port())) {
            s.setSoTimeout(3000);
            final OutputStream out = s.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            final InputStream in = s.getInputStream();
            final byte[] b = new byte[4096];
            int n;
            try {
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            } catch (IOException ignored) {
            }
            return buf.toString("UTF-8");
        }
    }
}
