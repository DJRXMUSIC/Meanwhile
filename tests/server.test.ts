// The Meanwhile server's sync route, as the app reaches it: from the app's own
// origin, across sites. It must answer the app, and no other site.
import "./env";
import test from "node:test";
import assert from "node:assert/strict";
import { APP_ORIGIN } from "../src/lib/cors";
import * as route from "../src/app/api/sync/route";

function post(origin: string | null, body: unknown) {
  const headers: Record<string, string> = { "content-type": "application/json" };
  if (origin) headers.origin = origin;
  return new Request("https://my-site.netlify.app/api/sync", { method: "POST", headers, body: JSON.stringify(body) });
}

test("the app's backup is accepted and comes back on the next sync", async () => {
  const key = "app-test-key-0001";
  const pushed = await route.POST(post(APP_ORIGIN, {
    sync_key: key, since: 0,
    push: { insulin: [{ id: 1, ts: 1_800_000_000_000, units: 3, kind: "bolus" }], bg: [{ id: 7, ts: 1_800_000_000_000, mgdl: 131, source: "xdrip" }] },
  }));
  assert.equal(pushed.status, 200);
  assert.equal(pushed.headers.get("access-control-allow-origin"), APP_ORIGIN);
  const a = await pushed.json();
  assert.equal(a.pushed, 2);

  // another device (or the app after a reinstall) pulls everything back
  const pulled = await route.POST(post(APP_ORIGIN, { sync_key: key, since: 0, push: {} }));
  const b = await pulled.json();
  assert.equal(b.pull.insulin[0].units, 3);
  assert.equal(b.pull.bg[0].mgdl, 131);
});

test("the browser's check before a cross-site request is answered for the app only", async () => {
  const ok = route.OPTIONS(new Request("https://my-site.netlify.app/api/sync", {
    method: "OPTIONS", headers: { origin: APP_ORIGIN, "access-control-request-method": "POST" },
  }));
  assert.equal(ok.status, 204);
  assert.equal(ok.headers.get("access-control-allow-origin"), APP_ORIGIN);
  assert.match(ok.headers.get("access-control-allow-methods")!, /POST/);
  assert.match(ok.headers.get("access-control-allow-headers")!, /content-type/);

  const other = route.OPTIONS(new Request("https://my-site.netlify.app/api/sync", {
    method: "OPTIONS", headers: { origin: "https://evil.example" },
  }));
  assert.equal(other.headers.get("access-control-allow-origin"), null);
});

test("other sites get no permission to read the answer; the website itself is unchanged", async () => {
  const evil = await route.POST(post("https://evil.example", { sync_key: "app-test-key-0002", since: 0, push: {} }));
  assert.equal(evil.headers.get("access-control-allow-origin"), null);
  const sameSite = await route.POST(post(null, { sync_key: "app-test-key-0002", since: 0, push: {} }));
  assert.equal(sameSite.status, 200);
  assert.equal(sameSite.headers.get("access-control-allow-origin"), null);
  const bad = await route.POST(post(APP_ORIGIN, { sync_key: "x", since: 0, push: {} }));
  assert.equal(bad.status, 400);
  assert.equal(bad.headers.get("access-control-allow-origin"), APP_ORIGIN); // so the app can show the error
});
