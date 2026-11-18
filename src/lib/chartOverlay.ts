import type { BgReading, InsulinDose } from "./types";

// Blank space after "now" on the chart, where the −24h / −48h traces carry on.
export const LOOKAHEAD_MS = 190 * 60_000;

export const DAY_MS = 24 * 3600_000;

export interface Pt { ts: number; mgdl: number }

// BG from `offsetMs` in the past, shifted forward so it lines up with the
// same wall-clock time in [from, to].
export function buildOverlay(readings: BgReading[], from: number, to: number, offsetMs: number): Pt[] {
  return readings
    .filter((r) => r.ts >= from - offsetMs && r.ts <= to - offsetMs)
    .map((r) => ({ ts: r.ts + offsetMs, mgdl: r.mgdl }))
    .sort((a, b) => a.ts - b.ts);
}

// Boluses from `offsetMs` in the past, shifted the same way.
export function overlayBoluses(doses: InsulinDose[], from: number, to: number, offsetMs: number): InsulinDose[] {
  return doses
    .filter((d) => d.kind === "bolus" && d.ts >= from - offsetMs && d.ts <= to - offsetMs)
    .map((d) => ({ ...d, ts: d.ts + offsetMs }))
    .sort((a, b) => a.ts - b.ts);
}

// BG at `ts` on a trace, interpolated between the readings either side.
export function bgAt(ts: number, pts: Pt[]): number | null {
  if (!pts.length) return null;
  let prev: Pt | null = null;
  let next: Pt | null = null;
  for (const r of pts) {
    if (r.ts <= ts) prev = r;
    if (r.ts >= ts) { next = r; break; }
  }
  if (prev && next && prev !== next) {
    const span = next.ts - prev.ts;
    if (span === 0) return prev.mgdl;
    return prev.mgdl + (next.mgdl - prev.mgdl) * ((ts - prev.ts) / span);
  }
  return prev?.mgdl ?? next?.mgdl ?? null;
}

// The point nearest in time to `t`, or null for an empty trace.
export function nearest<T extends Pt>(pts: T[], t: number): T | null {
  let best: T | null = null;
  for (const p of pts) if (!best || Math.abs(p.ts - t) < Math.abs(best.ts - t)) best = p;
  return best;
}
