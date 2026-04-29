"use client";

import { useMemo } from "react";
import type { BgReading, InsulinDose } from "@/lib/types";

// 5h overview chart, modeled after the standard look used by Loop /
// Nightscout / xDripViewer: a large readable BG line with target-range
// shading, hourly gridlines, and an insulin pin strip across the bottom.
export function Chart5h({
  readings,
  doses,
  windowHours = 5,
  targetLow = 70,
  targetHigh = 160,
  yMinFloor = 40,
  yMaxCeiling = 350,
}: {
  readings: BgReading[];
  doses: InsulinDose[];
  windowHours?: number;
  targetLow?: number;
  targetHigh?: number;
  yMinFloor?: number;
  yMaxCeiling?: number;
}) {
  // Chart coordinate space (SVG viewBox). preserveAspectRatio="none" lets
  // it stretch to the container width while keeping a fixed pixel height,
  // which makes the labels render at predictable sizes on any phone.
  const view = { w: 1000, h: 460 };
  const pad = { l: 44, r: 12, t: 16, b: 110 }; // bottom band reserved for insulin pins

  const now = Date.now();
  const minT = now - windowHours * 3600_000;
  const maxT = now;

  const bgInWin = useMemo(
    () => readings.filter((r) => r.ts >= minT && r.ts <= maxT).sort((a, b) => a.ts - b.ts),
    [readings, minT, maxT]
  );
  const dosesInWin = useMemo(
    () => doses.filter((d) => d.ts >= minT && d.ts <= maxT).sort((a, b) => a.ts - b.ts),
    [doses, minT, maxT]
  );

  // Y axis fixed to 40–350 mg/dL per spec.
  const yMin = yMinFloor;
  const yMax = yMaxCeiling;

  const plotW = view.w - pad.l - pad.r;
  const plotH = view.h - pad.t - pad.b;

  const xOf = (t: number) => pad.l + ((t - minT) / (maxT - minT)) * plotW;
  const yOf = (mgdl: number) =>
    pad.t + (1 - (Math.max(yMin, Math.min(yMax, mgdl)) - yMin) / (yMax - yMin)) * plotH;

  // Hour gridlines at every full hour inside the window
  const startHour = new Date(minT);
  startHour.setMinutes(0, 0, 0);
  const hourMarks: number[] = [];
  for (let t = startHour.getTime(); t <= maxT; t += 3600_000) {
    if (t >= minT) hourMarks.push(t);
  }

  // Y reference lines: target band edges + a couple of guides.
  const yLines = [40, targetLow, 100, targetHigh, 250, 350].filter((v) => v >= yMin && v <= yMax);

  const bgPath = bgInWin.length
    ? bgInWin.map((r, i) => `${i === 0 ? "M" : "L"} ${xOf(r.ts).toFixed(1)} ${yOf(r.mgdl).toFixed(1)}`).join(" ")
    : "";

  // Insulin pin band. Pin height scales linearly with units, capped to
  // bandH at maxUnits (≥7 so a 1U pin always reads small relative to a 7U).
  const bandTop = view.h - pad.b + 12;
  const bandBot = view.h - 30;
  const bandH = bandBot - bandTop;
  const maxUnits = Math.max(7, ...dosesInWin.map((d) => d.units));
  const pinH = (u: number) => Math.max(14, (u / maxUnits) * bandH);

  return (
    <div className="px-3">
      <svg
        viewBox={`0 0 ${view.w} ${view.h}`}
        className="w-full h-[340px]"
        preserveAspectRatio="none"
      >
        {/* In-range band */}
        <rect
          x={pad.l}
          y={yOf(targetHigh)}
          width={plotW}
          height={yOf(targetLow) - yOf(targetHigh)}
          fill="rgba(61,220,151,0.10)"
        />

        {/* Hour gridlines */}
        {hourMarks.map((t) => (
          <line
            key={`h-${t}`}
            x1={xOf(t)} x2={xOf(t)}
            y1={pad.t} y2={pad.t + plotH}
            stroke="rgba(255,255,255,0.06)"
            strokeWidth={1.5}
          />
        ))}

        {/* Y reference lines */}
        {yLines.map((v) => {
          const isTarget = v === targetLow || v === targetHigh;
          return (
            <g key={`yl-${v}`}>
              <line
                x1={pad.l} x2={pad.l + plotW}
                y1={yOf(v)} y2={yOf(v)}
                stroke={isTarget ? "rgba(255,184,77,0.45)" : "rgba(255,255,255,0.10)"}
                strokeDasharray={isTarget ? "6 5" : "2 6"}
                strokeWidth={1.5}
              />
              <text x={6} y={yOf(v) + 6} fontSize="18" fill="rgba(255,255,255,0.6)">{v}</text>
            </g>
          );
        })}

        {/* BG line + dots */}
        {bgPath && (
          <path
            d={bgPath}
            fill="none"
            stroke="#7c5cff"
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
            fill={r.mgdl < targetLow ? "#ff5c7c" : r.mgdl > targetHigh ? "#ffb84d" : "#7c5cff"}
          />
        ))}

        {/* Now line */}
        <line
          x1={xOf(now)} x2={xOf(now)}
          y1={pad.t} y2={view.h - 30}
          stroke="rgba(255,255,255,0.32)"
          strokeDasharray="4 4"
          strokeWidth={1.5}
        />

        {/* Insulin pin band — separator + pins */}
        <line
          x1={pad.l} x2={pad.l + plotW}
          y1={bandBot} y2={bandBot}
          stroke="rgba(255,255,255,0.18)"
          strokeWidth={1.5}
        />
        {dosesInWin.map((d) => {
          const x = xOf(d.ts);
          const h = pinH(d.units);
          const top = bandBot - h;
          const color = d.kind === "basal" ? "#3ddc97" : "#7c5cff";
          return (
            <g key={`d-${d.id ?? d.ts}-${d.units}`}>
              <line
                x1={x} x2={x}
                y1={bandBot} y2={top}
                stroke={color}
                strokeWidth={3}
                strokeLinecap="round"
              />
              <circle cx={x} cy={top} r={5} fill={color} />
              <text
                x={x}
                y={top - 8}
                fontSize="20"
                fontWeight="600"
                textAnchor="middle"
                fill="#ffffff"
              >
                {d.units}U
              </text>
            </g>
          );
        })}

        {/* Hour labels along bottom */}
        {hourMarks.map((t, i) => {
          const d = new Date(t);
          if (i === 0 && t === minT) return null;
          return (
            <text
              key={`hl-${t}`}
              x={xOf(t)} y={view.h - 8}
              fontSize="18"
              textAnchor="middle"
              fill="rgba(255,255,255,0.6)"
            >
              {d.getHours().toString().padStart(2, "0")}:{d.getMinutes().toString().padStart(2, "0")}
            </text>
          );
        })}

        {/* Legend (right side, compact) */}
        <g transform={`translate(${pad.l + plotW - 240}, ${pad.t + 8})`}>
          <rect width={232} height={28} rx={8} fill="rgba(0,0,0,0.35)" />
          <circle cx={14} cy={14} r={5} fill="#7c5cff" />
          <text x={26} y={19} fontSize="16" fill="rgba(255,255,255,0.85)">BG</text>
          <line x1={62} y1={14} x2={86} y2={14} stroke="#7c5cff" strokeWidth={3} strokeLinecap="round" />
          <text x={92} y={19} fontSize="16" fill="rgba(255,255,255,0.85)">bolus</text>
          <line x1={150} y1={14} x2={174} y2={14} stroke="#3ddc97" strokeWidth={3} strokeLinecap="round" />
          <text x={180} y={19} fontSize="16" fill="rgba(255,255,255,0.85)">basal</text>
        </g>
      </svg>
      {bgInWin.length === 0 && dosesInWin.length === 0 && (
        <div className="-mt-44 mb-24 text-center text-xs text-muted">no data in last {windowHours}h</div>
      )}
    </div>
  );
}
