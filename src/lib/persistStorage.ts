"use client";

// Request the browser to mark IndexedDB as persistent so it survives
// browser cache clears, storage-pressure eviction, and reloads. Chrome
// grants this automatically for installed PWAs / engaged sites; some
// browsers require a user gesture, so callers can re-invoke this on
// button press as well.
//
// Returns the current persistence + usage state, regardless of whether
// this call succeeded in flipping the flag.

export interface PersistState {
  supported: boolean;
  persisted: boolean;
  quota?: number;
  usage?: number;
}

export async function requestPersistentStorage(): Promise<PersistState> {
  if (typeof navigator === "undefined" || !navigator.storage) {
    return { supported: false, persisted: false };
  }
  const storage = navigator.storage;

  let persisted = false;
  try {
    if (typeof storage.persisted === "function") {
      persisted = await storage.persisted();
    }
    if (!persisted && typeof storage.persist === "function") {
      persisted = await storage.persist();
    }
  } catch {
    persisted = false;
  }

  let quota: number | undefined;
  let usage: number | undefined;
  try {
    if (typeof storage.estimate === "function") {
      const est = await storage.estimate();
      quota = est.quota ?? undefined;
      usage = est.usage ?? undefined;
    }
  } catch {
    /* ignore */
  }

  return { supported: true, persisted, quota, usage };
}

export function formatBytes(n?: number): string {
  if (n == null || !Number.isFinite(n)) return "—";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let v = n;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v < 10 ? v.toFixed(1) : Math.round(v)} ${units[i]}`;
}
