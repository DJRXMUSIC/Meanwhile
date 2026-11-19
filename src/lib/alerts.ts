// The Alerts tab's link to the app: the alerts live and sound in the app (so they work with
// Meanwhile closed); the tab reads and changes them through window.EverBridgeNative.

import { everBridge } from "./native";

export const MGDL_PER_MMOL = 18.0182;

// One alert, in the app's stored field names (threshold always mg/dL).
export interface AlertRule {
  uuid: string;
  name: string;
  active: boolean;
  above: boolean;
  threshold: number;
  all_day: boolean;
  start_time_minutes: number;
  end_time_minutes: number;
  minutes_between: number;
  default_snooze: number;
  vibrate: boolean;
  override_silent_mode: boolean;
  force_speaker: boolean;
  mp3_file?: string;
  volume_percent: number;
}

export interface AlertOptions {
  bg_alert_profile: string;
  smart_snoozing: boolean;
  smart_alerting: boolean;
  disable_alerts_stale_data: boolean;
  disable_alerts_stale_data_minutes: number;
  start_snoozed: boolean;
  delay_ascending_3min: boolean;
  ascending_volume_to_medium: boolean;
  no_alarms_during_calls: boolean;
  alerts_disabled_until: number;
  low_alerts_disabled_until: number;
  high_alerts_disabled_until: number;
}

export interface AlertsState {
  config: { units: string; settings: AlertOptions; alerts: AlertRule[] };
  active: { uuid: string; snoozed: boolean; next_alert_at: number } | null;
  now: number;
  /** When Snooze was last pressed (0 if never). */
  snoozed_at?: number;
  last: { mgdl: number; ts: number } | null;
  snooze_choices: number[];
  sounds: Record<string, { name: string; playable: boolean }>;
  perms: { overlay: boolean; full_screen: boolean; dnd: boolean; notifications: boolean };
}

export const SOUND_BEEP = "android.resource://app.meanwhile.bridge/raw/alert_beep";
export const SOUND_VIBRATION = "android.resource://app.meanwhile.bridge/raw/alert_vibration";

export const PROFILES: { id: string; label: string }[] = [
  { id: "High", label: "High" },
  { id: "medium", label: "Medium" },
  { id: "ascending", label: "Ascending" },
  { id: "vibrate only", label: "Vibrate only" },
  { id: "Silent", label: "Silent" },
];

export interface AlertsNative {
  alertsState?(): string | null;
  alertsSaveRule?(json: string): string | null;
  alertsRemoveRule?(uuid: string): void;
  alertsSaveSettings?(json: string): string | null;
  alertsSnooze?(minutes: number): void;
  alertsDisable?(which: string, minutes: number): void;
  alertsUnsnooze?(): void;
  alertsSetActive?(uuid: string, on: boolean): void;
  alertsPreSnooze?(uuid: string, minutes: number): void;
  alertsTest?(json: string): string | null;
  alertsStopTest?(): void;
  alertsSoundInfo?(uri: string): string | null;
  alertsPickSound?(kind: string, current: string): void;
  alertsOpenPermission?(which: string): void;
}

// The app's alert functions, or null outside the app (or in an app without them).
export function alertsApp(): AlertsNative | null {
  const n = everBridge() as unknown as AlertsNative | null;
  return n && typeof n.alertsState === "function" ? n : null;
}

export function readState(): AlertsState | null {
  const app = alertsApp();
  if (!app) return null;
  try {
    const t = app.alertsState?.();
    return t ? (JSON.parse(t) as AlertsState) : null;
  } catch {
    return null;
  }
}

export function newAlert(above: boolean): AlertRule {
  return {
    uuid: "",
    name: above ? "High alert" : "Low alert",
    active: true,
    above,
    threshold: above ? 180 : 70,
    all_day: true,
    start_time_minutes: 0,
    end_time_minutes: 0,
    minutes_between: above ? 3 : 1,
    default_snooze: above ? 120 : 35,
    vibrate: true,
    override_silent_mode: true,
    force_speaker: true,
    mp3_file: above ? SOUND_VIBRATION : SOUND_BEEP,
    volume_percent: 50,
  };
}

// The snooze an alert gives: its own, else xDrip+'s 35 (low) / 120 (high).
export function snoozeMinutes(r: AlertRule): number {
  return r.default_snooze > 0 ? r.default_snooze : r.above ? 120 : 35;
}

export function minutesLabel(m: number): string {
  if (m < 60) return `${m} min`;
  if (m % 60 === 0) return `${m / 60} h`;
  return `${Math.floor(m / 60)} h ${m % 60} min`;
}

export function showThreshold(mgdl: number, units: string): string {
  return units === "mmol" ? (mgdl / MGDL_PER_MMOL).toFixed(1) : String(Math.round(mgdl));
}

// What was typed, in mg/dL; NaN if it isn't a number.
export function parseThreshold(text: string, units: string): number {
  const v = Number(text.trim().replace(",", "."));
  if (!text.trim() || !Number.isFinite(v)) return NaN;
  return units === "mmol" ? Math.round(v * MGDL_PER_MMOL) : v;
}

export function unitLabel(units: string): string {
  return units === "mmol" ? "mmol/L" : "mg/dL";
}

export function hhmm(minutes: number): string {
  const h = Math.floor(minutes / 60) % 24;
  const m = minutes % 60;
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
}

export function fromHhmm(t: string): number {
  const [h, m] = t.split(":").map(Number);
  return Number.isFinite(h) && Number.isFinite(m) ? h * 60 + m : 0;
}

export function clock(ms: number): string {
  const d = new Date(ms);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

// One line for the list: "≤ 90 mg/dL · all day · 50%".
export function summary(r: AlertRule, units: string): string {
  const when = r.all_day ? "all day" : `${hhmm(r.start_time_minutes)}–${hhmm(r.end_time_minutes)}`;
  return `${r.above ? "≥" : "≤"} ${showThreshold(r.threshold, units)} ${unitLabel(units)} · ${when} · ${r.volume_percent}%`;
}

// What the editor would save, or what's wrong with it.
export function validate(r: AlertRule): string | null {
  if (!Number.isFinite(r.threshold) || r.threshold < 40 || r.threshold > 400) return "Threshold must be 40–400 mg/dL";
  if (!Number.isInteger(r.minutes_between) || r.minutes_between < 1 || r.minutes_between > 120) return "Repeat must be 1–120 min";
  if (r.volume_percent < 1 || r.volume_percent > 100) return "Volume must be 1–100%";
  return null;
}

// ---- Changes from the app ------------------------------------------------

declare global {
  interface Window {
    __alertsChanged?: () => void;
    __alertsSoundPicked?: (uri: string | null) => void;
  }
}

export const ALERTS_CHANGED = "meanwhile:alerts";
// Sent to the Now page when it should show the present (back to Now after a snooze).
export const SHOW_NOW = "meanwhile:now";

// The app calls window.__alertsChanged() after a change; everyone listening hears it.
export function onAlertsChanged(cb: () => void): () => void {
  if (typeof window === "undefined") return () => {};
  window.__alertsChanged = () => window.dispatchEvent(new Event(ALERTS_CHANGED));
  window.addEventListener(ALERTS_CHANGED, cb);
  return () => window.removeEventListener(ALERTS_CHANGED, cb);
}

// The snoozed alert to show on Now, or null when none is snoozed now.
export function activeSnooze(st: AlertsState | null): { name: string; until: number } | null {
  if (!st?.active || !st.active.snoozed || st.active.next_alert_at <= st.now) return null;
  const rule = st.config.alerts.find((r) => r.uuid === st.active!.uuid);
  return rule ? { name: rule.name, until: st.active.next_alert_at } : null;
}

export const BACK_TO_NOW_MS = 2 * 60_000;

// Two minutes after a snooze with no touch since: go back to Now (once per snooze).
export function shouldReturnToNow(
  snoozedAt: number,
  lastInteraction: number,
  now: number,
  handledSnoozeAt: number,
): boolean {
  if (!snoozedAt || snoozedAt === handledSnoozeAt) return false;
  return now - Math.max(snoozedAt, lastInteraction) >= BACK_TO_NOW_MS;
}

// ---- snoozing and turning off, from the Alerts tab and the Now pill

/** "Off until I turn it on", in the snooze lists. */
export const OFF_UNTIL_TURNED_ON = -1;
/** The snooze lists: 30-minute steps up to 5 hours, then off until turned on. */
export const SNOOZE_STEPS = [30, 60, 90, 120, 150, 180, 210, 240, 270, 300, OFF_UNTIL_TURNED_ON];

export function snoozeStepLabel(m: number): string {
  return m === OFF_UNTIL_TURNED_ON ? "Off until I turn it on" : minutesLabel(m);
}

/** Off with no end ("off until I turn it on" is stored as a date far ahead). */
export function offForever(until: number, now: number): boolean {
  return until - now > 365 * 24 * 3600_000;
}

export type OffWhich = "low" | "high" | "all";

/** Which groups of alerts are turned off now, and until when. */
export function alertsOff(st: AlertsState | null): { which: OffWhich; until: number }[] {
  if (!st) return [];
  const s = st.config.settings;
  return ([["low", s.low_alerts_disabled_until], ["high", s.high_alerts_disabled_until],
    ["all", s.alerts_disabled_until]] as const)
    .filter(([, until]) => until > st.now)
    .map(([which, until]) => ({ which, until }));
}

const OFF_NAMES: Record<OffWhich, string> = { low: "Low", high: "High", all: "Alerts" };

/** What the Now pill says: the snooze and what's off, first one in words, "+N" for the rest. */
export function quietSummary(st: AlertsState | null): string | null {
  const parts: string[] = [];
  const sn = activeSnooze(st);
  if (sn) parts.push(`${sn.name} ${clock(sn.until)}`);
  for (const o of alertsOff(st)) {
    parts.push(`${OFF_NAMES[o.which]} off${offForever(o.until, st!.now) ? "" : ` ${clock(o.until)}`}`);
  }
  if (!parts.length) return null;
  return parts[0] + (parts.length > 1 ? ` +${parts.length - 1}` : "");
}
