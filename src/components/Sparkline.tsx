"use client";

import type { BgReading } from "@/lib/types";

export function Sparkline({ readings }: { readings: BgReading[] }) {
  if (!readings.length) {
    return <div className="h-20 mx-4 rounded-xl bg-surface/40 grid place-items-center text-muted text-xs">no recent BG data</div>;
  }
  const w = 600;
  const h = 80;
  const pad = 6;
  const xs = readings.map((r) => r.ts);
  const ys = readings.map((r) => r.mgdl);
  const minX = Math.min(...xs), maxX = Math.max(...xs);
  const minY = Math.min(54, ...ys), maxY = Math.max(220, ...ys);
  const dx = maxX - minX || 1;
  const dy = maxY - minY || 1;
  const px = (t: number) => pad + ((t - minX) / dx) * (w - pad * 2);
  const py = (v: number) => h - pad - ((v - minY) / dy) * (h - pad * 2);

  const d = readings.map((r, i) => `${i === 0 ? "M" : "L"} ${px(r.ts).toFixed(1)} ${py(r.mgdl).toFixed(1)}`).join(" ");

  // 70 and 180 lines
  const y70 = py(70);
  const y180 = py(180);

  return (
    <div className="mx-4">
      <svg viewBox={`0 0 ${w} ${h}`} className="w-full h-20" preserveAspectRatio="none">
        <rect x="0" y={y180} width={w} height={py(70) - py(180)} fill="rgba(61,220,151,0.08)" />
        <line x1="0" x2={w} y1={y70} y2={y70} stroke="rgba(255,184,77,0.35)" strokeDasharray="3 3" />
        <line x1="0" x2={w} y1={y180} y2={y180} stroke="rgba(255,184,77,0.35)" strokeDasharray="3 3" />
        <path d={d} fill="none" stroke="#7c5cff" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
        {readings.length > 0 && (
          <circle cx={px(readings[readings.length - 1].ts)} cy={py(readings[readings.length - 1].mgdl)} r="3" fill="#7c5cff" />
        )}
      </svg>
    </div>
  );
}
