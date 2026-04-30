"use client";

import { useEffect, useMemo, useState } from "react";
import type { InsulinDose } from "@/lib/types";

const WINDOW_MIN = 45;

// Pre-bolus timer. After a bolus is logged, the home screen shows how
// many minutes have elapsed since the most recent bolus for 45 minutes,
// then disappears. The clock is based on the dose's administration time
// (`ts`), not when it was logged — so backdated entries show their
// actual elapsed time, which is what matters for pre-bolus timing.
export function PreBolusTimer({ doses }: { doses: InsulinDose[] }) {
  const [nowTs, setNowTs] = useState<number>(() => Date.now());

  const lastBolus = useMemo(() => {
    let best: InsulinDose | null = null;
    for (const d of doses) {
      if (d.kind !== "bolus" && d.kind !== "correction") continue;
      if (!best || d.ts > best.ts) best = d;
    }
    return best;
  }, [doses]);

  const elapsedMin = lastBolus ? Math.floor((nowTs - lastBolus.ts) / 60_000) : -1;
  const visible = !!lastBolus && elapsedMin >= 0 && elapsedMin < WINDOW_MIN;

  useEffect(() => {
    if (!visible) return;
    const id = setInterval(() => setNowTs(Date.now()), 15_000);
    return () => clearInterval(id);
  }, [visible]);

  if (!lastBolus || !visible) return null;

  const remainingMin = WINDOW_MIN - elapsedMin;
  const progress = Math.min(1, elapsedMin / WINDOW_MIN);
  const adminClock = new Date(lastBolus.ts).toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit",
  });

  return (
    <section className="mx-3 rounded-2xl bg-surface p-3 ring-1 ring-accent/40">
      <div className="flex items-baseline justify-between gap-3">
        <div className="min-w-0">
          <div className="text-[10px] uppercase tracking-wider text-muted">Pre-bolus timer</div>
          <div className="mt-0.5 flex items-baseline gap-2">
            <span className="num text-3xl font-semibold text-accent leading-none">{elapsedMin}</span>
            <span className="text-base text-muted">min since {lastBolus.units}U at {adminClock}</span>
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
