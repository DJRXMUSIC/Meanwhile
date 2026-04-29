"use client";

import { useEffect, useMemo, useState } from "react";
import { useLiveQuery } from "dexie-react-hooks";
import { db, getProfile, saveProfile } from "@/lib/db";
import { attachOutcomes, refineProfile, timeInRange, tdd, avgCarbsPerDay } from "@/lib/refine";
import type { Profile } from "@/lib/types";

export default function ProfilePage() {
  const [profile, setProfile] = useState<Profile | null>(null);
  const [draft, setDraft] = useState<Profile | null>(null);
  const [saving, setSaving] = useState(false);
  const [savedAt, setSavedAt] = useState<number | null>(null);

  useEffect(() => { getProfile().then((p) => { setProfile(p); setDraft(p); }); }, []);

  const since = Date.now() - 14 * 24 * 3600_000;
  const decisions = useLiveQuery(() => db().decisions.where("ts").above(since).toArray(), [since], []) ?? [];
  const bgList = useLiveQuery(() => db().bg.where("ts").above(since).sortBy("ts"), [since], []) ?? [];
  const insulinList = useLiveQuery(() => db().insulin.where("ts").above(since).sortBy("ts"), [since], []) ?? [];
  const carbsList = useLiveQuery(() => db().carbs.where("ts").above(since).sortBy("ts"), [since], []) ?? [];

  const tirSummary = useMemo(() => timeInRange(bgList), [bgList]);
  const dailyTDD = useMemo(() => tdd(insulinList), [insulinList]);
  const dailyCarbs = useMemo(() => avgCarbsPerDay(carbsList), [carbsList]);

  const refinement = useMemo(() => {
    if (!profile) return null;
    const outcomes = attachOutcomes(decisions, bgList, profile.target_bg);
    return refineProfile(profile, outcomes);
  }, [profile, decisions, bgList]);

  const save = async () => {
    if (!draft) return;
    setSaving(true);
    const next = await saveProfile(draft);
    setProfile(next);
    setDraft(next);
    setSavedAt(Date.now());
    setSaving(false);
  };

  const applyRefinement = async () => {
    if (!refinement || !profile) return;
    const next = await saveProfile({
      ai_overrides: {
        ic_ratio: refinement.ic_ratio,
        isf: refinement.isf,
        notes: refinement.notes,
        updated_ts: Date.now(),
      },
    });
    setProfile(next);
    setDraft(next);
  };

  const clearOverride = async () => {
    const next = await saveProfile({ ai_overrides: undefined });
    setProfile(next);
    setDraft(next);
  };

  if (!profile || !draft) return <div className="p-6 text-muted">Loading…</div>;

  const eff = {
    ic: profile.ai_overrides?.ic_ratio ?? profile.ic_ratio,
    isf: profile.ai_overrides?.isf ?? profile.isf,
  };

  return (
    <div className="px-4 py-3 space-y-4 pb-24">
      <h1 className="text-xl font-semibold">Profile</h1>

      <section className="rounded-2xl bg-surface p-4 ring-1 ring-white/5">
        <div className="text-xs uppercase tracking-wider text-muted mb-2">Active ratios</div>
        <div className="grid grid-cols-3 gap-2">
          <Tile label="I:C" value={`1:${eff.ic}`} hint={profile.ai_overrides?.ic_ratio ? `from ${profile.ic_ratio}` : "base"} />
          <Tile label="ISF" value={`1:${eff.isf}`} hint={profile.ai_overrides?.isf ? `from ${profile.isf}` : "base"} />
          <Tile label="Target" value={`${profile.target_bg}`} hint="mg/dL" />
        </div>
      </section>

      <section className="rounded-2xl bg-surface p-4 ring-1 ring-white/5">
        <div className="text-xs uppercase tracking-wider text-muted mb-2">Last 14 days</div>
        <div className="grid grid-cols-3 gap-2">
          <Tile label="TIR" value={`${(tirSummary.tir * 100).toFixed(0)}%`} hint={`70–180`} />
          <Tile label="TDD (24h)" value={`${dailyTDD.toFixed(1)}U`} hint="all insulin" />
          <Tile label="Carbs/day" value={`${dailyCarbs.toFixed(0)}g`} hint="7d avg" />
        </div>
        <div className="mt-3 text-[11px] text-muted">
          Below 70: {(tirSummary.below * 100).toFixed(0)}% · Above 180: {(tirSummary.above * 100).toFixed(0)}%
        </div>
      </section>

      <section className="rounded-2xl bg-surface p-4 ring-1 ring-white/5">
        <div className="flex items-center justify-between">
          <div className="text-xs uppercase tracking-wider text-muted">AI refinement</div>
          <div className="text-[11px] text-muted">{refinement?.samples_used ?? 0} samples</div>
        </div>
        {refinement && refinement.samples_used > 0 ? (
          <>
            <pre className="mt-2 whitespace-pre-wrap text-sm text-ink/90 font-sans">{refinement.notes}</pre>
            <div className="mt-3 flex gap-2">
              <button onClick={applyRefinement} className="rounded-xl bg-accent text-white px-3 py-2 text-sm font-medium">
                Apply suggestion
              </button>
              {profile.ai_overrides && (
                <button onClick={clearOverride} className="rounded-xl bg-surface2 px-3 py-2 text-sm">
                  Reset to base
                </button>
              )}
            </div>
          </>
        ) : (
          <div className="mt-2 text-sm text-muted">Log more decisions with outcomes to enable refinement.</div>
        )}
        {profile.ai_overrides?.notes && (
          <div className="mt-3 text-[11px] text-muted whitespace-pre-wrap">Last applied: {profile.ai_overrides.notes}</div>
        )}
      </section>

      <section className="rounded-2xl bg-surface p-4 ring-1 ring-white/5 space-y-3">
        <div className="text-xs uppercase tracking-wider text-muted">Base profile</div>
        <NumberField label="I:C ratio (1U covers Xg)" value={draft.ic_ratio} onChange={(v) => setDraft({ ...draft, ic_ratio: v })} />
        <NumberField label="ISF (1U drops X mg/dL)" value={draft.isf} onChange={(v) => setDraft({ ...draft, isf: v })} />
        <NumberField label="Target BG (mg/dL)" value={draft.target_bg} onChange={(v) => setDraft({ ...draft, target_bg: v })} />
        <NumberField label="Basal (U/hr)" value={draft.basal_u_per_hr} step={0.05} onChange={(v) => setDraft({ ...draft, basal_u_per_hr: v })} />
        <NumberField label="DIA (hours)" value={draft.dia_hours} step={0.5} onChange={(v) => setDraft({ ...draft, dia_hours: v })} />
        <button
          onClick={save}
          disabled={saving}
          className="rounded-xl bg-accent text-white px-3 py-2 text-sm font-medium disabled:opacity-60"
        >
          {saving ? "Saving…" : "Save profile"}
        </button>
        {savedAt && <span className="text-[11px] text-muted ml-2">Saved.</span>}
      </section>

      <section className="rounded-2xl bg-surface p-4 ring-1 ring-white/5">
        <div className="text-xs uppercase tracking-wider text-muted mb-2">Recent decisions</div>
        <ul className="space-y-2 max-h-80 overflow-auto">
          {decisions.slice(-25).reverse().map((d) => (
            <li key={d.id} className="rounded-xl bg-surface2/60 p-3">
              <div className="text-sm">{d.headline}</div>
              <div className="text-[11px] text-muted mt-1">
                {new Date(d.ts).toLocaleString()} · BG {d.bg_at_time ?? "—"} · IOB {d.iob_at_time?.toFixed(2) ?? "—"} · {d.suggested_units != null ? `${d.suggested_units}U` : "no dose"} · {d.suggested_carbs_g ?? d.extracted?.carbs_g ?? 0}g
              </div>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}

function Tile({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="rounded-xl bg-surface2/60 p-3">
      <div className="text-[10px] uppercase tracking-wider text-muted">{label}</div>
      <div className="num text-2xl font-semibold mt-0.5">{value}</div>
      {hint && <div className="text-[11px] text-muted">{hint}</div>}
    </div>
  );
}

function NumberField({ label, value, onChange, step = 1 }: { label: string; value: number; onChange: (v: number) => void; step?: number }) {
  return (
    <label className="block">
      <span className="text-xs text-muted">{label}</span>
      <input
        type="number"
        value={value}
        step={step}
        onChange={(e) => onChange(Number(e.target.value))}
        className="num mt-1 w-full rounded-xl bg-surface2 px-3 py-2 outline-none ring-1 ring-white/5 focus:ring-accent/60"
      />
    </label>
  );
}
