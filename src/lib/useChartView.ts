"use client";

import { useCallback, useEffect, useState } from "react";
import type { WindowHours } from "@/components/Chart5h";

const KEY_WIN = "meanwhile.chart.windowHours";

// Window/pan state for the home chart. Window choice persists in
// localStorage; pan does NOT — opening the app should always land on
// "live now" view, then the user can drag back as desired.
export function useChartView() {
  const [windowHours, _setWindowHours] = useState<WindowHours>(5);
  const [panMs, setPanMs] = useState(0);

  useEffect(() => {
    try {
      const raw = localStorage.getItem(KEY_WIN);
      const v = raw ? Number(raw) : 5;
      if ([3, 5, 8, 24].includes(v)) _setWindowHours(v as WindowHours);
    } catch {}
  }, []);

  const setWindowHours = useCallback((w: WindowHours) => {
    _setWindowHours(w);
    try { localStorage.setItem(KEY_WIN, String(w)); } catch {}
  }, []);

  return { windowHours, setWindowHours, panMs, setPanMs };
}
