// The build that goes inside the EverBridge app (npm run build:app), where there
// is no server of its own: AI advice goes to the Meanwhile server whose
// address was entered in the app; the cloud backup goes to a folder (Drive).
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

test("cloud backup: the day's file, with the readings and the alert setup, sent to the app in pieces", async () => {
  await dbm.db().delete();
  await dbm.db().open();
  await dbm.getProfile();
  await dbm.db().insulin.add({ ts: 1_800_000_000_000, units: 3, kind: "bolus" });
  const drive = require("../src/lib/driveBackup") as typeof import("../src/lib/driveBackup");
  let written = "";
  let name = "";
  const state = { folder: "content://drive/tree/x", folder_name: "Meanwhile backups", ok_at: 0, bytes: 0, file: "",
    attempt_at: 0, error: "" };
  const config = { units: "mgdl", settings: {}, alerts: [{ uuid: "a", name: "LOW WARNING" }] };
  setNative({
    sgv: () => "[]",
    alertsState: () => JSON.stringify({ config, active: null, now: 1, last: null, snooze_choices: [], sounds: {},
      perms: {} }),
    backupState: () => JSON.stringify(state),
    saveBegin: (n: string) => { name = n; written = ""; return "id1"; },
    saveChunk: (id: string, t: string) => { assert.equal(id, "id1"); written += t; return true; },
    backupEnd: (id: string) => {
      assert.equal(id, "id1");
      Object.assign(state, { ok_at: 5, attempt_at: 5, bytes: written.length, file: name });
      return JSON.stringify(state);
    },
  });
  const s = await drive.backupNow();
  assert.match(name, /^meanwhile-\d{4}-\d{2}-\d{2}\.json$/);
  const file = JSON.parse(written);
  assert.equal(file.insulin.length, 1);
  assert.equal(file.alerts.alerts[0].name, "LOW WARNING");
  assert.equal(s!.file, name);
  assert.equal(drive.backupFailing(s), false);
});

test("cloud backup: when it's due, and when it's failing", () => {
  const drive = require("../src/lib/driveBackup") as typeof import("../src/lib/driveBackup");
  const H = 3600_000;
  const base = { folder: "x", folder_name: "", ok_at: 0, bytes: 0, file: "", attempt_at: 0, error: "" };
  const now = 1_000 * H;
  assert.equal(drive.backupDue(null, now), false);
  assert.equal(drive.backupDue({ ...base, folder: "" }, now), false); // no folder: nothing to do
  assert.equal(drive.backupDue(base, now), true); // never backed up
  assert.equal(drive.backupDue({ ...base, ok_at: now - 11 * H }, now), false);
  assert.equal(drive.backupDue({ ...base, ok_at: now - 13 * H }, now), true);
  // failed 10 minutes ago: wait; 40 minutes ago: try again
  assert.equal(drive.backupDue({ ...base, error: "x", attempt_at: now - 10 * 60_000 }, now), false);
  assert.equal(drive.backupDue({ ...base, error: "x", attempt_at: now - 40 * 60_000 }, now), true);
  assert.equal(drive.backupFailing({ ...base, error: "x", attempt_at: 5, ok_at: 4 }), true);
  assert.equal(drive.backupFailing({ ...base, error: "", attempt_at: 5, ok_at: 5 }), false);
  assert.equal(drive.backupName(new Date(2026, 0, 5, 23, 59).getTime()), "meanwhile-2026-01-05.json");
});

test("in the app, readings never go through a server", async () => {
  // the bridge read fails and there is no server: an error, not a request elsewhere
  setNative({ sgv: () => null, apiBase: () => "https://my-site.netlify.app" });
  const calls = stubFetch((url) => (url.startsWith("http://127.0.0.1:17590") ? [{ date: 1, sgv: 100 }] : new Error("no")));
  assert.equal((await xdrip.fetchXdrip("http://127.0.0.1:17590", 3)).length, 1);
  assert.deepEqual(calls, ["http://127.0.0.1:17590/sgv.json?count=3"]);
});
