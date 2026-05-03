"use client";

import { useEffect, useRef, useState } from "react";
import { getProfile, saveProfile } from "@/lib/db";
import { THEMES, type Profile, type ThemeName } from "@/lib/types";
import { syncOnce, exportAll, importAll } from "@/lib/sync";
import { manualBg, syncXdripOnce } from "@/lib/xdrip";

export default function SettingsPage() {
  const [profile, setProfile] = useState<Profile | null>(null);
  const [endpoint, setEndpoint] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [bg, setBg] = useState("");
  const [status, setStatus] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    getProfile().then(setProfile);
    setEndpoint(localStorage.getItem("meanwhile.syncEndpoint") || "");
    setApiKey(localStorage.getItem("meanwhile.syncApiKey") || "");
  }, []);

  const update = async (patch: Partial<Profile>) => {
    const next = await saveProfile(patch);
    setProfile(next);
  };

  if (!profile) return <div className="p-6 text-muted">Loading…</div>;

  return (
    <div className="px-3 py-3 space-y-4 pb-24">
      <h1 className="text-xl font-semibold">Settings</h1>

      <Section title="Theme">
        <div className="grid grid-cols-3 gap-2">
          {THEMES.map((t) => {
            const active = (profile.theme ?? "default") === t.id;
            return (
              <button
                key={t.id}
                onClick={() => update({ theme: t.id })}
                className={`rounded-xl px-3 py-2 text-left transition flex items-center gap-2 ${
                  active ? "bg-accent/15 ring-1 ring-accent/50" : "bg-surface2/60 hover:bg-surface2"
                }`}
              >
                <span
                  className="size-5 rounded-full ring-1 ring-white/10 shrink-0"
                  style={{ backgroundColor: t.swatch }}
                />
                <span className={`text-sm ${active ? "text-ink" : "text-ink/80"}`}>{t.label}</span>
              </button>
            );
          })}
        </div>
        <p className="text-[11px] text-muted mt-2">
          Themes change UI surfaces and accents. Chart colors stay consistent across themes for analytical clarity.
        </p>
      </Section>

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

      <Section title="xDrip+ local URL">
        <input
          type="url"
          placeholder="http://192.168.x.x:17580"
          value={profile.xdrip_url ?? ""}
          onChange={(e) => update({ xdrip_url: e.target.value })}
          className="w-full rounded-xl bg-surface2 px-3 py-2 outline-none ring-1 ring-white/5 focus:ring-accent/60 text-sm"
        />
        <div className="flex gap-2 mt-2">
          <button
            onClick={async () => {
              const url = (profile.xdrip_url || "").trim();
              if (!url) { setStatus("xDrip: enter a URL first"); return; }
              setStatus("xDrip: testing…");
              try {
                const n = await syncXdripOnce(url);
                const msg = n > 0
                  ? `xDrip: pulled ${n} new readings ✓`
                  : `xDrip: connected ✓ (no new readings since last sync)`;
                setStatus(msg);
              } catch (e) {
                setStatus(`xDrip error: ${(e as Error).message}. Tip: when this app is on a different device than xDrip+, use the phone's LAN IP (not 127.0.0.1) and make sure both are on the same WiFi.`);
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
          xDrip+ → Settings → Inter-app settings → enable <b>Local web service</b>. Direct browser fetches work only if this page is HTTP and on the same device. Otherwise the app falls back to the server proxy at <code>/api/bg/proxy</code>, which requires the deployed server to be able to reach the URL.
        </p>
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
          <label className="block">
            <span className="text-xs text-muted">DIA (h)</span>
            <input
              type="number"
              step={0.5}
              min={2}
              max={10}
              value={profile.dia_hours}
              onChange={(e) => update({ dia_hours: Math.max(2, Math.min(10, Number(e.target.value) || 6)) })}
              className="num mt-1 w-full rounded-xl bg-surface2 px-3 py-2 outline-none ring-1 ring-white/5 focus:ring-accent/60"
            />
          </label>
          <label className="block">
            <span className="text-xs text-muted">Peak (min)</span>
            <input
              type="number"
              step={5}
              min={20}
              max={180}
              value={profile.peak_min ?? 75}
              onChange={(e) => update({ peak_min: Math.max(20, Math.min(180, Number(e.target.value) || 75)) })}
              className="num mt-1 w-full rounded-xl bg-surface2 px-3 py-2 outline-none ring-1 ring-white/5 focus:ring-accent/60"
            />
          </label>
          <label className="block">
            <span className="text-xs text-muted">Delay (min)</span>
            <input
              type="number"
              step={5}
              min={0}
              max={60}
              value={profile.delay_min ?? 15}
              onChange={(e) => update({ delay_min: Math.max(0, Math.min(60, Number(e.target.value) || 15)) })}
              className="num mt-1 w-full rounded-xl bg-surface2 px-3 py-2 outline-none ring-1 ring-white/5 focus:ring-accent/60"
            />
          </label>
        </div>
        <p className="text-[11px] text-muted mt-2">
          IOB uses the Loop / OpenAPS exponential model with an oref0-style absorption delay. Defaults: DIA <b>6h</b>, peak <b>75 min</b>, delay <b>15 min</b> (insulin stays at 100% IOB during the lag, then decays). Long-acting basal is excluded from this calculation.
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
          <label className="block">
            <span className="text-xs text-muted">Units</span>
            <input
              type="number"
              step={0.5}
              min={0}
              max={200}
              value={profile.daily_basal_units ?? 20}
              onChange={(e) => update({ daily_basal_units: Math.max(0, Math.min(200, Number(e.target.value) || 0)) })}
              className="num mt-1 w-full rounded-xl bg-surface2 px-3 py-2 outline-none ring-1 ring-white/5 focus:ring-accent/60"
            />
          </label>
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
          <label className="block">
            <span className="text-xs text-muted">Low (mg/dL)</span>
            <input
              type="number"
              value={profile.tir_low ?? 70}
              onChange={(e) => update({ tir_low: Math.max(40, Number(e.target.value) || 70) })}
              className="num mt-1 w-full rounded-xl bg-surface2 px-3 py-2 outline-none ring-1 ring-white/5 focus:ring-accent/60"
            />
          </label>
          <label className="block">
            <span className="text-xs text-muted">High (mg/dL)</span>
            <input
              type="number"
              value={profile.tir_high ?? 160}
              onChange={(e) => update({ tir_high: Math.min(350, Number(e.target.value) || 160) })}
              className="num mt-1 w-full rounded-xl bg-surface2 px-3 py-2 outline-none ring-1 ring-white/5 focus:ring-accent/60"
            />
          </label>
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

      <Section title="Multi-device sync">
        <input
          placeholder="https://your-sync-endpoint"
          value={endpoint}
          onChange={(e) => { setEndpoint(e.target.value); localStorage.setItem("meanwhile.syncEndpoint", e.target.value); }}
          className="w-full rounded-xl bg-surface2 px-3 py-2 ring-1 ring-white/5 text-sm"
        />
        <input
          placeholder="API key (optional)"
          value={apiKey}
          onChange={(e) => { setApiKey(e.target.value); localStorage.setItem("meanwhile.syncApiKey", e.target.value); }}
          className="w-full mt-2 rounded-xl bg-surface2 px-3 py-2 ring-1 ring-white/5 text-sm"
        />
        <div className="flex gap-2 mt-2">
          <button
            onClick={async () => {
              try {
                const r = await syncOnce(endpoint, apiKey);
                setStatus(r ? `Sync: pushed ${r.pushed}, pulled ${r.pulled}` : "Sync skipped");
              } catch (e) { setStatus(`Sync error: ${(e as Error).message}`); }
            }}
            className="rounded-xl bg-accent text-white px-3 py-2 text-sm font-medium"
          >
            Sync now
          </button>
          <button
            onClick={async () => {
              const blob = await exportAll();
              const url = URL.createObjectURL(blob);
              const a = document.createElement("a");
              a.href = url; a.download = `meanwhile-${new Date().toISOString().slice(0,10)}.json`;
              a.click();
              URL.revokeObjectURL(url);
            }}
            className="rounded-xl bg-surface2 px-3 py-2 text-sm"
          >
            Export
          </button>
          <button
            onClick={() => fileRef.current?.click()}
            className="rounded-xl bg-surface2 px-3 py-2 text-sm"
          >
            Import
          </button>
          <input
            ref={fileRef}
            type="file"
            accept="application/json"
            className="hidden"
            onChange={async (e) => {
              const f = e.target.files?.[0];
              if (!f) return;
              try { await importAll(f); setStatus(`Imported ${f.name}`); }
              catch (err) { setStatus(`Import error: ${(err as Error).message}`); }
            }}
          />
        </div>
      </Section>

      <Section title="App version">
        <p className="text-[11px] text-muted">
          Meanwhile is a PWA — old code can stick around in the service-worker cache. Hard refresh unregisters the worker, clears all caches, and reloads from the network. Your data (logs, profile, decisions) is untouched.
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
              // Cache-busting reload: query string forces network round-trip
              // even on browsers that ignore location.reload(true).
              const url = new URL(window.location.href);
              url.searchParams.set("_r", Date.now().toString());
              window.location.replace(url.toString());
            }}
            className="rounded-xl bg-accent text-white px-3 py-2 text-sm font-medium"
          >
            Hard refresh
          </button>
        </div>
      </Section>

      <Section title="About">
        <div className="text-xs text-muted leading-relaxed">
          Meanwhile is a personal decision-support tool for adults managing T1D. It does not have hard safety caps; you must verify every dose. Your data lives in IndexedDB on this device and (optionally) syncs through your own endpoint.
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
