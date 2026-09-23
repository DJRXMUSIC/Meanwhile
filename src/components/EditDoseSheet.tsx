"use client";

import { useState } from "react";
import { deleteInsulin, updateInsulin } from "@/lib/db";
import type { InsulinDose } from "@/lib/types";

export function EditDoseSheet({
  dose,
  onClose,
}: {
  dose: InsulinDose;
  onClose: () => void;
}) {
  const [units, setUnits] = useState(dose.units);
  const [tsLocal, setTsLocal] = useState(toLocalInput(dose.ts));
  const [saving, setSaving] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);

  // Doses are whole units. A legacy row may still hold a fraction, so
  // rather than rounding it behind the user's back the save is blocked
  // until they pick a whole number — the steppers snap to one.
  const wholeUnits = Number.isInteger(units);

  const save = async () => {
    if (!dose.id) return;
    setSaving(true);
    try {
      const newTs = fromLocalInput(tsLocal);
      const patch: Partial<InsulinDose> = {
        units: Math.max(0, units),
        ts: newTs,
        backdated_min: Math.max(0, Math.round((Date.now() - newTs) / 60_000)),
      };
      // Every note the app writes itself records the original entry
      // ("quick 4U"), so it goes stale the moment the amount changes and
      // would sit under the new number contradicting it.
      if (units !== dose.units) patch.note = undefined;
      await updateInsulin(dose.id, patch);
      onClose();
    } finally {
      setSaving(false);
    }
  };

  const remove = async () => {
    if (!dose.id) return;
    if (!confirmDelete) {
      setConfirmDelete(true);
      return;
    }
    await deleteInsulin(dose.id);
    onClose();
  };

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
        <h2 className="text-lg font-semibold">Edit dose</h2>
        <p className="text-xs text-muted mt-0.5 break-words">
          {dose.source ? `Logged via ${dose.source} · ` : ""}
          original time {new Date(dose.ts).toLocaleString([], { dateStyle: "short", timeStyle: "short" })}
        </p>

        <div className="mt-4">
          <div className="text-xs uppercase tracking-wider text-muted mb-1">Units</div>
          <div className="flex items-center gap-2 min-w-0">
            <button
              onClick={() => setUnits((u) => Math.max(0, Math.round(u) - 1))}
              className="size-12 shrink-0 rounded-xl bg-surface2 text-xl"
            >−</button>
            <input
              type="number"
              inputMode="numeric"
              step="1"
              min="0"
              value={units}
              onChange={(e) => setUnits(Math.max(0, Number(e.target.value) || 0))}
              className="num min-w-0 flex-1 h-12 text-center rounded-xl bg-surface2 text-2xl font-semibold outline-none ring-1 ring-white/5 focus:ring-accent/60"
            />
            <button
              onClick={() => setUnits((u) => Math.round(u) + 1)}
              className="size-12 shrink-0 rounded-xl bg-surface2 text-xl"
            >+</button>
          </div>
          {!wholeUnits && (
            <p className="text-[11px] text-warn mt-1.5">Whole units only — use − or + to round.</p>
          )}
        </div>

        <div className="mt-5">
          <div className="text-xs uppercase tracking-wider text-muted mb-1">Time</div>
          <input
            type="datetime-local"
            value={tsLocal}
            onChange={(e) => setTsLocal(e.target.value)}
            className="num block w-full max-w-full h-12 rounded-xl bg-surface2 px-3 text-base outline-none ring-1 ring-white/5 focus:ring-accent/60 box-border"
          />
        </div>

        <div className="mt-5 grid grid-cols-2 gap-2">
          <button
            onClick={save}
            disabled={saving || units < 0 || !wholeUnits}
            className="col-span-2 rounded-xl bg-accent text-white px-3 py-3 text-sm font-semibold disabled:opacity-40"
          >
            {saving ? "Saving…" : `Save ${units}U`}
          </button>
          <button
            onClick={onClose}
            className="rounded-xl bg-surface2 px-3 py-3 text-sm"
          >
            Cancel
          </button>
          <button
            onClick={remove}
            className={`rounded-xl px-3 py-3 text-sm font-medium transition truncate ${
              confirmDelete
                ? "bg-bad text-white"
                : "bg-bad/15 ring-1 ring-bad/40 text-bad"
            }`}
          >
            {confirmDelete ? "Confirm delete" : "Delete"}
          </button>
        </div>
      </div>
    </div>
  );
}

function toLocalInput(ts: number): string {
  const d = new Date(ts);
  const pad = (n: number) => n.toString().padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function fromLocalInput(s: string): number {
  const d = new Date(s);
  return Number.isFinite(d.getTime()) ? d.getTime() : Date.now();
}
