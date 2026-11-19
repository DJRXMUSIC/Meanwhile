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

test("the snoozed alert shown on Now: only while its snooze lasts", async () => {
  const { activeSnooze } = await import("../src/lib/alerts");
  const rule = { ...newAlert(false), uuid: "a", name: "LOW WARNING" };
  const base = { config: { units: "mgdl", settings: {} as never, alerts: [rule] }, now: 1_000_000, last: null,
    snooze_choices: [], sounds: {}, perms: { overlay: true, full_screen: true, dnd: true, notifications: true } };
  assert.equal(activeSnooze(null), null);
  assert.equal(activeSnooze({ ...base, active: null }), null);
  assert.equal(activeSnooze({ ...base, active: { uuid: "a", snoozed: false, next_alert_at: 2_000_000 } }), null);
  assert.equal(activeSnooze({ ...base, active: { uuid: "a", snoozed: true, next_alert_at: 900_000 } }), null);
  assert.equal(activeSnooze({ ...base, active: { uuid: "gone", snoozed: true, next_alert_at: 2_000_000 } }), null);
  assert.deepEqual(activeSnooze({ ...base, active: { uuid: "a", snoozed: true, next_alert_at: 2_000_000 } }),
    { name: "LOW WARNING", until: 2_000_000 });
});

test("back to Now: two minutes after a snooze with no touch since, once", async () => {
  const { shouldReturnToNow, BACK_TO_NOW_MS } = await import("../src/lib/alerts");
  const snoozed = 10_000_000;
  assert.equal(BACK_TO_NOW_MS, 120_000);
  assert.equal(shouldReturnToNow(0, 0, snoozed + 999_999, 0), false); // no snooze
  assert.equal(shouldReturnToNow(snoozed, 0, snoozed + 119_000, 0), false); // not yet
  assert.equal(shouldReturnToNow(snoozed, 0, snoozed + 120_000, 0), true);
  // a touch after the snooze restarts the two minutes
  assert.equal(shouldReturnToNow(snoozed, snoozed + 60_000, snoozed + 150_000, 0), false);
  assert.equal(shouldReturnToNow(snoozed, snoozed + 60_000, snoozed + 180_000, 0), true);
  // a touch before the snooze doesn't count
  assert.equal(shouldReturnToNow(snoozed, snoozed - 5_000, snoozed + 120_000, 0), true);
  // once per snooze
  assert.equal(shouldReturnToNow(snoozed, 0, snoozed + 500_000, snoozed), false);
});

test("snooze lists: 30-minute steps to 5 hours, then off until turned on", async () => {
  const { SNOOZE_STEPS, snoozeStepLabel, OFF_UNTIL_TURNED_ON } = await import("../src/lib/alerts");
  assert.deepEqual(SNOOZE_STEPS.slice(0, -1), [30, 60, 90, 120, 150, 180, 210, 240, 270, 300]);
  assert.equal(SNOOZE_STEPS[SNOOZE_STEPS.length - 1], OFF_UNTIL_TURNED_ON);
  assert.equal(snoozeStepLabel(150), "2 h 30 min");
  assert.equal(snoozeStepLabel(OFF_UNTIL_TURNED_ON), "Off until I turn it on");
});

test("the Now pill: what's snoozed or off, in a few words", async () => {
  const { quietSummary, alertsOff, clock } = await import("../src/lib/alerts");
  const now = 1_000_000_000;
  const rule = { ...newAlert(false), uuid: "a", name: "LOW WARNING" };
  const settings = { alerts_disabled_until: 0, low_alerts_disabled_until: 0, high_alerts_disabled_until: 0 };
  const base = { config: { units: "mgdl", settings: settings as never, alerts: [rule] }, active: null, now, last: null,
    snooze_choices: [], sounds: {}, perms: { overlay: true, full_screen: true, dnd: true, notifications: true } };
  assert.equal(quietSummary(base), null);
  assert.equal(quietSummary(null), null);
  const lowOff = { ...base, config: { ...base.config, settings: { ...settings, low_alerts_disabled_until: now + 3_600_000 } as never } };
  assert.equal(quietSummary(lowOff), `Low off ${clock(now + 3_600_000)}`);
  // off until turned on: no time
  const forever = { ...base, config: { ...base.config, settings: { ...settings, high_alerts_disabled_until: 4_102_444_800_000 } as never } };
  assert.equal(quietSummary(forever), "High off");
  assert.deepEqual(alertsOff(forever).map((o) => o.which), ["high"]);
  // a snooze and something off: the snooze first, "+1" for the rest
  const both = { ...lowOff, active: { uuid: "a", snoozed: true, next_alert_at: now + 600_000 } };
  assert.equal(quietSummary(both), `LOW WARNING ${clock(now + 600_000)} +1`);
  // ended: nothing
  const ended = { ...base, config: { ...base.config, settings: { ...settings, low_alerts_disabled_until: now - 1 } as never } };
  assert.equal(quietSummary(ended), null);
});
