import test from "node:test";
import assert from "node:assert/strict";
import { bgAt, buildOverlay, DAY_MS, LOOKAHEAD_MS, nearest, overlayBoluses } from "../src/lib/chartOverlay";

const H = 3600_000;
const now = 100 * DAY_MS;

test("lookahead is 190 minutes", () => {
  assert.equal(LOOKAHEAD_MS, 190 * 60_000);
});

test("yesterday's trace carries on past now, into the lookahead", () => {
  const readings = [
    { ts: now - DAY_MS - 4 * H, mgdl: 100 }, // before the window
    { ts: now - DAY_MS - H, mgdl: 110 },
    { ts: now - DAY_MS + 2 * H, mgdl: 150 },  // 2 h after now, yesterday
    { ts: now - DAY_MS + 4 * H, mgdl: 200 },  // past the lookahead
  ] as never[];
  const pts = buildOverlay(readings, now - 3 * H, now + LOOKAHEAD_MS, DAY_MS);
  assert.deepEqual(pts, [{ ts: now - H, mgdl: 110 }, { ts: now + 2 * H, mgdl: 150 }]);
});

test("boluses from 24 h and 48 h back, shifted onto today; basal left out", () => {
  const doses = [
    { ts: now - DAY_MS + H, units: 4, kind: "bolus" },
    { ts: now - DAY_MS + H, units: 10, kind: "basal" },
    { ts: now - 2 * DAY_MS - H, units: 3, kind: "bolus" },
    { ts: now - DAY_MS + 5 * H, units: 2, kind: "bolus" },
  ] as never[];
  const d24 = overlayBoluses(doses, now - 3 * H, now + LOOKAHEAD_MS, DAY_MS);
  assert.deepEqual(d24.map((d) => [d.ts, d.units]), [[now + H, 4]]);
  const d48 = overlayBoluses(doses, now - 3 * H, now + LOOKAHEAD_MS, 2 * DAY_MS);
  assert.deepEqual(d48.map((d) => [d.ts, d.units]), [[now - H, 3]]);
});

test("BG at a time on a trace, and the nearest point", () => {
  const pts = [{ ts: 0, mgdl: 100 }, { ts: 10, mgdl: 200 }];
  assert.equal(bgAt(5, pts), 150);
  assert.equal(bgAt(-5, pts), 100);
  assert.equal(bgAt(20, pts), 200);
  assert.equal(bgAt(5, []), null);
  assert.deepEqual(nearest(pts, 7), { ts: 10, mgdl: 200 });
  assert.equal(nearest([], 7), null);
});
