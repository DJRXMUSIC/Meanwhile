"use client";

import { db, latestBg } from "./db";
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

// Default fetch sized to cover overnight gaps without hammering xDrip:
// 144 entries at 5-min intervals = 12 hours of history. We over-fetch
// vs the typical 1-min poll interval so we survive long sleeps / closed
// app gaps as long as xDrip itself is still recording on the phone.
const DEFAULT_COUNT = 144;
// First poll after start: pull a much larger window so first-open after
// a long absence backfills cleanly.
const CATCHUP_COUNT = 360; // ~30 hours

export async function fetchXdrip(base: string, count = DEFAULT_COUNT): Promise<XdripEntry[]> {
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

export async function syncXdripOnce(base: string, count = DEFAULT_COUNT): Promise<number> {
  const entries = await fetchXdrip(base, count);
  if (!entries.length) return 0;
  const last = await latestBg();
  const cutoff = last?.ts ?? 0;
  // Build the full set of new readings first, then bulkAdd in a single
  // transaction so the Dexie live query fires once instead of N times —
  // that's what was causing the chart and BG tile to "flicker" while
  // backfilling many readings at once.
  const sorted = [...entries].sort((a, b) => a.date - b.date);
  const fresh: Omit<BgReading, "id">[] = [];
  for (const e of sorted) {
    if (!e?.date || !e?.sgv || e.date <= cutoff) continue;
    fresh.push({
      ts: e.date,
      mgdl: Math.round(e.sgv),
      trend: (e.direction ?? "None") as Trend,
      delta: typeof e.delta === "number" ? Math.round(e.delta * 10) / 10 : undefined,
      source: "xdrip",
      raw: e,
    });
  }
  if (fresh.length === 0) return 0;
  await db().bg.bulkAdd(fresh as BgReading[]);
  return fresh.length;
}

export class XdripPoller {
  private timer: ReturnType<typeof setInterval> | null = null;
  private inFlight = false;
  private firstRun = true;
  constructor(
    private base: string,
    private intervalMs = 60_000,
    private onUpdate?: (added: number) => void
  ) {}

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
    // If the URL changed, do another aggressive catch-up next tick.
    this.firstRun = true;
  }
  private async tick() {
    if (this.inFlight || !this.base) return;
    this.inFlight = true;
    try {
      // First fetch after start (or after URL change) pulls ~30h to
      // backfill anything missed while the app was closed; subsequent
      // ticks fall back to the 12-hour rolling window.
      const count = this.firstRun ? CATCHUP_COUNT : DEFAULT_COUNT;
      const added = await syncXdripOnce(this.base, count);
      this.firstRun = false;
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
