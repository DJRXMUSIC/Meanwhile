"use client";

import { db } from "./db";
import { everBridge } from "./native";
import { nextReading, READING_INTERVAL_MS } from "./nextReading";
import type { BgReading, Trend } from "./types";

// Glucose from the EverBridge app on the phone (http://127.0.0.1:17590), which
// serves Nightscout-style JSON. (The names here date from when this read xDrip+; xDrip+
// itself, on port 17580, is never read.)
// Endpoint convention used here: GET {base}/sgv.json?count=N
// Each entry: { date: ms, sgv: mg/dL, direction: Trend, delta: mg/dL change since the
// previous reading }

export interface XdripEntry {
  date: number;
  sgv: number;
  direction?: Trend;
  delta?: number;
}

// Used by "Test connection": 144 entries at 5-min intervals = 12 hours.
const DEFAULT_COUNT = 144;
// First poll after start, and a manual refresh: ~30 hours, so first-open after
// a long absence backfills cleanly.
const CATCHUP_COUNT = 360;
// Every other poll asks for just the readings since the last one we hold
// (plus a margin), not a fixed 12 hours: a few hundred bytes instead of ~40 KB.
const MIN_COUNT = 3;

// The newest reading that came from the bridge. Not just the newest reading:
// a BG typed in by hand must not hide bridge readings timestamped before it.
export async function latestBridgeBg(): Promise<BgReading | undefined> {
  return db().bg.orderBy("ts").reverse().filter((r) => r.source === "xdrip").first();
}

export function countSince(lastTs: number | undefined, now: number): number {
  if (lastTs == null) return CATCHUP_COUNT;
  const n = Math.ceil((now - lastTs) / READING_INTERVAL_MS) + 2;
  return Math.min(CATCHUP_COUNT, Math.max(MIN_COUNT, n));
}

export async function fetchXdrip(base: string, count = DEFAULT_COUNT): Promise<XdripEntry[]> {
  if (!base) return [];

  // 0) Inside the EverBridge app: read the bridge's store directly. Same JSON
  //    as /sgv.json, with no network request to fail or be blocked.
  const native = everBridge();
  if (native) {
    try {
      const raw = native.sgv(count);
      if (raw) {
        const json = JSON.parse(raw) as XdripEntry[];
        if (Array.isArray(json)) return json;
      }
    } catch {
      // fall through to the HTTP endpoint
    }
  }

  const direct = `${base.replace(/\/$/, "")}/sgv.json?count=${count}`;

  // 1) Try direct fetch from the browser. Works when the page is on the
  //    same phone as the bridge (it answers CORS and Private Network Access).
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
  //    server fetches the bridge on our behalf — works as long as the deploy
  //    can reach `base` on the network.
  const proxied = `/api/bg/proxy?base=${encodeURIComponent(base)}&count=${count}`;
  const res2 = await fetch(proxied, { cache: "no-store" });
  if (!res2.ok) throw new Error(`Bridge fetch failed: ${res2.status}`);
  const json = (await res2.json()) as XdripEntry[];
  return Array.isArray(json) ? json : [];
}

export async function syncXdripOnce(base: string, count = DEFAULT_COUNT): Promise<number> {
  const entries = await fetchXdrip(base, count);
  if (!entries.length) return 0;
  const last = await latestBridgeBg();
  const cutoff = last?.ts ?? 0;
  // Build the full set of new readings first, then bulkAdd in a single
  // transaction so the Dexie live query fires once instead of N times —
  // that's what was causing the chart and BG tile to "flicker" while
  // backfilling many readings at once.
  const sorted = entries.filter((e) => e && e.date && e.sgv).sort((a, b) => a.date - b.date);
  const fresh: Omit<BgReading, "id">[] = [];
  for (const e of sorted) {
    if (e.date <= cutoff) continue;
    fresh.push({
      ts: e.date,
      mgdl: Math.round(e.sgv),
      trend: (e.direction ?? "None") as Trend,
      delta: typeof e.delta === "number" ? Math.round(e.delta) : undefined,
      source: "xdrip",
      raw: e,
    });
  }
  if (fresh.length === 0) return 0;
  await db().bg.bulkAdd(fresh as BgReading[]);
  return fresh.length;
}

// Check this long after a reading is due: the Eversense app posts about a
// second after the reading time, and the bridge stores it straight away.
const DUE_GRACE_MS = 2_000;
// While a reading is due, check this often until it arrives.
const DUE_RETRY_MS = 5_000;
// Stop the quick checks once the last reading is this old (sensor off, phone
// away); the normal interval carries on.
const QUICK_CHECKS_FOR_MS = 30 * 60_000;

// How long to wait before the next poll. Polls land just after each reading
// is due and repeat every few seconds until it arrives, so a new reading shows
// within seconds; otherwise every `intervalMs`.
export function nextPollDelay(lastTs: number | undefined, now: number, intervalMs: number): number {
  if (lastTs == null || now - lastTs > QUICK_CHECKS_FOR_MS) return intervalMs;
  const t = now - DUE_GRACE_MS; // the bridge has had DUE_GRACE_MS to store it
  const next = nextReading(lastTs, t);
  if (next.due) return Math.min(intervalMs, DUE_RETRY_MS);
  return Math.max(DUE_RETRY_MS / 5, Math.min(intervalMs, next.at - t));
}

export class XdripPoller {
  private timer: ReturnType<typeof setTimeout> | null = null;
  private running = false;
  private inFlight = false;
  private firstRun = true;
  constructor(
    private base: string,
    private intervalMs = 60_000,
    private onUpdate?: (added: number) => void
  ) {}

  start() {
    if (this.running) return;
    this.running = true;
    this.poll();
  }
  stop() {
    this.running = false;
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
  }
  setBase(base: string) {
    this.base = base;
    // If the URL changed, do another aggressive catch-up next tick.
    this.firstRun = true;
  }

  // Background poll. Soft-fails: the bridge is routinely unreachable (phone off
  // the network, app closed) and that is not worth interrupting anyone over.
  // Also called on foreground wake, where timers may have been throttled or
  // suspended while the PWA was backgrounded.
  async poll(): Promise<void> {
    try {
      await this.run();
    } catch {
      // ignored by design — see refreshNow() for the reporting path
    }
  }

  // Explicit user-initiated refresh. Always pulls the wide catch-up window,
  // and lets failures through so the UI can say what went wrong: someone who
  // just tapped refresh is owed an answer either way.
  async refreshNow(): Promise<number> {
    if (!this.base) throw new Error("No EverBridge URL configured");
    return this.run(CATCHUP_COUNT);
  }

  private async run(forceCount?: number): Promise<number> {
    if (this.inFlight || !this.base) return 0;
    this.inFlight = true;
    let lastTs: number | undefined;
    try {
      // First fetch after start (or after URL change) pulls ~30h to backfill
      // anything missed while the app was closed; after that, only what's new.
      const before = await latestBridgeBg();
      lastTs = before?.ts; // kept if the fetch fails, so a due reading is still checked for
      const count = forceCount ?? (this.firstRun ? CATCHUP_COUNT : countSince(before?.ts, Date.now()));
      const added = await syncXdripOnce(this.base, count);
      this.firstRun = false;
      if (added > 0) {
        lastTs = (await latestBridgeBg())?.ts;
        this.onUpdate?.(added);
      }
      return added;
    } finally {
      this.inFlight = false;
      this.schedule(lastTs);
    }
  }

  // One timer at a time: every run replaces whatever was scheduled.
  private schedule(lastTs: number | undefined) {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    if (!this.running) return;
    this.timer = setTimeout(() => this.poll(), nextPollDelay(lastTs, Date.now(), this.intervalMs));
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
