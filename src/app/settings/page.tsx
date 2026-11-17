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
import { requestPersistentStorage } from "@/lib/persistStorage";
import { manualBg, syncXdripOnce } from "@/lib/xdrip";
import { copyTextNatively, everBridge, IN_APP_BUILD, saveTextNatively } from "@/lib/native";

export default function SettingsPage() {
  const [profile, setProfile] = useState<Profile | null>(null);
  const [bg, setBg] = useState("");
  const [status, setStatus] = useState<string | null>(null);
  const [lastSyncTs, setLastSyncTs] = useState(0);
  const [lastExportTs, setLastExportTs] = useState(0);
  const [lastSyncSummary, setLastSyncSummary] = useState("");
  const [syncing, setSyncing] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);
  // the restore's outcome, shown under its button (the page's status line is far below)
  const [restoreMsg, setRestoreMsg] = useState("");
  const [server, setServer] = useState("");

  useEffect(() => {
    getProfile().then(setProfile);
    // keeps the browser from clearing the data under storage pressure (no settings needed)
    void requestPersistentStorage();
    refreshSyncMeta();
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

  const accentIndex = Math.max(0, ACCENTS.findIndex((a) => a.id === (profile.theme_accent ?? "aurora")));
  const accent = ACCENTS[accentIndex];

  return (
    <div className="px-3 py-3 space-y-4 pb-24">
      <h1 className="text-xl font-semibold">Settings</h1>

      <Section title="Theme">
        <div className="grid grid-cols-2 gap-2">
          {(["dark", "light"] as const).map((m) => {
            const active = (profile.theme_mode ?? "dark") === m;
            return (
              <button
                key={m}
                onClick={() => update({ theme_mode: m as ThemeMode })}
                className={`rounded-xl px-3 py-2.5 text-sm font-medium transition ${
                  active ? "bg-accent/15 ring-1 ring-accent/50 text-ink" : "bg-surface2/60 hover:bg-surface2 text-ink/80"
                }`}
              >
                {m === "dark" ? "Dark" : "Light"}
              </button>
            );
          })}
        </div>
        <div className="flex items-center justify-between mt-3">
          <span className="text-xs text-muted">Color</span>
          <span className="flex items-center gap-2 text-sm">
            <span className="size-5 rounded-full ring-1 ring-white/10" style={{ backgroundColor: accent.swatch }} />
            {accent.label}
          </span>
        </div>
        <input
          type="range"
          min={0}
          max={ACCENTS.length - 1}
          step={1}
          value={accentIndex}
          aria-label="Color"
          onChange={(e) => update({ theme_accent: ACCENTS[Number(e.target.value)].id as AccentName })}
          className="color-range w-full h-8 rounded-full appearance-none cursor-pointer"
          style={{ background: `linear-gradient(to right, ${ACCENTS.map((a) => a.swatch).join(", ")})` }}
        />
      </Section>

      <Section title="Mode">
        <div className="grid grid-cols-2 gap-2">
          {(["decide", "learn"] as const).map((m) => {
            const active = (profile.mode ?? "decide") === m;
            return (
              <button
                key={m}
                onClick={() => update({ mode: m })}
                className={`rounded-xl px-3 py-2.5 text-sm font-medium transition ${
                  active ? "bg-accent/15 ring-1 ring-accent/50 text-ink" : "bg-surface2/60 hover:bg-surface2 text-ink/80"
                }`}
              >
                {m === "decide" ? "Decide" : "Learn"}
              </button>
            );
          })}
        </div>
      </Section>

      {IN_APP_BUILD ? (
        <Section title="Eversense">
          <button onClick={() => everBridge()?.openStatus?.()} className="rounded-xl bg-surface2 px-3 py-2 text-sm">
            Eversense connection
          </button>
        </Section>
      ) : (
        <Section title="Glucose URL">
          <input
            type="url"
            placeholder="http://127.0.0.1:17590"
            value={profile.xdrip_url ?? ""}
            onChange={(e) => update({ xdrip_url: e.target.value })}
            className="w-full rounded-xl bg-surface2 px-3 py-2 outline-none ring-1 ring-white/5 focus:ring-accent/60 text-sm"
          />
          <button
            onClick={async () => {
              const url = (profile.xdrip_url || "").trim();
              if (!url) return;
              setStatus("Testing…");
              try {
                const n = await syncXdripOnce(url);
                setStatus(`Connected ✓ ${n} new readings`);
              } catch (e) {
                setStatus(`Error: ${(e as Error).message}`);
              }
            }}
            className="rounded-xl bg-accent text-white px-3 py-2 text-sm font-medium"
          >
            Test
          </button>
        </Section>
      )}

      <Section title="Manual BG">
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
          <NumberField label="DIA (h)" step={0.5} value={profile.dia_hours} onCommit={(v) => update({ dia_hours: v })} />
          <NumberField label="Peak (min)" step={5} value={profile.peak_min ?? 64} onCommit={(v) => update({ peak_min: v })} />
          <NumberField label="Delay (min)" step={5} value={profile.delay_min ?? 15} onCommit={(v) => update({ delay_min: v })} />
        </div>
      </Section>

      <Section title="Daily basal reminder">
        <label className="flex items-center justify-between gap-2 cursor-pointer">
          <span className="text-sm">On</span>
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
      </Section>

      <Section title="Time in range">
        <div className="grid grid-cols-2 gap-2">
          <NumberField label="Low (mg/dL)" value={profile.tir_low ?? 70} onCommit={(v) => update({ tir_low: v })} />
          <NumberField label="High (mg/dL)" value={profile.tir_high ?? 160} onCommit={(v) => update({ tir_high: v })} />
        </div>
      </Section>

      <Section title="AI provider">
        <select
          value={profile.ai_provider ?? "auto"}
          onChange={(e) => update({ ai_provider: e.target.value as Profile["ai_provider"] })}
          className="w-full rounded-xl bg-surface2 px-3 py-2 ring-1 ring-white/5 text-sm"
        >
          <option value="auto">Auto</option>
          <option value="anthropic">Anthropic (Claude)</option>
          <option value="openai">OpenAI</option>
          <option value="google">Google (Gemini)</option>
        </select>
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
          <button onClick={saveServer} className="rounded-xl bg-accent text-white px-3 py-2 text-sm font-medium">
            Save
          </button>
        </Section>
      )}

      <Section title="Backup">
        <div className="flex items-center justify-between gap-2">
          <div className="flex gap-2">
            <button onClick={downloadBackup} className="rounded-xl bg-accent text-white px-3 py-2 text-sm font-medium">
              Download backup
            </button>
            <button onClick={() => fileRef.current?.click()} className="rounded-xl bg-surface2 px-3 py-2 text-sm">
              Restore from file
            </button>
          </div>
          <span className="text-[11px] text-muted">{lastExportTs ? `${fmtAgo(lastExportTs)} ago` : ""}</span>
        </div>
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
              console.error("restore failed:", err);
              const msg = `Restore error: ${(err as Error).message}`;
              setRestoreMsg(msg);
              setStatus(msg);
            }
          }}
        />
        {restoreMsg && <p className="text-xs" role="status">{restoreMsg}</p>}
      </Section>

      <Section title="Cloud sync">
        <label className="flex items-center justify-between gap-2 cursor-pointer">
          <span className="text-sm">Auto-sync</span>
          <input
            type="checkbox"
            checked={profile.sync_auto ?? true}
            onChange={(e) => update({ sync_auto: e.target.checked })}
            className="size-5 accent-accent"
          />
        </label>
        <label className="block">
          <span className="text-xs text-muted">Sync key</span>
          <input
            type="text"
            value={profile.sync_key ?? ""}
            onChange={(e) => update({ sync_key: e.target.value.trim() })}
            className="num mt-1 w-full rounded-xl bg-surface2 px-3 py-2 ring-1 ring-white/5 text-xs font-mono break-all"
          />
        </label>
        <div className="flex items-center justify-between gap-2">
          <div className="flex gap-2">
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
                if (!confirm("New sync key? Other devices stop syncing until they have it.")) return;
                const k = await regenerateSyncKey();
                setProfile((p) => p ? { ...p, sync_key: k } : p);
                setStatus("New sync key generated");
              }}
              className="rounded-xl bg-surface2 px-3 py-1.5 text-xs"
            >
              Regenerate
            </button>
          </div>
          <button
            onClick={runSyncNow}
            disabled={syncing}
            className="rounded-xl bg-accent text-white px-3 py-1.5 text-xs font-medium disabled:opacity-50"
          >
            {syncing ? "Syncing…" : "Sync now"}
          </button>
        </div>
        <div className="text-[11px] text-muted truncate">
          {lastSyncTs ? `${fmtAgo(lastSyncTs)} ago${lastSyncSummary ? ` · ${lastSyncSummary}` : ""}` : "Never synced"}
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
