import test from "node:test";
import assert from "node:assert/strict";
import { MIN, setNative } from "./env";
import { bridgeUrl, db, getProfile, profileNeedsMigration } from "../src/lib/db";
import { dropDuplicateBridgeReadings, exportAll, importAll } from "../src/lib/sync";
import { syncXdripOnce } from "../src/lib/xdrip";
import type { BgReading, Profile } from "../src/lib/types";

const T = 1_800_000_000_000;
const BASE = "http://127.0.0.1:17590";

// The bridge's history: one reading every 5 minutes up to `newest`, newest first.
function bridge(newest: number) {
  return {
    sgv: (count: number) =>
      JSON.stringify(Array.from({ length: count }, (_, i) => {
        const date = newest - i * 5 * MIN;
        return { date, sgv: 100 + (Math.floor(date / (5 * MIN)) % 80), delta: 1, direction: "Flat" };
      })),
  };
}

async function freshDatabase() {
  await db().delete();
  await db().open();
}

async function duplicateBridgeTimes(): Promise<number> {
  const seen = new Set<number>();
  let dups = 0;
  await db().bg.each((r) => {
    if (r.source !== "xdrip") return;
    if (seen.has(r.ts)) dups++;
    else seen.add(r.ts);
  });
  return dups;
}

// The old Chrome app's data: `bgCount` bridge readings ending at T, a few
// doses, a hand-typed BG, and a profile saved by the previous version.
async function oldAppBackup(bgCount: number, url: string): Promise<{ file: File; profile: Profile }> {
  await freshDatabase();
  const profile = { ...(await getProfile()), isf: 55, target_bg: 105, xdrip_url: url, schema_version: 4, sync_key: "oldkey0123456789" };
  await db().profile.put(profile);
  setNative(bridge(T));
  // filled the way the app fills it, a day at a time
  for (let n = bgCount; n > 0; n -= 288) {
    setNative(bridge(T - (n - Math.min(n, 288)) * 5 * MIN));
    await syncXdripOnce(BASE, Math.min(n, 288));
  }
  assert.equal(await db().bg.count(), bgCount);
  await db().bg.add({ ts: T - 3 * MIN, mgdl: 142, trend: "None", source: "manual" } as BgReading);
  await db().insulin.bulkAdd([
    { ts: T - 200 * MIN, units: 4, kind: "bolus" },
    { ts: T - 90 * MIN, units: 20, kind: "basal" },
    { ts: T - 20 * MIN, units: 2, kind: "bolus", note: "pizza 🍕" },
  ]);
  const blob = await exportAll();
  return { file: new File([await blob.text()], "meanwhile-2026-11-16.json", { type: "application/json" }), profile };
}

for (const bgCount of [2000, 40]) {
  test(`moving to the new app: restore a backup of ${bgCount} readings over one that has already fetched`, async () => {
    const { file } = await oldAppBackup(bgCount, "http://127.0.0.1:17580");
    const before = JSON.parse(await file.text()) as { bg: BgReading[]; insulin: unknown[] };
    assert.equal(before.bg.length, bgCount + 1);

    // --- the new app, first open: its own profile, and 30 h fetched from the bridge,
    //     including two readings newer than the backup
    await freshDatabase();
    const seeded = await getProfile();
    assert.notEqual(seeded.sync_key, "oldkey0123456789");
    setNative(bridge(T + 10 * MIN));
    assert.equal(await syncXdripOnce(BASE, 360), 360);

    // --- restore the file
    const applied = await importAll(file);
    assert.ok(applied >= bgCount + 5);

    // every reading and dose from the backup is there, exactly once
    const bg = await db().bg.toArray();
    for (const r of before.bg) {
      assert.equal(bg.filter((x) => x.ts === r.ts && x.mgdl === r.mgdl && x.source === r.source).length, 1,
        `backup reading at ${r.ts} (${r.source})`);
    }
    assert.equal(await duplicateBridgeTimes(), 0);
    assert.equal(bg.filter((r) => r.source === "manual").length, 1);
    const insulin = await db().insulin.orderBy("ts").toArray();
    assert.deepEqual(insulin.map((d) => d.units), [4, 20, 2]);
    assert.equal(insulin[2].note, "pizza 🍕");

    // nothing in the last 30 h is missing: one bridge reading per 5-minute slot
    const times = new Set(bg.filter((r) => r.source === "xdrip").map((r) => r.ts));
    const fetchedOrBackedUp = Math.max(bgCount, 358);
    for (let i = -2; i < fetchedOrBackedUp; i++) assert.ok(times.has(T - i * 5 * MIN), `reading ${i * 5} min before the backup`);
    assert.equal(times.size, fetchedOrBackedUp + 2);

    // the profile is the old one (ratios, sync key), brought up to date on first use
    const raw = await db().profile.get("current");
    assert.equal(raw!.isf, 55);
    assert.equal(raw!.sync_key, "oldkey0123456789");
    assert.equal(profileNeedsMigration(raw!), true);
    const p = await getProfile();
    assert.equal(p.isf, 55);
    assert.equal(p.target_bg, 105);
    assert.equal(p.xdrip_url, "http://127.0.0.1:17590");
    assert.equal(profileNeedsMigration(p), false);

    // carrying on: the next fetch adds only what is new, and restoring twice changes nothing
    setNative(bridge(T + 20 * MIN));
    // (the two readings fetched after the backup was made survived the restore)
    assert.equal(await syncXdripOnce(BASE, 10), 2);
    assert.equal(await duplicateBridgeTimes(), 0);
    const count = await db().bg.count();
    await importAll(file);
    assert.equal(await db().bg.count(), count);
    assert.equal(await duplicateBridgeTimes(), 0);
    assert.equal(await dropDuplicateBridgeReadings(), 0);
  });
}

test("a dose logged in the new app before restoring is kept, not replaced", async () => {
  const { file } = await oldAppBackup(40, BASE);
  await freshDatabase();
  await getProfile();
  // same row id (1) as the backup's first dose, but a different dose
  await db().insulin.add({ ts: T + 2 * MIN, units: 3, kind: "bolus", note: "logged in the new app" });
  await importAll(file);
  const insulin = await db().insulin.orderBy("ts").toArray();
  assert.deepEqual(insulin.map((d) => d.units), [4, 20, 2, 3]);
  assert.equal(insulin[3].note, "logged in the new app");
  await importAll(file); // and a second restore doesn't double anything
  assert.equal(await db().insulin.count(), 4);
});

test("a broken backup file changes nothing", async () => {
  await freshDatabase();
  await getProfile();
  setNative(bridge(T));
  await syncXdripOnce(BASE, 20);
  const bad = new File(['{"bg":[{"id":1,"ts":5,"mgdl":99,"source":"xdrip"}],"insulin":[{"id":{"not":"a key"},"ts":1}]}'], "bad.json");
  await assert.rejects(importAll(bad));
  const bg = await db().bg.orderBy("ts").toArray();
  assert.equal(bg.length, 20);
  assert.ok(bg.every((r) => r.ts > 5), "the half-applied reading was rolled back");
  await assert.rejects(importAll(new File(["not json at all"], "bad.json")));
  assert.equal(await db().bg.count(), 20);
});

test("the saved bridge address moves off xDrip+'s port, and nothing else", async () => {
  assert.equal(bridgeUrl("http://127.0.0.1:17580"), "http://127.0.0.1:17590");
  assert.equal(bridgeUrl("http://127.0.0.1:17580/"), "http://127.0.0.1:17590/");
  assert.equal(bridgeUrl("http://192.168.1.5:17580"), "http://192.168.1.5:17590");
  assert.equal(bridgeUrl("http://127.0.0.1:175801"), "http://127.0.0.1:175801");
  assert.equal(bridgeUrl("http://127.0.0.1:17590"), "http://127.0.0.1:17590");
  assert.equal(bridgeUrl("https://example.com/bg"), "https://example.com/bg");
  assert.equal(bridgeUrl(""), "");
  assert.equal(bridgeUrl(undefined), "http://127.0.0.1:17590");
});

test("an existing install is brought up to date without losing its settings", async () => {
  await freshDatabase();
  const seeded = await getProfile();
  await db().profile.put({ ...seeded, isf: 62, basal_u_per_hr: 0.9, xdrip_url: "http://127.0.0.1:17580", schema_version: 4 });
  const p = await getProfile();
  assert.equal(p.schema_version, 5);
  assert.equal(p.xdrip_url, "http://127.0.0.1:17590");
  assert.equal(p.isf, 62);
  assert.equal(p.basal_u_per_hr, 0.9);
  assert.equal(p.sync_key, seeded.sync_key);
  // and it sticks
  assert.equal((await db().profile.get("current"))!.xdrip_url, "http://127.0.0.1:17590");
  assert.equal(profileNeedsMigration(p), false);
});

test("duplicate removal only touches exact repeats of bridge readings", async () => {
  await freshDatabase();
  await db().bg.bulkAdd([
    { ts: T, mgdl: 120, trend: "Flat", source: "xdrip" },
    { ts: T, mgdl: 120, trend: "Flat", source: "xdrip" },   // exact repeat: removed
    { ts: T, mgdl: 121, trend: "Flat", source: "xdrip" },   // same time, different value: kept
    { ts: T, mgdl: 120, trend: "None", source: "manual" },  // typed by hand: kept
    { ts: T, mgdl: 120, trend: "None", source: "manual" },  // kept
    { ts: T + 5 * MIN, mgdl: 120, trend: "Flat", source: "xdrip" },
  ] as BgReading[]);
  assert.equal(await dropDuplicateBridgeReadings(), 1);
  assert.equal(await db().bg.count(), 5);
});
