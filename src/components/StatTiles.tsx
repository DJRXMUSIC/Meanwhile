"use client";

import type { BgReading } from "@/lib/types";
import { bgClass } from "@/lib/insulin";
import { TrendArrow } from "./BGTrend";

export function StatTiles({ bg, iob, cob }: { bg?: BgReading; iob: number; cob: number }) {
  const cls = bg ? bgClass(bg.mgdl) : "in-range";
  const color =
    cls === "very-low" || cls === "low" ? "text-bad" :
    cls === "high" ? "text-warn" :
    cls === "very-high" ? "text-bad" : "text-good";

  const ageMin = bg ? Math.floor((Date.now() - bg.ts) / 60_000) : null;
  const stale = ageMin != null && ageMin > 10;

  return (
    <div className="grid grid-cols-3 gap-2 px-4">
      <div className="glass rounded-2xl p-4 col-span-2">
        <div className="text-xs uppercase tracking-wider text-muted">Blood Glucose</div>
        <div className="mt-1 flex items-baseline gap-3">
          <div className={`num text-5xl font-semibold ${color}`}>{bg ? bg.mgdl : "—"}</div>
          <div className="text-2xl text-muted"><TrendArrow trend={bg?.trend} /></div>
          {bg?.delta != null && (
            <div className="num text-sm text-muted">{bg.delta > 0 ? "+" : ""}{bg.delta}</div>
          )}
        </div>
        <div className="mt-1 text-xs text-muted">
          mg/dL {ageMin != null ? `· ${ageMin}m ago` : ""} {stale ? "· stale" : ""}
        </div>
      </div>
      <div className="glass rounded-2xl p-4">
        <div className="text-xs uppercase tracking-wider text-muted">IOB</div>
        <div className="num text-3xl font-semibold mt-1">{iob.toFixed(2)}<span className="text-base text-muted ml-1">U</span></div>
        <div className="mt-2 text-xs uppercase tracking-wider text-muted">COB</div>
        <div className="num text-2xl font-medium">{cob.toFixed(0)}<span className="text-sm text-muted ml-1">g</span></div>
      </div>
    </div>
  );
}
