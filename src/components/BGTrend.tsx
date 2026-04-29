import type { Trend } from "@/lib/types";

const ARROWS: Record<Trend, string> = {
  DoubleUp: "⇈",
  SingleUp: "↑",
  FortyFiveUp: "↗",
  Flat: "→",
  FortyFiveDown: "↘",
  SingleDown: "↓",
  DoubleDown: "⇊",
  NotComputable: "·",
  RateOutOfRange: "·",
  None: "·",
};

export function TrendArrow({ trend }: { trend: Trend | undefined }) {
  if (!trend) return <span className="text-muted">·</span>;
  return <span aria-label={trend}>{ARROWS[trend]}</span>;
}
