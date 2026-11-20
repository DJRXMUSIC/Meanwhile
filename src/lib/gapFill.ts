import type { BgReading } from "./types";

// Estimated readings for the gaps between real ones, so the chart can show
// where BG most likely went and the stats aren't skewed by missing stretches.
// Estimates are never stored: they're worked out from the real readings each
// time, and only used for the chart and the statistics.

export const SLOT_MS = 5 * 60_000;
/** Two real readings further apart than this have at least one missing reading between them. */
export const MISSED_AFTER_MS = 1.5 * SLOT_MS;
/** Gaps longer than this aren't guessed at: they stay empty and count as missing. */
export const MAX_FILL_MS = 2 * 3600_000;
/** BG can't plausibly move faster than this (mg/dL per minute). */
const MAX_RATE = 3 / 60_000;

export interface Estimate { ts: number; mgdl: number }

// Rate of change at a real reading, from a reading 10–30 minutes away on the given side.
function rateAt(sorted: BgReading[], i: number, dir: -1 | 1): number | null {
  const a = sorted[i];
  for (let j = i + dir; j >= 0 && j < sorted.length; j += dir) {
    const dt = Math.abs(sorted[j].ts - a.ts);
    if (dt > 30 * 60_000) return null;
    if (dt >= 10 * 60_000) return (a.mgdl - sorted[j].mgdl) / (a.ts - sorted[j].ts);
  }
  return null;
}

/**
 * Estimated readings, one per missing 5-minute slot, for every gap of up to
 * MAX_FILL_MS between two real readings. The curve leaves and enters the gap
 * along BG's trend at each end (cubic Hermite), bending less the longer the
 * gap, so it reads as a plausible path rather than a straight ruler line.
 */
export function fillGaps(readings: BgReading[], maxFillMs = MAX_FILL_MS): Estimate[] {
  const sorted = [...readings].sort((a, b) => a.ts - b.ts);
  const out: Estimate[] = [];
  for (let i = 0; i + 1 < sorted.length; i++) {
    const a = sorted[i];
    const b = sorted[i + 1];
    const gap = b.ts - a.ts;
    if (gap <= MISSED_AFTER_MS || gap > maxFillMs) continue;
    const secant = (b.mgdl - a.mgdl) / gap;
    // trend at each end, damped towards the straight line for long gaps
    const damp = Math.min(1, (45 * 60_000) / gap);
    const clamp = (m: number) => Math.max(-MAX_RATE, Math.min(MAX_RATE, m));
    const m0 = clamp(secant + ((rateAt(sorted, i, -1) ?? secant) - secant) * damp);
    const m1 = clamp(secant + ((rateAt(sorted, i + 1, 1) ?? secant) - secant) * damp);
    const slots = Math.round(gap / SLOT_MS) - 1;
    for (let k = 1; k <= slots; k++) {
      const s = k / (slots + 1);
      const ts = a.ts + s * gap;
      const h00 = 2 * s ** 3 - 3 * s ** 2 + 1;
      const h10 = s ** 3 - 2 * s ** 2 + s;
      const h01 = -2 * s ** 3 + 3 * s ** 2;
      const h11 = s ** 3 - s ** 2;
      const v = h00 * a.mgdl + h10 * gap * m0 + h01 * b.mgdl + h11 * gap * m1;
      out.push({ ts: Math.round(ts), mgdl: Math.round(Math.max(40, Math.min(400, v))) });
    }
  }
  return out;
}

export interface Capture {
  expected: number;   // 5-minute readings the window should hold
  caught: number;     // real readings
  estimated: number;  // filled in
  missing: number;    // neither
}

/** Sensor readings closer together than this are the same reading stored twice. */
export const SAME_READING_MS = 2 * 60_000;

/**
 * One row per sensor reading. Sensor readings within SAME_READING_MS of the
 * first of a run are one reading, and the newest of them is kept: the Eversense
 * app re-posts its last value just before a new one arrives, so the later row
 * is the real update. Copies brought in twice by a restore collapse the same
 * way. Fingersticks are kept as they are.
 */
export function dedupeReadings(readings: BgReading[]): BgReading[] {
  const sorted = [...readings].sort((a, b) => a.ts - b.ts);
  const out: BgReading[] = [];
  let runStart = -Infinity;
  let lastSensor = -1; // index in out
  for (const r of sorted) {
    if (r.source === "manual") { out.push(r); continue; }
    if (lastSensor >= 0 && r.ts - runStart < SAME_READING_MS) { out[lastSensor] = r; continue; }
    runStart = r.ts;
    lastSensor = out.length;
    out.push(r);
  }
  return out;
}

/**
 * How many sensor readings in [now − windowMs, now] were caught, estimated, or
 * missing. Each reading counts once, however many times it was stored.
 */
export function captureStats(readings: BgReading[], estimates: Estimate[], windowMs: number, now: number): Capture {
  const from = now - windowMs;
  const expected = Math.floor(windowMs / SLOT_MS);
  const sensor = dedupeReadings(readings).filter((r) => r.source !== "manual");
  const caught = Math.min(expected, sensor.filter((r) => r.ts > from && r.ts <= now).length);
  const estimated = Math.min(expected - caught, estimates.filter((e) => e.ts > from && e.ts <= now).length);
  return { expected, caught, estimated, missing: expected - caught - estimated };
}

/** Real readings plus estimates, for the statistics: each counts as one 5-minute reading. */
export function withEstimates(readings: BgReading[], estimates: Estimate[]): BgReading[] {
  const filled: BgReading[] = estimates.map((e) => ({ ts: e.ts, mgdl: e.mgdl, trend: "Flat", source: "xdrip", estimated: true }));
  return [...readings, ...filled].sort((a, b) => a.ts - b.ts);
}
