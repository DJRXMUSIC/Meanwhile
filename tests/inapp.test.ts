// The build that goes inside the EverBridge app (npm run build:app), where there
// is no server of its own: AI advice and cloud backup go to the Meanwhile
// server whose address was entered in the app.
process.env.NEXT_PUBLIC_EVERBRIDGE_APP = "1";

import test from "node:test";
import assert from "node:assert/strict";
import { setNative, stubFetch } from "./env";

// required after the variable is set (it is read when the module loads)
// eslint-disable-next-line @typescript-eslint/no-require-imports
const native = require("../src/lib/native") as typeof import("../src/lib/native");
// eslint-disable-next-line @typescript-eslint/no-require-imports
const sync = require("../src/lib/sync") as typeof import("../src/lib/sync");
// eslint-disable-next-line @typescript-eslint/no-require-imports
const dbm = require("../src/lib/db") as typeof import("../src/lib/db");
// eslint-disable-next-line @typescript-eslint/no-require-imports
const xdrip = require("../src/lib/xdrip") as typeof import("../src/lib/xdrip");

test("this is the app build", () => {
  assert.equal(native.IN_APP_BUILD, true);
});

test("server requests go to the address saved in the app, and nowhere without one", () => {
  setNative({ sgv: () => "[]", apiBase: () => "https://my-site.netlify.app" });
  assert.equal(native.apiUrl("/api/sync"), "https://my-site.netlify.app/api/sync");
  setNative({ sgv: () => "[]", apiBase: () => "https://my-site.netlify.app/" });
  assert.equal(native.apiUrl("/api/ai/decide"), "https://my-site.netlify.app/api/ai/decide");
  setNative({ sgv: () => "[]", apiBase: () => "" });
  assert.equal(native.apiUrl("/api/sync"), null);
  setNative({ sgv: () => "[]" }); // an app without the setting
  assert.equal(native.apiUrl("/api/sync"), null);
  setNative({ sgv: () => "[]", apiBase: () => { throw new Error("x"); } });
  assert.equal(native.apiUrl("/api/sync"), null);
  setNative(undefined);
  assert.equal(native.apiUrl("/api/sync"), null);
});

test("cloud backup from the app sends your data to your server and takes back what's new", async () => {
  await dbm.db().delete();
  await dbm.db().open();
  const profile = await dbm.getProfile();
  await dbm.db().insulin.add({ ts: 1_800_000_000_000, units: 3, kind: "bolus" });
  setNative({ sgv: () => "[]", apiBase: () => "https://my-site.netlify.app" });

  let sent: { url: string; body: { sync_key: string; push: { insulin: unknown[] } } } | null = null;
  (globalThis as unknown as { fetch: unknown }).fetch = async (url: string, init: { body: string }) => {
    sent = { url, body: JSON.parse(init.body) };
    return {
      ok: true,
      status: 200,
      json: async () => ({
        pushed: 2, pulled: 1, backend: "blobs", server_ts: 1_800_000_100_000,
        pull: { insulin: [{ id: 99, ts: 1_799_999_000_000, units: 20, kind: "basal" }] },
      }),
      text: async () => "",
    };
  };
  const r = await sync.syncOnce(profile.sync_key!);
  assert.equal(sent!.url, "https://my-site.netlify.app/api/sync");
  assert.equal(sent!.body.sync_key, profile.sync_key);
  assert.equal(sent!.body.push.insulin.length, 1);
  assert.equal(r.backend, "blobs");
  assert.equal(await dbm.db().insulin.count(), 2); // the pulled basal dose arrived
});

test("without a server address, sync says what to do instead of failing silently", async () => {
  setNative({ sgv: () => "[]", apiBase: () => "" });
  const calls = stubFetch(() => ({}));
  await assert.rejects(sync.syncOnce("abcdefgh12345678"), new RegExp("server address in Settings"));
  assert.deepEqual(calls, []);
});

test("in the app, readings never go through a server", async () => {
  // the bridge read fails and there is no server: an error, not a request elsewhere
  setNative({ sgv: () => null, apiBase: () => "https://my-site.netlify.app" });
  const calls = stubFetch((url) => (url.startsWith("http://127.0.0.1:17590") ? [{ date: 1, sgv: 100 }] : new Error("no")));
  assert.equal((await xdrip.fetchXdrip("http://127.0.0.1:17590", 3)).length, 1);
  assert.deepEqual(calls, ["http://127.0.0.1:17590/sgv.json?count=3"]);
});
