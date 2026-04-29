"use client";

import { useMemo, useState } from "react";
import type { InsulinDose } from "@/lib/types";
import { EditDoseSheet } from "./EditDoseSheet";

export function DoseList({ doses }: { doses: InsulinDose[] }) {
  const [editing, setEditing] = useState<InsulinDose | null>(null);

  // Show the last 24h of bolus/correction entries, newest first.
  const recent = useMemo(() => {
    const cutoff = Date.now() - 24 * 3600_000;
    return doses
      .filter((d) => d.kind === "bolus" || d.kind === "correction")
      .filter((d) => d.ts >= cutoff)
      .sort((a, b) => b.ts - a.ts);
  }, [doses]);

  return (
    <section className="mx-3 rounded-2xl bg-surface p-3 ring-1 ring-white/5">
      <div className="flex items-baseline justify-between mb-2 px-1">
        <div className="text-xs uppercase tracking-wider text-muted">Recent doses · 24h</div>
        <div className="text-[10px] text-muted">tap to edit</div>
      </div>
      {recent.length === 0 ? (
        <div className="text-sm text-muted px-1 py-2">No doses logged in the last 24 hours.</div>
      ) : (
        <ul className="divide-y divide-white/5">
          {recent.map((d) => (
            <li key={d.id ?? d.ts}>
              <button
                onClick={() => setEditing(d)}
                className="w-full text-left flex items-center gap-3 py-2.5 px-1 active:bg-white/5 rounded-md"
              >
                <span className="num text-2xl font-semibold leading-none w-14">
                  {d.units}<span className="text-xs text-muted ml-0.5">U</span>
                </span>
                <span className="flex-1 min-w-0">
                  <span className="block text-sm">{formatWhen(d.ts)}</span>
                  <span className="block text-[11px] text-muted truncate">{describe(d)}</span>
                </span>
                <span className="text-muted text-lg">›</span>
              </button>
            </li>
          ))}
        </ul>
      )}

      {editing && (
        <EditDoseSheet
          dose={editing}
          onClose={() => setEditing(null)}
        />
      )}
    </section>
  );
}

function formatWhen(ts: number): string {
  const d = new Date(ts);
  const now = new Date();
  const sameDay =
    d.getFullYear() === now.getFullYear() &&
    d.getMonth() === now.getMonth() &&
    d.getDate() === now.getDate();
  const time = d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  if (sameDay) return time;
  // yesterday or earlier: include short date
  return `${d.toLocaleDateString([], { month: "short", day: "numeric" })} ${time}`;
}

function describe(d: InsulinDose): string {
  const parts: string[] = [];
  if (d.source) parts.push(d.source);
  if (d.backdated_min && d.backdated_min > 0) parts.push(`backdated ${d.backdated_min}m`);
  if (d.note) parts.push(d.note);
  return parts.join(" · ") || d.kind;
}
