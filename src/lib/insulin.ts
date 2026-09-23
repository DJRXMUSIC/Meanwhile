import type { InsulinDose, Profile } from "./types";

// Loop / OpenAPS / oref0 exponential IOB curve.
// Defaults match Loop's "rapid-acting adult" preset: peak 75 min, DIA 6h,
// with an oref0-style absorption delay so IOB stays at 100% during the
// first `delayMin` minutes (insulin in subcutaneous tissue, not yet
// metabolically active). After the delay the exponential takes over with
// the clock effectively shifted by `delayMin`.
//
// Reference: github.com/LoopKit/LoopKit (ExponentialInsulinModel) and
// github.com/openaps/oref0 (lib/iob/calculate.js, `delay` parameter).
//
// Formula (after the delay):
//   tau = peak * (1 - peak/dia) / (1 - 2*peak/dia)
//   a   = 2 * tau / dia
//   S   = 1 / (1 - a + (1+a) * exp(-dia/tau))
//   IOB(t) = 1 - S*(1-a) * ((t^2/(tau*dia*(1-a)) - t/dia - 1) * exp(-t/tau) + 1)
export function iobFraction(
  tMin: number,
  diaHours: number,
  peakMin = 75,
  delayMin = 15
): number {
  if (tMin <= 0) return 1;
  if (tMin <= delayMin) return 1;
  const t = tMin - delayMin; // shift past the absorption lag
  const dia = diaHours * 60;
  if (t >= dia) return 0;
  // Guard against degenerate parameters.
  const peak = Math.max(20, Math.min(peakMin, dia * 0.45));
  const tau = (peak * (1 - peak / dia)) / (1 - (2 * peak) / dia);
  const a = (2 * tau) / dia;
  const S = 1 / (1 - a + (1 + a) * Math.exp(-dia / tau));
  const iob =
    1 -
    S * (1 - a) *
      (((t * t) / (tau * dia * (1 - a)) - t / dia - 1) * Math.exp(-t / tau) + 1);
  return Math.max(0, Math.min(1, iob));
}

// Rapid-acting = anything that isn't long-acting basal. Tested this way
// round rather than against "bolus" so rows carrying a legacy kind — a
// pre-v3 `correction`, or one synced from a device still on the old
// build — keep counting toward IOB instead of silently dropping out.
export function isRapidActing(d: InsulinDose): boolean {
  return d.kind !== "basal";
}

// Sum rapid-acting IOB only. Long-acting basal is intentionally excluded
// because its kinetics are entirely different (24h+ depot, near
// steady-state once equilibrated) and lumping it through the same
// rapid-acting curve would double-count and mislead the IOB tile.
export function totalIOB(
  doses: InsulinDose[],
  at: number,
  diaHours: number,
  peakMin = 75,
  delayMin = 15
): number {
  let iob = 0;
  for (const d of doses) {
    if (!isRapidActing(d)) continue;
    const tMin = (at - d.ts) / 60_000;
    if (tMin < 0 || tMin > delayMin + diaHours * 60) continue;
    iob += d.units * iobFraction(tMin, diaHours, peakMin, delayMin);
  }
  return round(iob, 2);
}

export interface DoseCalc {
  correctionDose: number;
  iobOffset: number;
  total: number;
  formula: string;
}

// Correction-only dosing. With carb tracking removed there is no meal
// component left to add, so the suggestion is purely "how far is BG from
// target, minus what is already on board".
export function suggestDose(input: {
  bg: number;
  iob: number;
  profile: Profile;
}): DoseCalc {
  const { bg, iob, profile } = input;
  const { isf, target_bg } = profile;
  const bgDelta = bg - target_bg;
  const correctionDose = bgDelta / isf;
  const total = round(correctionDose - iob, 2);
  const formula =
    `total = ((BG − target) ÷ ISF) − IOB\n` +
    `      = ((${bg} − ${target_bg}) ÷ ${isf}) − ${round(iob,2)}\n` +
    `      = ${round(correctionDose,2)} − ${round(iob,2)}\n` +
    `      = ${total}U`;
  return {
    correctionDose: round(correctionDose, 2),
    iobOffset: round(iob, 2),
    total,
    formula,
  };
}

export function trendDelta(curr: number | undefined, prev: number | undefined): number | undefined {
  if (curr == null || prev == null) return undefined;
  return curr - prev;
}

export function bgClass(mgdl: number): "low" | "in-range" | "high" | "very-high" | "very-low" {
  if (mgdl < 55) return "very-low";
  if (mgdl < 70) return "low";
  if (mgdl <= 180) return "in-range";
  if (mgdl <= 250) return "high";
  return "very-high";
}

function round(n: number, digits = 2): number {
  const f = 10 ** digits;
  return Math.round(n * f) / f;
}
