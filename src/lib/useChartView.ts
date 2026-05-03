"use client";

import { useCallback, useEffect, useState } from "react";
import type { WindowHours } from "@/components/Chart5h";

const KEY_WIN = "meanwhile.chart.windowHours";
const KEY_OV24 = "meanwhile.chart.overlay24";
const KEY_OV48 = "meanwhile.chart.overlay48";

// Window/pan/overlay state for the home chart. Window choice and
// overlay toggles persist in localStorage; pan does NOT — opening the
// app should always land on "live now", then the user can drag back
// or use the jump-back buttons.
export function useChartView() {
  const [windowHours, _setWindowHours] = useState<WindowHours>(5);
  const [panMs, setPanMs] = useState(0);
  const [overlay24, _setOverlay24] = useState(false);
  const [overlay48, _setOverlay48] = useState(false);

  useEffect(() => {
    try {
      const raw = localStorage.getItem(KEY_WIN);
      const v = raw ? Number(raw) : 5;
      if ([3, 5, 8, 24].includes(v)) _setWindowHours(v as WindowHours);
      _setOverlay24(localStorage.getItem(KEY_OV24) === "1");
      _setOverlay48(localStorage.getItem(KEY_OV48) === "1");
    } catch {}
  }, []);

  const setWindowHours = useCallback((w: WindowHours) => {
    _setWindowHours(w);
    try { localStorage.setItem(KEY_WIN, String(w)); } catch {}
  }, []);

  const setOverlay24 = useCallback((on: boolean) => {
    _setOverlay24(on);
    try { localStorage.setItem(KEY_OV24, on ? "1" : "0"); } catch {}
  }, []);

  const setOverlay48 = useCallback((on: boolean) => {
    _setOverlay48(on);
    try { localStorage.setItem(KEY_OV48, on ? "1" : "0"); } catch {}
  }, []);

  return {
    windowHours, setWindowHours,
    panMs, setPanMs,
    overlay24, setOverlay24,
    overlay48, setOverlay48,
  };
}
