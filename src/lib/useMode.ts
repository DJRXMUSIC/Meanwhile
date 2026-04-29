"use client";

import { useEffect, useState } from "react";

type Mode = "decide" | "learn";

const KEY = "meanwhile.mode";

export function useMode(): [Mode, (m: Mode) => void] {
  const [mode, setMode] = useState<Mode>("decide");

  useEffect(() => {
    try {
      const v = localStorage.getItem(KEY);
      if (v === "learn" || v === "decide") setMode(v);
    } catch {}
  }, []);

  const set = (m: Mode) => {
    setMode(m);
    try { localStorage.setItem(KEY, m); } catch {}
  };

  return [mode, set];
}
