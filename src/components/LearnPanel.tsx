"use client";

import { useState } from "react";
import { logInsulin } from "@/lib/db";

const QUICK_UNITS = [1, 2, 3, 4, 5, 6, 7];

export function LearnPanel() {
  const [busy, setBusy] = useState<number | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [open, setOpen] = useState(false);

  const quickLog = async (units: number) => {
    setBusy(units);
    try {
      await logInsulin({ ts: Date.now(), units, kind: "basal", note: `quick ${units}U basal` });
      flash(`Logged ${units}U basal`);
    } finally {
      setBusy(null);
    }
  };

  const flash = (msg: string) => {
    setToast(msg);
    setTimeout(() => setToast((t) => (t === msg ? null : t)), 1800);
  };

  return (
    <div className="px-4 pb-[calc(env(safe-area-inset-bottom)+12px)] pt-2 sticky bottom-0 bg-gradient-to-t from-bg via-bg to-transparent">
      <div className="rounded-3xl bg-surface ring-1 ring-white/10 p-3">
        <div className="flex items-center justify-between mb-2">
          <div className="text-xs uppercase tracking-wider text-muted">Quick basal</div>
          <button
            onClick={() => setOpen(true)}
            className="text-xs rounded-full bg-accent/15 ring-1 ring-accent/40 text-accent px-3 py-1"
          >
            Custom dose…
          </button>
        </div>
        <div className="grid grid-cols-7 gap-1.5">
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
  const [units, setUnits] = useState(2);
  const [stepsBack, setStepsBack] = useState(0); // each step = 30 min, max 16 = 8h
  const [submitting, setSubmitting] = useState(false);

  const offsetMin = stepsBack * 30;
  const ts = Date.now() - offsetMin * 60_000;

  const labelFor = (steps: number) => {
    if (steps === 0) return "Now";
    const m = steps * 30;
    const h = Math.floor(m / 60);
    const r = m % 60;
    if (h === 0) return `${r}m ago`;
    if (r === 0) return `${h}h ago`;
    return `${h}h ${r}m ago`;
  };

  const submit = async () => {
    if (units <= 0) return;
    setSubmitting(true);
    try {
      await logInsulin({ ts, units, kind: "bolus", note: offsetMin > 0 ? `backdated ${offsetMin}m` : undefined });
      onLogged(`Logged ${units}U bolus · ${labelFor(stepsBack)}`);
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
        <p className="text-xs text-muted mt-0.5">Adjust units and backdate up to 8 hours.</p>

        <div className="mt-4">
          <div className="text-xs uppercase tracking-wider text-muted mb-1">Units</div>
          <div className="flex items-center gap-2">
            <button
              onClick={() => setUnits((u) => Math.max(0, +(u - 0.5).toFixed(1)))}
              className="size-12 rounded-xl bg-surface2 text-xl"
            >−</button>
            <input
              type="number"
              inputMode="decimal"
              step="0.5"
              min="0"
              value={units}
              onChange={(e) => setUnits(Math.max(0, Number(e.target.value) || 0))}
              className="num flex-1 h-12 text-center rounded-xl bg-surface2 text-2xl font-semibold outline-none ring-1 ring-white/5 focus:ring-accent/60"
            />
            <button
              onClick={() => setUnits((u) => +(u + 0.5).toFixed(1))}
              className="size-12 rounded-xl bg-surface2 text-xl"
            >+</button>
          </div>
        </div>

        <div className="mt-5">
          <div className="flex items-baseline justify-between mb-1">
            <div className="text-xs uppercase tracking-wider text-muted">When</div>
            <div className="text-sm">{labelFor(stepsBack)} <span className="text-muted">· {new Date(ts).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</span></div>
          </div>
          <input
            type="range"
            min={0}
            max={16}
            step={1}
            value={stepsBack}
            onChange={(e) => setStepsBack(Number(e.target.value))}
            className="w-full accent-accent"
          />
          <div className="flex justify-between text-[10px] text-muted mt-1">
            <span>now</span><span>2h</span><span>4h</span><span>6h</span><span>8h ago</span>
          </div>
        </div>

        <div className="mt-5 flex gap-2">
          <button onClick={onClose} className="flex-1 rounded-xl bg-surface2 px-3 py-3 text-sm">Cancel</button>
          <button
            onClick={submit}
            disabled={submitting || units <= 0}
            className="flex-1 rounded-xl bg-accent text-white px-3 py-3 text-sm font-medium disabled:opacity-40"
          >
            {submitting ? "Logging…" : `Log ${units}U`}
          </button>
        </div>
      </div>
    </div>
  );
}
