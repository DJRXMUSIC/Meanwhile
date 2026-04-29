"use client";

import { useEffect } from "react";
import { useLiveQuery } from "dexie-react-hooks";
import { db } from "@/lib/db";
import type { ThemeName } from "@/lib/types";

export function ThemeApplier() {
  const profile = useLiveQuery(() => db().profile.get("current"), []);
  const theme: ThemeName = (profile?.theme as ThemeName) ?? "default";

  useEffect(() => {
    if (typeof document === "undefined") return;
    document.documentElement.setAttribute("data-theme", theme);
  }, [theme]);

  return null;
}
