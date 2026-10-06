package app.meanwhile.bridge.core;

import java.util.List;

/** URL routing for the local endpoint. */
public final class BridgeRoutes implements HttpServer.Handler {

    /** xDrip+ WebServiceSgv default and limits. */
    public static final int DEFAULT_COUNT = 24;
    public static final int MAX_COUNT = 1000;

    public interface Status {
        /** Health/diagnostic JSON for {@code /status.json}. */
        String statusJson();

        /** "mgdl" or "mmol". */
        String unitsHint();
    }

    private final ReadingStore store;
    private final Status status;

    public BridgeRoutes(ReadingStore store, Status status) {
        this.store = store;
        this.status = status;
    }

    @Override
    public HttpServer.Response handle(HttpServer.Request req) {
        switch (req.path) {
            case "/sgv.json":
            case "/api/v1/entries/sgv.json":
            case "/api/v1/entries.json":
            case "/api/v1/entries":
                return HttpServer.Response.json(sgv(parseCount(req.query.get("count"))));
            case "/status.json":
                return HttpServer.Response.json(status.statusJson());
            case "/":
                return HttpServer.Response.text(200, "Meanwhile Eversense bridge: GET /sgv.json?count=N or /status.json\n");
            default:
                return null;
        }
    }

    public String sgv(int count) {
        final List<Reading> rows = store.latest(count + 1);
        return SgvJson.entries(rows, count, status.unitsHint());
    }

    static int parseCount(String raw) {
        if (raw == null) return DEFAULT_COUNT;
        try {
            return Math.max(1, Math.min(MAX_COUNT, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return DEFAULT_COUNT;
        }
    }
}
