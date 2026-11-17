package app.meanwhile.bridge.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class AppPagesTest {

    private static final String H = AppPages.HOME;

    @Test
    public void pagesMapToTheirIndexFiles() {
        assertEquals(Collections.singletonList("web/index.html"), AppPages.candidates(H));
        assertEquals(Collections.singletonList("web/index.html"), AppPages.candidates(AppPages.ORIGIN));
        assertEquals(Collections.singletonList("web/settings/index.html"), AppPages.candidates(H + "settings/"));
        assertEquals(Arrays.asList("web/settings", "web/settings/index.html", "web/settings.html"),
                AppPages.candidates(H + "settings"));
        // the data a page fetches when navigating, with its cache-busting query
        assertEquals(Collections.singletonList("web/settings/index.txt"), AppPages.candidates(H + "settings/index.txt?_rsc=abc"));
        assertEquals(Collections.singletonList("web/_next/static/chunks/main-1a2b.js"),
                AppPages.candidates(H + "_next/static/chunks/main-1a2b.js"));
        assertEquals(Collections.singletonList("web/index.html"), AppPages.candidates(H + "#top"));
        assertEquals(Collections.singletonList("web/a b.css"), AppPages.candidates(H + "a%20b.css"));
    }

    @Test
    public void nothingOutsideThePagesFolder() {
        assertEquals(Collections.emptyList(), AppPages.candidates(H + "../secret"));
        assertEquals(Collections.emptyList(), AppPages.candidates(H + "_next/../../x.js"));
        assertEquals(Collections.emptyList(), AppPages.candidates(H + "%2e%2e/x"));
        assertEquals(Collections.emptyList(), AppPages.candidates(H + "./x"));
        assertEquals(Collections.emptyList(), AppPages.candidates(H + "a%5Cb"));
        assertEquals(Collections.emptyList(), AppPages.candidates(H + "a%00b"));
    }

    @Test
    public void onlyTheAppAddress() {
        assertEquals(Collections.emptyList(), AppPages.candidates("https://example.org/"));
        assertEquals(Collections.emptyList(), AppPages.candidates("http://" + AppPages.HOST + "/"));
        assertEquals(Collections.emptyList(), AppPages.candidates("https://" + AppPages.HOST + ".evil.example/"));
        assertTrue(AppPages.isAppUrl(H + "profile/"));
        assertFalse(AppPages.isAppUrl("https://my-site.netlify.app/"));
        assertFalse(AppPages.isAppUrl(null));
    }

    @Test
    public void types() {
        assertEquals("text/html", AppPages.mimeType("web/index.html"));
        assertEquals("text/javascript", AppPages.mimeType("web/_next/x.js"));
        assertEquals("text/css", AppPages.mimeType("web/_next/x.CSS"));
        assertEquals("image/svg+xml", AppPages.mimeType("web/icon-192.svg"));
        assertEquals("text/plain", AppPages.mimeType("web/index.txt"));
        assertEquals("application/octet-stream", AppPages.mimeType("web/BUILD"));
        assertEquals("utf-8", AppPages.encoding("text/html"));
        assertEquals("utf-8", AppPages.encoding("image/svg+xml"));
        assertEquals("utf-8", AppPages.encoding("application/json"));
        assertNull(AppPages.encoding("image/png"));
        assertNull(AppPages.encoding("font/woff2"));
    }
}
