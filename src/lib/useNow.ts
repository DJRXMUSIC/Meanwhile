"use client";

import { useEffect, useState } from "react";

// One shared clock for the whole app.
//
// Several places need "what time is it now?" to stay fresh: the IOB tile
// decays continuously, the pre-bolus timer counts minutes since the last
// dose, the daily basal reminder becomes due at a wall-clock time, and the
// chart's right edge is "now". Each of those used to read Date.now()
// during render and/or run its own interval, which meant any memo whose
// dependencies included a derived timestamp changed on every render and
// could never be cached.
//
// Instead: a single interval drives every subscriber, and each subscriber
// asks for the granularity it actually needs. A component re-renders only
// when its own bucket rolls over, so the 5-minute database query window
// doesn't re-render at the cadence the pre-bolus timer wants.
//
// visibilitychange / focus / pageshow force an immediate catch-up when the
// PWA returns to the foreground, since background intervals are throttled
// or suspended outright.

const TICK_MS = 15_000;

const subscribers = new Set<() => void>();
let timer: ReturnType<typeof setInterval> | null = null;

function fire() {
  for (const notify of subscribers) notify();
}

function start() {
  if (timer != null || typeof window === "undefined") return;
  timer = setInterval(fire, TICK_MS);
  document.addEventListener("visibilitychange", fire);
  window.addEventListener("focus", fire);
  window.addEventListener("pageshow", fire);
}

function stop() {
  if (timer == null) return;
  clearInterval(timer);
  timer = null;
  document.removeEventListener("visibilitychange", fire);
  window.removeEventListener("focus", fire);
  window.removeEventListener("pageshow", fire);
}

// Floor a timestamp to a granularity. Exported for callers deriving a
// coarser value from `now` where an *earlier* boundary is what's wanted —
// the lower edge of a database query window, for instance.
export function bucket(ts: number, granularityMs: number): number {
  return Math.floor(ts / granularityMs) * granularityMs;
}

// Ceiling, not floor. A quantized clock that rounded down would sit up to
// `granularityMs` in the past, and everything consuming it treats data
// timestamped after "now" as invalid: totalIOB and totalCOB skip rows with
// a negative elapsed time, and the chart clips anything past its right
// edge. A dose logged this instant would then be missing from the IOB tile
// and off the chart until the clock caught up. Rounding up keeps `now` at
// or ahead of the true time, so freshly logged rows always count.
function ceilTo(ts: number, granularityMs: number): number {
  return Math.ceil(ts / granularityMs) * granularityMs;
}

// Returns the current time, rounded up to `granularityMs`, advancing on the
// shared tick. Stable across renders within a bucket, so it is safe to use
// as a memo or query dependency.
export function useNow(granularityMs: number = TICK_MS): number {
  const [now, setNow] = useState(() => ceilTo(Date.now(), granularityMs));

  useEffect(() => {
    const update = () => {
      const next = ceilTo(Date.now(), granularityMs);
      setNow((prev) => (prev === next ? prev : next));
    };
    // Catch up immediately: on mount, and whenever the granularity changes.
    update();
    subscribers.add(update);
    start();
    return () => {
      subscribers.delete(update);
      if (subscribers.size === 0) stop();
    };
  }, [granularityMs]);

  return now;
}
