"use client";

import { useMemo, useRef, useState } from "react";
import type { BgReading, InsulinDose } from "@/lib/types";

// Five-zone bolus chart with a piecewise-linear y-axis:
//   • 40–70   → 10% of plot height  (compressed; we rarely live there)
//   • 70–250  → 75% of plot height  (focus zone)
//   • 250–350 → 15% of plot height  (compressed; out-of-range high)
// Compression is honest — gridlines are drawn at every reference value
// so the visual squash is plainly visible. Data points always land on
// the geometrically correct y for their value.
//
// X-axis: configurable window (3/5/8/24h) plus pannable history via
// pointer drag (touch and mouse). When the user drags into the past,
// the "now" line is hidden and a "viewing N h ago" caption appears.

export type WindowHours = 3 | 5 | 8 | 24;

interface Props {
  readings: BgReading[];
  doses: InsulinDose[];
  windowHours: WindowHours;
  panMs: number;                 // how far back from "now" the right edge sits
  onPanChange: (next: number) => void;
  targetLow?: number;            // band shading
  targetHigh?: number;
}

export function Chart5h({
  readings,
  doses,
  windowHours,
  panMs,
  onPanChange,
  targetLow = 70,
  targetHigh = 160,
}: Props) {
  const view = { w: 1000, h: 460 };
  const pad = { l: 44, r: 14, t: 18, b: 36 };
  const plotH = view.h - pad.t - pad.b;
  const plotW = view.w - pad.l - pad.r;

  // Y-axis: piecewise linear over [40, 350] with breakpoints at 70 and 250.
  const lowFrac = 0.10, midFrac = 0.75, highFrac = 0.15;
  const lowH = plotH * lowFrac;
  const midH = plotH * midFrac;
  const highH = plotH * highFrac;

  const yOf = (mgdl: number): number => {
    const v = Math.max(40, Math.min(350, mgdl));
    if (v >= 250) return pad.t + highH * (1 - (350 - v) / 100);
    if (v >= 70)  return pad.t + highH + midH * ((250 - v) / 180);
    return pad.t + highH + midH + lowH * ((70 - v) / 30);
  };

  // X window (rightmost edge = now − panMs).
  const now = Date.now();
  const maxT = now - panMs;
  const minT = maxT - windowHours * 3600_000;
  const xOf = (t: number) => pad.l + ((t - minT) / (maxT - minT)) * plotW;

  const bgInWin = useMemo(
    () => readings.filter((r) => r.ts >= minT && r.ts <= maxT).sort((a, b) => a.ts - b.ts),
    [readings, minT, maxT]
  );
  const dosesInWin = useMemo(
    () =>
      doses
        .filter((d) => d.kind === "bolus" || d.kind === "correction" || d.kind === "basal")
        .filter((d) => d.ts >= minT && d.ts <= maxT)
        .sort((a, b) => a.ts - b.ts),
    [doses, minT, maxT]
  );

  // BG path
  const bgPath = bgInWin.length
    ? bgInWin
        .map((r, i) => `${i === 0 ? "M" : "L"} ${xOf(r.ts).toFixed(1)} ${yOf(r.mgdl).toFixed(1)}`)
        .join(" ")
    : "";

  // Linear interpolation of BG at a given timestamp, for placing dose
  // markers on the BG line itself.
  const bgAt = (ts: number, allBg: BgReading[]): number | null => {
    if (!allBg.length) return null;
    let prev: BgReading | null = null;
    let next: BgReading | null = null;
    for (const r of allBg) {
      if (r.ts <= ts) prev = r;
      if (r.ts >= ts && !next) { next = r; break; }
    }
    if (prev && next && prev !== next) {
      const span = next.ts - prev.ts;
      if (span === 0) return prev.mgdl;
      const frac = (ts - prev.ts) / span;
      return prev.mgdl + (next.mgdl - prev.mgdl) * frac;
    }
    return prev?.mgdl ?? next?.mgdl ?? null;
  };

  // Hour gridlines: pick spacing based on window
  const labelEveryMin =
    windowHours <= 3 ? 30 :
    windowHours <= 5 ? 60 :
    windowHours <= 8 ? 60 :
    240;
  const tickEveryMin =
    windowHours <= 3 ? 15 :
    windowHours <= 5 ? 30 :
    windowHours <= 8 ? 60 :
    120;

  const tickFloor = Math.floor(minT / (tickEveryMin * 60_000)) * tickEveryMin * 60_000;
  const ticks: number[] = [];
  for (let t = tickFloor; t <= maxT; t += tickEveryMin * 60_000) {
    if (t >= minT) ticks.push(t);
  }
  const labels = ticks.filter((t) => Math.round((t / 60_000) % labelEveryMin) === 0);

  // Y reference lines: every 50, plus the target band edges.
  const yValues = [40, 55, 70, 100, 130, 160, 200, 250, 300, 350].filter((v) => v >= 40 && v <= 350);

  // ---- Pan via pointer drag ---------------------------------------------
  const containerRef = useRef<HTMLDivElement>(null);
  const dragRef = useRef<{ startX: number; startPan: number; pid: number } | null>(null);
  const [dragging, setDragging] = useState(false);

  const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (e.pointerType === "mouse" && e.button !== 0) return;
    e.currentTarget.setPointerCapture(e.pointerId);
    dragRef.current = { startX: e.clientX, startPan: panMs, pid: e.pointerId };
    setDragging(true);
  };
  const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!dragRef.current || !containerRef.current) return;
    const dx = e.clientX - dragRef.current.startX;
    const w = containerRef.current.clientWidth || 1;
    const windowMs = windowHours * 3600_000;
    // Drag the content rightward → reveal older data on the left → increase panMs.
    const deltaPan = (dx / w) * windowMs;
    const next = Math.max(0, dragRef.current.startPan + deltaPan);
    onPanChange(next);
  };
  const onPointerUp = (e: React.PointerEvent<HTMLDivElement>) => {
    if (dragRef.current) {
      try { e.currentTarget.releasePointerCapture(dragRef.current.pid); } catch {}
    }
    dragRef.current = null;
    setDragging(false);
  };

  // ---- Render ------------------------------------------------------------
  const showNowLine = panMs < 60_000;
  const ageLabel =
    panMs < 60_000 ? null :
    panMs < 3600_000 ? `${Math.round(panMs / 60_000)}m back` :
    `${(panMs / 3600_000).toFixed(panMs >= 36 * 3600_000 ? 0 : 1)}h back`;

  return (
    <div className="px-3">
      {ageLabel && (
        <div className="text-[11px] text-muted text-center mb-1">viewing {ageLabel}</div>
      )}
      <div
        ref={containerRef}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        className={`relative select-none touch-none ${dragging ? "cursor-grabbing" : "cursor-grab"}`}
      >
        <svg
          viewBox={`0 0 ${view.w} ${view.h}`}
          className="w-full h-[340px]"
          preserveAspectRatio="none"
        >
          {/* Compressed-zone tints */}
          <rect x={pad.l} y={pad.t} width={plotW} height={highH} fill="rgba(255,255,255,0.025)" />
          <rect x={pad.l} y={pad.t + highH + midH} width={plotW} height={lowH} fill="rgba(255,255,255,0.025)" />

          {/* In-range band */}
          <rect
            x={pad.l}
            y={yOf(targetHigh)}
            width={plotW}
            height={yOf(targetLow) - yOf(targetHigh)}
            fill="rgba(92,208,255,0.07)"
          />

          {/* Y reference lines + labels */}
          {yValues.map((v) => {
            const isBoundary = v === 70 || v === 250;
            const isTarget = v === targetLow || v === targetHigh;
            return (
              <g key={`yl-${v}`}>
                <line
                  x1={pad.l} x2={pad.l + plotW}
                  y1={yOf(v)} y2={yOf(v)}
                  stroke={
                    isBoundary ? "rgba(255,255,255,0.22)" :
                    isTarget ? "rgba(255,184,77,0.40)" :
                    "rgba(255,255,255,0.08)"
                  }
                  strokeDasharray={isTarget ? "6 5" : isBoundary ? "0" : "2 6"}
                  strokeWidth={isBoundary ? 1.6 : 1.4}
                />
                <text x={6} y={yOf(v) + 6} fontSize="18" fill="rgba(255,255,255,0.62)">{v}</text>
              </g>
            );
          })}

          {/* X gridlines */}
          {ticks.map((t) => (
            <line
              key={`xt-${t}`}
              x1={xOf(t)} x2={xOf(t)}
              y1={pad.t} y2={pad.t + plotH}
              stroke="rgba(255,255,255,0.05)"
              strokeWidth={1.2}
            />
          ))}

          {/* BG line + dots */}
          {bgPath && (
            <path
              d={bgPath}
              fill="none"
              stroke="#5cd0ff"
              strokeWidth="3.5"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          )}
          {bgInWin.map((r) => (
            <circle
              key={`bg-${r.ts}`}
              cx={xOf(r.ts)}
              cy={yOf(r.mgdl)}
              r="4.5"
              fill={r.mgdl < 70 ? "#ff5c7c" : r.mgdl > 250 ? "#ff5c7c" : "#5cd0ff"}
            />
          ))}

          {/* Now line — only when not panned */}
          {showNowLine && (
            <line
              x1={xOf(now)} x2={xOf(now)}
              y1={pad.t} y2={pad.t + plotH}
              stroke="rgba(255,255,255,0.32)"
              strokeDasharray="4 4"
              strokeWidth={1.5}
            />
          )}

          {/* Dose markers (dot only — labels are HTML overlay below for crisp text).
              Bolus = orange circle; basal = green diamond. */}
          {dosesInWin.map((d) => {
            const interpolated = bgAt(d.ts, bgInWin);
            const cx = xOf(d.ts);
            const cy = interpolated != null ? yOf(interpolated) : pad.t + plotH * 0.25;
            const isBasal = d.kind === "basal";
            const fill = isBasal ? "#3ddc97" : "#ff9d4d";
            if (isBasal) {
              const s = 7;
              return (
                <polygon
                  key={`dm-${d.id ?? d.ts}-${d.units}`}
                  points={`${cx},${cy - s} ${cx + s},${cy} ${cx},${cy + s} ${cx - s},${cy}`}
                  fill={fill}
                  stroke="#0a0a0c"
                  strokeWidth={2.5}
                />
              );
            }
            return (
              <circle
                key={`dm-${d.id ?? d.ts}-${d.units}`}
                cx={cx}
                cy={cy}
                r={7}
                fill={fill}
                stroke="#0a0a0c"
                strokeWidth={2.5}
              />
            );
          })}

          {/* X-axis labels */}
          {labels.map((t, i) => {
            const d = new Date(t);
            if (i === 0 && t === minT) return null;
            return (
              <text
                key={`hl-${t}`}
                x={xOf(t)} y={view.h - 10}
                fontSize="16"
                textAnchor="middle"
                fill="rgba(255,255,255,0.6)"
              >
                {d.getHours().toString().padStart(2, "0")}:{d.getMinutes().toString().padStart(2, "0")}
              </text>
            );
          })}

          {/* Compact legend (top-right) */}
          <g transform={`translate(${pad.l + plotW - 256}, ${pad.t + 4})`}>
            <rect width={252} height={26} rx={8} fill="rgba(0,0,0,0.40)" />
            <line x1={10} y1={13} x2={32} y2={13} stroke="#5cd0ff" strokeWidth={3} strokeLinecap="round" />
            <text x={38} y={18} fontSize="14" fill="rgba(255,255,255,0.85)">BG</text>
            <circle cx={78} cy={13} r={5} fill="#ff9d4d" stroke="#0a0a0c" strokeWidth={1.5} />
            <text x={88} y={18} fontSize="14" fill="rgba(255,255,255,0.85)">bolus</text>
            <polygon points="158,8 164,13 158,18 152,13" fill="#3ddc97" stroke="#0a0a0c" strokeWidth={1.5} />
            <text x={170} y={18} fontSize="14" fill="rgba(255,255,255,0.85)">basal</text>
          </g>
        </svg>

        {/* HTML overlay for bolus unit labels — rendered outside the SVG so
            text isn't squished by preserveAspectRatio="none", and so labels
            near the right/left edges can flip their anchor to stay visible. */}
        <div className="absolute inset-0 pointer-events-none">
          {dosesInWin.map((d) => {
            const interpolated = bgAt(d.ts, bgInWin);
            const cx = xOf(d.ts);
            const cy = interpolated != null ? yOf(interpolated) : pad.t + plotH * 0.25;
            const pctX = (cx / view.w) * 100;
            const pctY = (cy / view.h) * 100;
            const align: "start" | "end" | "center" =
              pctX < 12 ? "start" : pctX > 88 ? "end" : "center";
            const transform =
              align === "start" ? "translate(0, -100%)" :
              align === "end"   ? "translate(-100%, -100%)" :
                                  "translate(-50%, -100%)";
            const isBasal = d.kind === "basal";
            const ringColor = isBasal ? "#3ddc97" : "#ff9d4d";
            const textColor = isBasal ? "#bff5d2" : "#ffd6a8";
            const tag = isBasal ? "basal" : null;
            return (
              <div
                key={`dl-${d.id ?? d.ts}-${d.units}`}
                className="absolute pb-2"
                style={{
                  left: `${pctX}%`,
                  top: `${pctY}%`,
                  transform,
                }}
              >
                <div
                  className="num leading-none px-2 py-1 rounded-md bg-black/85 ring-1 text-base font-bold whitespace-nowrap shadow-lg shadow-black/40 flex items-center gap-1.5"
                  style={{ borderColor: ringColor, color: textColor, boxShadow: `0 0 0 1px ${ringColor} inset` }}
                >
                  {tag && (
                    <span className="text-[10px] font-semibold uppercase tracking-wider opacity-80">
                      {tag}
                    </span>
                  )}
                  <span>{d.units}U</span>
                </div>
              </div>
            );
          })}
        </div>
      </div>
      {bgInWin.length === 0 && dosesInWin.length === 0 && (
        <div className="text-center text-xs text-muted -mt-44 mb-32">no data in this window</div>
      )}
    </div>
  );
}
