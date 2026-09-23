"use client";

import type { BgReading, Decision, InsulinDose, Profile } from "./types";
import { totalIOB } from "./insulin";

// Profile refinement: scan completed decisions where we have outcome BG values
// and estimate an adjustment to ISF based on observed deviation from target.

export interface Outcome {
  decision_id: number;
  ts: number;
  units: number;
  bg_start: number;
  bg_120: number | null;
  delta_to_target: number | null;
  classification: "low" | "in-range" | "high";
}

export function attachOutcomes(decisions: Decision[], bg: BgReading[], target: number): Outcome[] {
  const out: Outcome[] = [];
  for (const d of decisions) {
    if (d.bg_at_time == null || d.suggested_units == null) continue;
    const units = d.suggested_units;
    // A zero-unit suggestion carries no signal about ISF.
    if (units === 0) continue;
    const t120 = d.ts + 120 * 60_000;
    // closest BG within ±15min of t120
    const candidate = bg
      .filter((r) => Math.abs(r.ts - t120) < 15 * 60_000)
      .sort((a, b) => Math.abs(a.ts - t120) - Math.abs(b.ts - t120))[0];
    const bg120 = candidate?.mgdl ?? null;
    const delta = bg120 != null ? bg120 - target : null;
    out.push({
      decision_id: d.id!,
      ts: d.ts,
      units,
      bg_start: d.bg_at_time,
      bg_120: bg120,
      delta_to_target: delta,
      classification: bg120 == null ? "in-range" : bg120 < 70 ? "low" : bg120 > 180 ? "high" : "in-range",
    });
  }
  return out;
}

export interface RefinementSuggestion {
  isf: number;
  samples_used: number;
  notes: string;
}

// Very conservative least-squares-style nudge: if dosed decisions land
// consistently above or below target at BG@2h, move ISF in proportion.
export function refineProfile(profile: Profile, outcomes: Outcome[]): RefinementSuggestion {
  const dosed = outcomes.filter((o) => o.units > 0 && o.delta_to_target != null);

  const avg = (xs: number[]) => xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : 0;

  const deltaAvg = avg(dosed.map((o) => o.delta_to_target!));

  // If doses end +30 above target on average, ISF is too weak → lower it
  // (more insulin per mg/dL). Magnitude scales by
  // (target / (target + delta))-ish; clamp to ±15%.
  const isfScale = clamp(profile.target_bg / (profile.target_bg + deltaAvg), 0.85, 1.15);

  const isf = round(profile.isf * isfScale, 0);

  const notes = dosed.length
    ? `Corrections (n=${dosed.length}): avg BG@2h ${signed(deltaAvg)} from target → ISF ${profile.isf} → ${isf}`
    : "Not enough dosed samples.";

  return {
    isf,
    samples_used: dosed.length,
    notes,
  };
}

function clamp(n: number, lo: number, hi: number) { return Math.max(lo, Math.min(hi, n)); }
function round(n: number, d: number) { const f = 10 ** d; return Math.round(n * f) / f; }
function signed(n: number) { return (n >= 0 ? "+" : "") + n.toFixed(0); }

// Auxiliary: time-in-range over last N days. Defaults to the "tight" range
// 70-160 mg/dL used in modern T1D management literature.
export function timeInRange(bg: BgReading[], lo = 70, hi = 160): { tir: number; below: number; above: number } {
  if (!bg.length) return { tir: 0, below: 0, above: 0 };
  let inR = 0, below = 0, above = 0;
  for (const r of bg) {
    if (r.mgdl < lo) below++;
    else if (r.mgdl > hi) above++;
    else inR++;
  }
  const total = bg.length;
  return { tir: inR / total, below: below / total, above: above / total };
}

// Total daily dose (TDD) over last 24h.
export function tdd(insulin: InsulinDose[], windowMs = 24 * 3600_000): number {
  const cutoff = Date.now() - windowMs;
  return insulin.filter((d) => d.ts >= cutoff).reduce((a, d) => a + d.units, 0);
}

// ---- Statistics helpers ------------------------------------------------

// TIR over a sliding window.
export function tirWindow(
  bg: BgReading[],
  windowMs: number,
  lo = 70,
  hi = 160
): { tir: number; below: number; above: number; n: number } {
  const cutoff = Date.now() - windowMs;
  const subset = bg.filter((r) => r.ts >= cutoff);
  if (!subset.length) return { tir: 0, below: 0, above: 0, n: 0 };
  let inR = 0, below = 0, above = 0;
  for (const r of subset) {
    if (r.mgdl < lo) below++;
    else if (r.mgdl > hi) above++;
    else inR++;
  }
  const n = subset.length;
  return { tir: inR / n, below: below / n, above: above / n, n };
}

export function median(values: number[]): number {
  if (!values.length) return 0;
  const s = [...values].sort((a, b) => a - b);
  const m = Math.floor(s.length / 2);
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

export function medianBgWindow(bg: BgReading[], windowMs: number): number | null {
  const cutoff = Date.now() - windowMs;
  const vals = bg.filter((r) => r.ts >= cutoff).map((r) => r.mgdl);
  return vals.length ? median(vals) : null;
}

// Average per-bolus dose (mean of bolus units, not "per day").
export function avgBolusWindow(insulin: InsulinDose[], windowMs: number): number | null {
  const cutoff = Date.now() - windowMs;
  const vals = insulin
    .filter((d) => d.ts >= cutoff)
    .filter((d) => d.kind === "bolus" || d.kind === "correction")
    .map((d) => d.units);
  if (!vals.length) return null;
  return vals.reduce((a, b) => a + b, 0) / vals.length;
}

// Average total daily bolus over the last N days. Sums bolus + correction
// units in the window and divides by the number of days, giving "average
// daily insulin from meal/correction boluses".
export function avgDailyBolus(insulin: InsulinDose[], days = 3): { units: number; n: number } | null {
  const cutoff = Date.now() - days * 24 * 3600_000;
  const subset = insulin
    .filter((d) => d.ts >= cutoff)
    .filter((d) => d.kind === "bolus" || d.kind === "correction");
  if (!subset.length) return null;
  const total = subset.reduce((a, d) => a + d.units, 0);
  return { units: total / days, n: subset.length };
}

// Total bolus units logged today (since local midnight).
export function totalBolusToday(insulin: InsulinDose[]): { units: number; n: number } {
  const start = new Date();
  start.setHours(0, 0, 0, 0);
  const cutoff = start.getTime();
  const subset = insulin
    .filter((d) => d.ts >= cutoff)
    .filter((d) => d.kind === "bolus" || d.kind === "correction");
  return {
    units: subset.reduce((a, d) => a + d.units, 0),
    n: subset.length,
  };
}

// Convenience IOB-now wrapper
export const iobNow = totalIOB;
