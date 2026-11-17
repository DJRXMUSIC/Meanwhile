import test from "node:test";
import assert from "node:assert/strict";
import { formatCountdown, nextReading } from "../src/lib/nextReading";
import { MIN } from "./env";

const T = 1_800_000_000_000;

test("counts down to five minutes after the last reading", () => {
  assert.deepEqual(nextReading(T, T), { due: false, at: T + 5 * MIN });
  const now = T + 78_500;
  assert.equal(formatCountdown(nextReading(T, now).at - now), "3:42");
  assert.equal(formatCountdown(nextReading(T, T + 5 * MIN - 1).at - (T + 5 * MIN - 1)), "0:01");
});

test("says due for two minutes once the reading is expected", () => {
  assert.deepEqual(nextReading(T, T + 5 * MIN), { due: true, at: T + 5 * MIN });
  assert.equal(nextReading(T, T + 6 * MIN + 59_000).due, true);
});

test("a missed reading moves on to the next five-minute slot", () => {
  assert.deepEqual(nextReading(T, T + 7 * MIN), { due: false, at: T + 10 * MIN });
  assert.deepEqual(nextReading(T, T + 10 * MIN + 1000), { due: true, at: T + 10 * MIN });
  // hours later it is still on the same beat
  assert.deepEqual(nextReading(T, T + 63 * MIN), { due: false, at: T + 65 * MIN });
});

test("formats minutes and seconds, rounding up, never negative", () => {
  assert.equal(formatCountdown(5 * MIN), "5:00");
  assert.equal(formatCountdown(61_000), "1:01");
  assert.equal(formatCountdown(999), "0:01");
  assert.equal(formatCountdown(0), "0:00");
  assert.equal(formatCountdown(-5000), "0:00");
});
