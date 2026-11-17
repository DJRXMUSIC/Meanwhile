package app.meanwhile.bridge.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * The web address of the Meanwhile site the app shows, and the rule for what counts as
 * "that site": same scheme, host and port. Only pages on it get the readings and the other
 * native functions; links anywhere else open in the browser.
 */
public final class WebOrigin {

    private WebOrigin() {
    }

    /**
     * Cleans up what someone typed or pasted. Returns the address to load, or null if it
     * isn't usable. A missing scheme becomes https://. Plain http is accepted only for this
     * phone itself (localhost / 127.0.0.1), where nothing leaves the device.
     */
    public static String normalize(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty() || s.contains(" ")) return null;
        if (!s.contains("://")) s = "https://" + s;
        final URI u;
        try {
            u = new URI(s);
        } catch (URISyntaxException e) {
            return null;
        }
        final String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        final String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty() || u.getUserInfo() != null) return null;
        final boolean loopback = isLoopback(host);
        if (!scheme.equals("https") && !(scheme.equals("http") && loopback)) return null;
        if (!loopback && !host.contains(".")) return null;
        final String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        return scheme + "://" + host + (u.getPort() >= 0 ? ":" + u.getPort() : "") + path
                + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
    }

    /** True if {@code url} is on the same scheme, host and port as {@code base}. */
    public static boolean sameOrigin(String base, String url) {
        final String a = origin(base);
        return a != null && a.equals(origin(url));
    }

    /** True for an http(s) address on this phone itself. */
    public static boolean isLoopbackUrl(String url) {
        try {
            final String host = new URI(url).getHost();
            return host != null && isLoopback(host.toLowerCase(Locale.ROOT));
        } catch (URISyntaxException | NullPointerException e) {
            return false;
        }
    }

    private static boolean isLoopback(String host) {
        return host.equals("localhost") || host.equals("127.0.0.1");
    }

    /** "scheme://host:port" with the default port filled in, or null if not http(s). */
    static String origin(String url) {
        if (url == null) return null;
        try {
            final URI u = new URI(url.trim());
            if (u.getScheme() == null || u.getHost() == null) return null;
            final String scheme = u.getScheme().toLowerCase(Locale.ROOT);
            final int def = scheme.equals("https") ? 443 : scheme.equals("http") ? 80 : -1;
            if (def < 0) return null;
            return scheme + "://" + u.getHost().toLowerCase(Locale.ROOT) + ":" + (u.getPort() >= 0 ? u.getPort() : def);
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
