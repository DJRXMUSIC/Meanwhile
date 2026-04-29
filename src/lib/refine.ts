"use client";

import type { BgReading, CarbEntry, Decision, InsulinDose, Profile } from "./types";
import { totalIOB } from "./insulin";

// Profile refinement: scan completed decisions where we have outcome BG values
// and estimate adjustments to I:C and ISF based on observed deviation from target.

export interface Outcome {
  decision_id: number;
  ts: number;
  carbs_g: number;
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
    const carbs = d.suggested_carbs_g ?? d.extracted?.carbs_g ?? 0;
    const units = d.suggested_units;
    if (carbs === 0 && units === 0) continue;
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
      carbs_g: carbs,
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
  ic_ratio: number;
  isf: number;
  samples_used: number;
  notes: string;
}

// Very conservative least-squares-style nudge:
// for meals (carbs > 15g, dose > 0): if BG@2h is consistently above/below target, nudge I:C.
// for corrections (carbs ≈ 0): nudge ISF.
export function refineProfile(profile: Profile, outcomes: Outcome[]): RefinementSuggestion {
  const meals = outcomes.filter((o) => o.carbs_g >= 15 && o.delta_to_target != null);
  const corrections = outcomes.filter((o) => o.carbs_g < 5 && o.units > 0 && o.delta_to_target != null);

  const avg = (xs: number[]) => xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : 0;

  const mealDeltaAvg = avg(meals.map((o) => o.delta_to_target!));
  const corrDeltaAvg = avg(corrections.map((o) => o.delta_to_target!));

  // If meals end +30 above target on average, I:C is too weak → lower the ratio (more insulin per carb).
  // Magnitude: scale ratio by (target / (target + delta))-ish; clamp 5–20%.
  const icScale = clamp(profile.target_bg / (profile.target_bg + mealDeltaAvg), 0.85, 1.15);
  const isfScale = clamp(profile.target_bg / (profile.target_bg + corrDeltaAvg), 0.85, 1.15);

  const ic = round(profile.ic_ratio * icScale, 1);
  const isf = round(profile.isf * isfScale, 0);

  const notes = [
    meals.length ? `Meals (n=${meals.length}): avg BG@2h ${signed(mealDeltaAvg)} from target → I:C ${profile.ic_ratio} → ${ic}` : "Not enough meal samples.",
    corrections.length ? `Corrections (n=${corrections.length}): avg BG@2h ${signed(corrDeltaAvg)} → ISF ${profile.isf} → ${isf}` : "Not enough correction samples.",
  ].join("\n");

  return {
    ic_ratio: ic,
    isf,
    samples_used: meals.length + corrections.length,
    notes,
  };
}

function clamp(n: number, lo: number, hi: number) { return Math.max(lo, Math.min(hi, n)); }
function round(n: number, d: number) { const f = 10 ** d; return Math.round(n * f) / f; }
function signed(n: number) { return (n >= 0 ? "+" : "") + n.toFixed(0); }

// Auxiliary: time-in-range over last N days
export function timeInRange(bg: BgReading[], lo = 70, hi = 180): { tir: number; below: number; above: number } {
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

// Average carbs/day
export function avgCarbsPerDay(carbs: CarbEntry[], days = 7): number {
  const cutoff = Date.now() - days * 24 * 3600_000;
  const total = carbs.filter((c) => c.ts >= cutoff).reduce((a, c) => a + c.carbs_g, 0);
  return total / days;
}

// Convenience IOB-now wrapper
export const iobNow = totalIOB;
