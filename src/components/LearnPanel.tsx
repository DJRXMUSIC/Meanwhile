"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { deleteCarbs, deleteInsulin, logCarbs, logInsulin, updateInsulin } from "@/lib/db";
import type { InsulinDose } from "@/lib/types";

const QUICK_UNITS = [1, 2, 3, 4, 5, 6, 7, 8, 9];
const CARB_PRESETS = [15, 30, 45, 60];

// How long an action toast stays on screen. Long enough to notice a
// mis-tap and undo it, short enough not to hang around.
const TOAST_MS = 6000;
// Two taps of the same value inside this window are almost certainly one
// intended dose registered twice.
const DUPLICATE_MS = 2000;

type ToastAction = { label: string; run: () => Promise<void> };
type Toast = { id: number; text: string; tone: "good" | "warn"; actions: ToastAction[] };

export function LearnPanel() {
  const [busy, setBusy] = useState<string | null>(null);
  const [toast, setToast] = useState<Toast | null>(null);
  const [openDose, setOpenDose] = useState(false);
  const [openCarbs, setOpenCarbs] = useState(false);

  // Keyed by toast id rather than by message text: two identical messages
  // used to cancel each other's timers, so the second toast could vanish
  // early or the first could clear the second.
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const toastSeq = useRef(0);
  const lastQuickRef = useRef<{ units: number; at: number } | null>(null);

  const show = useCallback(
    (text: string, actions: ToastAction[] = [], tone: "good" | "warn" = "good") => {
      const id = ++toastSeq.current;
      setToast({ id, text, tone, actions });
      if (timerRef.current) clearTimeout(timerRef.current);
      timerRef.current = setTimeout(() => {
        setToast((t) => (t && t.id === id ? null : t));
      }, TOAST_MS);
    },
    []
  );

  useEffect(() => {
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, []);

  const quickLog = async (units: number) => {
    const key = `u${units}`;
    setBusy(key);
    try {
      const now = Date.now();
      const id = await logInsulin({
        ts: now,
        units,
        kind: "bolus",
        source: "quick",
        entered_at: now,
        backdated_min: 0,
        note: `quick ${units}U`,
      });

      const prev = lastQuickRef.current;
      const duplicate = !!prev && prev.units === units && now - prev.at < DUPLICATE_MS;
      lastQuickRef.current = { units, at: now };

      show(
        duplicate ? `Logged ${units}U twice` : `Logged ${units}U`,
        [
          {
            label: duplicate ? "Undo one" : "Undo",
            run: async () => {
              await deleteInsulin(id);
              lastQuickRef.current = null;
              show(`Removed ${units}U`);
            },
          },
          {
            // Quick-log defaults to a meal bolus so the one-tap path stays
            // one tap; this makes the correction case reachable without a
            // trip to the dose list.
            label: "Correction",
            run: async () => {
              await updateInsulin(id, { kind: "correction" });
              show(`${units}U marked as correction`);
            },
          },
        ],
        duplicate ? "warn" : "good"
      );
    } finally {
      setBusy(null);
    }
  };

  const quickCarbs = async (grams: number) => {
    const key = `c${grams}`;
    setBusy(key);
    try {
      const now = Date.now();
      const id = await logCarbs({ ts: now, carbs_g: grams, description: `quick ${grams}g` });
      show(`Logged ${grams}g`, [
        {
          label: "Undo",
          run: async () => {
            await deleteCarbs(id);
            show(`Removed ${grams}g`);
          },
        },
      ]);
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="px-3 space-y-2">
      <div className="rounded-2xl bg-surface ring-1 ring-white/10 p-3">
        <div className="flex items-center justify-between mb-2">
          <div className="text-xs uppercase tracking-wider text-muted">Quick bolus</div>
          <button
            onClick={() => setOpenDose(true)}
            className="text-xs rounded-full bg-accent/15 ring-1 ring-accent/40 text-accent px-3 py-1"
          >
            Custom dose…
          </button>
        </div>
        <div className="grid grid-cols-3 gap-1.5">
          {QUICK_UNITS.map((u) => (
            <button
              key={u}
              disabled={busy === `u${u}`}
              onClick={() => quickLog(u)}
              aria-label={`Log ${u} units`}
              className={`num h-9 rounded-lg bg-surface2 active:scale-[0.97] transition text-sm font-semibold ${
                busy === `u${u}` ? "opacity-50" : "hover:bg-surface2/80"
              }`}
            >
              {u}<span className="text-[10px] text-muted ml-0.5">U</span>
            </button>
          ))}
        </div>
      </div>

      <div className="rounded-2xl bg-surface ring-1 ring-white/10 p-3">
        <div className="flex items-center justify-between mb-2">
          <div className="text-xs uppercase tracking-wider text-muted">Quick carbs</div>
          <button
            onClick={() => setOpenCarbs(true)}
            className="text-xs rounded-full bg-accent/15 ring-1 ring-accent/40 text-accent px-3 py-1"
          >
            Custom carbs…
          </button>
        </div>
        <div className="grid grid-cols-4 gap-1.5">
          {CARB_PRESETS.map((g) => (
            <button
              key={g}
              disabled={busy === `c${g}`}
              onClick={() => quickCarbs(g)}
              aria-label={`Log ${g} grams of carbohydrate`}
              className={`num h-9 rounded-lg bg-surface2 active:scale-[0.97] transition text-sm font-semibold ${
                busy === `c${g}` ? "opacity-50" : "hover:bg-surface2/80"
              }`}
            >
              {g}<span className="text-[10px] text-muted ml-0.5">g</span>
            </button>
          ))}
        </div>
      </div>

      {toast && (
        <div
          role="status"
          aria-live="polite"
          className={`flex items-center gap-2 rounded-xl px-3 py-2 ring-1 ${
            toast.tone === "warn"
              ? "bg-warn/15 ring-warn/40 text-warn"
              : "bg-good/10 ring-good/30 text-good"
          }`}
        >
          <span className="flex-1 min-w-0 text-xs truncate">{toast.text}</span>
          {toast.actions.map((a) => (
            <button
              key={a.label}
              onClick={async () => {
                if (timerRef.current) clearTimeout(timerRef.current);
                await a.run();
              }}
              className="shrink-0 rounded-full bg-white/10 ring-1 ring-white/15 text-ink px-2.5 py-1 text-xs font-medium"
            >
              {a.label}
            </button>
          ))}
        </div>
      )}

      {openDose && (
        <CustomDoseSheet
          onClose={() => setOpenDose(false)}
          onLogged={(msg, undo) => show(msg, [{ label: "Undo", run: undo }])}
        />
      )}
      {openCarbs && (
        <CustomCarbSheet
          onClose={() => setOpenCarbs(false)}
          onLogged={(msg, undo) => show(msg, [{ label: "Undo", run: undo }])}
        />
      )}
    </div>
  );
}

// ---- Shared sheet chrome ------------------------------------------------

function Sheet({ children, onClose }: { children: React.ReactNode; onClose: () => void }) {
  return (
    <div
      className="fixed inset-0 z-30 bg-black/60 backdrop-blur-sm grid items-end"
      onClick={onClose}
    >
      <div
        className="rounded-t-3xl bg-surface ring-1 ring-white/10 mx-auto w-full max-w-xl max-h-[88svh] overflow-y-auto overflow-x-hidden"
        style={{
          paddingLeft: "max(env(safe-area-inset-left), 16px)",
          paddingRight: "max(env(safe-area-inset-right), 16px)",
          paddingTop: 16,
          paddingBottom: "calc(env(safe-area-inset-bottom) + 16px)",
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mx-auto h-1 w-12 rounded-full bg-white/15 mb-4" />
        {children}
      </div>
    </div>
  );
}

// Time model shared by both sheets: either a preset offset in minutes
// (0/10/20/30) or an absolute clock time. Defaults to "Now".
function useWhen() {
  const [offsetMin, setOffsetMin] = useState<number | null>(0); // null = "Custom time"
  const [customTimeStr, setCustomTimeStr] = useState(() => toHHMM(new Date()));

  const ts =
    offsetMin != null ? Date.now() - offsetMin * 60_000 : hhmmToTsToday(customTimeStr);
  const effectiveOffset = Math.max(0, Math.round((Date.now() - ts) / 60_000));

  return { offsetMin, setOffsetMin, customTimeStr, setCustomTimeStr, ts, effectiveOffset };
}

function WhenPicker({ when }: { when: ReturnType<typeof useWhen> }) {
  const { offsetMin, setOffsetMin, customTimeStr, setCustomTimeStr, ts, effectiveOffset } = when;
  return (
    <div className="mt-4">
      <div className="flex items-baseline justify-between mb-1">
        <div className="text-xs uppercase tracking-wider text-muted">When</div>
        <div className="text-sm text-muted">
          {new Date(ts).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}
          {effectiveOffset > 0 && ` · ${effectiveOffset}m ago`}
        </div>
      </div>

      <div className="grid grid-cols-4 gap-1.5">
        <TimeBtn label="Now"  active={offsetMin === 0}  onClick={() => setOffsetMin(0)} />
        <TimeBtn label="−10m" active={offsetMin === 10} onClick={() => setOffsetMin(10)} />
        <TimeBtn label="−20m" active={offsetMin === 20} onClick={() => setOffsetMin(20)} />
        <TimeBtn label="−30m" active={offsetMin === 30} onClick={() => setOffsetMin(30)} />
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
            className="num block w-full max-w-full h-12 text-center rounded-xl bg-surface2 text-base outline-none ring-1 ring-white/5 focus:ring-accent/60 box-border"
          />
          <p className="text-[11px] text-muted mt-1">
            Time today; if it&apos;s in the future we&apos;ll use yesterday&apos;s time.
          </p>
        </div>
      )}
    </div>
  );
}

// ---- Custom bolus -------------------------------------------------------

function CustomDoseSheet({
  onClose,
  onLogged,
}: {
  onClose: () => void;
  onLogged: (msg: string, undo: () => Promise<void>) => void;
}) {
  const [unitsText, setUnitsText] = useState("");
  const [kind, setKind] = useState<Extract<InsulinDose["kind"], "bolus" | "correction">>("bolus");
  const [submitting, setSubmitting] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const when = useWhen();

  // Auto-focus the units input on open and pre-select so the user can
  // immediately type the dose with the native numeric keyboard.
  useEffect(() => {
    const id = window.setTimeout(() => {
      inputRef.current?.focus();
      inputRef.current?.select();
    }, 50);
    return () => window.clearTimeout(id);
  }, []);

  const units = parseFloat(unitsText);
  const validUnits = Number.isFinite(units) && units > 0;

  const submit = async () => {
    if (!validUnits) return;
    setSubmitting(true);
    try {
      const { ts, effectiveOffset } = when;
      const enteredAt = Date.now();
      const id = await logInsulin({
        ts,
        units,
        kind,
        source: "custom",
        entered_at: enteredAt,
        backdated_min: effectiveOffset,
        note: effectiveOffset > 0 ? `backdated ${effectiveOffset}m` : undefined,
      });
      onLogged(
        `Logged ${units}U ${kind === "correction" ? "correction" : "bolus"} · ${
          effectiveOffset === 0 ? "now" : `${effectiveOffset}m ago`
        }`,
        async () => { await deleteInsulin(id); }
      );
      onClose();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Sheet onClose={onClose}>
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
        <div className="text-xs uppercase tracking-wider text-muted mb-1">Kind</div>
        <KindChips value={kind} onChange={setKind} />
      </div>

      <WhenPicker when={when} />

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
    </Sheet>
  );
}

// ---- Custom carbs -------------------------------------------------------

function CustomCarbSheet({
  onClose,
  onLogged,
}: {
  onClose: () => void;
  onLogged: (msg: string, undo: () => Promise<void>) => void;
}) {
  const [gramsText, setGramsText] = useState("");
  const [description, setDescription] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const when = useWhen();

  useEffect(() => {
    const id = window.setTimeout(() => {
      inputRef.current?.focus();
      inputRef.current?.select();
    }, 50);
    return () => window.clearTimeout(id);
  }, []);

  const grams = parseFloat(gramsText);
  const validGrams = Number.isFinite(grams) && grams > 0;

  const submit = async () => {
    if (!validGrams) return;
    setSubmitting(true);
    try {
      const { ts, effectiveOffset } = when;
      const id = await logCarbs({
        ts,
        carbs_g: grams,
        description: description.trim() || undefined,
      });
      onLogged(
        `Logged ${grams}g · ${effectiveOffset === 0 ? "now" : `${effectiveOffset}m ago`}`,
        async () => { await deleteCarbs(id); }
      );
      onClose();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Sheet onClose={onClose}>
      <h2 className="text-lg font-semibold">Custom carbs</h2>

      <div className="mt-3">
        <div className="text-xs uppercase tracking-wider text-muted mb-1">Grams</div>
        <input
          ref={inputRef}
          type="number"
          inputMode="decimal"
          step="1"
          min="0"
          placeholder="0"
          value={gramsText}
          onChange={(e) => setGramsText(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") submit(); }}
          className="num w-full h-14 text-center rounded-xl bg-surface2 text-3xl font-semibold outline-none ring-1 ring-white/5 focus:ring-accent/60"
        />
      </div>

      <div className="mt-4">
        <div className="text-xs uppercase tracking-wider text-muted mb-1">What (optional)</div>
        <input
          type="text"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="bagel, pasta, …"
          className="w-full h-12 rounded-xl bg-surface2 px-3 text-base outline-none ring-1 ring-white/5 focus:ring-accent/60"
        />
      </div>

      <WhenPicker when={when} />

      <div className="mt-5 flex gap-2">
        <button onClick={onClose} className="flex-1 rounded-xl bg-surface2 px-3 py-3 text-sm">Cancel</button>
        <button
          onClick={submit}
          disabled={submitting || !validGrams}
          className="flex-1 rounded-xl bg-accent text-white px-3 py-3 text-sm font-medium disabled:opacity-40"
        >
          {submitting ? "Logging…" : `Log ${validGrams ? grams : "—"}g`}
        </button>
      </div>
    </Sheet>
  );
}

// ---- Bits ---------------------------------------------------------------

export function KindChips({
  value,
  onChange,
}: {
  value: "bolus" | "correction";
  onChange: (k: "bolus" | "correction") => void;
}) {
  const chip = (k: "bolus" | "correction", label: string) => (
    <button
      key={k}
      onClick={() => onChange(k)}
      className={`h-11 rounded-xl text-sm font-semibold transition ${
        value === k
          ? "bg-accent text-white"
          : "bg-surface2/60 ring-1 ring-white/5 text-ink hover:bg-surface2"
      }`}
    >
      {label}
    </button>
  );
  return (
    <div className="grid grid-cols-2 gap-1.5">
      {chip("bolus", "Meal")}
      {chip("correction", "Correction")}
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
