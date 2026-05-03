"use client";

import { useEffect, useState } from "react";
import { StatTiles } from "@/components/StatTiles";
import { Chart5h, type WindowHours } from "@/components/Chart5h";
import { ChartControls } from "@/components/ChartControls";
import { StatsPanel } from "@/components/StatsPanel";
import { DoseList } from "@/components/DoseList";
import { PreBolusTimer } from "@/components/PreBolusTimer";
import { DailyBasalCard } from "@/components/DailyBasalCard";
import { InputBar } from "@/components/InputBar";
import { DecisionCard } from "@/components/DecisionCard";
import { LearnPanel } from "@/components/LearnPanel";
import { useLiveData, useXdripPolling } from "@/lib/useLiveData";
import { useMode } from "@/lib/useMode";
import { useChartView } from "@/lib/useChartView";
import { logDecision, recentDecisions } from "@/lib/db";
import { suggestDose } from "@/lib/insulin";
import type { Decision } from "@/lib/types";

export default function HomePage() {
  const { profile, bg, bgList, iob, cob, insulinList, carbsList } = useLiveData();
  useXdripPolling(profile);
  const [mode] = useMode();
  const { windowHours, setWindowHours, panMs, setPanMs } = useChartView();

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [decision, setDecision] = useState<Decision | null>(null);
  const [history, setHistory] = useState<Decision[]>([]);

  useEffect(() => {
    recentDecisions(5).then(setHistory);
  }, [decision]);

  const ask = async (text: string) => {
    if (!profile) return;
    setBusy(true);
    setError(null);
    try {
      const ctx = {
        profile,
        bg_now: bg?.mgdl ?? null,
        bg_trend: bg?.trend ?? null,
        bg_delta: bg?.delta ?? null,
        bg_history: bgList.map((r) => ({ ts: r.ts, mgdl: r.mgdl })),
        iob, cob,
        recent_doses: insulinList.map((d) => ({ ts: d.ts, units: d.units, kind: d.kind })),
        recent_carbs: carbsList.map((c) => ({ ts: c.ts, g: c.carbs_g, desc: c.description })),
      };
      const res = await fetch("/api/ai/decide", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          user_input: text,
          context: ctx,
          preferred_provider: profile.ai_provider,
        }),
      });
      if (!res.ok) {
        const j = await res.json().catch(() => ({}));
        throw new Error(j.error || `Server error ${res.status}`);
      }
      const { decision: d, provider, model } = await res.json();

      const extractedCarbs = d.suggested_carbs_g ?? d.extracted?.carbs_g ?? 0;
      let math: Record<string, number | string> = {};
      if (bg?.mgdl != null && (d.suggested_units == null) && extractedCarbs > 0) {
        const calc = suggestDose({ bg: bg.mgdl, carbs_g: extractedCarbs, iob, profile });
        math = { ...calc, computed_locally: 1 };
      }

      const record: Omit<Decision, "id"> = {
        ts: Date.now(),
        user_input: text,
        bg_at_time: bg?.mgdl,
        iob_at_time: iob,
        cob_at_time: cob,
        headline: d.headline,
        rationale: d.rationale,
        suggested_units: d.suggested_units ?? undefined,
        suggested_carbs_g: d.suggested_carbs_g ?? d.extracted?.carbs_g ?? undefined,
        extracted: d.extracted ?? {},
        math,
        provider,
        model,
      };
      const id = await logDecision(record);
      setDecision({ ...record, id });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex flex-col gap-3 pb-2">
      <StatTiles bg={bg} iob={iob} cob={cob} showCob={mode === "decide"} />
      <DailyBasalCard profile={profile} doses={insulinList} />
      <PreBolusTimer doses={insulinList} />
      {mode === "learn" && <LearnPanel />}
      <ChartControls
        windowHours={windowHours}
        onWindowChange={(w: WindowHours) => { setWindowHours(w); setPanMs(0); }}
        panMs={panMs}
        onResetPan={() => setPanMs(0)}
      />
      <Chart5h
        readings={bgList}
        doses={insulinList}
        windowHours={windowHours}
        panMs={panMs}
        onPanChange={setPanMs}
        targetLow={profile?.tir_low ?? 70}
        targetHigh={profile?.tir_high ?? 160}
      />
      <StatsPanel bgList={bgList} insulinList={insulinList} />
      <DoseList doses={insulinList} />

      {mode === "decide" && error && (
        <div className="mx-3 rounded-xl bg-bad/15 ring-1 ring-bad/40 text-bad p-3 text-sm">
          {error}
        </div>
      )}

      {mode === "decide" && decision && (
        <DecisionCard decision={decision} onLogged={() => recentDecisions(5).then(setHistory)} />
      )}

      {mode === "decide" && !decision && history.length > 0 && (
        <div className="mx-3 mt-2">
          <div className="text-xs uppercase tracking-wider text-muted mb-2">Recent</div>
          <ul className="space-y-2">
            {history.slice(0, 3).map((h) => (
              <li key={h.id} className="rounded-xl bg-surface/70 p-3 ring-1 ring-white/5">
                <div className="text-sm font-medium">{h.headline}</div>
                <div className="text-xs text-muted mt-1">{new Date(h.ts).toLocaleTimeString()} · BG {h.bg_at_time ?? "—"} · {h.suggested_units != null ? `${h.suggested_units}U` : "no dose"}</div>
              </li>
            ))}
          </ul>
        </div>
      )}

      {mode === "decide" && <InputBar onSubmit={ask} disabled={busy} />}
    </div>
  );
}
