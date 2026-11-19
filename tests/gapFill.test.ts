import test from "node:test";
import assert from "node:assert/strict";
import { captureStats, dedupeReadings, fillGaps, MAX_FILL_MS, SLOT_MS, withEstimates } from "../src/lib/gapFill";

const M = 60_000;
const r = (min: number, mgdl: number) => ({ ts: min * M, mgdl, trend: "Flat", source: "xdrip" }) as never;

test("no gaps, nothing estimated", () => {
  const rs = [0, 5, 10, 15].map((m) => r(m, 100));
  assert.deepEqual(fillGaps(rs), []);
});

test("one missed reading: one estimate, between its neighbours", () => {
  const e = fillGaps([r(0, 100), r(5, 104), r(15, 112), r(20, 116)]);
  assert.equal(e.length, 1);
  assert.equal(e[0].ts, 10 * M);
  assert.ok(e[0].mgdl > 104 && e[0].mgdl < 112, String(e[0].mgdl));
});

test("a 40-minute gap on a steady rise follows the rise", () => {
  const rs = [r(0, 100), r(5, 105), r(10, 110), r(50, 150), r(55, 155)];
  const e = fillGaps(rs);
  assert.equal(e.length, 7);
  assert.deepEqual(e.map((x) => x.ts), [15, 20, 25, 30, 35, 40, 45].map((m) => m * M));
  e.forEach((x, i) => assert.ok(Math.abs(x.mgdl - (115 + 5 * i)) <= 1, `${x.mgdl} at ${i}`));
});

test("a gap at a turn bends with the trend at each end, within plausible limits", () => {
  // rising into the gap, falling out of it: the estimate peaks inside
  const rs = [r(0, 120), r(10, 140), r(20, 160), r(50, 160), r(60, 140), r(70, 120)];
  const e = fillGaps(rs);
  const peak = Math.max(...e.map((x) => x.mgdl));
  assert.ok(peak > 160 && peak < 200, String(peak));
});

test("gaps longer than the limit stay empty", () => {
  const e = fillGaps([r(0, 100), r(0 + MAX_FILL_MS / M + 5, 150)]);
  assert.deepEqual(e, []);
});

test("caught / estimated / missing over a window", () => {
  // 1 hour = 12 slots; readings at 5..60 except 20, 25 (filled) and a > 2 h gap before
  const rs = [r(-200, 90), ...[5, 10, 15, 30, 35, 40, 45, 50, 55, 60].map((m) => r(m, 100))];
  const est = fillGaps(rs);
  const c = captureStats(rs, est, 60 * M, 60 * M);
  assert.deepEqual(c, { expected: 12, caught: 10, estimated: 2, missing: 0 });
  // the window reaching back into the unfilled gap: those slots are missing
  const c2 = captureStats(rs, est, 90 * M, 60 * M);
  assert.equal(c2.expected, 18);
  assert.equal(c2.caught, 10);
  assert.equal(c2.estimated, 2);
  assert.equal(c2.missing, 6);
});

test("stats see estimates as readings, marked as estimated", () => {
  const all = withEstimates([r(0, 100), r(15, 110)], fillGaps([r(0, 100), r(15, 110)]));
  assert.equal(all.length, 4);
  assert.equal(all.filter((x) => x.estimated).length, 2);
  assert.equal(SLOT_MS, 5 * M);
});

test("a reading stored twice counts once: a gap can't hide behind duplicates", () => {
  // an hour (12 readings due): 9 real ones, each stored twice (a restore, an old sync, a
  // corrected value seconds later), and a 20-minute hole
  const rs = [5, 10, 15, 20, 45, 50, 55, 60, 35].flatMap((m) =>
    [r(m, 100), { ts: m * M + 20_000, mgdl: 101, trend: "Flat", source: "xdrip" } as never]);
  const once = dedupeReadings(rs);
  assert.equal(once.length, 9);
  const est = fillGaps(once);
  const c = captureStats(rs, est, 60 * M, 60 * M);
  assert.equal(c.caught, 9);
  assert.ok(c.estimated + c.missing === 3, JSON.stringify(c));
  // fingersticks don't count as caught sensor readings
  const finger = { ts: 25 * M, mgdl: 150, trend: "Flat", source: "manual" } as never;
  assert.equal(captureStats([...rs, finger], est, 60 * M, 60 * M).caught, 9);
  assert.equal(dedupeReadings([...rs, finger]).length, 10);
});
