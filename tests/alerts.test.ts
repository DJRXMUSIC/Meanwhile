import test from "node:test";
import assert from "node:assert/strict";
import { setNative } from "./env";
import {
  alertsApp,
  fromHhmm,
  hhmm,
  minutesLabel,
  newAlert,
  parseThreshold,
  readState,
  showThreshold,
  snoozeMinutes,
  summary,
  validate,
} from "../src/lib/alerts";

test("outside the app, or in an app without alerts, there's no Alerts tab data", () => {
  setNative(undefined);
  assert.equal(alertsApp(), null);
  assert.equal(readState(), null);
  setNative({ sgv: () => "[]" });
  assert.equal(alertsApp(), null);
});

test("reads the app's alert state, and survives a broken reply", () => {
  const state = { config: { units: "mgdl", settings: {}, alerts: [] }, active: null, now: 1, last: null,
    snooze_choices: [10, 15], sounds: {}, perms: { overlay: true, full_screen: true, dnd: false, notifications: true } };
  setNative({ sgv: () => "[]", alertsState: () => JSON.stringify(state) });
  assert.deepEqual(readState(), state);
  setNative({ sgv: () => "[]", alertsState: () => "{broken" });
  assert.equal(readState(), null);
  setNative(undefined);
});

test("new alerts: a low and a high, sounding on silent, at 50%", () => {
  const low = newAlert(false);
  assert.equal(low.above, false);
  assert.equal(low.threshold, 70);
  assert.equal(low.volume_percent, 50);
  assert.equal(low.override_silent_mode, true);
  assert.equal(low.uuid, "");
  const high = newAlert(true);
  assert.equal(high.above, true);
  assert.equal(high.threshold, 180);
  assert.equal(snoozeMinutes({ ...high, default_snooze: 0 }), 120);
  assert.equal(snoozeMinutes({ ...low, default_snooze: 0 }), 35);
  assert.equal(snoozeMinutes({ ...low, default_snooze: 60 }), 60);
});

test("thresholds in mg/dL or mmol/L", () => {
  assert.equal(showThreshold(90, "mgdl"), "90");
  assert.equal(showThreshold(90, "mmol"), "5.0");
  assert.equal(parseThreshold("90", "mgdl"), 90);
  assert.equal(parseThreshold("5,0", "mmol"), 90);
  assert.ok(Number.isNaN(parseThreshold("", "mgdl")));
  assert.ok(Number.isNaN(parseThreshold("x", "mgdl")));
});

test("labels, times and the list line", () => {
  assert.equal(minutesLabel(15), "15 min");
  assert.equal(minutesLabel(60), "1 h");
  assert.equal(minutesLabel(90), "1 h 30 min");
  assert.equal(hhmm(1320), "22:00");
  assert.equal(fromHhmm("07:05"), 425);
  assert.equal(summary({ ...newAlert(false), threshold: 90, volume_percent: 35 }, "mgdl"), "≤ 90 mg/dL · all day · 35%");
  assert.equal(summary({ ...newAlert(true), threshold: 155, all_day: false, start_time_minutes: 1320, end_time_minutes: 420 }, "mgdl"),
    "≥ 155 mg/dL · 22:00–07:00 · 50%");
});

test("what can't be saved", () => {
  assert.equal(validate(newAlert(false)), null);
  assert.match(validate({ ...newAlert(false), threshold: NaN }) ?? "", /Threshold/);
  assert.match(validate({ ...newAlert(false), threshold: 30 }) ?? "", /Threshold/);
  assert.match(validate({ ...newAlert(false), minutes_between: 0 }) ?? "", /Repeat/);
  assert.match(validate({ ...newAlert(false), volume_percent: 0 }) ?? "", /Volume/);
});
