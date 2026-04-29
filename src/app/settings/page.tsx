"use client";

import { useEffect, useRef, useState } from "react";
import { getProfile, saveProfile } from "@/lib/db";
import type { Profile } from "@/lib/types";
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
    <div className="px-4 py-3 space-y-4 pb-24">
      <h1 className="text-xl font-semibold">Settings</h1>

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
