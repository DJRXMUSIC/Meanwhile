"use client";

import { useEffect, useRef, useState } from "react";
import { logInsulin } from "@/lib/db";

const QUICK_UNITS = [1, 2, 3, 4, 5, 6, 7, 8, 9];

export function LearnPanel() {
  const [busy, setBusy] = useState<number | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [open, setOpen] = useState(false);

  const quickLog = async (units: number) => {
    setBusy(units);
    try {
      const now = Date.now();
      await logInsulin({
        ts: now,
        units,
        kind: "bolus",
        source: "quick",
        entered_at: now,
        backdated_min: 0,
        note: `quick ${units}U`,
      });
      flash(`Logged ${units}U`);
    } finally {
      setBusy(null);
    }
  };

  const flash = (msg: string) => {
    setToast(msg);
    setTimeout(() => setToast((t) => (t === msg ? null : t)), 1800);
  };

  return (
    <div className="px-3">
      <div className="rounded-2xl bg-surface ring-1 ring-white/10 p-3">
        <div className="flex items-center justify-between mb-2">
          <div className="text-xs uppercase tracking-wider text-muted">Quick bolus</div>
          <button
            onClick={() => setOpen(true)}
            className="text-xs rounded-full bg-accent/15 ring-1 ring-accent/40 text-accent px-3 py-1"
          >
            Custom dose…
          </button>
        </div>
        <div className="grid grid-cols-3 gap-1.5">
          {QUICK_UNITS.map((u) => (
            <button
              key={u}
              disabled={busy === u}
              onClick={() => quickLog(u)}
              className={`num h-12 rounded-xl bg-surface2 active:scale-[0.97] transition font-semibold ${
                busy === u ? "opacity-50" : "hover:bg-surface2/80"
              }`}
            >
              {u}<span className="text-xs text-muted ml-0.5">U</span>
            </button>
          ))}
        </div>
        {toast && (
          <div className="mt-2 text-center text-xs text-good">{toast}</div>
        )}
      </div>

      {open && <CustomDoseSheet onClose={() => setOpen(false)} onLogged={(m) => flash(m)} />}
    </div>
  );
}

function CustomDoseSheet({
  onClose,
  onLogged,
}: {
  onClose: () => void;
  onLogged: (msg: string) => void;
}) {
  // Time model: either a preset offset in minutes (0/10/20/30) or an
  // absolute clock time picked via the time input. Defaults to "Now".
  const [unitsText, setUnitsText] = useState("");
  const [offsetMin, setOffsetMin] = useState<number | null>(0); // null = "Custom time"
  const [customTimeStr, setCustomTimeStr] = useState(() => toHHMM(new Date()));
  const [submitting, setSubmitting] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  // Auto-focus the units input on open and pre-select so the user can
  // immediately type the dose with the native numeric keyboard.
  useEffect(() => {
    const id = window.setTimeout(() => {
      inputRef.current?.focus();
      inputRef.current?.select();
    }, 50);
    return () => window.clearTimeout(id);
  }, []);

  const ts =
    offsetMin != null
      ? Date.now() - offsetMin * 60_000
      : hhmmToTsToday(customTimeStr);

  const effectiveOffset = Math.max(0, Math.round((Date.now() - ts) / 60_000));
  const units = parseFloat(unitsText);
  const validUnits = Number.isFinite(units) && units > 0;

  const submit = async () => {
    if (!validUnits) return;
    setSubmitting(true);
    try {
      const enteredAt = Date.now();
      await logInsulin({
        ts,
        units,
        kind: "bolus",
        source: "custom",
        entered_at: enteredAt,
        backdated_min: effectiveOffset,
        note: effectiveOffset > 0 ? `backdated ${effectiveOffset}m` : undefined,
      });
      onLogged(
        `Logged ${units}U bolus · ${effectiveOffset === 0 ? "now" : `${effectiveOffset}m ago`}`
      );
      onClose();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-30 bg-black/60 backdrop-blur-sm grid items-end"
      onClick={onClose}
    >
      <div
        className="rounded-t-3xl bg-surface ring-1 ring-white/10 p-4 pb-[calc(env(safe-area-inset-bottom)+16px)] mx-auto w-full max-w-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mx-auto h-1 w-12 rounded-full bg-white/15 mb-4" />
        <h2 className="text-lg font-semibold">Custom bolus</h2>

        <div className="mt-3">
          <div className="text-xs uppercase tracking-wider text-muted mb-1">Units</div>
          <input
            ref={inputRef}
            type="number"
            inputMode="decimal"
            step="0.5"
            min="0"
            placeholder="0.0"
            value={unitsText}
            onChange={(e) => setUnitsText(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter") submit(); }}
            className="num w-full h-14 text-center rounded-xl bg-surface2 text-3xl font-semibold outline-none ring-1 ring-white/5 focus:ring-accent/60"
          />
        </div>

        <div className="mt-4">
          <div className="flex items-baseline justify-between mb-1">
            <div className="text-xs uppercase tracking-wider text-muted">When</div>
            <div className="text-sm text-muted">
              {new Date(ts).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}
              {effectiveOffset > 0 && ` · ${effectiveOffset}m ago`}
            </div>
          </div>

          <div className="grid grid-cols-4 gap-1.5">
            <TimeBtn label="Now"    active={offsetMin === 0}  onClick={() => setOffsetMin(0)} />
            <TimeBtn label="−10m"   active={offsetMin === 10} onClick={() => setOffsetMin(10)} />
            <TimeBtn label="−20m"   active={offsetMin === 20} onClick={() => setOffsetMin(20)} />
            <TimeBtn label="−30m"   active={offsetMin === 30} onClick={() => setOffsetMin(30)} />
          </div>

          <button
            onClick={() => setOffsetMin(offsetMin == null ? 0 : null)}
            className={`mt-2 w-full rounded-xl px-3 py-2 text-sm font-medium transition ${
              offsetMin == null
                ? "bg-accent/15 ring-1 ring-accent/50 text-ink"
                : "bg-surface2/60 ring-1 ring-white/5 text-muted"
            }`}
          >
            {offsetMin == null ? "Pick another time ▾" : "Custom time…"}
          </button>

          {offsetMin == null && (
            <div className="mt-2">
              <input
                type="time"
                value={customTimeStr}
                onChange={(e) => setCustomTimeStr(e.target.value)}
                className="num w-full h-12 text-center rounded-xl bg-surface2 text-base outline-none ring-1 ring-white/5 focus:ring-accent/60"
              />
              <p className="text-[11px] text-muted mt-1">
                Time today; if it's in the future we'll use yesterday's time.
              </p>
            </div>
          )}
        </div>

        <div className="mt-5 flex gap-2">
          <button onClick={onClose} className="flex-1 rounded-xl bg-surface2 px-3 py-3 text-sm">Cancel</button>
          <button
            onClick={submit}
            disabled={submitting || !validUnits}
            className="flex-1 rounded-xl bg-accent text-white px-3 py-3 text-sm font-medium disabled:opacity-40"
          >
            {submitting ? "Logging…" : `Log ${validUnits ? units : "—"}U`}
          </button>
        </div>
      </div>
    </div>
  );
}

function TimeBtn({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className={`h-11 rounded-xl text-sm font-semibold transition ${
        active
          ? "bg-accent text-white"
          : "bg-surface2/60 ring-1 ring-white/5 text-ink hover:bg-surface2"
      }`}
    >
      {label}
    </button>
  );
}

function toHHMM(d: Date): string {
  const pad = (n: number) => n.toString().padStart(2, "0");
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function hhmmToTsToday(hhmm: string): number {
  const [h, m] = hhmm.split(":").map(Number);
  const d = new Date();
  d.setHours(h || 0, m || 0, 0, 0);
  let ts = d.getTime();
  // If the picked time is in the future, assume the user meant yesterday.
  if (ts > Date.now()) ts -= 24 * 3600_000;
  return ts;
}
