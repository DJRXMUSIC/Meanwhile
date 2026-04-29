"use client";

import { useCallback } from "react";
import { useLiveQuery } from "dexie-react-hooks";
import { db, saveProfile } from "./db";

export type Mode = "decide" | "learn";

// Mode is stored on the Profile (IndexedDB), so it survives app restarts,
// reboots, reinstalls (when sync is enabled), and is shared across devices.
export function useMode(): [Mode, (m: Mode) => Promise<void>] {
  const profile = useLiveQuery(() => db().profile.get("current"), []);
  const mode: Mode = (profile?.mode as Mode) ?? "decide";

  const set = useCallback(async (m: Mode) => {
    await saveProfile({ mode: m });
  }, []);

  return [mode, set];
}
