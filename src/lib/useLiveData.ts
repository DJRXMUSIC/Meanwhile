"use client";

import { useEffect, useMemo, useState } from "react";
import { useLiveQuery } from "dexie-react-hooks";
import { db, getProfile } from "./db";
import { totalCOB, totalIOB } from "./insulin";
import type { Profile } from "./types";
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
  // Fetch enough history to feed both the 24h chart window and the
  // 3-day statistics panel without re-querying. 4 days is a safe margin.
  const since = Date.now() - 4 * 24 * 3600_000;

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
    () => totalIOB(insulinList, Date.now(), dia, peak, delay),
    [insulinList, dia, peak, delay]
  );
  const cob = useMemo(() => totalCOB(carbsList, Date.now()), [carbsList]);

  return { profile, bg, bgList, insulinList, carbsList, iob, cob };
}

export function useXdripPolling(profile: Profile | null) {
  useEffect(() => {
    if (!profile?.xdrip_url) return;
    const poller = new XdripPoller(profile.xdrip_url, 60_000);
    poller.start();
    return () => poller.stop();
  }, [profile?.xdrip_url]);
}
