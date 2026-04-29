import type { CarbEntry, InsulinDose, Profile } from "./types";

// Loop / OpenAPS / oref0 exponential IOB curve.
// Defaults match Loop's "rapid-acting adult" preset: peak 75 min, DIA 6h.
// Reference: github.com/LoopKit/LoopKit (ExponentialInsulinModel) and
// github.com/openaps/oref0 (lib/iob/calculate.js).
//
// Formula:
//   tau = peak * (1 - peak/dia) / (1 - 2*peak/dia)
//   a   = 2 * tau / dia
//   S   = 1 / (1 - a + (1+a) * exp(-dia/tau))
//   IOB(t) = 1 - S*(1-a) * ((t^2/(tau*dia*(1-a)) - t/dia - 1) * exp(-t/tau) + 1)
export function iobFraction(tMin: number, diaHours: number, peakMin = 75): number {
  if (tMin <= 0) return 1;
  const dia = diaHours * 60;
  if (tMin >= dia) return 0;
  // Guard against degenerate parameters.
  const peak = Math.max(20, Math.min(peakMin, dia * 0.45));
  const tau = (peak * (1 - peak / dia)) / (1 - (2 * peak) / dia);
  const a = (2 * tau) / dia;
  const S = 1 / (1 - a + (1 + a) * Math.exp(-dia / tau));
  const iob =
    1 -
    S * (1 - a) *
      (((tMin * tMin) / (tau * dia * (1 - a)) - tMin / dia - 1) *
        Math.exp(-tMin / tau) +
        1);
  return Math.max(0, Math.min(1, iob));
}

export function totalIOB(
  doses: InsulinDose[],
  at: number,
  diaHours: number,
  peakMin = 75
): number {
  let iob = 0;
  for (const d of doses) {
    const tMin = (at - d.ts) / 60_000;
    if (tMin < 0 || tMin > diaHours * 60) continue;
    iob += d.units * iobFraction(tMin, diaHours, peakMin);
  }
  return round(iob, 2);
}

// COB model: linear absorption over `absorption_min` (default 180).
// Fat/protein extend absorption slightly (heuristic: +6 min per 10g fat, +4 per 10g protein).
export function carbAbsorptionMinutes(c: CarbEntry): number {
  const base = c.absorption_min ?? 180;
  const fatExt = (c.fat_g ?? 0) * 0.6;
  const protExt = (c.protein_g ?? 0) * 0.4;
  return Math.min(360, base + fatExt + protExt);
}

export function totalCOB(carbs: CarbEntry[], at: number): number {
  let cob = 0;
  for (const c of carbs) {
    const tMin = (at - c.ts) / 60_000;
    if (tMin < 0) continue;
    const absMin = carbAbsorptionMinutes(c);
    if (tMin >= absMin) continue;
    const remaining = 1 - tMin / absMin;
    cob += c.carbs_g * remaining;
  }
  return round(cob, 1);
}

export interface DoseCalc {
  carbDose: number;
  correctionDose: number;
  iobOffset: number;
  total: number;
  formula: string;
}

export function suggestDose(input: {
  bg: number;
  carbs_g: number;
  iob: number;
  profile: Profile;
}): DoseCalc {
  const { bg, carbs_g, iob, profile } = input;
  const { ic_ratio, isf, target_bg } = profile;
  const carbDose = carbs_g > 0 ? carbs_g / ic_ratio : 0;
  const bgDelta = bg - target_bg;
  const correctionDose = bgDelta / isf;
  const total = round(carbDose + correctionDose - iob, 2);
  const formula =
    `total = (carbs ÷ I:C) + ((BG − target) ÷ ISF) − IOB\n` +
    `      = (${carbs_g}g ÷ ${ic_ratio}) + ((${bg} − ${target_bg}) ÷ ${isf}) − ${round(iob,2)}\n` +
    `      = ${round(carbDose,2)} + ${round(correctionDose,2)} − ${round(iob,2)}\n` +
    `      = ${total}U`;
  return {
    carbDose: round(carbDose, 2),
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
