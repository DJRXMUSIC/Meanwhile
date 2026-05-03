"use client";

import { useEffect, useMemo, useState } from "react";
import { logInsulin } from "@/lib/db";
import type { InsulinDose, Profile } from "@/lib/types";

// Once-daily long-acting basal reminder. The trigger time AND the
// "today" calendar boundary are both evaluated in the configured
// timezone (default America/New_York), not the device's local zone —
// so the reminder fires at the same wall-clock time wherever the
// device is, and DST transitions are handled by the IANA database.
export function DailyBasalCard({
  profile,
  doses,
}: {
  profile: Profile | null;
  doses: InsulinDose[];
}) {
  const [, setTick] = useState(0);
  const [confirming, setConfirming] = useState(false);
  const [logging, setLogging] = useState(false);

  // Re-evaluate every minute and on wake.
  useEffect(() => {
    const id = setInterval(() => setTick((t) => (t + 1) | 0), 60_000);
    const wake = () => setTick((t) => (t + 1) | 0);
    document.addEventListener("visibilitychange", wake);
    window.addEventListener("focus", wake);
    return () => {
      clearInterval(id);
      document.removeEventListener("visibilitychange", wake);
      window.removeEventListener("focus", wake);
    };
  }, []);

  const enabled = profile?.daily_basal_enabled ?? true;
  const units = profile?.daily_basal_units ?? 20;
  const timeStr = profile?.daily_basal_time ?? "18:30";
  const tz = profile?.daily_basal_tz ?? "America/New_York";

  const due = useMemo(() => {
    if (!enabled) return false;
    const nowMs = Date.now();
    const todayInTz = ymdInTz(nowMs, tz);
    const nowMin = minutesInTz(nowMs, tz);
    const [hh = 18, mm = 30] = timeStr.split(":").map((s) => Number(s));
    const triggerMin = hh * 60 + mm;

    // Has any basal dose been logged "today" (per the configured zone)?
    const loggedToday = doses.some(
      (d) => d.kind === "basal" && ymdInTz(d.ts, tz) === todayInTz
    );
    if (loggedToday) return false;
    return nowMin >= triggerMin;
  }, [enabled, units, timeStr, tz, doses]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!due) return null;

  const submit = async () => {
    if (logging) return;
    setLogging(true);
    try {
      const now = Date.now();
      await logInsulin({
        ts: now,
        units,
        kind: "basal",
        source: "manual",
        entered_at: now,
        backdated_min: 0,
        note: "daily basal",
      });
    } finally {
      setLogging(false);
      setConfirming(false);
    }
  };

  const tzShort = formatTzAbbrev(tz);

  return (
    <section className="mx-3 rounded-2xl bg-surface p-3 ring-1 ring-good/40">
      <div className="flex items-center gap-3">
        <button
          onClick={() => setConfirming(true)}
          disabled={confirming || logging}
          className="size-20 rounded-full bg-good/20 ring-2 ring-good grid place-items-center active:scale-[0.97] transition shrink-0 disabled:opacity-60"
          aria-label={`Log ${units} units daily basal`}
        >
          <span className="num text-2xl font-bold text-good leading-none">
            {units}<span className="text-sm font-semibold ml-0.5">U</span>
          </span>
        </button>
        <div className="min-w-0 flex-1">
          <div className="text-[10px] uppercase tracking-wider text-muted">Daily basal due</div>
          <div className="mt-0.5 text-sm">
            Tap the circle to log your <b>{units}U</b> long-acting dose for today.
          </div>
          <div className="text-[11px] text-muted mt-1">
            Configured for {timeStr} {tzShort}
          </div>
        </div>
      </div>

      {confirming && (
        <div
          className="fixed inset-0 z-30 bg-black/60 backdrop-blur-sm grid items-end"
          onClick={() => !logging && setConfirming(false)}
        >
          <div
            className="rounded-t-3xl bg-surface ring-1 ring-white/10 p-4 pb-[calc(env(safe-area-inset-bottom)+16px)] mx-auto w-full max-w-xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="mx-auto h-1 w-12 rounded-full bg-white/15 mb-4" />
            <h2 className="text-lg font-semibold">Log daily basal?</h2>
            <p className="text-sm text-muted mt-1">
              Confirm logging <b className="text-ink">{units}U</b> of long-acting basal at{" "}
              {new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}
              {" "}local.
            </p>
            <div className="mt-5 flex gap-2">
              <button
                onClick={() => setConfirming(false)}
                disabled={logging}
                className="flex-1 rounded-xl bg-surface2 px-3 py-3 text-sm"
              >
                Cancel
              </button>
              <button
                onClick={submit}
                disabled={logging}
                className="flex-1 rounded-xl bg-good text-black px-3 py-3 text-sm font-semibold disabled:opacity-40"
              >
                {logging ? "Logging…" : `Confirm ${units}U`}
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}

// ---- Timezone helpers (no Date math; lean on Intl.DateTimeFormat) ------

function ymdInTz(ts: number, tz: string): string {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: tz,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date(ts));
  const get = (t: string) => parts.find((p) => p.type === t)?.value ?? "00";
  return `${get("year")}-${get("month")}-${get("day")}`;
}

function minutesInTz(ts: number, tz: string): number {
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: tz,
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).formatToParts(new Date(ts));
  const hh = Number(parts.find((p) => p.type === "hour")?.value ?? 0);
  const mm = Number(parts.find((p) => p.type === "minute")?.value ?? 0);
  return hh * 60 + mm;
}

function formatTzAbbrev(tz: string): string {
  try {
    const parts = new Intl.DateTimeFormat("en-US", {
      timeZone: tz,
      timeZoneName: "short",
    }).formatToParts(new Date());
    return parts.find((p) => p.type === "timeZoneName")?.value ?? tz;
  } catch {
    return tz;
  }
}
