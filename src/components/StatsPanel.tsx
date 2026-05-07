"use client";

import type { BgReading, InsulinDose } from "@/lib/types";
import {
  tirWindow,
  medianBgWindow,
  avgBolusWindow,
  totalBolusToday,
} from "@/lib/refine";

const DAY = 24 * 3600_000;

export function StatsPanel({
  bgList,
  insulinList,
}: {
  bgList: BgReading[];
  insulinList: InsulinDose[];
}) {
  const tir24 = tirWindow(bgList, 1 * DAY, 70, 160);
  const tir72 = tirWindow(bgList, 3 * DAY, 70, 160);
  const median3d = medianBgWindow(bgList, 3 * DAY);
  const avgBolus3d = avgBolusWindow(insulinList, 3 * DAY);
  const today = totalBolusToday(insulinList);

  return (
    <section className="mx-3 rounded-2xl bg-surface p-3 ring-1 ring-white/5">
      <div className="flex items-baseline justify-between mb-2 px-1">
        <div className="text-xs uppercase tracking-wider text-muted">Statistics</div>
        <div className="text-[10px] text-muted">target 70–160</div>
      </div>
      <div className="grid grid-cols-2 gap-2">
        <Tile
          label="TIR · 24h"
          value={tir24.n ? `${(tir24.tir * 100).toFixed(0)}%` : "—"}
          hint={tir24.n ? `${tir24.n} pts` : "no data"}
        />
        <Tile
          label="TIR · 3d"
          value={tir72.n ? `${(tir72.tir * 100).toFixed(0)}%` : "—"}
          hint={tir72.n ? `${tir72.n} pts` : "no data"}
        />
        <Tile
          label="Median BG · 3d"
          value={median3d != null ? `${Math.round(median3d)}` : "—"}
          hint="mg/dL"
        />
        <Tile
          label="Avg bolus · 3d"
          value={avgBolus3d != null ? `${avgBolus3d.toFixed(1)}` : "—"}
          hint="U / dose"
        />
        <Tile
          label="Total bolus · today"
          value={`${today.units.toFixed(1)}`}
          hint={`${today.n} dose${today.n === 1 ? "" : "s"}`}
          wide
        />
      </div>
    </section>
  );
}

function Tile({
  label,
  value,
  hint,
  wide,
}: {
  label: string;
  value: string;
  hint?: string;
  wide?: boolean;
}) {
  return (
    <div className={`rounded-xl bg-surface2/60 p-3 ring-1 ring-white/5 ${wide ? "col-span-2" : ""}`}>
      <div className="text-[10px] uppercase tracking-wider text-muted">{label}</div>
      <div className="num text-2xl font-semibold mt-0.5 text-ink">{value}</div>
      {hint && <div className="text-[11px] text-muted">{hint}</div>}
    </div>
  );
}
