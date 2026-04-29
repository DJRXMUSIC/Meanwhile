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

  const save = async () => {
    if (!dose.id) return;
    setSaving(true);
    try {
      const newTs = fromLocalInput(tsLocal);
      const patch: Partial<InsulinDose> = {
        units: Math.max(0, units),
        ts: newTs,
        // Capture that this row was edited; preserve original entry context.
        backdated_min: Math.max(0, Math.round((Date.now() - newTs) / 60_000)),
      };
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
        className="rounded-t-3xl bg-surface ring-1 ring-white/10 p-4 pb-[calc(env(safe-area-inset-bottom)+16px)] mx-auto w-full max-w-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mx-auto h-1 w-12 rounded-full bg-white/15 mb-4" />
        <h2 className="text-lg font-semibold">Edit dose</h2>
        <p className="text-xs text-muted mt-0.5">
          {dose.source ? `Logged via ${dose.source} · ` : ""}
          original time {new Date(dose.ts).toLocaleString([], { dateStyle: "short", timeStyle: "short" })}
        </p>

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
          <div className="text-xs uppercase tracking-wider text-muted mb-1">Time</div>
          <input
            type="datetime-local"
            value={tsLocal}
            onChange={(e) => setTsLocal(e.target.value)}
            className="num w-full h-12 rounded-xl bg-surface2 px-3 text-base outline-none ring-1 ring-white/5 focus:ring-accent/60"
          />
        </div>

        <div className="mt-5 flex gap-2">
          <button
            onClick={remove}
            className={`rounded-xl px-3 py-3 text-sm font-medium transition ${
              confirmDelete
                ? "bg-bad text-white"
                : "bg-bad/15 ring-1 ring-bad/40 text-bad"
            }`}
          >
            {confirmDelete ? "Tap to confirm" : "Delete"}
          </button>
          <button
            onClick={onClose}
            className="flex-1 rounded-xl bg-surface2 px-3 py-3 text-sm"
          >
            Cancel
          </button>
          <button
            onClick={save}
            disabled={saving || units < 0}
            className="flex-1 rounded-xl bg-accent text-white px-3 py-3 text-sm font-medium disabled:opacity-40"
          >
            {saving ? "Saving…" : "Save"}
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
  // datetime-local strings are interpreted as local time; new Date() does the right thing.
  const d = new Date(s);
  return Number.isFinite(d.getTime()) ? d.getTime() : Date.now();
}
