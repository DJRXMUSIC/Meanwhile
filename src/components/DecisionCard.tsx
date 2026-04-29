"use client";

import { useState } from "react";
import type { Decision } from "@/lib/types";
import { logCarbs, logInsulin } from "@/lib/db";

export function DecisionCard({
  decision,
  onLogged,
}: {
  decision: Decision;
  onLogged?: () => void;
}) {
  const [logged, setLogged] = useState<{ insulin?: boolean; carbs?: boolean }>({});

  const acceptDose = async () => {
    if (decision.suggested_units == null || decision.suggested_units === 0) return;
    await logInsulin({
      ts: Date.now(),
      units: decision.suggested_units,
      kind: "bolus",
      note: decision.headline,
    });
    setLogged((s) => ({ ...s, insulin: true }));
    onLogged?.();
  };

  const acceptCarbs = async () => {
    const g = decision.suggested_carbs_g ?? decision.extracted?.carbs_g;
    if (!g || g <= 0) return;
    await logCarbs({
      ts: Date.now(),
      carbs_g: g,
      fat_g: decision.extracted?.fat_g ?? undefined,
      protein_g: decision.extracted?.protein_g ?? undefined,
      description: decision.user_input,
    });
    setLogged((s) => ({ ...s, carbs: true }));
    onLogged?.();
  };

  const dose = decision.suggested_units;
  const carbs = decision.suggested_carbs_g ?? decision.extracted?.carbs_g ?? 0;

  return (
    <div className="mx-4 my-3 rounded-2xl bg-surface p-4 ring-1 ring-white/5">
      <div className="text-xs uppercase tracking-wider text-accent mb-1">Next best action</div>
      <h2 className="text-xl font-semibold leading-snug">{decision.headline}</h2>

      <div className="mt-3 grid grid-cols-2 gap-2">
        {dose != null && (
          <button
            disabled={logged.insulin}
            onClick={acceptDose}
            className="rounded-xl bg-accent/15 ring-1 ring-accent/40 p-3 text-left active:scale-[0.99] transition disabled:opacity-50"
          >
            <div className="text-xs text-muted">Bolus</div>
            <div className="num text-2xl font-semibold">{dose}<span className="text-sm text-muted ml-1">U</span></div>
            <div className="text-xs text-accent mt-1">{logged.insulin ? "Logged ✓" : "Tap to log"}</div>
          </button>
        )}
        {carbs > 0 && (
          <button
            disabled={logged.carbs}
            onClick={acceptCarbs}
            className="rounded-xl bg-good/10 ring-1 ring-good/40 p-3 text-left active:scale-[0.99] transition disabled:opacity-50"
          >
            <div className="text-xs text-muted">Carbs</div>
            <div className="num text-2xl font-semibold">{carbs}<span className="text-sm text-muted ml-1">g</span></div>
            <div className="text-xs text-good mt-1">{logged.carbs ? "Logged ✓" : "Tap to log"}</div>
          </button>
        )}
      </div>

      <details className="mt-4 group" open>
        <summary className="cursor-pointer text-sm text-muted list-none flex items-center justify-between">
          <span>Rationale & math</span>
          <span className="group-open:rotate-180 transition">⌄</span>
        </summary>
        <pre className="mt-2 whitespace-pre-wrap text-sm text-ink/90 font-sans">{decision.rationale}</pre>
      </details>

      <div className="mt-3 flex flex-wrap gap-2 text-[11px] text-muted">
        <span className="rounded-full bg-surface2 px-2 py-0.5">{decision.provider}</span>
        <span className="rounded-full bg-surface2 px-2 py-0.5">{decision.model}</span>
        {decision.bg_at_time != null && <span className="rounded-full bg-surface2 px-2 py-0.5">BG {decision.bg_at_time}</span>}
        {decision.iob_at_time != null && <span className="rounded-full bg-surface2 px-2 py-0.5">IOB {decision.iob_at_time.toFixed(2)}</span>}
        {decision.cob_at_time != null && <span className="rounded-full bg-surface2 px-2 py-0.5">COB {decision.cob_at_time.toFixed(0)}g</span>}
      </div>
    </div>
  );
}
