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

export async function importAll(file: File): Promise<number> {
  const txt = await file.text();
  const json = JSON.parse(txt) as Partial<Record<TableName, unknown[]>>;
  return applyPull(json);
}
