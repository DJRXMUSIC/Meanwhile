"use client";

import { useEffect, useMemo, useState } from "react";
import { useLiveQuery } from "dexie-react-hooks";
import { db, getProfile } from "./db";
import { totalCOB, totalIOB } from "./insulin";
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
  //    every render tore down and rebuilt all three Dexie subscriptions
  //    each time, re-running three range queries over a four-day window.
  //  - IOB and COB decay continuously, so they must recompute on a clock
  //    rather than only when the underlying rows happen to change.
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
  const carbsList = useLiveQuery(
    () => db().carbs.where("ts").above(since).sortBy("ts"),
    [since],
    []
  ) ?? [];

  const bg = bgList[bgList.length - 1];
  const dia = profile?.dia_hours ?? 6;
  const peak = profile?.peak_min ?? 75;
  const delay = profile?.delay_min ?? 15;
  const iob = useMemo(
    () => totalIOB(insulinList, now, dia, peak, delay),
    [insulinList, now, dia, peak, delay]
  );
  const cob = useMemo(() => totalCOB(carbsList, now), [carbsList, now]);

  // `now` is returned so consumers (the chart, in particular) share this
  // clock instead of starting their own.
  return { profile, bg, bgList, insulinList, carbsList, iob, cob, now };
}

export function useXdripPolling(profile: Profile | null) {
  useEffect(() => {
    if (!profile?.xdrip_url) return;
    const poller = new XdripPoller(profile.xdrip_url, 60_000);
    poller.start();
    return () => poller.stop();
  }, [profile?.xdrip_url]);
}
