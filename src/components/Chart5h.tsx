"use client";

import { useMemo } from "react";
import type { BgReading, InsulinDose } from "@/lib/types";

// Loop/Nightscout-style 5h overview: BG line + insulin pins along the
// bottom strip. SVG, viewBox-scaled so it fills any width on phones.
export function Chart5h({
  readings,
  doses,
  windowHours = 5,
  targetLow = 70,
  targetHigh = 180,
}: {
  readings: BgReading[];
  doses: InsulinDose[];
  windowHours?: number;
  targetLow?: number;
  targetHigh?: number;
}) {
  const view = { w: 600, h: 240 };
  const pad = { l: 32, r: 8, t: 12, b: 56 }; // bottom band reserved for insulin pins

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

  // Y range: clamp to a sensible BG range, expand if data exceeds it.
  const yMin = Math.min(50, ...bgInWin.map((r) => r.mgdl));
  const yMax = Math.max(240, ...bgInWin.map((r) => r.mgdl));

  const plotW = view.w - pad.l - pad.r;
  const plotH = view.h - pad.t - pad.b;

  const xOf = (t: number) => pad.l + ((t - minT) / (maxT - minT)) * plotW;
  const yOf = (mgdl: number) => pad.t + (1 - (mgdl - yMin) / (yMax - yMin)) * plotH;

  // Hour gridlines (each hour boundary inside the window)
  const startHour = new Date(minT);
  startHour.setMinutes(0, 0, 0);
  const hourMarks: number[] = [];
  for (let t = startHour.getTime(); t <= maxT; t += 3600_000) {
    if (t >= minT) hourMarks.push(t);
  }

  // Y gridlines at 70 / 180 (target band) plus 100, 250 reference if visible.
  const yLines = [targetLow, targetHigh, 100, 250].filter((v) => v >= yMin && v <= yMax);

  const bgPath = bgInWin.length
    ? bgInWin.map((r, i) => `${i === 0 ? "M" : "L"} ${xOf(r.ts).toFixed(1)} ${yOf(r.mgdl).toFixed(1)}`).join(" ")
    : "";

  // Insulin pin geometry. Pins live in the bottom strip (between plot bottom
  // and chart bottom). Height scales with units, capped to band height.
  const bandTop = view.h - pad.b + 6;          // a little gap above the band
  const bandBot = view.h - 18;                  // leave room for time labels
  const bandH = bandBot - bandTop;
  const maxUnits = Math.max(7, ...dosesInWin.map((d) => d.units));
  const pinH = (u: number) => Math.max(6, (u / maxUnits) * bandH);

  return (
    <div className="mx-4">
      <svg
        viewBox={`0 0 ${view.w} ${view.h}`}
        className="w-full h-[220px]"
        preserveAspectRatio="xMidYMid meet"
      >
        {/* In-range band */}
        {targetHigh <= yMax && targetLow >= yMin && (
          <rect
            x={pad.l}
            y={yOf(targetHigh)}
            width={plotW}
            height={yOf(targetLow) - yOf(targetHigh)}
            fill="rgba(61,220,151,0.10)"
          />
        )}

        {/* Hour gridlines */}
        {hourMarks.map((t) => (
          <line
            key={`h-${t}`}
            x1={xOf(t)} x2={xOf(t)}
            y1={pad.t} y2={pad.t + plotH}
            stroke="rgba(255,255,255,0.05)"
          />
        ))}

        {/* Y reference lines */}
        {yLines.map((v) => (
          <g key={`yl-${v}`}>
            <line
              x1={pad.l} x2={pad.l + plotW}
              y1={yOf(v)} y2={yOf(v)}
              stroke={v === targetLow || v === targetHigh ? "rgba(255,184,77,0.35)" : "rgba(255,255,255,0.08)"}
              strokeDasharray={v === targetLow || v === targetHigh ? "3 3" : "1 4"}
            />
            <text x={4} y={yOf(v) + 3} fontSize="10" fill="rgba(255,255,255,0.45)">{v}</text>
          </g>
        ))}

        {/* BG line + dots */}
        {bgPath && (
          <path d={bgPath} fill="none" stroke="#7c5cff" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
        )}
        {bgInWin.map((r) => (
          <circle
            key={`bg-${r.ts}`}
            cx={xOf(r.ts)} cy={yOf(r.mgdl)} r="2.5"
            fill={r.mgdl < 70 ? "#ff5c7c" : r.mgdl > 180 ? "#ffb84d" : "#7c5cff"}
          />
        ))}

        {/* Now line */}
        <line
          x1={xOf(now)} x2={xOf(now)}
          y1={pad.t} y2={view.h - 18}
          stroke="rgba(255,255,255,0.25)"
          strokeDasharray="2 3"
        />

        {/* Insulin pins (bottom band) */}
        <line x1={pad.l} x2={pad.l + plotW} y1={bandBot} y2={bandBot} stroke="rgba(255,255,255,0.1)" />
        {dosesInWin.map((d) => {
          const x = xOf(d.ts);
          const h = pinH(d.units);
          const top = bandBot - h;
          const color = d.kind === "basal" ? "#3ddc97" : "#7c5cff";
          return (
            <g key={`d-${d.id ?? d.ts}-${d.units}`}>
              <line x1={x} x2={x} y1={bandBot} y2={top} stroke={color} strokeWidth="2" strokeLinecap="round" />
              <circle cx={x} cy={top} r="3" fill={color} />
              <text
                x={x}
                y={top - 4}
                fontSize="9"
                textAnchor="middle"
                fill="rgba(255,255,255,0.8)"
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
              x={xOf(t)} y={view.h - 4}
              fontSize="9"
              textAnchor="middle"
              fill="rgba(255,255,255,0.45)"
            >
              {d.getHours().toString().padStart(2, "0")}:{d.getMinutes().toString().padStart(2, "0")}
            </text>
          );
        })}
      </svg>
      {bgInWin.length === 0 && dosesInWin.length === 0 && (
        <div className="-mt-32 mb-12 text-center text-xs text-muted">no data in last {windowHours}h</div>
      )}
    </div>
  );
}
