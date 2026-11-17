// When the next Eversense reading is due, from the time of the last one.
//
// Eversense sends a reading every 5 minutes, so readings are due at
// last + 5, last + 10, … minutes. If one doesn't come, the next is still on
// the same 5-minute beat. Shared by the countdown on the BG tile and by the
// poller, which checks for the reading as it falls due.

export const READING_INTERVAL_MS = 5 * 60_000;

// A reading counts as "due now" for this long after its slot. The poller
// checks every few seconds over the same window; after it, the reading is
// treated as missed and the next slot counted down to.
export const DUE_WINDOW_MS = 2 * 60_000;

export type NextReading =
  | { due: false; at: number }   // next reading expected at `at`
  | { due: true; at: number };   // the reading for slot `at` is due now

export function nextReading(lastTs: number, now: number): NextReading {
  const first = lastTs + READING_INTERVAL_MS;
  if (now < first) return { due: false, at: first };
  // the latest slot at or before now
  const slot = first + Math.floor((now - first) / READING_INTERVAL_MS) * READING_INTERVAL_MS;
  if (now - slot < DUE_WINDOW_MS) return { due: true, at: slot };
  return { due: false, at: slot + READING_INTERVAL_MS };
}

// "4:07" for a positive span of milliseconds, rounded up to the second.
export function formatCountdown(ms: number): string {
  const s = Math.max(0, Math.ceil(ms / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}
