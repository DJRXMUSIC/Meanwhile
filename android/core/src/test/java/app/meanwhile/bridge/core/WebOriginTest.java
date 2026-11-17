package app.meanwhile.bridge.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WebOriginTest {

    @Test
    public void normalizeAcceptsWhatPeoplePaste() {
        assertEquals("https://my-site.netlify.app/", WebOrigin.normalize("https://my-site.netlify.app"));
        assertEquals("https://my-site.netlify.app/", WebOrigin.normalize("  my-site.netlify.app  "));
        assertEquals("https://my-site.netlify.app/", WebOrigin.normalize("HTTPS://My-Site.Netlify.App/"));
        assertEquals("https://example.com/app/", WebOrigin.normalize("example.com/app/"));
        assertEquals("https://example.com:8443/", WebOrigin.normalize("https://example.com:8443"));
        assertEquals("https://example.com/?a=1", WebOrigin.normalize("https://example.com/?a=1#frag"));
    }

    @Test
    public void plainHttpOnlyForThisPhone() {
        assertEquals("http://localhost:3000/", WebOrigin.normalize("http://localhost:3000"));
        assertEquals("http://127.0.0.1:3000/", WebOrigin.normalize("http://127.0.0.1:3000/"));
        assertNull(WebOrigin.normalize("http://my-site.netlify.app"));
        assertNull(WebOrigin.normalize("http://192.168.1.20:3000"));
    }

    @Test
    public void normalizeRejectsJunk() {
        assertNull(WebOrigin.normalize(null));
        assertNull(WebOrigin.normalize(""));
        assertNull(WebOrigin.normalize("   "));
        assertNull(WebOrigin.normalize("my site"));
        assertNull(WebOrigin.normalize("meanwhile"));
        assertNull(WebOrigin.normalize("javascript:alert(1)"));
        assertNull(WebOrigin.normalize("file:///sdcard/x.html"));
        assertNull(WebOrigin.normalize("ftp://example.com"));
        assertNull(WebOrigin.normalize("https://user:pw@example.com"));
        assertNull(WebOrigin.normalize("https://"));
    }

    @Test
    public void sameOriginComparesSchemeHostAndPort() {
        final String base = "https://my-site.netlify.app/";
        assertTrue(WebOrigin.sameOrigin(base, "https://my-site.netlify.app/settings"));
        assertTrue(WebOrigin.sameOrigin(base, "https://MY-SITE.netlify.app:443/profile?x=1"));
        assertFalse(WebOrigin.sameOrigin(base, "http://my-site.netlify.app/"));
        assertFalse(WebOrigin.sameOrigin(base, "https://my-site.netlify.app.evil.example/"));
        assertFalse(WebOrigin.sameOrigin(base, "https://evil.example/?https://my-site.netlify.app/"));
        assertFalse(WebOrigin.sameOrigin(base, "https://my-site.netlify.app:8443/"));
        assertFalse(WebOrigin.sameOrigin(base, "about:blank"));
        assertFalse(WebOrigin.sameOrigin(base, "data:text/html,hi"));
        assertFalse(WebOrigin.sameOrigin(base, null));
        assertFalse(WebOrigin.sameOrigin(null, base));
        assertTrue(WebOrigin.sameOrigin("http://localhost:3000/", "http://localhost:3000/settings"));
        assertFalse(WebOrigin.sameOrigin("http://localhost:3000/", "http://localhost:17590/sgv.json"));
    }

    @Test
    public void loopback() {
        assertTrue(WebOrigin.isLoopbackUrl("http://localhost:3000/"));
        assertTrue(WebOrigin.isLoopbackUrl("http://127.0.0.1:3000/"));
        assertFalse(WebOrigin.isLoopbackUrl("https://localhost.evil.example/"));
        assertFalse(WebOrigin.isLoopbackUrl("https://my-site.netlify.app/"));
        assertFalse(WebOrigin.isLoopbackUrl("nonsense"));
    }
}
