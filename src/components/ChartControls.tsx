"use client";

import type { WindowHours } from "./Chart5h";

const OPTIONS: WindowHours[] = [3, 5, 8, 24];

export function ChartControls({
  windowHours,
  onWindowChange,
  panMs,
  onResetPan,
}: {
  windowHours: WindowHours;
  onWindowChange: (w: WindowHours) => void;
  panMs: number;
  onResetPan: () => void;
}) {
  const panned = panMs > 60_000;
  return (
    <div className="mx-3 flex items-center justify-between gap-2">
      <div className="flex gap-1 rounded-xl bg-surface2/60 p-1 ring-1 ring-white/5">
        {OPTIONS.map((h) => {
          const active = h === windowHours;
          return (
            <button
              key={h}
              onClick={() => onWindowChange(h)}
              className={`px-3 py-1.5 rounded-lg text-sm font-medium transition ${
                active ? "bg-accent text-white" : "text-muted hover:text-ink"
              }`}
            >
              {h}h
            </button>
          );
        })}
      </div>
      <button
        onClick={onResetPan}
        disabled={!panned}
        className={`px-3 py-1.5 rounded-xl text-sm font-medium transition ${
          panned ? "bg-surface2 text-ink hover:bg-surface2/80" : "bg-transparent text-muted/50"
        }`}
      >
        ⟳ Now
      </button>
    </div>
  );
}
