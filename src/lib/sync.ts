"use client";

import { db } from "./db";

// Generic sync: pushes recent rows from each table to a user-configured endpoint
// and pulls newer rows since lastSync. The server is responsible for
// authentication and conflict resolution; this is a thin client.

const META_KEY = "meanwhile.lastSync";

export interface SyncBundle {
  bg: unknown[];
  insulin: unknown[];
  carbs: unknown[];
  decisions: unknown[];
  context: unknown[];
  profile: unknown[];
  client_ts: number;
  since: number;
}

export async function buildBundle(since: number): Promise<SyncBundle> {
  const d = db();
  const [bg, insulin, carbs, decisions, context, profile] = await Promise.all([
    d.bg.where("ts").above(since).toArray(),
    d.insulin.where("ts").above(since).toArray(),
    d.carbs.where("ts").above(since).toArray(),
    d.decisions.where("ts").above(since).toArray(),
    d.context.where("ts").above(since).toArray(),
    d.profile.toArray(),
  ]);
  return { bg, insulin, carbs, decisions, context, profile, client_ts: Date.now(), since };
}

export async function applyRemote(bundle: Partial<SyncBundle>): Promise<void> {
  const d = db();
  await d.transaction("rw", [d.bg, d.insulin, d.carbs, d.decisions, d.context, d.profile], async () => {
    if (bundle.bg) for (const r of bundle.bg as { ts: number; mgdl: number }[]) await d.bg.put(r as Parameters<typeof d.bg.put>[0]);
    if (bundle.insulin) for (const r of bundle.insulin as Parameters<typeof d.insulin.put>[0][]) await d.insulin.put(r);
    if (bundle.carbs) for (const r of bundle.carbs as Parameters<typeof d.carbs.put>[0][]) await d.carbs.put(r);
    if (bundle.decisions) for (const r of bundle.decisions as Parameters<typeof d.decisions.put>[0][]) await d.decisions.put(r);
    if (bundle.context) for (const r of bundle.context as Parameters<typeof d.context.put>[0][]) await d.context.put(r);
    if (bundle.profile) for (const r of bundle.profile as Parameters<typeof d.profile.put>[0][]) await d.profile.put(r);
  });
}

export function lastSync(): number {
  const v = localStorage.getItem(META_KEY);
  return v ? Number(v) : 0;
}
export function setLastSync(ts: number) { localStorage.setItem(META_KEY, String(ts)); }

export async function syncOnce(endpoint: string, apiKey: string): Promise<{ pushed: number; pulled: number } | null> {
  if (!endpoint) return null;
  const since = lastSync();
  const bundle = await buildBundle(since);
  const res = await fetch(endpoint.replace(/\/$/, "") + "/sync", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(apiKey ? { Authorization: `Bearer ${apiKey}` } : {}),
    },
    body: JSON.stringify(bundle),
  });
  if (!res.ok) throw new Error(`Sync failed: ${res.status}`);
  const remote = await res.json() as Partial<SyncBundle>;
  await applyRemote(remote);
  setLastSync(Date.now());
  const pushed = bundle.bg.length + bundle.insulin.length + bundle.carbs.length + bundle.decisions.length + bundle.context.length + bundle.profile.length;
  const pulled = (remote.bg?.length ?? 0) + (remote.insulin?.length ?? 0) + (remote.carbs?.length ?? 0) + (remote.decisions?.length ?? 0) + (remote.context?.length ?? 0) + (remote.profile?.length ?? 0);
  return { pushed, pulled };
}

export async function exportAll(): Promise<Blob> {
  const bundle = await buildBundle(0);
  return new Blob([JSON.stringify(bundle, null, 2)], { type: "application/json" });
}

export async function importAll(file: File): Promise<void> {
  const txt = await file.text();
  const json = JSON.parse(txt) as Partial<SyncBundle>;
  await applyRemote(json);
}
