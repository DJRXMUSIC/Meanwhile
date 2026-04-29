import type { CarbEntry, InsulinDose, Profile } from "./types";

// Bilinear IOB curve — simple, transparent, widely used in DIY loop projects.
// Returns the fraction of a dose still active at `tMin` minutes after delivery.
export function iobFraction(tMin: number, diaHours: number): number {
  if (tMin <= 0) return 1;
  const dia = diaHours * 60;
  if (tMin >= dia) return 0;
  const peak = dia * 0.30; // ~75 min for DIA=4h
  if (tMin <= peak) {
    return 1 - 0.5 * (tMin / peak);
  }
  // remaining 50% decays linearly from peak to dia
  const remaining = (dia - tMin) / (dia - peak);
  return 0.5 * remaining;
}

export function totalIOB(doses: InsulinDose[], at: number, diaHours: number): number {
  let iob = 0;
  for (const d of doses) {
    const tMin = (at - d.ts) / 60_000;
    if (tMin < 0 || tMin > diaHours * 60) continue;
    iob += d.units * iobFraction(tMin, diaHours);
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
