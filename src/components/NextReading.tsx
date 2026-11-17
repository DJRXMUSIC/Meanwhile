"use client";

import { useEffect, useState } from "react";
import { formatCountdown, nextReading } from "@/lib/nextReading";

// "next in 3:42", counting down each second to the next Eversense reading, or
// "due now" while it is expected. Ticks on its own one-second timer so only
// this text re-renders, and not while the page is hidden.
export function NextReading({ lastTs }: { lastTs: number }) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const tick = () => {
      if (document.visibilityState !== "hidden") setNow(Date.now());
    };
    const id = setInterval(tick, 1000);
    document.addEventListener("visibilitychange", tick);
    return () => {
      clearInterval(id);
      document.removeEventListener("visibilitychange", tick);
    };
  }, []);

  const next = nextReading(lastTs, now);
  return <span className="num">{next.due ? "due now" : `next in ${formatCountdown(next.at - now)}`}</span>;
}
