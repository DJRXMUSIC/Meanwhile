"use client";

import { useCallback, useEffect, useState } from "react";
import {
  alertsApp,
  clock,
  fromHhmm,
  hhmm,
  minutesLabel,
  newAlert,
  parseThreshold,
  PROFILES,
  readState,
  showThreshold,
  snoozeMinutes,
  SOUND_BEEP,
  SOUND_VIBRATION,
  summary,
  unitLabel,
  validate,
  type AlertOptions,
  type AlertRule,
  type AlertsState,
} from "@/lib/alerts";

declare global {
  interface Window {
    __alertsChanged?: () => void;
    __alertsSoundPicked?: (uri: string | null) => void;
  }
}

const btn = "rounded-xl bg-surface2 px-3 py-2 text-sm";
const btnAccent = "rounded-xl bg-accent text-white px-3 py-2 text-sm font-medium";
const field = "w-full rounded-xl bg-surface2 px-3 py-2 outline-none ring-1 ring-white/5 focus:ring-accent/60 text-sm";

export default function AlertsPage() {
  const [st, setSt] = useState<AlertsState | null>(null);
  const [ready, setReady] = useState(false);
  const [edit, setEdit] = useState<{ rule: AlertRule; thresholdText: string } | null>(null);
  const [toneName, setToneName] = useState("");
  const [msg, setMsg] = useState("");
  const [testing, setTesting] = useState(false);

  const load = useCallback(() => setSt(readState()), []);

  useEffect(() => {
    load();
    setReady(true);
    window.__alertsChanged = load;
    const t = setInterval(load, 5000);
    return () => {
      clearInterval(t);
      window.__alertsChanged = undefined;
      window.__alertsSoundPicked = undefined;
    };
  }, [load]);

  if (!ready) return null;
  const app = alertsApp();
  if (!app || !st) return <div className="p-6 text-muted">Alerts are in the Meanwhile v5 app.</div>;

  const units = st.config.units;
  const opts = st.config.settings;
  const rules = st.config.alerts;
  const activeRule = st.active ? rules.find((r) => r.uuid === st.active!.uuid) ?? null : null;
  const snoozedNow = !!st.active && st.active.snoozed && st.active.next_alert_at > st.now;

  const openEditor = (r: AlertRule) => {
    setMsg("");
    setEdit({ rule: { ...r }, thresholdText: showThreshold(r.threshold, units) });
    setToneName(r.uuid ? st.sounds[r.uuid]?.name ?? "" : soundName(r.mp3_file));
  };
  const setRule = (patch: Partial<AlertRule>) => edit && setEdit({ ...edit, rule: { ...edit.rule, ...patch } });

  const saveOptions = (patch: Partial<AlertOptions>) => {
    const err = app.alertsSaveSettings?.(JSON.stringify({ ...opts, ...patch }));
    if (err) setMsg(err);
  };

  const pickTone = (choice: string) => {
    if (!edit) return;
    if (choice === "beep") { setRule({ mp3_file: SOUND_BEEP }); setToneName("Beep"); return; }
    if (choice === "vibration") { setRule({ mp3_file: SOUND_VIBRATION }); setToneName("Vibration sound"); return; }
    if (choice === "alarm") { setRule({ mp3_file: undefined }); setToneName("Phone alarm"); return; }
    window.__alertsSoundPicked = (uri) => {
      window.__alertsSoundPicked = undefined;
      if (!uri) return;
      setEdit((e) => (e ? { ...e, rule: { ...e.rule, mp3_file: uri } } : e));
      try {
        const info = JSON.parse(app.alertsSoundInfo?.(uri) ?? "{}") as { name?: string };
        setToneName(info.name ?? "Sound");
      } catch {
        setToneName("Sound");
      }
    };
    app.alertsPickSound?.(choice, edit.rule.mp3_file ?? "");
  };

  const editorRule = (): AlertRule | null => {
    if (!edit) return null;
    return { ...edit.rule, threshold: parseThreshold(edit.thresholdText, units) };
  };

  const save = () => {
    const r = editorRule();
    if (!r) return;
    const err = validate(r) ?? app.alertsSaveRule?.(JSON.stringify(r)) ?? null;
    if (err) { setMsg(err); return; }
    setEdit(null);
    setMsg("");
  };

  const test = () => {
    const r = editorRule();
    if (!r) return;
    const err = validate(r) ?? app.alertsTest?.(JSON.stringify(r)) ?? null;
    if (err) { setMsg(err); return; }
    setTesting(true);
  };
  const stopTest = () => { app.alertsStopTest?.(); setTesting(false); };

  const choices = st.snooze_choices;

  return (
    <div className="px-3 py-3 space-y-4 pb-24">
      <h1 className="text-xl font-semibold">Alerts</h1>

      {activeRule && !snoozedNow && (
        <section className={`rounded-2xl p-4 space-y-3 ${activeRule.above ? "bg-amber-600/25" : "bg-red-600/30"}`}>
          <div className="flex items-baseline justify-between gap-2">
            <span className="text-lg font-semibold">{activeRule.name}</span>
            {st.last && <span className="num text-2xl font-semibold">{showThreshold(st.last.mgdl, units)}</span>}
          </div>
          <button onClick={() => app.alertsSnooze?.(0)} className="w-full rounded-xl bg-white text-black py-4 text-xl font-bold">
            Snooze {minutesLabel(snoozeMinutes(activeRule))}
          </button>
          <select className={field} value="" onChange={(e) => e.target.value && app.alertsSnooze?.(Number(e.target.value))}>
            <option value="">Snooze for…</option>
            {choices.map((m) => <option key={m} value={m}>{minutesLabel(m)}</option>)}
          </select>
        </section>
      )}
      {activeRule && snoozedNow && (
        <section className="rounded-2xl bg-surface p-4 ring-1 ring-white/5 text-sm">
          {activeRule.name} snoozed until {clock(st.active!.next_alert_at)}
        </section>
      )}

      {(!st.perms.overlay || !st.perms.full_screen || !st.perms.dnd || !st.perms.notifications) && (
        <Section title="Allow">
          {([["overlay", "Alarm screen while using the phone", st.perms.overlay],
             ["full_screen", "Alarm screen on the lock screen", st.perms.full_screen],
             ["dnd", "Through Do Not Disturb", st.perms.dnd],
             ["notifications", "Notifications", st.perms.notifications]] as const)
            .filter(([, , ok]) => !ok)
            .map(([which, label]) => (
              <div key={which} className="flex items-center justify-between gap-2">
                <span className="text-sm">{label}</span>
                <button className={btn} onClick={() => app.alertsOpenPermission?.(which)}>Allow</button>
              </div>
            ))}
        </Section>
      )}

      {edit ? (
        <Section title={edit.rule.uuid ? `Edit ${edit.rule.above ? "high" : "low"} alert` : `New ${edit.rule.above ? "high" : "low"} alert`}>
          <Label text="Name">
            <input className={field} value={edit.rule.name} onChange={(e) => setRule({ name: e.target.value })} />
          </Label>
          <Label text={`${edit.rule.above ? "At or above" : "At or below"} (${unitLabel(units)})`}>
            <input className={`num ${field}`} inputMode="decimal" value={edit.thresholdText}
              onChange={(e) => setEdit({ ...edit, thresholdText: e.target.value })} />
          </Label>
          <Label text={`Volume ${edit.rule.volume_percent}%`}>
            <input type="range" min={1} max={100} step={1} value={edit.rule.volume_percent} className="w-full accent-accent"
              onChange={(e) => setRule({ volume_percent: Number(e.target.value) })} />
            <div className="grid grid-cols-4 gap-1.5 mt-1">
              {[-5, -1, 1, 5].map((d) => (
                <button key={d} className={btn}
                  onClick={() => setRule({ volume_percent: Math.max(1, Math.min(100, edit.rule.volume_percent + d)) })}>
                  {d > 0 ? `+${d}` : `−${-d}`}%
                </button>
              ))}
            </div>
          </Label>
          <Label text={`Tone: ${toneName || "Phone alarm"}`}>
            <select className={field} value="" onChange={(e) => e.target.value && pickTone(e.target.value)}>
              <option value="">Change…</option>
              <option value="phone">Phone sounds</option>
              <option value="beep">Beep</option>
              <option value="vibration">Vibration sound</option>
              <option value="file">Sound file</option>
              <option value="alarm">Phone alarm</option>
            </select>
          </Label>
          <div className="grid grid-cols-2 gap-2">
            <Label text="Snooze">
              <select className={field} value={snoozeMinutes(edit.rule)}
                onChange={(e) => setRule({ default_snooze: Number(e.target.value) })}>
                {Array.from(new Set([...choices, snoozeMinutes(edit.rule)])).sort((a, b) => a - b)
                  .map((m) => <option key={m} value={m}>{minutesLabel(m)}</option>)}
              </select>
            </Label>
            <Label text="Repeat every (min)">
              <input className={`num ${field}`} inputMode="numeric" value={String(edit.rule.minutes_between)}
                onChange={(e) => setRule({ minutes_between: Math.round(Number(e.target.value) || 0) })} />
            </Label>
          </div>
          <Toggle label="All day" on={edit.rule.all_day} set={(v) => setRule({ all_day: v })} />
          {!edit.rule.all_day && (
            <div className="grid grid-cols-2 gap-2">
              <Label text="From">
                <input type="time" className={field} value={hhmm(edit.rule.start_time_minutes)}
                  onChange={(e) => setRule({ start_time_minutes: fromHhmm(e.target.value) })} />
              </Label>
              <Label text="To">
                <input type="time" className={field} value={hhmm(edit.rule.end_time_minutes)}
                  onChange={(e) => setRule({ end_time_minutes: fromHhmm(e.target.value) })} />
              </Label>
            </div>
          )}
          <Toggle label="Sound on silent and Do Not Disturb" on={edit.rule.override_silent_mode}
            set={(v) => setRule({ override_silent_mode: v })} />
          <Toggle label="Alarm volume (speaker)" on={edit.rule.force_speaker} set={(v) => setRule({ force_speaker: v })} />
          <Toggle label="Vibrate" on={edit.rule.vibrate} set={(v) => setRule({ vibrate: v })} />
          <Toggle label="On" on={edit.rule.active} set={(v) => setRule({ active: v })} />
          {msg && <p className="text-sm text-warn" role="status">{msg}</p>}
          <div className="grid grid-cols-2 gap-2">
            <button className={btnAccent} onClick={save}>Save</button>
            <button className={btn} onClick={() => { stopTest(); setEdit(null); setMsg(""); }}>Cancel</button>
            {testing
              ? <button className={btn} onClick={stopTest}>Stop</button>
              : <button className={btn} onClick={test}>Test</button>}
            {edit.rule.uuid && (
              <button className={btn} onClick={() => {
                if (confirm(`Delete ${edit.rule.name}?`)) { app.alertsRemoveRule?.(edit.rule.uuid); setEdit(null); }
              }}>Delete</button>
            )}
          </div>
          {edit.rule.uuid && (
            <select className={field} value="" onChange={(e) => e.target.value && app.alertsPreSnooze?.(edit.rule.uuid, Number(e.target.value))}>
              <option value="">Snooze before it fires…</option>
              {choices.map((m) => <option key={m} value={m}>{minutesLabel(m)}</option>)}
            </select>
          )}
        </Section>
      ) : (
        <Section title="Alerts">
          <div className="grid grid-cols-2 gap-2">
            <button className={btnAccent} onClick={() => openEditor(newAlert(false))}>+ Low alert</button>
            <button className={btnAccent} onClick={() => openEditor(newAlert(true))}>+ High alert</button>
          </div>
          {[false, true].map((above) => (
            <div key={String(above)} className="space-y-1.5">
              <div className="text-[11px] uppercase tracking-wider text-muted mt-2">{above ? "High" : "Low"}</div>
              {rules.filter((r) => r.above === above).map((r) => (
                <button key={r.uuid} onClick={() => openEditor(r)}
                  className={`w-full text-left rounded-xl px-3 py-2.5 bg-surface2/60 hover:bg-surface2 ${r.active ? "" : "opacity-50"}`}>
                  <div className="text-sm font-medium">{r.name}{r.active ? "" : " (off)"}</div>
                  <div className="num text-[12px] text-muted">{summary(r, units)} · {st.sounds[r.uuid]?.name ?? ""}</div>
                </button>
              ))}
            </div>
          ))}
        </Section>
      )}

      <Section title="Turn off">
        {([["low", "Low", opts.low_alerts_disabled_until], ["high", "High", opts.high_alerts_disabled_until],
           ["all", "All", opts.alerts_disabled_until]] as const).map(([which, label, until]) => (
          <div key={which} className="flex items-center justify-between gap-2">
            <span className="text-sm">{label}{until > st.now ? ` · off until ${clock(until)}` : ""}</span>
            {until > st.now ? (
              <button className={btn} onClick={() => app.alertsDisable?.(which, 0)}>Turn on</button>
            ) : (
              <select className="rounded-xl bg-surface2 px-3 py-2 text-sm" value=""
                onChange={(e) => e.target.value && app.alertsDisable?.(which, Number(e.target.value))}>
                <option value="">Off for…</option>
                {choices.map((m) => <option key={m} value={m}>{minutesLabel(m)}</option>)}
              </select>
            )}
          </div>
        ))}
      </Section>

      <Section title="All alerts">
        <div className="grid grid-cols-5 gap-1">
          {PROFILES.map((p) => (
            <button key={p.id} onClick={() => saveOptions({ bg_alert_profile: p.id })}
              className={`rounded-lg px-1 py-2 text-[12px] ${opts.bg_alert_profile === p.id ? "bg-accent/15 ring-1 ring-accent/50" : "bg-surface2/60"}`}>
              {p.label}
            </button>
          ))}
        </div>
        <Toggle label="Smart snoozing" on={opts.smart_snoozing} set={(v) => saveOptions({ smart_snoozing: v })} />
        <Toggle label="Smart alerting" on={opts.smart_alerting} set={(v) => saveOptions({ smart_alerting: v })} />
        <Toggle label="Silent during calls" on={opts.no_alarms_during_calls} set={(v) => saveOptions({ no_alarms_during_calls: v })} />
        <Toggle label="Start snoozed" on={opts.start_snoozed} set={(v) => saveOptions({ start_snoozed: v })} />
        <Toggle label="Off when readings are missing" on={opts.disable_alerts_stale_data}
          set={(v) => saveOptions({ disable_alerts_stale_data: v })} />
        {opts.disable_alerts_stale_data && (
          <Label text="After (min)">
            <input className={`num ${field}`} inputMode="numeric" defaultValue={String(opts.disable_alerts_stale_data_minutes)}
              onBlur={(e) => saveOptions({ disable_alerts_stale_data_minutes: Math.round(Number(e.target.value) || 15) })} />
          </Label>
        )}
        <Toggle label="Ascending: 3 min vibration first" on={opts.delay_ascending_3min}
          set={(v) => saveOptions({ delay_ascending_3min: v })} />
        <Toggle label="Ascending: up to medium" on={opts.ascending_volume_to_medium}
          set={(v) => saveOptions({ ascending_volume_to_medium: v })} />
        {!edit && msg && <p className="text-sm text-warn" role="status">{msg}</p>}
      </Section>
    </div>
  );
}

function soundName(uri?: string): string {
  if (!uri) return "Phone alarm";
  if (uri === SOUND_BEEP) return "Beep";
  if (uri === SOUND_VIBRATION) return "Vibration sound";
  return "Sound";
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded-2xl bg-surface p-4 ring-1 ring-white/5 space-y-2">
      <div className="text-xs uppercase tracking-wider text-muted">{title}</div>
      {children}
    </section>
  );
}

function Label({ text, children }: { text: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="text-xs text-muted">{text}</span>
      <div className="mt-1">{children}</div>
    </label>
  );
}

function Toggle({ label, on, set }: { label: string; on: boolean; set: (v: boolean) => void }) {
  return (
    <label className="flex items-center justify-between gap-2 cursor-pointer py-0.5">
      <span className="text-sm">{label}</span>
      <input type="checkbox" checked={on} onChange={(e) => set(e.target.checked)} className="size-5 accent-accent" />
    </label>
  );
}
