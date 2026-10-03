package app.meanwhile.bridge.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tiny HTTP/1.1 server (GET/HEAD/OPTIONS, Connection: close) for the local data endpoint.
 *
 * Equivalent to the parts of xDrip+ {@code webservices/XdripWebService} the bridge needs:
 * loopback-only by default, optional LAN listening, xDrip's {@code api-secret} check for
 * non-loopback clients, and {@code Access-Control-Allow-Origin: *}. It also answers
 * CORS / Private Network Access preflights so an HTTPS-hosted PWA can call it.
 */
public final class HttpServer {

    public static final class Request {
        public final String method;
        public final String path;
        public final Map<String, String> query;
        /** Header names are lower-cased. */
        public final Map<String, String> headers;
        public final InetAddress remote;

        Request(String method, String path, Map<String, String> query, Map<String, String> headers, InetAddress remote) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.remote = remote;
        }
    }

    public static final class Response {
        public final int status;
        public final String contentType;
        public final String body;

        public Response(int status, String contentType, String body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        public static Response json(String body) {
            return new Response(200, "application/json", body);
        }

        public static Response text(int status, String body) {
            return new Response(status, "text/plain; charset=utf-8", body);
        }
    }

    public interface Handler {
        Response handle(Request request) throws Exception;
    }

    private static final int MAX_HEADER_LINES = 100;
    private static final int SOCKET_TIMEOUT_MS = 5_000;

    private final Handler handler;
    private final Object lock = new Object();
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService workers;
    private volatile int port = -1;
    private volatile boolean lan;
    private volatile String apiSecretSha1;
    private volatile String lastError;
    private final AtomicLong requestsServed = new AtomicLong();

    public HttpServer(Handler handler) {
        this.handler = handler;
    }

    /**
     * (Re)starts listening. Port 0 picks a free port (tests). Returns false and records
     * {@link #lastError()} if binding fails, e.g. because xDrip+ already has the port.
     */
    public boolean start(int port, boolean lan, String apiSecret) {
        synchronized (lock) {
            stop();
            this.lan = lan;
            this.apiSecretSha1 = apiSecret == null || apiSecret.isEmpty() ? null : Hashes.sha1Hex(apiSecret);
            try {
                final ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                final InetAddress bindAddr = lan ? null : InetAddress.getByName("127.0.0.1");
                ss.bind(new InetSocketAddress(bindAddr, port), 16);
                serverSocket = ss;
                this.port = ss.getLocalPort();
                lastError = null;
            } catch (IOException e) {
                lastError = "Cannot listen on port " + port + ": " + e.getMessage();
                this.port = -1;
                return false;
            }
            workers = new ThreadPoolExecutor(1, 4, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(32), r -> {
                final Thread t = new Thread(r, "bridge-http-worker");
                t.setDaemon(true);
                return t;
            });
            final ServerSocket ss = serverSocket;
            final ExecutorService pool = workers;
            acceptThread = new Thread(() -> acceptLoop(ss, pool), "bridge-http-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            return true;
        }
    }

    public void stop() {
        synchronized (lock) {
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                }
                serverSocket = null;
            }
            if (workers != null) {
                workers.shutdownNow();
                workers = null;
            }
            acceptThread = null;
            port = -1;
        }
    }

    public boolean isRunning() {
        synchronized (lock) {
            return serverSocket != null && !serverSocket.isClosed() && acceptThread != null && acceptThread.isAlive();
        }
    }

    public int port() {
        return port;
    }

    public boolean isLan() {
        return lan;
    }

    public String lastError() {
        return lastError;
    }

    public long requestsServed() {
        return requestsServed.get();
    }

    private void acceptLoop(ServerSocket ss, ExecutorService pool) {
        while (!ss.isClosed()) {
            final Socket s;
            try {
                s = ss.accept();
            } catch (IOException e) {
                if (!ss.isClosed()) lastError = "accept failed: " + e.getMessage();
                break;
            }
            try {
                pool.execute(() -> serve(s));
            } catch (RejectedExecutionException e) {
                closeQuietly(s);
            }
        }
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(SOCKET_TIMEOUT_MS);
            final Request req = readRequest(s);
            if (req == null) return;
            final Response response = respond(req);
            requestsServed.incrementAndGet();
            write(s, req, response);
        } catch (SocketException ignored) {
            // client went away
        } catch (Exception e) {
            try {
                write(s, null, Response.text(500, "error: " + e.getClass().getSimpleName()));
            } catch (Exception ignored) {
            }
        } finally {
            closeQuietly(s);
        }
    }

    Response respond(Request req) throws Exception {
        if (req.method.equals("OPTIONS")) {
            return new Response(204, null, "");
        }
        if (!req.method.equals("GET") && !req.method.equals("HEAD")) {
            return Response.text(405, "method not allowed");
        }
        if (!isAuthorized(req)) {
            return Response.text(403, "Forbidden: api-secret header required for non-local clients");
        }
        final Response r = handler.handle(req);
        return r != null ? r : Response.text(404, "not found");
    }

    /** xDrip+ semantics: a secret is only required from non-loopback clients. */
    boolean isAuthorized(Request req) {
        final String expected = apiSecretSha1;
        if (expected == null || req.remote == null || req.remote.isLoopbackAddress()) return true;
        final String given = req.headers.get("api-secret");
        return given != null && given.trim().equalsIgnoreCase(expected);
    }

    private static Request readRequest(Socket s) throws IOException {
        final BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
        final String line = in.readLine();
        if (line == null || line.isEmpty()) return null;
        final String[] parts = line.split(" ");
        if (parts.length < 2) return null;
        final Map<String, String> headers = new HashMap<>();
        String h;
        int count = 0;
        while ((h = in.readLine()) != null && !h.isEmpty() && count++ < MAX_HEADER_LINES) {
            final int colon = h.indexOf(':');
            if (colon > 0) headers.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
        }
        String target = parts[1];
        String query = "";
        final int q = target.indexOf('?');
        if (q >= 0) {
            query = target.substring(q + 1);
            target = target.substring(0, q);
        }
        return new Request(parts[0].toUpperCase(Locale.ROOT), target, parseQuery(query), headers, s.getInetAddress());
    }

    static Map<String, String> parseQuery(String query) {
        final Map<String, String> out = new HashMap<>();
        if (query == null || query.isEmpty()) return out;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            final int eq = pair.indexOf('=');
            try {
                final String k = URLDecoder.decode(eq >= 0 ? pair.substring(0, eq) : pair, "UTF-8");
                final String v = eq >= 0 ? URLDecoder.decode(pair.substring(eq + 1), "UTF-8") : "";
                out.put(k, v);
            } catch (IllegalArgumentException | java.io.UnsupportedEncodingException ignored) {
                // skip malformed parameter
            }
        }
        return out;
    }

    private static void write(Socket s, Request req, Response r) throws IOException {
        final byte[] body = r.body == null ? new byte[0] : r.body.getBytes(StandardCharsets.UTF_8);
        final StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n");
        if (r.contentType != null) h.append("Content-Type: ").append(r.contentType).append("\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Cache-Control: no-store\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n");
        h.append("Access-Control-Allow-Headers: api-secret, content-type\r\n");
        // Chrome Private Network Access / Local Network Access preflight
        h.append("Access-Control-Allow-Private-Network: true\r\n");
        h.append("Access-Control-Max-Age: 600\r\n");
        h.append("Connection: close\r\n\r\n");
        final OutputStream out = s.getOutputStream();
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (req == null || !req.method.equals("HEAD")) out.write(body);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            default: return "Error";
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
