package app.meanwhile.bridge.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Meanwhile's pages, packaged inside the app (assets/web), and the address they are
 * served from. Android reserves {@link #HOST} for exactly this, so it is never a real site:
 * nothing can be fetched from the network in its place.
 */
public final class AppPages {

    public static final String HOST = "appassets.androidplatform.net";
    public static final String ORIGIN = "https://" + HOST;
    public static final String HOME = ORIGIN + "/";
    /** Folder in the app's assets that holds the pages. */
    public static final String ROOT = "web";

    private AppPages() {
    }

    /** True if {@code url} is one of the app's own pages. */
    public static boolean isAppUrl(String url) {
        return WebOrigin.sameOrigin(HOME, url);
    }

    /**
     * The asset files that could hold {@code url}, best first; empty if it isn't an app
     * page or tries to leave the pages folder. Pages are folders with an index.html
     * ({@code /settings/} → {@code web/settings/index.html}); a path without a trailing
     * slash or extension is tried as that folder, then as an .html file.
     */
    public static List<String> candidates(String url) {
        if (!isAppUrl(url)) return Collections.emptyList();
        final String path;
        try {
            path = new URI(url).getPath(); // decoded; the query and fragment are dropped
        } catch (URISyntaxException e) {
            return Collections.emptyList();
        }
        if (path == null || path.isEmpty() || path.equals("/")) return Collections.singletonList(ROOT + "/index.html");
        if (!path.startsWith("/") || path.contains("\\") || path.indexOf('\0') >= 0) return Collections.emptyList();
        for (String seg : path.substring(1).split("/", -1)) {
            if (seg.equals("..") || seg.equals(".")) return Collections.emptyList();
        }
        final String p = ROOT + path;
        if (p.endsWith("/")) return Collections.singletonList(p + "index.html");
        final List<String> out = new ArrayList<>();
        out.add(p);
        if (extension(p).isEmpty()) {
            out.add(p + "/index.html");
            out.add(p + ".html");
        }
        return out;
    }

    /** The page served when nothing matches. */
    public static final String NOT_FOUND = ROOT + "/404.html";

    public static String mimeType(String assetPath) {
        switch (extension(assetPath)) {
            case "html": return "text/html";
            case "js": return "text/javascript";
            case "css": return "text/css";
            case "json": return "application/json";
            case "txt": return "text/plain";
            case "svg": return "image/svg+xml";
            case "png": return "image/png";
            case "ico": return "image/x-icon";
            case "woff2": return "font/woff2";
            case "woff": return "font/woff";
            case "webmanifest": return "application/manifest+json";
            default: return "application/octet-stream";
        }
    }

    /** UTF-8 for text types, null for binary ones. */
    public static String encoding(String mimeType) {
        return mimeType.startsWith("text/") || mimeType.endsWith("json") || mimeType.endsWith("+xml")
                || mimeType.equals("application/manifest+json") ? "utf-8" : null;
    }

    private static String extension(String path) {
        final int slash = path.lastIndexOf('/');
        final int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
