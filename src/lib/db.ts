"use client";

import Dexie, { type Table } from "dexie";
import {
  type BgReading,
  type CarbEntry,
  type ContextEntry,
  type Decision,
  type InsulinDose,
  type Profile,
  DEFAULT_PROFILE,
  LEGACY_THEME_MAP,
} from "./types";

class MeanwhileDB extends Dexie {
  bg!: Table<BgReading, number>;
  insulin!: Table<InsulinDose, number>;
  carbs!: Table<CarbEntry, number>;
  decisions!: Table<Decision, number>;
  context!: Table<ContextEntry, number>;
  profile!: Table<Profile, string>;

  constructor() {
    super("meanwhile");
    this.version(1).stores({
      bg: "++id, ts",
      insulin: "++id, ts, kind",
      carbs: "++id, ts",
      decisions: "++id, ts",
      context: "++id, ts, kind",
      profile: "id",
    });
  }
}

let _db: MeanwhileDB | null = null;
export function db(): MeanwhileDB {
  if (typeof window === "undefined") {
    throw new Error("db() called on server — use only in client components/hooks.");
  }
  if (!_db) _db = new MeanwhileDB();
  return _db;
}

// Schema version bumps trigger one-shot, non-destructive migrations.
const CURRENT_SCHEMA = 4;

function newSyncKey(): string {
  // 32-char hex from a UUID — enough entropy to be unguessable, short
  // enough to copy to another device by hand if needed.
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID().replace(/-/g, "");
  }
  // Fallback (older WebViews / SSR): build from getRandomValues
  if (typeof crypto !== "undefined" && typeof crypto.getRandomValues === "function") {
    const arr = new Uint8Array(16);
    crypto.getRandomValues(arr);
    return Array.from(arr, (b) => b.toString(16).padStart(2, "0")).join("");
  }
  // Last resort: Math.random — not cryptographically secure, but the
  // user can rotate any time from Settings.
  return Array.from({ length: 32 }, () => Math.floor(Math.random() * 16).toString(16)).join("");
}

export async function getProfile(): Promise<Profile> {
  const existing = await db().profile.get("current");
  if (!existing) {
    const seeded: Profile = { ...DEFAULT_PROFILE, sync_key: newSyncKey() };
    await db().profile.put(seeded);
    return seeded;
  }
  // Backfill theme_mode / theme_accent for profiles saved before the split.
  let needsThemeMigration = false;
  let migratedMode = existing.theme_mode;
  let migratedAccent = existing.theme_accent;
  if (!existing.theme_mode || !existing.theme_accent) {
    const legacy = LEGACY_THEME_MAP[existing.theme ?? "default"] ?? { mode: "dark" as const, accent: "aurora" as const };
    migratedMode = existing.theme_mode ?? legacy.mode;
    migratedAccent = existing.theme_accent ?? legacy.accent;
    needsThemeMigration = true;
  }

  if ((existing.schema_version ?? 1) < CURRENT_SCHEMA || needsThemeMigration) {
    const migrated: Profile = {
      ...existing,
      // v2 fields (Loop-style insulin action)
      peak_min: existing.peak_min ?? 75,
      dia_hours: existing.dia_hours === 4 ? 6 : existing.dia_hours,
      tir_low: existing.tir_low ?? 70,
      tir_high: existing.tir_high ?? 160,
      mode: existing.mode ?? "decide",
      // v3 fields
      delay_min: existing.delay_min ?? 15,
      daily_basal_enabled: existing.daily_basal_enabled ?? true,
      daily_basal_units: existing.daily_basal_units ?? 20,
      daily_basal_time: existing.daily_basal_time ?? "18:30",
      daily_basal_tz: existing.daily_basal_tz ?? "America/New_York",
      // v4 fields — sync
      sync_key: existing.sync_key ?? newSyncKey(),
      sync_auto: existing.sync_auto ?? true,
      // theme split
      theme_mode: migratedMode,
      theme_accent: migratedAccent,
      schema_version: CURRENT_SCHEMA,
      updated_ts: Date.now(),
    };
    await db().profile.put(migrated);
    return migrated;
  }
  return existing;
}

export async function regenerateSyncKey(): Promise<string> {
  const key = newSyncKey();
  await saveProfile({ sync_key: key });
  return key;
}

export async function saveProfile(patch: Partial<Profile>): Promise<Profile> {
  const current = await getProfile();
  const next: Profile = { ...current, ...patch, id: "current", updated_ts: Date.now() };
  await db().profile.put(next);
  return next;
}

export async function recentBg(sinceMin = 360): Promise<BgReading[]> {
  const cutoff = Date.now() - sinceMin * 60_000;
  return db().bg.where("ts").above(cutoff).sortBy("ts");
}

export async function latestBg(): Promise<BgReading | undefined> {
  return db().bg.orderBy("ts").last();
}

export async function recentInsulin(sinceMin = 360): Promise<InsulinDose[]> {
  const cutoff = Date.now() - sinceMin * 60_000;
  return db().insulin.where("ts").above(cutoff).sortBy("ts");
}

export async function recentCarbs(sinceMin = 360): Promise<CarbEntry[]> {
  const cutoff = Date.now() - sinceMin * 60_000;
  return db().carbs.where("ts").above(cutoff).sortBy("ts");
}

export async function recentDecisions(limit = 50): Promise<Decision[]> {
  const all = await db().decisions.orderBy("ts").reverse().limit(limit).toArray();
  return all;
}

export async function logBg(r: Omit<BgReading, "id">): Promise<number> {
  return db().bg.add(r as BgReading);
}

export async function logInsulin(d: Omit<InsulinDose, "id">): Promise<number> {
  return db().insulin.add(d as InsulinDose);
}

export async function updateInsulin(id: number, patch: Partial<InsulinDose>): Promise<void> {
  await db().insulin.update(id, patch);
}

export async function deleteInsulin(id: number): Promise<void> {
  await db().insulin.delete(id);
}

export async function logCarbs(c: Omit<CarbEntry, "id">): Promise<number> {
  return db().carbs.add(c as CarbEntry);
}

export async function logDecision(d: Omit<Decision, "id">): Promise<number> {
  return db().decisions.add(d as Decision);
}

export async function logContext(c: Omit<ContextEntry, "id">): Promise<number> {
  return db().context.add(c as ContextEntry);
}
