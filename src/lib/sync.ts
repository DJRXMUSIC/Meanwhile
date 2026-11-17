"use client";

import { db } from "./db";

// Multi-device sync via the built-in /api/sync route (Netlify Blobs in
// production, in-memory fallback during local dev). The server never
// sees credentials beyond the user-supplied sync_key, which acts as a
// shared namespace identifier.
//
// Bundle structure: each table is sent as an array of rows. The server
// merges by row.id (or row.ts for new pushes) using last-writer-wins on
// updated_ts. On pull, the server returns every row newer than `since`.

const META_LAST_SYNC = "meanwhile.lastSync";
const META_LAST_EXPORT = "meanwhile.lastExport";
const META_LAST_STATUS = "meanwhile.lastSyncStatus";

const TABLE_NAMES = ["bg", "insulin", "decisions", "context", "profile"] as const;
type TableName = (typeof TABLE_NAMES)[number];

export interface SyncBundle {
  bg: unknown[];
  insulin: unknown[];
  decisions: unknown[];
  context: unknown[];
  profile: unknown[];
}

export interface SyncResult {
  pushed: number;
  pulled: number;
  backend: "blobs" | "memory" | "unknown";
  server_ts: number;
}

// ---- Local meta --------------------------------------------------------

export function lastSync(): number {
  try { return Number(localStorage.getItem(META_LAST_SYNC)) || 0; }
  catch { return 0; }
}
function setLastSync(ts: number) {
  try { localStorage.setItem(META_LAST_SYNC, String(ts)); } catch {}
}

export function lastExport(): number {
  try { return Number(localStorage.getItem(META_LAST_EXPORT)) || 0; }
  catch { return 0; }
}
function setLastExport(ts: number) {
  try { localStorage.setItem(META_LAST_EXPORT, String(ts)); } catch {}
}

export function lastSyncStatus(): string {
  try { return localStorage.getItem(META_LAST_STATUS) || ""; }
  catch { return ""; }
}
function setLastSyncStatus(s: string) {
  try { localStorage.setItem(META_LAST_STATUS, s); } catch {}
}

// ---- Bundle build / apply ---------------------------------------------

export async function buildPush(since: number): Promise<Record<TableName, unknown[]>> {
  const d = db();
  const [bg, insulin, decisions, context, profile] = await Promise.all([
    d.bg.where("ts").above(since).toArray(),
    d.insulin.where("ts").above(since).toArray(),
    d.decisions.where("ts").above(since).toArray(),
    d.context.where("ts").above(since).toArray(),
    d.profile.toArray(),
  ]);
  return { bg, insulin, decisions, context, profile };
}

// Only TABLE_NAMES are applied, so carb rows in an older export or from a
// peer still running the previous build are ignored rather than imported.
export async function applyPull(pull: Partial<Record<TableName, unknown[]>>): Promise<number> {
  const d = db();
  let applied = 0;
  await d.transaction("rw", [d.bg, d.insulin, d.decisions, d.context, d.profile], async () => {
    for (const t of TABLE_NAMES) {
      const rows = pull[t];
      if (!rows) continue;
      for (const r of rows) {
        await d.table(t).put(r as Parameters<ReturnType<typeof d.table>["put"]>[0]);
        applied++;
      }
    }
  });
  return applied;
}

// ---- Sync API call -----------------------------------------------------

export async function syncOnce(syncKey: string): Promise<SyncResult> {
  if (!syncKey) throw new Error("Missing sync key");
  const since = lastSync();
  const push = await buildPush(since);
  const res = await fetch("/api/sync", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ sync_key: syncKey, since, push }),
  });
  if (!res.ok) {
    const t = await res.text().catch(() => "");
    throw new Error(`Sync failed (${res.status}): ${t.slice(0, 200)}`);
  }
  const body = await res.json() as {
    pushed: number;
    pulled: number;
    backend: "blobs" | "memory";
    server_ts: number;
    pull: Partial<Record<TableName, unknown[]>>;
  };
  await applyPull(body.pull);
  setLastSync(body.server_ts);
  const status = `pushed ${body.pushed}, pulled ${body.pulled}`;
  setLastSyncStatus(status);
  return {
    pushed: body.pushed,
    pulled: body.pulled,
    backend: body.backend,
    server_ts: body.server_ts,
  };
}

// ---- Export / import (manual backups) ----------------------------------

export async function exportAll(): Promise<Blob> {
  const all = await buildPush(0);
  setLastExport(Date.now());
  return new Blob([JSON.stringify({ ...all, exported_at: Date.now() }, null, 2)], {
    type: "application/json",
  });
}

// Restoring a backup file. Rows carry the ids they had in the app that made
// the backup, and this app may already hold different rows under those same
// ids (readings it has fetched from the bridge, a dose logged before the
// restore). Putting the backup's rows in place would silently replace them,
// so those local rows are set aside first and added back under new ids.
// All in one transaction: a restore either happens completely or not at all.
const AUTO_ID_TABLES = ["bg", "insulin", "decisions", "context"] as const;

export async function importAll(file: File): Promise<number> {
  const txt = await file.text();
  const json = JSON.parse(txt) as Partial<Record<TableName, unknown[]>>;
  const d = db();
  return d.transaction("rw", [d.bg, d.insulin, d.decisions, d.context, d.profile], async () => {
    const displaced: { table: (typeof AUTO_ID_TABLES)[number]; row: Record<string, unknown> }[] = [];
    for (const t of AUTO_ID_TABLES) {
      const rows = json[t];
      if (!Array.isArray(rows) || rows.length === 0) continue;
      const incoming = new Map<unknown, unknown>(); // id -> ts
      for (const r of rows as ({ id?: unknown; ts?: unknown } | null)[]) {
        if (r && r.id != null) incoming.set(r.id, r.ts);
      }
      await d.table(t).each((local: Record<string, unknown>) => {
        if (incoming.has(local.id) && incoming.get(local.id) !== local.ts) displaced.push({ table: t, row: local });
      });
    }
    const applied = await applyPull(json);
    for (const { table, row } of displaced) {
      const { id: _oldId, ...rest } = row;
      await d.table(table).add(rest);
    }
    await dropDuplicateBridgeReadings();
    return applied;
  });
}

// A backup restored into an app that has already fetched readings from the
// bridge can bring in the same reading twice: once from the file, once
// fetched, under different row ids. Keeps one of each (same time, same value,
// both from the bridge) so the chart and statistics don't count it double.
// Returns how many rows were removed.
export async function dropDuplicateBridgeReadings(): Promise<number> {
  const d = db();
  const seen = new Set<string>();
  const dups: number[] = [];
  await d.bg.orderBy("ts").each((r) => {
    if (r.source !== "xdrip" || r.id == null) return;
    const key = `${r.ts}|${r.mgdl}`;
    if (seen.has(key)) dups.push(r.id);
    else seen.add(key);
  });
  if (dups.length) await d.bg.bulkDelete(dups);
  return dups.length;
}
