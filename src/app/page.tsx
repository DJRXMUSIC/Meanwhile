"use client";

import { useEffect, useMemo, useState } from "react";
import { StatTiles } from "@/components/StatTiles";
import { Chart5h, type WindowHours } from "@/components/Chart5h";
import { ChartControls } from "@/components/ChartControls";
import { StatsPanel } from "@/components/StatsPanel";
import { DoseList } from "@/components/DoseList";
import { PreBolusTimer } from "@/components/PreBolusTimer";
import { DailyBasalCard } from "@/components/DailyBasalCard";
import { BackupWarning } from "@/components/BackupWarning";
import { InputBar } from "@/components/InputBar";
import { DecisionCard } from "@/components/DecisionCard";
import { LearnPanel } from "@/components/LearnPanel";
import { useLiveData, useXdripPolling } from "@/lib/useLiveData";
import { useMode } from "@/lib/useMode";
import { useChartView } from "@/lib/useChartView";
import { logDecision, recentDecisions } from "@/lib/db";
import { suggestDose } from "@/lib/insulin";
import type { Decision } from "@/lib/types";
import { apiUrl, NO_SERVER_MESSAGE } from "@/lib/native";
import { SHOW_NOW } from "@/lib/alerts";
import { dedupeReadings, fillGaps } from "@/lib/gapFill";
import { dropDuplicateBridgeReadings } from "@/lib/sync";

export default function HomePage() {
  const { profile, bg, bgList, iob, insulinList, now } = useLiveData();
  const xdrip = useXdripPolling(profile);
  // The newest EverBridge reading (not a BG typed in by hand): the tile's
  // countdown to the next reading runs from it.
  const lastBridgeTs = useMemo(() => {
    for (let i = bgList.length - 1; i >= 0; i--) if (bgList[i].source === "xdrip") return bgList[i].ts;
    return undefined;
  }, [bgList]);
  const [mode] = useMode();
  const {
    windowHours, setWindowHours,
    panMs, setPanMs,
    overlay24, setOverlay24,
    overlay48, setOverlay48,
  } = useChartView();

  // Back to Now after a snooze: the chart shows the present, from the top.
  useEffect(() => {
    const show = () => { setPanMs(0); window.scrollTo({ top: 0 }); };
    window.addEventListener(SHOW_NOW, show);
    return () => window.removeEventListener(SHOW_NOW, show);
  }, [setPanMs]);

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
        iob,
        recent_doses: insulinList.map((d) => ({ ts: d.ts, units: d.units, kind: d.kind })),
      };
      const url = apiUrl("/api/ai/decide");
      if (!url) throw new Error(NO_SERVER_MESSAGE);
      const res = await fetch(url, {
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

      // When the model declines to put a number on it, fall back to the
      // local correction math so the card still shows transparent working.
      let math: Record<string, number | string> = {};
      if (bg?.mgdl != null && d.suggested_units == null) {
        const calc = suggestDose({ bg: bg.mgdl, iob, profile });
        math = { ...calc, computed_locally: 1 };
      }

      const record: Omit<Decision, "id"> = {
        ts: Date.now(),
        user_input: text,
        bg_at_time: bg?.mgdl,
        iob_at_time: iob,
        headline: d.headline,
        rationale: d.rationale,
        suggested_units: d.suggested_units ?? undefined,
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

  // best guesses for missed readings: drawn on the chart and counted in the stats, never stored
  // (each sensor reading once, however many times it was stored)
  const estimates = useMemo(() => fillGaps(dedupeReadings(bgList)), [bgList]);
  // readings stored twice under different ids (an old restore or sync): drop the copies once
  useEffect(() => { void dropDuplicateBridgeReadings(); }, []);

  return (
    <div className="flex flex-col gap-3 pb-2">
      <StatTiles
        bg={bg}
        lastBridgeTs={lastBridgeTs}
        iob={iob}
        onRefresh={xdrip.configured ? xdrip.refresh : undefined}
        syncing={xdrip.syncing}
        refreshNote={xdrip.note}
      />
      <BackupWarning />
      <DailyBasalCard profile={profile} doses={insulinList} />
      <PreBolusTimer doses={insulinList} />
      {mode === "learn" && <LearnPanel />}
      <ChartControls
        windowHours={windowHours}
        onWindowChange={(w: WindowHours) => { setWindowHours(w); }}
        panMs={panMs}
        onSetPan={setPanMs}
        overlay24={overlay24}
        overlay48={overlay48}
        onToggleOverlay24={() => setOverlay24(!overlay24)}
        onToggleOverlay48={() => setOverlay48(!overlay48)}
      />
      <Chart5h
        readings={bgList}
        estimates={estimates}
        doses={insulinList}
        windowHours={windowHours}
        now={now}
        panMs={panMs}
        onPanChange={setPanMs}
        targetLow={profile?.tir_low ?? 70}
        targetHigh={profile?.tir_high ?? 160}
        overlay24={overlay24}
        overlay48={overlay48}
      />
      <StatsPanel bgList={bgList} estimates={estimates} insulinList={insulinList} now={now} />
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
