"use client";

import { db } from "./db";
import { readState } from "./alerts";

// Backup files: everything in the app's database, plus (inside the app) the
// glucose alerts' setup, which lives on the Android side. The same file is the
// manual "Download backup" and the daily cloud backup (lib/driveBackup).

const META_LAST_EXPORT = "meanwhile.lastExport";

const TABLE_NAMES = ["bg", "insulin", "decisions", "context", "profile"] as const;
type TableName = (typeof TABLE_NAMES)[number];

export function lastExport(): number {
  try { return Number(localStorage.getItem(META_LAST_EXPORT)) || 0; }
  catch { return 0; }
}
function setLastExport(ts: number) {
  try { localStorage.setItem(META_LAST_EXPORT, String(ts)); } catch {}
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

// ---- Export / import (manual backups) ----------------------------------

/** The backup file's text. */
export async function backupText(): Promise<string> {
  const all = await buildPush(0);
  const alerts = readState()?.config;
  return JSON.stringify({ ...all, ...(alerts ? { alerts } : {}), exported_at: Date.now() });
}

export async function exportAll(): Promise<Blob> {
  const text = await backupText();
  setLastExport(Date.now());
  return new Blob([text], { type: "application/json" });
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

/**
 * The glucose alerts' setup from a backup file, put back in the app. Returns
 * what happened, for the Settings message: "" when the file has none.
 */
export async function restoreAlertsFrom(file: File): Promise<string> {
  let alerts: unknown;
  try { alerts = (JSON.parse(await file.text()) as { alerts?: unknown }).alerts; }
  catch { return ""; }
  if (!alerts || typeof alerts !== "object") return "";
  const restore = (window as unknown as { EverBridgeNative?: { alertsRestore?: (json: string) => string } })
    .EverBridgeNative?.alertsRestore;
  if (!restore) return "";
  const err = restore.call((window as unknown as { EverBridgeNative: object }).EverBridgeNative, JSON.stringify(alerts));
  return err ? `alerts not restored: ${err}` : "alerts restored";
}
