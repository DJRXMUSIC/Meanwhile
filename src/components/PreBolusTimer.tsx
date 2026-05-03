"use client";

import { useEffect, useMemo, useState } from "react";
import type { InsulinDose } from "@/lib/types";

const WINDOW_MIN = 45;
const TICK_MS = 20_000;

// Pre-bolus timer. After a bolus is logged, the home screen shows how
// many minutes have elapsed since the most recent dose for 45 minutes,
// then disappears. Reliability notes:
//
// - We always read Date.now() at render time (no cached "now" in state)
//   so the elapsed value is correct even if the page was backgrounded
//   and the interval throttled.
// - A tick counter forces re-renders every 20s while the card is
//   visible so the minute number updates in the foreground.
// - visibilitychange / focus / pageshow listeners force an immediate
//   recompute when the PWA comes back to the foreground.
// - All insulin kinds are considered when locating "the last dose" —
//   any legacy rows logged as "basal" before the basal→bolus rename
//   still trigger the timer.
// - Uses dose.ts (administration time) so backdated entries reflect
//   real elapsed time.
export function PreBolusTimer({ doses }: { doses: InsulinDose[] }) {
  const [, setTick] = useState(0);
  const bump = () => setTick((t) => (t + 1) | 0);

  // Only meal/correction bolus triggers a pre-bolus timer — once-daily
  // long-acting basal isn't relevant to meal timing.
  const lastDose = useMemo(() => {
    let best: InsulinDose | null = null;
    for (const d of doses) {
      if (!d || typeof d.ts !== "number") continue;
      if (d.kind !== "bolus" && d.kind !== "correction") continue;
      if (!best || d.ts > best.ts) best = d;
    }
    return best;
  }, [doses]);

  // Recomputed every render — no stale state.
  const now = Date.now();
  const elapsedMin = lastDose ? Math.floor((now - lastDose.ts) / 60_000) : -1;
  const visible = !!lastDose && elapsedMin >= 0 && elapsedMin < WINDOW_MIN;

  // Foreground tick.
  useEffect(() => {
    if (!visible) return;
    const id = setInterval(bump, TICK_MS);
    return () => clearInterval(id);
  }, [visible]);

  // Wake / focus / BFCache restore — recompute immediately.
  useEffect(() => {
    if (typeof document === "undefined") return;
    const onWake = () => bump();
    document.addEventListener("visibilitychange", onWake);
    window.addEventListener("focus", onWake);
    window.addEventListener("pageshow", onWake);
    return () => {
      document.removeEventListener("visibilitychange", onWake);
      window.removeEventListener("focus", onWake);
      window.removeEventListener("pageshow", onWake);
    };
  }, []);

  if (!lastDose || !visible) return null;

  const remainingMin = Math.max(0, WINDOW_MIN - elapsedMin);
  const progress = Math.min(1, elapsedMin / WINDOW_MIN);
  const adminClock = new Date(lastDose.ts).toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit",
  });

  return (
    <section className="mx-3 rounded-2xl bg-surface p-3 ring-1 ring-accent/40">
      <div className="flex items-baseline justify-between gap-3">
        <div className="min-w-0">
          <div className="text-[10px] uppercase tracking-wider text-muted">Pre-bolus timer</div>
          <div className="mt-0.5 flex items-baseline gap-2 flex-wrap">
            <span className="num text-3xl font-semibold text-accent leading-none">{elapsedMin}</span>
            <span className="text-base text-muted">min since {lastDose.units}U at {adminClock}</span>
          </div>
        </div>
        <div className="text-right shrink-0">
          <div className="num text-sm text-muted">{remainingMin}m</div>
          <div className="text-[10px] text-muted">remaining</div>
        </div>
      </div>
      <div className="mt-2 h-1.5 rounded-full bg-surface2 overflow-hidden">
        <div
          className="h-full bg-accent transition-[width] duration-300"
          style={{ width: `${progress * 100}%` }}
        />
      </div>
    </section>
  );
}
