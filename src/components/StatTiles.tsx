"use client";

import type { BgReading } from "@/lib/types";
import { bgClass } from "@/lib/insulin";
import { TrendArrow } from "./BGTrend";

export function StatTiles({
  bg,
  iob,
  cob,
  onRefresh,
  syncing = false,
  refreshNote,
}: {
  bg?: BgReading;
  iob: number;
  cob: number;
  /** Omitted when no xDrip+ URL is configured — nothing to refresh from. */
  onRefresh?: () => void;
  syncing?: boolean;
  refreshNote?: string | null;
}) {
  const cls = bg ? bgClass(bg.mgdl) : "in-range";
  const color =
    cls === "very-low" || cls === "low" ? "text-bad" :
    cls === "high" ? "text-warn" :
    cls === "very-high" ? "text-bad" : "text-good";

  const ageMin = bg ? Math.floor((Date.now() - bg.ts) / 60_000) : null;
  const stale = ageMin != null && ageMin > 10;

  return (
    <div className="grid grid-cols-3 gap-2 px-3">
      <div className="glass rounded-2xl p-4 col-span-2">
        <div className="flex items-start justify-between gap-2">
          <div className="text-xs uppercase tracking-wider text-muted">Blood Glucose</div>
          {onRefresh && (
            <button
              onClick={onRefresh}
              disabled={syncing}
              aria-label="Refresh glucose from xDrip+"
              title="Refresh from xDrip+"
              className="-mt-1.5 -mr-1.5 size-8 shrink-0 grid place-items-center rounded-full text-muted hover:text-ink active:scale-90 transition disabled:opacity-40"
            >
              <span className={`text-base leading-none ${syncing ? "motion-safe:animate-spin" : ""}`}>⟳</span>
            </button>
          )}
        </div>
        <div className="mt-1 flex items-baseline gap-3 flex-wrap">
          <div className={`num text-5xl font-semibold ${color}`}>{bg ? bg.mgdl : "—"}</div>
          <div className="text-2xl text-muted"><TrendArrow trend={bg?.trend} /></div>
          {bg?.delta != null && (
            <div className="num text-sm text-muted">{bg.delta > 0 ? "+" : ""}{bg.delta}</div>
          )}
        </div>
        <div className="mt-1 text-xs text-muted">
          mg/dL {ageMin != null ? `· ${ageMin}m ago` : ""} {stale ? "· stale" : ""}
          {syncing ? " · checking…" : refreshNote ? ` · ${refreshNote}` : ""}
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
