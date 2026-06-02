"use client";

import { useEffect } from "react";
import { useLiveQuery } from "dexie-react-hooks";
import { db } from "@/lib/db";
import { LEGACY_THEME_MAP, type AccentName, type ThemeMode, type ThemeName } from "@/lib/types";

export function ThemeApplier() {
  const profile = useLiveQuery(() => db().profile.get("current"), []);

  // Prefer the new split (mode + accent). Fall back to the legacy `theme`
  // preset if the profile hasn't migrated yet (covers a brief render
  // before the migration runs on first load).
  let mode: ThemeMode = profile?.theme_mode ?? "dark";
  let accent: AccentName = profile?.theme_accent ?? "aurora";
  if ((!profile?.theme_mode || !profile?.theme_accent) && profile?.theme) {
    const fallback = LEGACY_THEME_MAP[profile.theme as ThemeName];
    if (fallback) {
      mode = profile?.theme_mode ?? fallback.mode;
      accent = profile?.theme_accent ?? fallback.accent;
    }
  }

  useEffect(() => {
    if (typeof document === "undefined") return;
    document.documentElement.setAttribute("data-mode", mode);
    document.documentElement.setAttribute("data-accent", accent);
  }, [mode, accent]);

  return null;
}
