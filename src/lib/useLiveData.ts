"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useLiveQuery } from "dexie-react-hooks";
import { db, getProfile } from "./db";
import { totalIOB } from "./insulin";
import type { Profile } from "./types";
import { bucket, useNow } from "./useNow";
import { XdripPoller } from "./xdrip";

export function useProfile(): Profile | null {
  const [profile, setProfile] = useState<Profile | null>(null);
  const live = useLiveQuery(() => db().profile.get("current"), []);
  useEffect(() => {
    if (live) setProfile(live);
    else getProfile().then(setProfile);
  }, [live]);
  return profile;
}

export function useLiveData() {
  const profile = useProfile();

  // A shared clock, not Date.now() at render time. Two things depend on
  // this being stable within a tick:
  //
  //  - `since` below is a useLiveQuery dependency. A value that changed on
  //    every render tore down and rebuilt both Dexie subscriptions each
  //    time, re-running two range queries over a four-day window.
  //  - IOB decays continuously, so it must recompute on a clock rather
  //    than only when the underlying rows happen to change.
  const now = useNow(30_000);

  // Fetch enough history to feed both the 24h chart window and the
  // 3-day statistics panel without re-querying. 4 days is a safe margin.
  // Bucketed to 5 minutes: the exact edge of a four-day window doesn't
  // matter, and this keeps the subscriptions alive across renders.
  const since = bucket(now, 5 * 60_000) - 4 * 24 * 3600_000;

  const bgList = useLiveQuery(
    () => db().bg.where("ts").above(since).sortBy("ts"),
    [since],
    []
  ) ?? [];
  const insulinList = useLiveQuery(
    () => db().insulin.where("ts").above(since).sortBy("ts"),
    [since],
    []
  ) ?? [];

  const bg = bgList[bgList.length - 1];
  const dia = profile?.dia_hours ?? 6;
  const peak = profile?.peak_min ?? 75;
  const delay = profile?.delay_min ?? 15;
  // `now` is the *dependency* — it is what makes this recompute as time
  // passes — but the evaluation instant is the real clock. A quantized now
  // can sit behind real time between ticks, and totalIOB discards rows
  // timestamped after the evaluation instant, so a dose logged a moment
  // ago would be dropped from its own tile. Logging a row also changes the
  // array identity, so this recomputes immediately.
  const iob = useMemo(
    () => totalIOB(insulinList, Math.max(now, Date.now()), dia, peak, delay),
    [insulinList, now, dia, peak, delay]
  );

  // `now` is returned so consumers (the chart, in particular) share this
  // clock instead of starting their own.
  return { profile, bg, bgList, insulinList, iob, now };
}

export interface XdripControl {
  /** Fetch now, reporting the outcome. Safe to call while a poll is running. */
  refresh: () => Promise<void>;
  syncing: boolean;
  /** Short outcome of the last manual refresh; clears itself. */
  note: string | null;
  configured: boolean;
}

export function useXdripPolling(profile: Profile | null): XdripControl {
  const base = profile?.xdrip_url ?? "";
  const pollerRef = useRef<XdripPoller | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const noteTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (!base) return;
    const poller = new XdripPoller(base, 60_000);
    pollerRef.current = poller;
    poller.start();

    // A backgrounded PWA has its intervals throttled or suspended outright,
    // so returning to the app could leave a stale reading on screen until
    // the next tick happened to fire. Sync on wake instead — the same
    // pattern lib/useNow, PreBolusTimer and DailyBasalCard already use.
    const onWake = () => {
      if (document.visibilityState === "hidden") return;
      poller.poll();
    };
    document.addEventListener("visibilitychange", onWake);
    window.addEventListener("focus", onWake);
    window.addEventListener("pageshow", onWake);

    return () => {
      poller.stop();
      pollerRef.current = null;
      document.removeEventListener("visibilitychange", onWake);
      window.removeEventListener("focus", onWake);
      window.removeEventListener("pageshow", onWake);
    };
  }, [base]);

  useEffect(() => {
    return () => {
      if (noteTimer.current) clearTimeout(noteTimer.current);
    };
  }, []);

  const flashNote = useCallback((msg: string) => {
    setNote(msg);
    if (noteTimer.current) clearTimeout(noteTimer.current);
    noteTimer.current = setTimeout(() => setNote(null), 4000);
  }, []);

  const refresh = useCallback(async () => {
    const poller = pollerRef.current;
    if (!poller) {
      flashNote("No xDrip+ URL set");
      return;
    }
    setSyncing(true);
    setNote(null);
    try {
      const n = await poller.refreshNow();
      flashNote(n > 0 ? `+${n} reading${n === 1 ? "" : "s"}` : "up to date");
    } catch (e) {
      flashNote(`failed: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setSyncing(false);
    }
  }, [flashNote]);

  return { refresh, syncing, note, configured: !!base };
}
