"use client";

import type { BgReading, InsulinDose } from "@/lib/types";
import { captureStats, dedupeReadings, withEstimates, type Capture, type Estimate } from "@/lib/gapFill";
import {
  tirWindow,
  medianBgWindow,
  avgBolusWindow,
  avgDailyBolus,
  totalBolusToday,
} from "@/lib/refine";

const DAY = 24 * 3600_000;

export function StatsPanel({
  bgList,
  estimates,
  insulinList,
  now,
}: {
  bgList: BgReading[];
  estimates: Estimate[];
  insulinList: InsulinDose[];
  now: number;
}) {
  // missed readings count as their estimates, so a gap doesn't skew the numbers
  const all = withEstimates(dedupeReadings(bgList), estimates);
  const tir24 = tirWindow(all, 1 * DAY, 70, 160);
  const tir72 = tirWindow(all, 3 * DAY, 70, 160);
  const median3d = medianBgWindow(all, 3 * DAY);
  const cap24 = captureStats(bgList, estimates, 1 * DAY, now);
  const cap72 = captureStats(bgList, estimates, 3 * DAY, now);
  const est = (w: number) => all.filter((r) => r.estimated && r.ts >= Date.now() - w).length;
  const pts = (n: number, w: number) => `${n} pts${est(w) ? ` · ${est(w)} est.` : ""}`;
  const avgBolus3d = avgBolusWindow(insulinList, 3 * DAY);
  const dailyBolus3d = avgDailyBolus(insulinList, 3);
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
          hint={tir24.n ? pts(tir24.n, DAY) : "no data"}
        />
        <Tile
          label="TIR · 3d"
          value={tir72.n ? `${(tir72.tir * 100).toFixed(0)}%` : "—"}
          hint={tir72.n ? pts(tir72.n, 3 * DAY) : "no data"}
        />
        <CaptureTile day={cap24} threeDays={cap72} />
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
          label="Avg daily bolus · 3d"
          value={dailyBolus3d != null ? `${dailyBolus3d.units.toFixed(1)}` : "—"}
          hint={dailyBolus3d != null ? `U / day · ${dailyBolus3d.n} dose${dailyBolus3d.n === 1 ? "" : "s"}` : "no data"}
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

// Readings caught vs estimated vs missing, as a split bar with the counts beside it.
function CaptureTile({ day, threeDays }: { day: Capture; threeDays: Capture }) {
  const pct = (n: number, c: Capture) => (c.expected ? Math.round((n / c.expected) * 100) : 0);
  const parts = [
    { n: day.caught, label: "caught", style: { background: "rgb(var(--g-in))" } },
    { n: day.estimated, label: "estimated", style: { background: "rgb(var(--g-in) / 0.35)" } },
    { n: day.missing, label: "missing", style: { background: "rgb(var(--c-ink) / 0.12)" } },
  ];
  return (
    <div className="col-span-2 rounded-xl bg-surface2/60 p-3 ring-1 ring-white/5">
      <div className="flex items-baseline justify-between">
        <div className="text-[10px] uppercase tracking-wider text-muted">Readings caught · 24h</div>
        <div className="text-[11px] text-muted">3d {pct(threeDays.caught, threeDays)}%</div>
      </div>
      <div className="num text-2xl font-semibold mt-0.5 text-ink">{pct(day.caught, day)}%</div>
      <div className="mt-2 flex h-2 gap-0.5 overflow-hidden rounded-full" aria-hidden>
        {parts.filter((p) => p.n > 0).map((p) => (
          <div key={p.label} style={{ ...p.style, flexGrow: p.n }} />
        ))}
      </div>
      <div className="mt-1.5 flex flex-wrap gap-x-3 text-[11px] text-muted">
        {parts.map((p) => (
          <span key={p.label} className="flex items-center gap-1">
            <span className="inline-block size-2 rounded-full" style={p.style} />
            <span className="num text-ink">{p.n}</span> {p.label}
          </span>
        ))}
      </div>
    </div>
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
