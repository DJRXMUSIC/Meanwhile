"use client";

import type { WindowHours } from "./Chart5h";

const OPTIONS: WindowHours[] = [3, 5, 8, 24];

export function ChartControls({
  windowHours,
  onWindowChange,
  panMs,
  onSetPan,
  overlay24,
  overlay48,
  onToggleOverlay24,
  onToggleOverlay48,
}: {
  windowHours: WindowHours;
  onWindowChange: (w: WindowHours) => void;
  panMs: number;
  onSetPan: (ms: number) => void;
  overlay24: boolean;
  overlay48: boolean;
  onToggleOverlay24: () => void;
  onToggleOverlay48: () => void;
}) {
  const isLive = panMs < 60_000;
  const at24 = Math.abs(panMs - 24 * 3600_000) < 30 * 60_000;
  const at48 = Math.abs(panMs - 48 * 3600_000) < 30 * 60_000;

  return (
    <div className="mx-3 space-y-2">
      <div className="flex items-center justify-between gap-2">
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
        <div className="flex gap-1 rounded-xl bg-surface2/60 p-1 ring-1 ring-white/5">
          <JumpBtn label="Now" active={isLive}  onClick={() => onSetPan(0)} />
          <JumpBtn label="−24h" active={at24}   onClick={() => onSetPan(24 * 3600_000)} />
          <JumpBtn label="−48h" active={at48}   onClick={() => onSetPan(48 * 3600_000)} />
        </div>
      </div>
      <div className="flex items-center gap-2">
        <span className="text-[10px] uppercase tracking-wider text-muted">Overlay</span>
        <OverlayToggle label="−24h ago" on={overlay24} onClick={onToggleOverlay24} />
        <OverlayToggle label="−48h ago" on={overlay48} onClick={onToggleOverlay48} />
      </div>
    </div>
  );
}

function JumpBtn({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className={`px-3 py-1.5 rounded-lg text-sm font-medium transition ${
        active ? (label === "Now" ? "bg-accent text-white" : "bg-warn text-black") : "text-muted hover:text-ink"
      }`}
    >
      {label}
    </button>
  );
}

function OverlayToggle({ label, on, onClick }: { label: string; on: boolean; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className={`flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs transition ${
        on ? "bg-accent/15 ring-1 ring-accent/50 text-ink" : "bg-surface2/60 ring-1 ring-white/5 text-muted"
      }`}
    >
      <span
        className={`size-3 rounded-full transition ${on ? "bg-accent" : "bg-white/15"}`}
      />
      {label}
    </button>
  );
}
