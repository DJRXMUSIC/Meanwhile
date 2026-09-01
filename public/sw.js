// Lightweight PWA service worker — app-shell + runtime caching.
//
// VERSION comes from the ?v= parameter this script is registered with (see
// ServiceWorkerRegister), which carries the build id. Cache names are
// derived from it, so a deploy activates a new worker and the activate
// handler below drops every cache belonging to the previous build. With a
// hardcoded version the runtime cache outlived deploys and the cache-first
// branch went on serving stale assets indefinitely.
const VERSION = new URL(self.location.href).searchParams.get("v") || "dev";
const SHELL = `meanwhile-shell-${VERSION}`;
const RUNTIME = `meanwhile-runtime-${VERSION}`;
const SHELL_URLS = [
  "/",
  "/profile",
  "/settings",
  "/manifest.webmanifest",
  "/icon-192.svg",
  "/icon-512.svg",
];

self.addEventListener("install", (event) => {
  // Deliberately no skipWaiting() here: a new worker taking over underneath
  // a running page can leave it fetching assets the old build doesn't have.
  // The page prompts instead, and posts SKIP_WAITING when the user accepts.
  event.waitUntil(caches.open(SHELL).then((c) => c.addAll(SHELL_URLS)));
});

self.addEventListener("message", (event) => {
  if (event.data === "SKIP_WAITING") self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => ![SHELL, RUNTIME].includes(k)).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);

  // Never cache AI calls or live BG fetches.
  if (url.pathname.startsWith("/api/ai") || url.pathname.startsWith("/api/bg")) return;

  // Network-first for HTML navigations, cache fallback for offline.
  if (req.mode === "navigate") {
    event.respondWith(
      fetch(req).then((res) => {
        const copy = res.clone();
        caches.open(RUNTIME).then((c) => c.put(req, copy));
        return res;
      }).catch(() => caches.match(req).then((m) => m || caches.match("/")))
    );
    return;
  }

  // Cache-first for static assets.
  event.respondWith(
    caches.match(req).then((cached) =>
      cached ||
      fetch(req).then((res) => {
        if (res.ok && (url.origin === self.location.origin)) {
          const copy = res.clone();
          caches.open(RUNTIME).then((c) => c.put(req, copy));
        }
        return res;
      }).catch(() => cached)
    )
  );
});
