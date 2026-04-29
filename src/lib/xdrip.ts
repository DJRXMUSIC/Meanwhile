"use client";

import { db, latestBg, logBg } from "./db";
import type { BgReading, Trend } from "./types";

// xDrip+ exposes a local web service (default :17580) returning Nightscout-style JSON.
// Endpoint convention used here: GET {base}/sgv.json?count=N
// Each entry: { date: ms, sgv: mg/dL, direction: Trend, delta: number }

export interface XdripEntry {
  date: number;
  sgv: number;
  direction?: Trend;
  delta?: number;
}

export async function fetchXdrip(base: string, count = 24): Promise<XdripEntry[]> {
  if (!base) return [];
  const direct = `${base.replace(/\/$/, "")}/sgv.json?count=${count}`;

  // 1) Try direct fetch from the browser. Works when the page is on the
  //    same device as xDrip+ AND the page is HTTP (or xDrip+ exposes HTTPS).
  try {
    const res = await fetch(direct, { cache: "no-store", mode: "cors" });
    if (res.ok) {
      const json = (await res.json()) as XdripEntry[];
      return Array.isArray(json) ? json : [];
    }
  } catch {
    // Mixed-content block or CORS denial — fall through to proxy.
  }

  // 2) Fall back to the server-side proxy at /api/bg/proxy. The Next.js
  //    server fetches xDrip+ on our behalf — works as long as the deploy
  //    can reach `base` on the network.
  const proxied = `/api/bg/proxy?base=${encodeURIComponent(base)}&count=${count}`;
  const res2 = await fetch(proxied, { cache: "no-store" });
  if (!res2.ok) throw new Error(`xDrip fetch failed: ${res2.status}`);
  const json = (await res2.json()) as XdripEntry[];
  return Array.isArray(json) ? json : [];
}

export async function syncXdripOnce(base: string): Promise<number> {
  const entries = await fetchXdrip(base, 36);
  if (!entries.length) return 0;
  const last = await latestBg();
  const cutoff = last?.ts ?? 0;
  let added = 0;
  // Insert oldest-first to preserve order/delta.
  const sorted = [...entries].sort((a, b) => a.date - b.date);
  for (const e of sorted) {
    if (!e?.date || !e?.sgv || e.date <= cutoff) continue;
    const reading: Omit<BgReading, "id"> = {
      ts: e.date,
      mgdl: Math.round(e.sgv),
      trend: (e.direction ?? "None") as Trend,
      delta: typeof e.delta === "number" ? Math.round(e.delta * 10) / 10 : undefined,
      source: "xdrip",
      raw: e,
    };
    await logBg(reading);
    added++;
  }
  return added;
}

export class XdripPoller {
  private timer: ReturnType<typeof setInterval> | null = null;
  private inFlight = false;
  constructor(private base: string, private intervalMs = 60_000, private onUpdate?: (added: number) => void) {}

  start() {
    if (this.timer) return;
    this.tick();
    this.timer = setInterval(() => this.tick(), this.intervalMs);
  }
  stop() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }
  setBase(base: string) {
    this.base = base;
  }
  private async tick() {
    if (this.inFlight || !this.base) return;
    this.inFlight = true;
    try {
      const added = await syncXdripOnce(this.base);
      if (added > 0) this.onUpdate?.(added);
    } catch {
      // soft-fail; xDrip+ may be unreachable when phone is off-network
    } finally {
      this.inFlight = false;
    }
  }
}

// Manual fallback: insert a BG reading by hand.
export async function manualBg(mgdl: number): Promise<void> {
  await db().bg.add({
    ts: Date.now(),
    mgdl: Math.round(mgdl),
    trend: "None",
    source: "manual",
  });
}
