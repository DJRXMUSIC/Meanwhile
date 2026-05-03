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
  overlay24?: boolean;           // ghost trace from 24h prior
  overlay48?: boolean;           // ghost trace from 48h prior
}

export function Chart5h({
  readings,
  doses,
  windowHours,
  panMs,
  onPanChange,
  targetLow = 70,
  targetHigh = 160,
  overlay24 = false,
  overlay48 = false,
}: Props) {
  const view = { w: 1000, h: 460 };
  // pad.l is wide enough that HTML axis labels render at native size
  // without overlapping the plot area. pad.b leaves room for x-axis time labels.
  const pad = { l: 80, r: 16, t: 18, b: 48 };
  const plotH = view.h - pad.t - pad.b;
  const plotW = view.w - pad.l - pad.r;

  // Y-axis: piecewise linear over [40, 350] with breakpoints at 70 and 250.
  const lowFrac = 0.10, midFrac = 0.75, highFrac = 0.15;
  const lowH = plotH * lowFrac;
  const midH = plotH * midFrac;
  const highH = plotH * highFrac;

  const yOf = (mgdl: number): number => {
    const v = Math.max(40, Math.min(350, mgdl));
    // Top zone (250..350) — compressed band sitting at the top of the plot.
    // v=350 → top of plot; v=250 → top of mid zone (boundary).
    if (v >= 250) return pad.t + highH * ((350 - v) / 100);
    // Mid zone (70..250) — focus band.
    if (v >= 70)  return pad.t + highH + midH * ((250 - v) / 180);
    // Bottom zone (40..70) — compressed band at the bottom.
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

  // Overlay traces: BG from 24h / 48h prior, shifted forward by the
  // offset so they overlay the current window at the same wall-clock
  // time. Useful for pattern matching ("what did this morning look
  // like yesterday?").
  const buildOverlay = (offsetMs: number) =>
    readings
      .filter((r) => r.ts >= minT - offsetMs && r.ts <= maxT - offsetMs)
      .map((r) => ({ ts: r.ts + offsetMs, mgdl: r.mgdl }))
      .sort((a, b) => a.ts - b.ts);

  const overlay24Pts = useMemo(
    () => (overlay24 ? buildOverlay(24 * 3600_000) : []),
    [overlay24, readings, minT, maxT]
  ); // eslint-disable-line react-hooks/exhaustive-deps
  const overlay48Pts = useMemo(
    () => (overlay48 ? buildOverlay(48 * 3600_000) : []),
    [overlay48, readings, minT, maxT]
  ); // eslint-disable-line react-hooks/exhaustive-deps

  const ovPath = (pts: { ts: number; mgdl: number }[]) =>
    pts.length
      ? pts
          .map((r, i) => `${i === 0 ? "M" : "L"} ${xOf(r.ts).toFixed(1)} ${yOf(r.mgdl).toFixed(1)}`)
          .join(" ")
      : "";
  const overlay24Path = ovPath(overlay24Pts);
  const overlay48Path = ovPath(overlay48Pts);

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
  const isHistorical = panMs >= 60_000;
  const ageLabel =
    !isHistorical ? null :
    panMs < 3600_000 ? `${Math.round(panMs / 60_000)}m back` :
    `${(panMs / 3600_000).toFixed(panMs >= 36 * 3600_000 ? 0 : 1)}h back`;
  const fmtClock = (t: number) =>
    new Date(t).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  const fmtDate = (t: number) =>
    new Date(t).toLocaleDateString([], { weekday: "short", month: "short", day: "numeric" });

  return (
    <div className="px-3">
      {isHistorical && (
        <div className="mb-2 rounded-xl bg-warn/15 ring-1 ring-warn/50 px-2 py-2 flex items-center gap-2">
          <button
            onClick={() => onPanChange(panMs + (windowHours / 2) * 3600_000)}
            className="size-9 shrink-0 rounded-lg bg-warn/20 ring-1 ring-warn/40 text-warn text-xl font-bold leading-none grid place-items-center active:scale-95 transition"
            aria-label="Scroll earlier"
          >
            ‹
          </button>
          <div className="min-w-0 flex-1 text-center">
            <div className="text-[10px] uppercase tracking-wider text-warn font-semibold">
              Historical view · {ageLabel}
            </div>
            <div className="text-sm truncate">
              {fmtDate(maxT)} · {fmtClock(minT)}–{fmtClock(maxT)}
            </div>
          </div>
          <button
            onClick={() => {
              const step = (windowHours / 2) * 3600_000;
              onPanChange(Math.max(0, panMs - step));
            }}
            className="size-9 shrink-0 rounded-lg bg-warn/20 ring-1 ring-warn/40 text-warn text-xl font-bold leading-none grid place-items-center active:scale-95 transition"
            aria-label="Scroll later"
          >
            ›
          </button>
        </div>
      )}
      <div
        ref={containerRef}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        className={`relative select-none touch-none ${dragging ? "cursor-grabbing" : "cursor-grab"} ${isHistorical ? "ring-2 ring-warn/30 rounded-2xl" : ""}`}
      >
        <svg
          viewBox={`0 0 ${view.w} ${view.h}`}
          className="w-full h-[400px]"
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

          {/* Y reference lines (labels rendered as HTML overlay below) */}
          {yValues.map((v) => {
            const isBoundary = v === 70 || v === 250;
            const isTarget = v === targetLow || v === targetHigh;
            return (
              <line
                key={`yl-${v}`}
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

          {/* Overlay traces (drawn before BG so the live trace stays on top) */}
          {overlay48Path && (
            <path
              d={overlay48Path}
              fill="none"
              stroke="#5cd0ff"
              strokeOpacity="0.30"
              strokeWidth="2"
              strokeDasharray="2 6"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          )}
          {overlay24Path && (
            <path
              d={overlay24Path}
              fill="none"
              stroke="#5cd0ff"
              strokeOpacity="0.55"
              strokeWidth="2.4"
              strokeDasharray="6 4"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          )}

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

          {/* X-axis labels and legend are rendered as HTML overlay below */}
        </svg>

        {/* HTML overlay layer — all text (axis labels, legend, dose labels)
            lives here so it isn't squished by preserveAspectRatio="none". */}
        <div className="absolute inset-0 pointer-events-none">
          {/* Y-axis labels (left gutter) */}
          {yValues.map((v) => {
            const pctY = (yOf(v) / view.h) * 100;
            return (
              <div
                key={`ylabel-${v}`}
                className="num absolute text-[13px] text-white/65 font-medium"
                style={{
                  top: `${pctY}%`,
                  left: 0,
                  width: `${(pad.l / view.w) * 100}%`,
                  paddingRight: 8,
                  textAlign: "right",
                  transform: "translateY(-50%)",
                }}
              >
                {v}
              </div>
            );
          })}

          {/* X-axis labels (bottom gutter) */}
          {labels.map((t, i) => {
            if (i === 0 && t === minT) return null;
            const d = new Date(t);
            const pctX = (xOf(t) / view.w) * 100;
            return (
              <div
                key={`xlabel-${t}`}
                className="num absolute text-[13px] text-white/65"
                style={{
                  left: `${pctX}%`,
                  bottom: 4,
                  transform: "translateX(-50%)",
                }}
              >
                {d.getHours().toString().padStart(2, "0")}:{d.getMinutes().toString().padStart(2, "0")}
              </div>
            );
          })}

          {/* Legend (top-right) */}
          <div
            className="absolute right-2 flex items-center gap-2.5 px-2.5 py-1 rounded-lg bg-black/55 ring-1 ring-white/10 text-[12px] text-white/85"
            style={{ top: 6 }}
          >
            <span className="flex items-center gap-1">
              <span className="block w-4 h-[3px] rounded-full" style={{ background: "#5cd0ff" }} />
              BG
            </span>
            <span className="flex items-center gap-1">
              <span className="block w-2 h-2 rounded-full ring-1 ring-black" style={{ background: "#ff9d4d" }} />
              bolus
            </span>
            <span className="flex items-center gap-1">
              <span className="block w-2.5 h-2.5 ring-1 ring-black rotate-45" style={{ background: "#3ddc97" }} />
              basal
            </span>
            {overlay24 && (
              <span className="flex items-center gap-1 opacity-90">
                <span className="block w-4 h-[2px]" style={{ background: "repeating-linear-gradient(90deg, #5cd0ff 0 6px, transparent 6px 10px)" }} />
                −24h
              </span>
            )}
            {overlay48 && (
              <span className="flex items-center gap-1 opacity-70">
                <span className="block w-4 h-[2px]" style={{ background: "repeating-linear-gradient(90deg, #5cd0ff 0 2px, transparent 2px 8px)" }} />
                −48h
              </span>
            )}
          </div>

          {/* Bolus / basal unit labels above their markers */}
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
