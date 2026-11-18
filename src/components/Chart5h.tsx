"use client";

import { memo, useCallback, useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { BgReading, InsulinDose } from "@/lib/types";
import { bgAt, buildOverlay, DAY_MS, LOOKAHEAD_MS, nearest, overlayBoluses, type Pt } from "@/lib/chartOverlay";
import { MISSED_AFTER_MS, type Estimate } from "@/lib/gapFill";

// The Now chart: BG over a chosen window (3/5/8/24h), pannable into the past by
// dragging, with 190 minutes of blank lookahead after the right edge where the
// −24h / −48h traces (and their boluses) carry on.
//
// Drawn at the container's real pixel width (no stretched viewBox), so dots
// stay round and lines keep one thickness. Colours come from CSS variables
// (--g-*), with their own values in light and dark mode.
//
// Y axis is piecewise: 40–70 gets 10% of the height, 70–250 gets 75%,
// 250–350 gets 15%. Gridlines sit at every labelled value, so the squeeze is
// visible; points land on their true y.

export type WindowHours = 3 | 5 | 8 | 24;

const H = 340;                                   // plot + axis, px
const pad = { l: 34, r: 10, t: 10, b: 24 };
const plotH = H - pad.t - pad.b;
const lowH = plotH * 0.10;
const midH = plotH * 0.75;
const highH = plotH * 0.15;

function yOf(mgdl: number): number {
  const v = Math.max(40, Math.min(350, mgdl));
  if (v >= 250) return pad.t + highH * ((350 - v) / 100);
  if (v >= 70) return pad.t + highH + midH * ((250 - v) / 180);
  return pad.t + highH + midH + lowH * ((70 - v) / 30);
}

// Readings further apart than this are not joined: a gap is drawn as a gap.
const GAP_MS = 15 * 60_000;
// A point that can be tapped: a reading, an estimate, or a −24h / −48h reading.
type Tapped = Pt & { ago?: string; est?: boolean };

// Theme colours (see globals.css): as CSS values, for style={{ ... }}.
const C = {
  low: "rgb(var(--g-low))",
  in: "rgb(var(--g-in))",
  high: "rgb(var(--g-high))",
  bolus: "rgb(var(--g-bolus))",
  basal: "rgb(var(--g-basal))",
  ink: "rgb(var(--c-ink))",
  muted: "rgb(var(--c-muted))",
  surface: "rgb(var(--c-surface))",
};

function rangeColor(mgdl: number, low: number, high: number): string {
  return mgdl < low ? C.low : mgdl > high ? C.high : C.in;
}

const clock = (t: number) => {
  const d = new Date(t);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
};

// Text drawn over the plot gets a halo in the surface colour so it reads over lines.
const halo = { paintOrder: "stroke", stroke: C.surface, strokeWidth: 4, strokeLinejoin: "round" } as const;

interface Props {
  readings: BgReading[];
  estimates?: Estimate[];        // best guesses for missed readings (lib/gapFill), drawn dashed
  doses: InsulinDose[];
  windowHours: WindowHours;
  now: number;                   // shared clock — see lib/useNow
  panMs: number;                 // how far back from "now" the right edge sits
  onPanChange: (next: number) => void;
  targetLow?: number;
  targetHigh?: number;
  overlay24?: boolean;
  overlay48?: boolean;
}

function Chart5hImpl({
  readings,
  estimates = [],
  doses,
  windowHours,
  now,
  panMs,
  onPanChange,
  targetLow = 70,
  targetHigh = 160,
  overlay24 = false,
  overlay48 = false,
}: Props) {
  // ---- Size: the real width of the box ----------------------------------
  const containerRef = useRef<HTMLDivElement>(null);
  const [W, setW] = useState(380);
  useLayoutEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const measure = () => { if (el.clientWidth) setW(el.clientWidth); };
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const plotW = Math.max(50, W - pad.l - pad.r);
  const gradId = `bg-range-${useId().replace(/:/g, "")}`;

  // ---- Time ---------------------------------------------------------------
  // The edge is pushed out to the newest row when that is later than the
  // quantized clock, so something just logged isn't clipped off its own chart.
  const latestTs = useMemo(() => {
    let m = 0;
    for (const r of readings) if (r.ts > m) m = r.ts;
    for (const d of doses) if (d.ts > m) m = d.ts;
    return m;
  }, [readings, doses]);

  // maxT: the present (or panned-to) edge; endT: the end of the lookahead after it.
  const maxT = Math.max(now, latestTs) - panMs;
  const minT = maxT - windowHours * 3600_000;
  const endT = maxT + LOOKAHEAD_MS;
  const xOf = useCallback(
    (t: number) => pad.l + ((t - minT) / (endT - minT)) * plotW,
    [minT, endT, plotW]
  );

  const [dragging, setDragging] = useState(false);
  // A tapped point (from the −24h / −48h trace when in the lookahead), shown for 8 s.
  const [inspect, setInspect] = useState<Tapped | null>(null);
  useEffect(() => {
    if (!inspect) return;
    const t = setTimeout(() => setInspect(null), 8_000);
    return () => clearTimeout(t);
  }, [inspect]);

  const bgInWin = useMemo(
    () => readings.filter((r) => r.ts >= minT && r.ts <= maxT).sort((a, b) => a.ts - b.ts),
    [readings, minT, maxT]
  );
  const dosesInWin = useMemo(
    () => doses.filter((d) => d.ts >= minT && d.ts <= maxT).sort((a, b) => a.ts - b.ts),
    [doses, minT, maxT]
  );
  const latest = bgInWin.length ? bgInWin[bgInWin.length - 1] : null;
  const estInWin = useMemo(
    () => estimates.filter((e) => e.ts >= minT && e.ts <= maxT),
    [estimates, minT, maxT]
  );

  // Overlays are skipped mid-drag: extra path work on every frame, for a
  // comparison nobody reads during the gesture.
  const overlay24Pts = useMemo(
    () => (overlay24 && !dragging ? buildOverlay(readings, minT, endT, DAY_MS) : []),
    [overlay24, dragging, readings, minT, endT]
  );
  const overlay48Pts = useMemo(
    () => (overlay48 && !dragging ? buildOverlay(readings, minT, endT, 2 * DAY_MS) : []),
    [overlay48, dragging, readings, minT, endT]
  );
  const overlayDoses = useMemo(() => {
    const out: { d: InsulinDose; trace: Pt[]; ago: string }[] = [];
    if (overlay48Pts.length)
      for (const d of overlayBoluses(doses, minT, endT, 2 * DAY_MS)) out.push({ d, trace: overlay48Pts, ago: "−48h" });
    if (overlay24Pts.length)
      for (const d of overlayBoluses(doses, minT, endT, DAY_MS)) out.push({ d, trace: overlay24Pts, ago: "−24h" });
    return out;
  }, [doses, minT, endT, overlay24Pts, overlay48Pts]);

  const toPath = useCallback(
    (pts: Pt[], gapMs = GAP_MS) =>
      pts
        .map((r, i) => {
          const jump = i === 0 || r.ts - pts[i - 1].ts > gapMs;
          return `${jump ? "M" : "L"}${xOf(r.ts).toFixed(1)} ${yOf(r.mgdl).toFixed(1)}`;
        })
        .join(""),
    [xOf]
  );
  // live line: broken at every missed reading; the estimates bridge those breaks, dashed
  const bgPath = useMemo(() => toPath(bgInWin, MISSED_AFTER_MS), [toPath, bgInWin]);
  const estPath = useMemo(() => {
    if (!estInWin.length) return "";
    const all: (Pt & { est?: boolean })[] = [...bgInWin, ...estInWin.map((e) => ({ ...e, est: true }))]
      .sort((a, b) => a.ts - b.ts);
    let d = "";
    let open = false;
    for (let i = 1; i < all.length; i++) {
      const p = all[i - 1];
      const q = all[i];
      if ((p.est || q.est) && q.ts - p.ts <= MISSED_AFTER_MS) {
        if (!open) d += `M${xOf(p.ts).toFixed(1)} ${yOf(p.mgdl).toFixed(1)}`;
        d += `L${xOf(q.ts).toFixed(1)} ${yOf(q.mgdl).toFixed(1)}`;
        open = true;
      } else open = false;
    }
    return d;
  }, [bgInWin, estInWin, xOf]);
  const overlay24Path = useMemo(() => toPath(overlay24Pts), [toPath, overlay24Pts]);
  const overlay48Path = useMemo(() => toPath(overlay48Pts), [toPath, overlay48Pts]);

  // Dots on each reading only when they're far enough apart not to bead the line.
  const showDots = bgInWin.length > 1 && plotW * (5 * 60_000) / (endT - minT) >= 7;

  // ---- Axes ---------------------------------------------------------------
  // Time labels: the smallest step that leaves ~46 px between labels, on local clock times.
  const pxPerMin = plotW / ((endT - minT) / 60_000);
  const stepMin = [15, 30, 60, 120, 180, 240, 360].find((m) => m * pxPerMin >= 46) ?? 360;
  const ticks: number[] = [];
  {
    const step = stepMin * 60_000;
    const offset = new Date(minT).getTimezoneOffset() * 60_000;
    for (let t = Math.ceil((minT - offset) / step) * step + offset; t <= endT; t += step) ticks.push(t);
  }

  // BG labels: the grid values plus the target edges, minus any crowding a target.
  const yValues = useMemo(() => {
    const base = [40, 70, 100, 130, 160, 200, 250, 300, 350];
    const targets = [targetLow, targetHigh];
    const out = [...targets];
    for (const v of base) if (targets.every((t) => Math.abs(yOf(v) - yOf(t)) > 14)) out.push(v);
    return out.sort((a, b) => a - b);
  }, [targetLow, targetHigh]);

  // ---- Pan by drag, tap to inspect ---------------------------------------
  // Pointer capture waits until the gesture is clearly horizontal, so a
  // vertical swipe over the chart still scrolls the page.
  const dragRef = useRef<{ startX: number; startY: number; startPan: number; pid: number; captured: boolean } | null>(null);
  const THRESHOLD_PX = 8;
  const rafRef = useRef<number | null>(null);
  const pendingPanRef = useRef<number | null>(null);
  const flushPan = useCallback(() => {
    rafRef.current = null;
    const next = pendingPanRef.current;
    pendingPanRef.current = null;
    if (next != null) onPanChange(next);
  }, [onPanChange]);
  const schedulePan = useCallback((next: number) => {
    pendingPanRef.current = next;
    if (rafRef.current == null) rafRef.current = requestAnimationFrame(flushPan);
  }, [flushPan]);
  useEffect(() => () => { if (rafRef.current != null) cancelAnimationFrame(rafRef.current); }, []);

  const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (e.pointerType === "mouse" && e.button !== 0) return;
    dragRef.current = { startX: e.clientX, startY: e.clientY, startPan: panMs, pid: e.pointerId, captured: false };
    if (e.pointerType === "mouse") {
      try { e.currentTarget.setPointerCapture(e.pointerId); } catch {}
      dragRef.current.captured = true;
    }
  };
  const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    if (!drag) return;
    const dx = e.clientX - drag.startX;
    const dy = e.clientY - drag.startY;
    if (!dragging) {
      if (Math.abs(dx) < THRESHOLD_PX && Math.abs(dy) < THRESHOLD_PX) return;
      // touch: a vertical swipe belongs to the page
      if (e.pointerType !== "mouse" && Math.abs(dy) >= Math.abs(dx)) { dragRef.current = null; return; }
      if (!drag.captured) {
        try { e.currentTarget.setPointerCapture(drag.pid); } catch {}
        drag.captured = true;
      }
      setDragging(true);
    }
    // drag right → older data comes in from the left → panMs grows
    schedulePan(Math.max(0, drag.startPan + (dx / plotW) * (endT - minT)));
  };
  const onPointerUp = (e: React.PointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    if (drag) {
      try { e.currentTarget.releasePointerCapture(drag.pid); } catch {}
      const moved = Math.hypot(e.clientX - drag.startX, e.clientY - drag.startY);
      if (moved < THRESHOLD_PX && e.type === "pointerup" && containerRef.current) {
        const x = e.clientX - containerRef.current.getBoundingClientRect().left;
        const t = minT + ((x - pad.l) / plotW) * (endT - minT);
        // before the edge: the reading; in the lookahead: the nearest −24h / −48h one
        let best: Tapped | null = null;
        if (t <= maxT) best = nearest<Tapped>([...bgInWin, ...estInWin.map((e) => ({ ...e, est: true }))], t);
        else {
          const near = (on: boolean, off: number) =>
            on ? nearest(buildOverlay(readings, t - 30 * 60_000, t + 30 * 60_000, off), t) : null;
          const a = near(overlay24, DAY_MS);
          const b = near(overlay48, 2 * DAY_MS);
          best = a && (!b || Math.abs(a.ts - t) <= Math.abs(b.ts - t)) ? { ...a, ago: "−24h" } : b ? { ...b, ago: "−48h" } : null;
        }
        if (best) setInspect(inspect && inspect.ts === best.ts && inspect.ago === best.ago ? null : { ts: best.ts, mgdl: best.mgdl, ago: best.ago, est: best.est });
      }
    }
    if (rafRef.current != null) { cancelAnimationFrame(rafRef.current); rafRef.current = null; }
    flushPan();
    dragRef.current = null;
    setDragging(false);
  };

  // ---- Render -------------------------------------------------------------
  const isHistorical = panMs >= 60_000;
  const ageLabel =
    !isHistorical ? null :
    panMs < 3600_000 ? `${Math.round(panMs / 60_000)}m back` :
    `${(panMs / 3600_000).toFixed(panMs >= 36 * 3600_000 ? 0 : 1)}h back`;
  const fmtDate = (t: number) =>
    new Date(t).toLocaleDateString([], { weekday: "short", month: "short", day: "numeric" });

  const edgeX = xOf(maxT);
  const dosePos = (d: InsulinDose, trace: Pt[]) => {
    const v = bgAt(d.ts, trace);
    return { x: xOf(d.ts), y: v != null ? yOf(v) : pad.t + plotH * 0.25 };
  };
  const inspectX = inspect ? xOf(inspect.ts) : 0;
  const inspectOn = inspect && inspect.ts >= minT && inspect.ts <= endT;
  const bandTop = (yOf(targetHigh) - pad.t) / plotH;
  const bandBottom = (yOf(targetLow) - pad.t) / plotH;

  return (
    <div className="px-3">
      {isHistorical && (
        <div className="mb-2 rounded-xl bg-warn/15 ring-1 ring-warn/50 px-2 py-2 flex items-center gap-2">
          <button
            onClick={() => onPanChange(panMs + 3 * 3600_000)}
            className="shrink-0 rounded-lg bg-warn/20 ring-1 ring-warn/40 text-warn px-3 h-9 text-sm font-semibold leading-none flex items-center gap-1 active:scale-95 transition"
            aria-label="Scroll 3 hours earlier"
          >
            ‹ 3h
          </button>
          <div className="min-w-0 flex-1 text-center">
            <div className="text-[10px] uppercase tracking-wider text-warn font-semibold">
              Historical view · {ageLabel}
            </div>
            <div className="text-sm truncate">
              {fmtDate(maxT)} · {clock(minT)}–{clock(maxT)}
            </div>
          </div>
          <button
            onClick={() => onPanChange(Math.max(0, panMs - 3 * 3600_000))}
            className="shrink-0 rounded-lg bg-warn/20 ring-1 ring-warn/40 text-warn px-3 h-9 text-sm font-semibold leading-none flex items-center gap-1 active:scale-95 transition"
            aria-label="Scroll 3 hours later"
          >
            3h ›
          </button>
        </div>
      )}
      <div className={`rounded-2xl bg-surface ${isHistorical ? "ring-2 ring-warn/40" : "ring-1 ring-ink/10"}`}>
        <div
          ref={containerRef}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onPointerCancel={onPointerUp}
          className={`relative select-none ${dragging ? "cursor-grabbing" : "cursor-grab"}`}
          style={{ touchAction: "pan-y" }}
        >
          <svg width={W} height={H} viewBox={`0 0 ${W} ${H}`} className="block text-ink" data-chart="bg">
            {/* target range band */}
            <rect x={pad.l} y={yOf(targetHigh)} width={plotW} height={yOf(targetLow) - yOf(targetHigh)}
              style={{ fill: C.in }} fillOpacity={0.09} />

            {/* lookahead: blank after the edge */}
            <rect x={edgeX} y={pad.t} width={Math.max(0, pad.l + plotW - edgeX)} height={plotH}
              fill="currentColor" fillOpacity={0.04} />

            {/* grid */}
            {yValues.map((v) => {
              const target = v === targetLow || v === targetHigh;
              return (
                <line key={`gy-${v}`} x1={pad.l} x2={pad.l + plotW} y1={yOf(v)} y2={yOf(v)}
                  style={{ stroke: target ? C.in : C.ink }}
                  strokeOpacity={target ? 0.55 : 0.08}
                  strokeDasharray={target ? "4 4" : undefined}
                  strokeWidth={1} />
              );
            })}
            {ticks.map((t) => (
              <line key={`gx-${t}`} x1={xOf(t)} x2={xOf(t)} y1={pad.t} y2={pad.t + plotH}
                stroke="currentColor" strokeOpacity={0.06} strokeWidth={1} />
            ))}

            {/* axis labels */}
            {yValues.map((v) => {
              const target = v === targetLow || v === targetHigh;
              return (
                <text key={`ly-${v}`} x={pad.l - 6} y={yOf(v)} textAnchor="end" dominantBaseline="central"
                  className="num" fontSize={11} fontWeight={target ? 600 : 400}
                  style={{ fill: target ? C.ink : C.muted }}>
                  {v}
                </text>
              );
            })}
            {ticks.map((t) => {
              const x = xOf(t);
              if (x < pad.l + 14 || x > pad.l + plotW - 14) return null;
              return (
                <text key={`lx-${t}`} x={x} y={H - 7} textAnchor="middle" className="num" fontSize={11}
                  style={{ fill: C.muted }}>
                  {clock(t)}
                </text>
              );
            })}

            {/* the edge: "now", or where a panned view ends */}
            <line x1={edgeX} x2={edgeX} y1={pad.t} y2={pad.t + plotH}
              stroke="currentColor" strokeOpacity={0.35} strokeWidth={1}
              strokeDasharray={isHistorical ? undefined : "3 3"} />

            {/* −48h / −24h traces, under the live one */}
            {overlay48Path && (
              <path d={overlay48Path} fill="none" stroke="currentColor" strokeOpacity={0.4} strokeWidth={1.75}
                strokeDasharray="1.5 4" strokeLinecap="round" strokeLinejoin="round" />
            )}
            {overlay24Path && (
              <path d={overlay24Path} fill="none" stroke="currentColor" strokeOpacity={0.55} strokeWidth={2}
                strokeDasharray="6 4" strokeLinecap="round" strokeLinejoin="round" />
            )}
            {overlayDoses.map(({ d, trace, ago }) => {
              const p = dosePos(d, trace);
              return (
                <g key={`od-${ago}-${d.id ?? d.ts}-${d.units}`} opacity={ago === "−24h" ? 1 : 0.65}>
                  <circle cx={p.x} cy={p.y} r={4.5} style={{ fill: C.surface, stroke: C.bolus }} strokeWidth={2} />
                  <text x={p.x} y={p.y - 9} textAnchor="middle" className="num" fontSize={11} fontWeight={600}
                    style={{ fill: C.bolus, ...halo }}>
                    {d.units}U
                  </text>
                </g>
              );
            })}

            {/* live BG: one line, coloured by range */}
            {bgPath && (
              <>
                <defs>
                  <linearGradient id={gradId} gradientUnits="userSpaceOnUse" x1={0} x2={0} y1={pad.t} y2={pad.t + plotH}>
                    <stop offset={bandTop} style={{ stopColor: C.high }} />
                    <stop offset={bandTop} style={{ stopColor: C.in }} />
                    <stop offset={bandBottom} style={{ stopColor: C.in }} />
                    <stop offset={bandBottom} style={{ stopColor: C.low }} />
                  </linearGradient>
                </defs>
                <path d={bgPath} fill="none" stroke={`url(#${gradId})`} strokeWidth={2.75}
                  strokeLinecap="round" strokeLinejoin="round" />
              </>
            )}
            {bgPath && estPath && (
              <path d={estPath} fill="none" stroke={`url(#${gradId})`} strokeOpacity={0.6} strokeWidth={2.25}
                strokeDasharray="2 5" strokeLinecap="round" strokeLinejoin="round" />
            )}
            {showDots && bgInWin.map((r) => (
              <circle key={`bg-${r.ts}`} cx={xOf(r.ts)} cy={yOf(r.mgdl)} r={2.75}
                style={{ fill: rangeColor(r.mgdl, targetLow, targetHigh) }} />
            ))}

            {/* doses on the live line */}
            {dosesInWin.map((d) => {
              const p = dosePos(d, bgInWin);
              const basal = d.kind === "basal";
              const color = basal ? C.basal : C.bolus;
              return (
                <g key={`d-${d.id ?? d.ts}-${d.units}`}>
                  {basal ? (
                    <rect x={p.x - 5} y={p.y - 5} width={10} height={10} rx={1.5}
                      transform={`rotate(45 ${p.x} ${p.y})`}
                      style={{ fill: color, stroke: C.surface }} strokeWidth={2} />
                  ) : (
                    <circle cx={p.x} cy={p.y} r={5.5} style={{ fill: color, stroke: C.surface }} strokeWidth={2} />
                  )}
                  <text x={p.x} y={p.y - 11} textAnchor="middle" className="num" fontSize={13} fontWeight={700}
                    style={{ fill: color, ...halo }}>
                    {d.units}U{basal ? " basal" : ""}
                  </text>
                </g>
              );
            })}

            {/* newest reading: ringed, value beside it */}
            {!isHistorical && latest && !inspect && (
              <g>
                <circle cx={xOf(latest.ts)} cy={yOf(latest.mgdl)} r={9}
                  style={{ fill: rangeColor(latest.mgdl, targetLow, targetHigh) }} fillOpacity={0.22} />
                <circle cx={xOf(latest.ts)} cy={yOf(latest.mgdl)} r={4.5}
                  style={{ fill: rangeColor(latest.mgdl, targetLow, targetHigh), stroke: C.surface }} strokeWidth={2} />
                <text x={xOf(latest.ts) - 12} y={yOf(latest.mgdl) - 12} textAnchor="end" className="num"
                  fontSize={14} fontWeight={700} style={{ fill: C.ink, ...halo }}>
                  {latest.mgdl}
                </text>
              </g>
            )}

            {/* tapped point */}
            {inspectOn && inspect && (
              <g>
                <line x1={inspectX} x2={inspectX} y1={pad.t} y2={pad.t + plotH}
                  stroke="currentColor" strokeOpacity={0.4} strokeWidth={1} />
                {inspect.est ? (
                  <circle cx={inspectX} cy={yOf(inspect.mgdl)} r={5}
                    style={{ fill: C.surface, stroke: rangeColor(inspect.mgdl, targetLow, targetHigh) }} strokeWidth={2.5} />
                ) : (
                  <circle cx={inspectX} cy={yOf(inspect.mgdl)} r={5.5}
                    style={{ fill: inspect.ago ? C.ink : rangeColor(inspect.mgdl, targetLow, targetHigh), stroke: C.surface }}
                    strokeWidth={2.5} />
                )}
              </g>
            )}
          </svg>

          {inspectOn && inspect && (
            <div
              className="num pointer-events-none absolute rounded-lg bg-surface ring-1 ring-ink/15 px-2.5 py-1.5 shadow-lg shadow-black/20 text-center whitespace-nowrap"
              style={{
                left: inspectX,
                top: pad.t + 4,
                transform: inspectX > W * 0.7 ? "translateX(calc(-100% - 8px))" : inspectX < W * 0.3 ? "translateX(8px)" : "translateX(-50%)",
              }}
            >
              <div className="text-lg font-bold leading-none text-ink">
                {inspect.est && "~"}{inspect.mgdl} <span className="text-[11px] font-normal text-muted">mg/dL</span>
              </div>
              <div className="text-[11px] text-muted mt-0.5">
                {clock(inspect.ts)}{inspect.ago && ` · ${inspect.ago}`}{inspect.est && " · estimated"}
              </div>
            </div>
          )}

          {bgInWin.length === 0 && dosesInWin.length === 0 && (
            <div className="pointer-events-none absolute inset-x-0 text-center text-xs text-muted" style={{ top: H / 2 - 8 }}>
              no data in this window
            </div>
          )}
        </div>

        {/* key */}
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 pb-2.5 text-[11px] text-muted">
          <span className="flex items-center gap-1.5">
            <svg width="16" height="6" aria-hidden><line x1="1" x2="15" y1="3" y2="3" style={{ stroke: C.in }} strokeWidth="2.75" strokeLinecap="round" /></svg>
            BG
          </span>
          {estPath && (
            <span className="flex items-center gap-1.5">
              <svg width="18" height="6" aria-hidden><line x1="1" x2="17" y1="3" y2="3" style={{ stroke: C.in }} strokeOpacity="0.6" strokeWidth="2.25" strokeDasharray="2 5" strokeLinecap="round" /></svg>
              estimated
            </span>
          )}
          <span className="flex items-center gap-1.5">
            <svg width="10" height="10" aria-hidden><circle cx="5" cy="5" r="4" style={{ fill: C.bolus }} /></svg>
            bolus
          </span>
          <span className="flex items-center gap-1.5">
            <svg width="10" height="10" aria-hidden><rect x="2" y="2" width="6" height="6" transform="rotate(45 5 5)" style={{ fill: C.basal }} /></svg>
            basal
          </span>
          {overlay24 && (
            <span className="flex items-center gap-1.5">
              <svg width="18" height="6" aria-hidden className="text-ink"><line x1="1" x2="17" y1="3" y2="3" stroke="currentColor" strokeOpacity="0.55" strokeWidth="2" strokeDasharray="6 4" /></svg>
              −24h
            </span>
          )}
          {overlay48 && (
            <span className="flex items-center gap-1.5">
              <svg width="18" height="6" aria-hidden className="text-ink"><line x1="1" x2="17" y1="3" y2="3" stroke="currentColor" strokeOpacity="0.4" strokeWidth="1.75" strokeDasharray="1.5 4" strokeLinecap="round" /></svg>
              −48h
            </span>
          )}
          {(overlay24 || overlay48) && (
            <span className="flex items-center gap-1.5">
              <svg width="10" height="10" aria-hidden><circle cx="5" cy="5" r="3.5" style={{ fill: "none", stroke: C.bolus }} strokeWidth="1.75" /></svg>
              past bolus
            </span>
          )}
        </div>
      </div>
    </div>
  );
}

// Memoized: the home screen re-renders on its own state and every clock tick.
export const Chart5h = memo(Chart5hImpl);
