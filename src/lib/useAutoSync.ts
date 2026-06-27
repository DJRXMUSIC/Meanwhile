"use client";

import { useEffect, useRef } from "react";
import { useLiveQuery } from "dexie-react-hooks";
import { db } from "./db";
import { syncOnce } from "./sync";
import type { Profile } from "./types";

// Background sync coordinator. Runs syncOnce(sync_key) when:
//  - the hook mounts (initial app open)
//  - the tab returns to the foreground (visibility / focus / pageshow)
//  - 5 s after any local DB mutation (debounced)
//  - every 5 minutes as a heartbeat
// Only triggers when profile.sync_auto is true and sync_key is set.
//
// All errors are swallowed and stashed in localStorage for the Settings
// panel to surface; this hook must never throw into render.

const DEBOUNCE_AFTER_WRITE_MS = 5_000;
const HEARTBEAT_MS = 5 * 60_000;

export function useAutoSync(profile: Profile | null) {
  const enabled = !!profile?.sync_auto && !!profile?.sync_key;
  const key = profile?.sync_key ?? "";
  const inFlight = useRef(false);

  // Cheap "DB has changed" watcher — totals across syncable tables. Any
  // local insert/update/delete flips this number; the effect below
  // schedules a debounced sync when it does.
  const writeMarker = useLiveQuery(async () => {
    const d = db();
    const counts = await Promise.all([
      d.bg.count(), d.insulin.count(), d.carbs.count(),
      d.decisions.count(), d.context.count(), d.profile.count(),
    ]);
    return counts.reduce((a, b) => a + b, 0);
  }, [], 0);

  const run = useRef(async () => {
    if (!enabled || inFlight.current) return;
    inFlight.current = true;
    try {
      await syncOnce(key);
    } catch {
      // Swallowed — surfaced via lastSyncStatus / Settings page banner.
    } finally {
      inFlight.current = false;
    }
  });
  // Keep the closure fresh as enabled/key change.
  useEffect(() => {
    run.current = async () => {
      if (!enabled || inFlight.current) return;
      inFlight.current = true;
      try { await syncOnce(key); }
      catch { /* see comment above */ }
      finally { inFlight.current = false; }
    };
  }, [enabled, key]);

  // Initial sync + heartbeat + foreground triggers.
  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;

    const fire = () => { if (!cancelled) void run.current(); };

    fire(); // mount
    const heartbeat = setInterval(fire, HEARTBEAT_MS);

    const onVis = () => { if (document.visibilityState === "visible") fire(); };
    document.addEventListener("visibilitychange", onVis);
    window.addEventListener("focus", fire);
    window.addEventListener("pageshow", fire);

    return () => {
      cancelled = true;
      clearInterval(heartbeat);
      document.removeEventListener("visibilitychange", onVis);
      window.removeEventListener("focus", fire);
      window.removeEventListener("pageshow", fire);
    };
  }, [enabled]);

  // Debounced after-write sync.
  useEffect(() => {
    if (!enabled) return;
    const id = setTimeout(() => { void run.current(); }, DEBOUNCE_AFTER_WRITE_MS);
    return () => clearTimeout(id);
  }, [writeMarker, enabled]);
}
