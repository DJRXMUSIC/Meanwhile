"use client";

import { useEffect, useRef, useState } from "react";
import { getProfile, regenerateSyncKey, saveProfile } from "@/lib/db";
import { ACCENTS, type AccentName, type Profile, type ThemeMode } from "@/lib/types";
import {
  exportAll,
  importAll,
  lastSync as readLastSync,
  lastExport as readLastExport,
  lastSyncStatus as readLastSyncStatus,
  syncOnce,
} from "@/lib/sync";
import { NumberField } from "@/components/NumberField";
import { formatBytes, requestPersistentStorage, type PersistState } from "@/lib/persistStorage";
import { manualBg, syncXdripOnce } from "@/lib/xdrip";
import { copyTextNatively, everBridge, IN_APP_BUILD, saveTextNatively } from "@/lib/native";

export default function SettingsPage() {
  const [profile, setProfile] = useState<Profile | null>(null);
  const [bg, setBg] = useState("");
  const [status, setStatus] = useState<string | null>(null);
  const [persist, setPersist] = useState<PersistState | null>(null);
  const [lastSyncTs, setLastSyncTs] = useState(0);
  const [lastExportTs, setLastExportTs] = useState(0);
  const [lastSyncSummary, setLastSyncSummary] = useState("");
  const [syncing, setSyncing] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);
  // the restore's outcome, shown under its button (the page's status line is far below)
  const [restoreMsg, setRestoreMsg] = useState("");
  // True inside the EverBridge app. Set after mount so the server-rendered
  // page and the first client render agree.
  const [inApp, setInApp] = useState(false);
  const [server, setServer] = useState("");

  useEffect(() => {
    getProfile().then(setProfile);
    requestPersistentStorage().then(setPersist);
    refreshSyncMeta();
    setInApp(!!everBridge());
    try { setServer(everBridge()?.apiBase?.() ?? ""); } catch { /* older app */ }
  }, []);

  const saveServer = () => {
    const n = everBridge();
    if (!n?.setApiBase) { setStatus("This version of the app can't save a server address"); return; }
    const saved = n.setApiBase(server.trim());
    if (saved == null) { setStatus("That isn't a usable address. It should look like https://your-site.netlify.app"); return; }
    setServer(saved);
    setStatus(saved ? "Server address saved ✓" : "Server address cleared");
  };

  const refreshSyncMeta = () => {
    setLastSyncTs(readLastSync());
    setLastExportTs(readLastExport());
    setLastSyncSummary(readLastSyncStatus());
  };

  const runSyncNow = async () => {
    if (!profile?.sync_key) { setStatus("Sync key not set"); return; }
    setSyncing(true);
    setStatus("Syncing…");
    try {
      const r = await syncOnce(profile.sync_key);
      setStatus(`Sync ${r.backend}: pushed ${r.pushed}, pulled ${r.pulled}`);
      refreshSyncMeta();
    } catch (e) {
      setStatus(`Sync error: ${(e as Error).message}`);
    } finally {
      setSyncing(false);
    }
  };

  const refreshPersist = async () => {
    const p = await requestPersistentStorage();
    setPersist(p);
    setStatus(p.persisted ? "Persistent storage granted ✓" : "Persistent storage not granted (yet)");
  };

  const downloadBackup = async () => {
    const blob = await exportAll();
    const name = `meanwhile-${new Date().toISOString().slice(0, 10)}.json`;
    // Inside the EverBridge app there is no browser download; the app saves the file.
    const saved = saveTextNatively(name, await blob.text());
    if (saved !== undefined) {
      refreshSyncMeta();
      setStatus(saved ? `Backup saved to ${saved} ✓` : "Backup could not be saved");
      return;
    }
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = name;
    a.click();
    URL.revokeObjectURL(url);
    refreshSyncMeta();
    setStatus("Backup downloaded ✓");
  };

  const update = async (patch: Partial<Profile>) => {
    const next = await saveProfile(patch);
    setProfile(next);
  };

  if (!profile) return <div className="p-6 text-muted">Loading…</div>;

  return (
    <div className="px-3 py-3 space-y-4 pb-24">
      <h1 className="text-xl font-semibold">Settings</h1>

      {inApp && (
        <Section title="Glucose alerts">
          <button
            onClick={() => {
              // call each as a method of the app's object: Android refuses one taken off it
              const app = everBridge();
              if (app?.openAlerts) app.openAlerts();
              else app?.openStatus?.();
            }}
            className="w-full rounded-xl bg-accent text-white px-3 py-2.5 text-sm font-medium"
          >
            Open glucose alerts
          </button>
          <p className="text-[11px] text-muted mt-2">
            Low and high alerts with their sounds: change, test or snooze them, or turn them off for a while.
            Also on the app icon: press and hold it, then tap <b>Glucose alerts</b>.
          </p>
        </Section>
      )}

      <Section title="Theme">
        <div className="text-xs text-muted mb-1">Base</div>
        <div className="grid grid-cols-2 gap-2">
          {(["dark", "light"] as const).map((m) => {
            const active = (profile.theme_mode ?? "dark") === m;
            return (
              <button
                key={m}
                onClick={() => update({ theme_mode: m as ThemeMode })}
                className={`rounded-xl px-3 py-2.5 text-left transition ${
                  active ? "bg-accent/15 ring-1 ring-accent/50" : "bg-surface2/60 hover:bg-surface2"
                }`}
              >
                <div className={`text-sm font-medium ${active ? "text-ink" : "text-ink/80"}`}>
                  {m === "dark" ? "Dark" : "Light"}
                </div>
                <div className="text-[11px] text-muted">
                  {m === "dark" ? "Near-black surfaces" : "White / cream surfaces"}
                </div>
              </button>
            );
          })}
        </div>

        <div className="flex items-baseline justify-between mt-3 mb-1">
          <span className="text-xs text-muted">Accent color</span>
          <span className="text-[10px] text-muted">{ACCENTS.length} options, hue sorted</span>
        </div>
        <div className="grid grid-cols-5 gap-1.5">
          {ACCENTS.map((a) => {
            const active = (profile.theme_accent ?? "aurora") === a.id;
            return (
              <button
                key={a.id}
                onClick={() => update({ theme_accent: a.id as AccentName })}
                className={`rounded-xl p-1.5 transition flex flex-col items-center gap-1 ${
                  active ? "bg-accent/15 ring-1 ring-accent/50" : "bg-surface2/60 hover:bg-surface2"
                }`}
                title={a.label}
                aria-label={a.label}
              >
                <span
                  className="size-7 rounded-full ring-1 ring-white/10 shrink-0"
                  style={{ backgroundColor: a.swatch }}
                />
                <span className={`text-[10px] leading-tight truncate w-full text-center ${active ? "text-ink" : "text-ink/80"}`}>{a.label}</span>
              </button>
            );
          })}
        </div>
        <p className="text-[11px] text-muted mt-2">
          Base mode chooses light or dark surfaces; the accent only colors highlights and primary buttons. Chart colors stay consistent across themes for analytical clarity.
        </p>
      </Section>

      {IN_APP_BUILD && (
        <Section title="Meanwhile server">
          <input
            type="url"
            placeholder="https://your-site.netlify.app"
            value={server}
            onChange={(e) => setServer(e.target.value)}
            className="w-full rounded-xl bg-surface2 px-3 py-2 outline-none ring-1 ring-white/5 focus:ring-accent/60 text-sm"
          />
          <div className="flex gap-2 mt-2">
            <button onClick={saveServer} className="rounded-xl bg-accent text-white px-3 py-2 text-sm font-medium">
              Save
            </button>
          </div>
          <p className="text-[11px] text-muted mt-2">
            The address you used to open Meanwhile in Chrome. The app needs it for AI advice and cloud backup;
            everything else (readings, doses, the chart) works without it.
          </p>
        </Section>
      )}

      <Section title="App mode">
        <div className="grid grid-cols-2 gap-2">
          {(["decide", "learn"] as const).map((m) => {
            const active = (profile.mode ?? "decide") === m;
            return (
              <button
                key={m}
                onClick={() => update({ mode: m })}
                className={`rounded-xl px-3 py-3 text-left transition ${
                  active ? "bg-accent/15 ring-1 ring-accent/50" : "bg-surface2/60 hover:bg-surface2"
                }`}
              >
                <div className={`text-sm font-medium ${active ? "text-ink" : "text-ink/80"}`}>
                  {m === "decide" ? "Decide" : "Learn"}
                </div>
                <div className="text-[11px] text-muted">
                  {m === "decide" ? "AI mic + text prompt" : "Quick log doses, no AI calls"}
                </div>
              </button>
            );
          })}
        </div>
        <p className="text-[11px] text-muted mt-2">
          Mode is stored in your profile and persists across app restarts, reboots, and reinstalls (when sync is enabled).
        </p>
      </Section>

      <Section title="Glucose source">
        {IN_APP_BUILD ? (
          <>
            <p className="text-sm">Readings come straight from the Eversense app on this phone. Nothing to set up here.</p>
            <button
              onClick={() => everBridge()?.openStatus?.()}
              className="rounded-xl bg-surface2 px-3 py-2 text-sm"
            >
              Eversense connection &amp; setup
            </button>
            <p className="text-[11px] text-muted mt-2">
              Shows the latest reading, anything that needs fixing (notification access, battery), a self-test and a log file for troubleshooting.
            </p>
          </>
        ) : (
          <>
            <input
              type="url"
              placeholder="http://127.0.0.1:17590"
              value={profile.xdrip_url ?? ""}
              onChange={(e) => update({ xdrip_url: e.target.value })}
              className="w-full rounded-xl bg-surface2 px-3 py-2 outline-none ring-1 ring-white/5 focus:ring-accent/60 text-sm"
            />
            <div className="flex gap-2 mt-2 flex-wrap">
              <button
                onClick={async () => {
                  const url = (profile.xdrip_url || "").trim();
                  if (!url) { setStatus("Glucose: enter an address first"); return; }
                  setStatus("Glucose: testing…");
                  try {
                    const n = await syncXdripOnce(url);
                    const msg = n > 0
                      ? `Glucose: pulled ${n} new readings ✓`
                      : `Glucose: connected ✓ (no new readings since last sync)`;
                    setStatus(msg);
                  } catch (e) {
                    setStatus(`Glucose error: ${(e as Error).message}. Check the Meanwhile v5 app is running on the phone (its self-test should PASS). From another device, turn on its Wi-Fi/LAN option and use the phone's LAN IP instead of 127.0.0.1.`);
                  }
                }}
                className="rounded-xl bg-accent text-white px-3 py-2 text-sm font-medium"
              >
                Test connection
              </button>
              <button
                onClick={async () => {
                  const url = (profile.xdrip_url || "").trim();
                  if (!url) return;
                  try {
                    const r = await fetch(`/api/bg/proxy?base=${encodeURIComponent(url)}&count=1`, { cache: "no-store" });
                    const t = await r.text();
                    setStatus(`Proxy ${r.status}: ${t.slice(0, 200)}`);
                  } catch (e) { setStatus(`Proxy error: ${(e as Error).message}`); }
                }}
                className="rounded-xl bg-surface2 px-3 py-2 text-sm"
              >
                Test via server proxy
              </button>
            </div>
            <p className="text-[11px] text-muted mt-2">
              Readings come from the <b>Meanwhile v5</b> app on your phone, at <code>http://127.0.0.1:17590</code>. xDrip+ (port 17580) is not used. Direct browser fetches work when this page is open on the same phone. Otherwise the site falls back to the server proxy at <code>/api/bg/proxy</code>, which requires the deployed server to be able to reach the address.
            </p>
          </>
        )}
      </Section>

      <Section title="Manual BG entry">
        <div className="flex gap-2">
          <input
            type="number"
            placeholder="mg/dL"
            value={bg}
            onChange={(e) => setBg(e.target.value)}
            className="num flex-1 rounded-xl bg-surface2 px-3 py-2 outline-none ring-1 ring-white/5"
          />
          <button
            onClick={async () => {
              const n = Number(bg);
              if (!n || n < 20 || n > 600) return;
              await manualBg(n);
              setBg("");
              setStatus(`Logged BG ${n}`);
            }}
            className="rounded-xl bg-accent text-white px-3 py-2 text-sm font-medium"
          >
            Log
          </button>
        </div>
      </Section>

      <Section title="Insulin action">
        <div className="grid grid-cols-3 gap-2">
          <NumberField
            label="DIA (h)"
            step={0.5}
            value={profile.dia_hours}
            onCommit={(v) => update({ dia_hours: v })}
          />
          <NumberField
            label="Peak (min)"
            step={5}
            value={profile.peak_min ?? 64}
            onCommit={(v) => update({ peak_min: v })}
          />
          <NumberField
            label="Delay (min)"
            step={5}
            value={profile.delay_min ?? 15}
            onCommit={(v) => update({ delay_min: v })}
          />
        </div>
        <p className="text-[11px] text-muted mt-2">
          IOB uses the Loop / OpenAPS exponential model with an oref0-style absorption delay. Defaults: DIA <b>5.5h</b>, peak <b>64 min</b>, delay <b>15 min</b> (insulin stays at 100% IOB during the lag, then decays). Long-acting basal is excluded from this calculation.
        </p>
      </Section>

      <Section title="Daily basal reminder">
        <label className="flex items-center justify-between gap-2 cursor-pointer">
          <span className="text-sm">Show daily reminder</span>
          <input
            type="checkbox"
            checked={profile.daily_basal_enabled ?? true}
            onChange={(e) => update({ daily_basal_enabled: e.target.checked })}
            className="size-5 accent-accent"
          />
        </label>
        <div className="grid grid-cols-3 gap-2 mt-2">
          <NumberField
            label="Units"
            step={0.5}
            value={profile.daily_basal_units ?? 20}
            onCommit={(v) => update({ daily_basal_units: v })}
          />
          <label className="block">
            <span className="text-xs text-muted">Time</span>
            <input
              type="time"
              value={profile.daily_basal_time ?? "18:30"}
              onChange={(e) => update({ daily_basal_time: e.target.value || "18:30" })}
              className="num mt-1 w-full rounded-xl bg-surface2 px-3 py-2 outline-none ring-1 ring-white/5 focus:ring-accent/60"
            />
          </label>
          <label className="block">
            <span className="text-xs text-muted">Timezone</span>
            <select
              value={profile.daily_basal_tz ?? "America/New_York"}
              onChange={(e) => update({ daily_basal_tz: e.target.value })}
              className="mt-1 w-full rounded-xl bg-surface2 px-2 py-2 ring-1 ring-white/5 text-sm"
            >
              <option value="America/New_York">Eastern (NY)</option>
              <option value="America/Chicago">Central (Chi)</option>
              <option value="America/Denver">Mountain (Den)</option>
              <option value="America/Los_Angeles">Pacific (LA)</option>
              <option value="America/Anchorage">Alaska</option>
              <option value="Pacific/Honolulu">Hawaii</option>
              <option value="Europe/London">London</option>
              <option value="Europe/Paris">Paris</option>
              <option value="Asia/Tokyo">Tokyo</option>
              <option value="Australia/Sydney">Sydney</option>
            </select>
          </label>
        </div>
        <p className="text-[11px] text-muted mt-2">
          A circular button appears on the home screen at the configured time each day until you tap it. Time is interpreted in the chosen IANA zone (DST handled automatically), so it fires at the same wall clock no matter where the device is.
        </p>
      </Section>

      <Section title="Time-in-range bounds">
        <div className="grid grid-cols-2 gap-2">
          <NumberField
            label="Low (mg/dL)"
            value={profile.tir_low ?? 70}
            onCommit={(v) => update({ tir_low: v })}
          />
          <NumberField
            label="High (mg/dL)"
            value={profile.tir_high ?? 160}
            onCommit={(v) => update({ tir_high: v })}
          />
        </div>
        <p className="text-[11px] text-muted mt-2">Used in chart band shading and Profile statistics.</p>
      </Section>

      <Section title="AI provider">
        <select
          value={profile.ai_provider ?? "auto"}
          onChange={(e) => update({ ai_provider: e.target.value as Profile["ai_provider"] })}
          className="w-full rounded-xl bg-surface2 px-3 py-2 ring-1 ring-white/5 text-sm"
        >
          <option value="auto">Auto (try primary, fall back)</option>
          <option value="anthropic">Anthropic (Claude)</option>
          <option value="openai">OpenAI</option>
          <option value="google">Google (Gemini)</option>
        </select>
        <p className="text-[11px] text-muted mt-2">
          Configure API keys via environment variables on the server: <code>ANTHROPIC_API_KEY</code>, <code>OPENAI_API_KEY</code>, or <code>GEMINI_API_KEY</code>. For Gemini, set <code>GEMINI_MODEL</code> (default <code>gemini-2.5-flash-lite</code>) and <code>GEMINI_THINKING_BUDGET=-1</code> for max reasoning.
        </p>
      </Section>

      <Section title="Data safety">
        <div className="rounded-xl bg-surface2/60 ring-1 ring-white/5 p-3 text-sm">
          <div className="flex items-baseline justify-between gap-2">
            <span className="font-medium">Persistent storage</span>
            <span className={persist?.persisted ? "text-good text-xs" : "text-warn text-xs"}>
              {persist?.persisted ? "Granted ✓" : persist == null ? "checking…" : "Not granted"}
            </span>
          </div>
          {!persist?.persisted && (
            <p className="text-[11px] text-muted mt-1">
              Without persistent storage the browser can evict your data
              (BG, doses, decisions) when it clears the cache. Installed
              PWAs and engaged sites get it automatically; tap below to
              re-request.
            </p>
          )}
          {persist?.persisted && (
            <p className="text-[11px] text-muted mt-1">
              Your data is protected from cache clears and storage-pressure
              eviction.
            </p>
          )}
          <div className="text-[11px] text-muted mt-2">
            Using {formatBytes(persist?.usage)} of {formatBytes(persist?.quota)}
          </div>
          <button
            onClick={refreshPersist}
            className="mt-2 rounded-xl bg-surface2 px-3 py-1.5 text-xs"
          >
            Re-request
          </button>
        </div>

        <div className="rounded-xl bg-surface2/60 ring-1 ring-white/5 p-3 text-sm">
          <div className="flex items-baseline justify-between gap-2">
            <span className="font-medium">Local backup</span>
            <span className="text-[11px] text-muted">
              {lastExportTs ? `Last ${fmtAgo(lastExportTs)} ago` : "Never"}
            </span>
          </div>
          <p className="text-[11px] text-muted mt-1">
            Download a full JSON snapshot. Keep one off-device every so
            often — especially if you haven't enabled cloud sync below.
          </p>
          <div className="flex gap-2 mt-2">
            <button
              onClick={downloadBackup}
              className="rounded-xl bg-accent text-white px-3 py-1.5 text-xs font-medium"
            >
              Download backup
            </button>
            <button
              onClick={() => fileRef.current?.click()}
              className="rounded-xl bg-surface2 px-3 py-1.5 text-xs"
            >
              Restore from file
            </button>
            <input
              ref={fileRef}
              type="file"
              accept="application/json"
              className="hidden"
              onChange={async (e) => {
                const input = e.target;
                const f = input.files?.[0];
                // let the same file be picked again next time
                input.value = "";
                if (!f) return;
                setRestoreMsg(`Restoring ${f.name}…`);
                try {
                  const applied = await importAll(f);
                  const msg = `Restored ${applied} rows from ${f.name} ✓`;
                  setRestoreMsg(msg);
                  setStatus(msg);
                } catch (err) {
                  const msg = `Restore error: ${(err as Error).message}. Pick a Meanwhile backup (.json) file.`;
                  setRestoreMsg(msg);
                  setStatus(msg);
                }
              }}
            />
          </div>
          {restoreMsg && <p className="text-xs mt-2" role="status">{restoreMsg}</p>}
        </div>
      </Section>

      <Section title="Cloud sync">
        <div className="text-xs text-muted">
          Sync your full history to all devices through a Netlify-hosted
          key/value store. The sync key is the shared password — paste
          it into Meanwhile on another device to join the same data set.
        </div>

        <label className="flex items-center justify-between mt-2 gap-2 cursor-pointer">
          <span className="text-sm">Auto-sync (on open, on focus, after every change)</span>
          <input
            type="checkbox"
            checked={profile.sync_auto ?? true}
            onChange={(e) => update({ sync_auto: e.target.checked })}
            className="size-5 accent-accent"
          />
        </label>

        <div className="mt-2 rounded-xl bg-surface2/60 ring-1 ring-white/5 p-3">
          <div className="text-[10px] uppercase tracking-wider text-muted">Sync key</div>
          <input
            type="text"
            value={profile.sync_key ?? ""}
            onChange={(e) => update({ sync_key: e.target.value.trim() })}
            placeholder="paste a key here to join another device's data"
            className="num mt-1 w-full rounded-lg bg-surface px-3 py-2 ring-1 ring-white/5 text-xs font-mono break-all"
          />
          <div className="flex gap-2 mt-2 flex-wrap">
            <button
              onClick={async () => {
                if (!profile.sync_key) return;
                try { await navigator.clipboard.writeText(profile.sync_key); setStatus("Sync key copied ✓"); }
                catch { setStatus(copyTextNatively(profile.sync_key) ? "Sync key copied ✓" : "Copy failed"); }
              }}
              className="rounded-xl bg-surface2 px-3 py-1.5 text-xs"
            >
              Copy
            </button>
            <button
              onClick={async () => {
                if (!confirm("Generate a new sync key? Other devices using the current key will stop syncing until you give them the new one.")) return;
                const k = await regenerateSyncKey();
                setProfile((p) => p ? { ...p, sync_key: k } : p);
                setStatus("New sync key generated");
              }}
              className="rounded-xl bg-surface2 px-3 py-1.5 text-xs"
            >
              Regenerate
            </button>
          </div>
        </div>

        <div className="flex items-center justify-between gap-2 mt-2">
          <div className="text-[11px] text-muted min-w-0 truncate">
            {lastSyncTs
              ? `Last synced ${fmtAgo(lastSyncTs)} ago${lastSyncSummary ? ` · ${lastSyncSummary}` : ""}`
              : "Never synced"}
          </div>
          <button
            onClick={runSyncNow}
            disabled={syncing}
            className="rounded-xl bg-accent text-white px-3 py-1.5 text-xs font-medium disabled:opacity-50"
          >
            {syncing ? "Syncing…" : "Sync now"}
          </button>
        </div>
      </Section>

      <Section title="App version">
        <p className="text-[11px] text-muted">
          Meanwhile is a PWA — old code can stick around in the service-worker cache. <b>Hard refresh</b> unregisters the worker, clears the SW caches, and reloads from the network. <b>This button does not touch your data.</b>
        </p>
        <p className="text-[11px] text-muted">
          ⚠ Clearing browser data from <i>Chrome / Safari settings</i> is a different operation and <b>will</b> wipe Meanwhile's IndexedDB unless persistent storage is granted above. Always have either Cloud sync enabled or a recent local backup before clearing browser data.
        </p>
        <div className="flex gap-2 mt-2">
          <button
            onClick={async () => {
              setStatus("Refreshing…");
              try {
                if ("serviceWorker" in navigator) {
                  const regs = await navigator.serviceWorker.getRegistrations();
                  await Promise.all(regs.map((r) => r.unregister()));
                }
                if ("caches" in window) {
                  const keys = await caches.keys();
                  await Promise.all(keys.map((k) => caches.delete(k)));
                }
              } catch (e) {
                setStatus(`Refresh prep error: ${(e as Error).message}`);
              }
              const url = new URL(window.location.href);
              url.searchParams.set("_r", Date.now().toString());
              window.location.replace(url.toString());
            }}
            className="rounded-xl bg-accent text-white px-3 py-2 text-sm font-medium"
          >
            Hard refresh
          </button>
          <button
            onClick={downloadBackup}
            className="rounded-xl bg-surface2 px-3 py-2 text-sm"
          >
            Download backup first
          </button>
        </div>
      </Section>

      <Section title="About">
        <div className="text-xs text-muted leading-relaxed">
          Meanwhile is a personal decision-support tool for adults managing T1D. It does not have hard safety caps; you must verify every dose. Your data lives in IndexedDB on this device and syncs to a Netlify-hosted key/value store keyed by your sync key.
        </div>
      </Section>

      {status && <div className="rounded-xl bg-surface2 px-3 py-2 text-sm text-muted">{status}</div>}
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded-2xl bg-surface p-4 ring-1 ring-white/5 space-y-2">
      <div className="text-xs uppercase tracking-wider text-muted">{title}</div>
      {children}
    </section>
  );
}

function fmtAgo(ts: number): string {
  const seconds = Math.max(0, Math.floor((Date.now() - ts) / 1000));
  if (seconds < 60) return `${seconds}s`;
  const m = Math.floor(seconds / 60);
  if (m < 60) return `${m}m`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h`;
  const d = Math.floor(h / 24);
  return `${d}d`;
}
