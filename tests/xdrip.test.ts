import test from "node:test";
import assert from "node:assert/strict";
import { MIN, setNative, stubFetch } from "./env";
import { db } from "../src/lib/db";
import { countSince, fetchXdrip, latestBridgeBg, manualBg, nextPollDelay, syncXdripOnce } from "../src/lib/xdrip";

const T = 1_800_000_000_000;
const BASE = "http://127.0.0.1:17590";

// What the bridge serves: newest first.
function entries(n: number, newest = T, start = 100) {
  return Array.from({ length: n }, (_, i) => ({
    date: newest - i * 5 * MIN,
    sgv: start + (n - 1 - i),
    delta: i === n - 1 ? null : 1,
    direction: i === n - 1 ? "NotComputable" : "Flat",
  }));
}

test.beforeEach(async () => {
  setNative(undefined);
  await db().bg.clear();
});

test("poll timing: just after a reading is due, then every 5 s until it arrives", () => {
  assert.equal(nextPollDelay(T, T + 10_000, 60_000), 60_000);
  assert.equal(nextPollDelay(T, T + 4 * MIN + 30_000, 60_000), 32_000);
  assert.equal(nextPollDelay(T, T + 5 * MIN + 10_000, 60_000), 5_000);
  assert.equal(nextPollDelay(T, T + 7 * MIN + 10_000, 60_000), 60_000);
  assert.equal(nextPollDelay(T, T + 9 * MIN + 50_000, 60_000), 12_000);
  assert.equal(nextPollDelay(T, T + 40 * MIN, 60_000), 60_000);
  assert.equal(nextPollDelay(undefined, T, 60_000), 60_000);
  // never a zero or negative wait, whatever the clock does
  for (let s = 0; s < 20 * 60; s += 7) assert.ok(nextPollDelay(T, T + s * 1000, 60_000) >= 1000);
});

test("each poll asks only for what it doesn't have", () => {
  assert.equal(countSince(T, T + 2 * MIN), 3);
  assert.equal(countSince(T, T + 60 * MIN), 14);
  assert.equal(countSince(undefined, T), 360);
  assert.equal(countSince(T, T + 48 * 60 * MIN), 360);
});

test("inside the app, readings come straight from the bridge with no network request", async () => {
  const asked: number[] = [];
  setNative({ sgv: (count: number) => { asked.push(count); return JSON.stringify(entries(3)); } });
  const calls = stubFetch(() => new Error("no network should be used"));
  const got = await fetchXdrip(BASE, 3);
  assert.equal(got.length, 3);
  assert.equal(got[0].sgv, 102);
  assert.deepEqual(asked, [3]);
  assert.deepEqual(calls, []);
});

test("if the app can't answer, the HTTP endpoint is used instead", async () => {
  for (const sgv of [() => null, () => "", () => "not json", () => "{}", () => { throw new Error("gone"); }]) {
    setNative({ sgv });
    const calls = stubFetch((url) => (url.startsWith(BASE) ? entries(2) : undefined));
    const got = await fetchXdrip(BASE, 2);
    assert.equal(got.length, 2, String(sgv));
    assert.deepEqual(calls, [`${BASE}/sgv.json?count=2`]);
  }
});

test("in a browser: direct to the bridge, then the server proxy, then an error", async () => {
  let calls = stubFetch(() => entries(2));
  assert.equal((await fetchXdrip(BASE + "/", 2)).length, 2);
  assert.deepEqual(calls, [`${BASE}/sgv.json?count=2`]);

  calls = stubFetch((url) => (url.startsWith("/api/bg/proxy") ? entries(1) : new Error("blocked")));
  assert.equal((await fetchXdrip(BASE, 2)).length, 1);
  assert.equal(calls.length, 2);
  assert.ok(calls[1].startsWith("/api/bg/proxy?base=" + encodeURIComponent(BASE)));

  stubFetch((url) => (url.startsWith("/api") ? undefined : new Error("blocked")));
  await assert.rejects(fetchXdrip(BASE, 2), /Bridge fetch failed: 502/);
  assert.deepEqual(await fetchXdrip("", 2), []);
});

test("a sync stores new readings once, oldest first, with the change since the last reading", async () => {
  setNative({ sgv: () => JSON.stringify(entries(4)) });
  assert.equal(await syncXdripOnce(BASE, 4), 4);
  assert.equal(await syncXdripOnce(BASE, 4), 0); // nothing new: nothing added
  const rows = await db().bg.orderBy("ts").toArray();
  assert.deepEqual(rows.map((r) => r.mgdl), [100, 101, 102, 103]);
  assert.deepEqual(rows.map((r) => r.delta), [undefined, 1, 1, 1]);
  assert.equal(rows[3].trend, "Flat");
  assert.equal(rows[0].trend, "NotComputable");
  assert.ok(rows.every((r) => r.source === "xdrip"));

  // one more reading arrives
  setNative({ sgv: () => JSON.stringify(entries(4, T + 5 * MIN, 101)) });
  assert.equal(await syncXdripOnce(BASE, 4), 1);
  assert.equal(await db().bg.count(), 5);
  assert.equal((await latestBridgeBg())?.ts, T + 5 * MIN);
});

test("a reading typed in by hand doesn't hide bridge readings from before it", async () => {
  await manualBg(150); // stamped "now", later than T
  setNative({ sgv: () => JSON.stringify(entries(2)) });
  assert.equal(await syncXdripOnce(BASE, 2), 2);
  assert.equal(await db().bg.count(), 3);
  assert.equal((await latestBridgeBg())?.ts, T);
});

test("odd entries from the bridge are skipped, not stored", async () => {
  setNative({ sgv: () => JSON.stringify([{ date: T, sgv: 120.4, delta: 5.6 }, { date: 0, sgv: 99 }, { date: T - 1, sgv: 0 }, null]) });
  assert.equal(await syncXdripOnce(BASE, 5), 1);
  const [row] = await db().bg.toArray();
  assert.equal(row.mgdl, 120);
  assert.equal(row.delta, 6);
  assert.equal(row.trend, "None");
});
