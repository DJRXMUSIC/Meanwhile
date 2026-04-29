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
const CURRENT_SCHEMA = 2;

export async function getProfile(): Promise<Profile> {
  const existing = await db().profile.get("current");
  if (!existing) {
    await db().profile.put(DEFAULT_PROFILE);
    return DEFAULT_PROFILE;
  }
  if ((existing.schema_version ?? 1) < CURRENT_SCHEMA) {
    const migrated: Profile = {
      ...existing,
      // Loop-style defaults; only touch fields we manage.
      peak_min: existing.peak_min ?? 75,
      // Old default was 4h (too short for rapid-acting). Bump only if the
      // user is still on the old default, leaving any customization alone.
      dia_hours: existing.dia_hours === 4 ? 6 : existing.dia_hours,
      tir_low: existing.tir_low ?? 70,
      tir_high: existing.tir_high ?? 160,
      mode: existing.mode ?? "decide",
      schema_version: CURRENT_SCHEMA,
      updated_ts: Date.now(),
    };
    await db().profile.put(migrated);
    return migrated;
  }
  return existing;
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

export async function logCarbs(c: Omit<CarbEntry, "id">): Promise<number> {
  return db().carbs.add(c as CarbEntry);
}

export async function logDecision(d: Omit<Decision, "id">): Promise<number> {
  return db().decisions.add(d as Decision);
}

export async function logContext(c: Omit<ContextEntry, "id">): Promise<number> {
  return db().context.add(c as ContextEntry);
}
