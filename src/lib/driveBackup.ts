"use client";

import { everBridge } from "./native";
import { backupText } from "./sync";

// The daily cloud backup: the backup file (lib/sync backupText), written by the
// app to a folder the user picked once, normally in Google Drive. Runs while
// the app is open, when the last good backup is over BACKUP_EVERY_MS old.

export const BACKUP_EVERY_MS = 12 * 3600_000;
/** After a failure, wait this long before trying again by itself. */
export const RETRY_AFTER_MS = 30 * 60_000;
export const BACKUP_EVENT = "meanwhile:backup";

export interface BackupState {
  folder: string;
  folder_name: string;
  ok_at: number;
  bytes: number;
  file: string;
  attempt_at: number;
  error: string;
}

interface BackupNative {
  backupState?(): string;
  backupPickFolder?(): void;
  backupEnd?(id: string): string;
  saveBegin?(name: string): string | null;
  saveChunk?(id: string, text: string): boolean;
}

function app(): BackupNative | null {
  const n = everBridge() as unknown as BackupNative | null;
  return n && typeof n.backupState === "function" && typeof n.backupEnd === "function" ? n : null;
}

function parse(text: string | null | undefined): BackupState | null {
  try {
    const s = JSON.parse(text ?? "") as Partial<BackupState>;
    return {
      folder: s.folder ?? "", folder_name: s.folder_name ?? "", ok_at: s.ok_at ?? 0, bytes: s.bytes ?? 0,
      file: s.file ?? "", attempt_at: s.attempt_at ?? 0, error: s.error ?? "",
    };
  } catch {
    return null;
  }
}

/** Null outside the app. */
export function readBackup(): BackupState | null {
  const n = app();
  return n ? parse(n.backupState?.()) : null;
}

/** meanwhile-YYYY-MM-DD.json, by the phone's date. */
export function backupName(now: number): string {
  const d = new Date(now);
  const p = (n: number) => String(n).padStart(2, "0");
  return `meanwhile-${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}.json`;
}

export function backupDue(s: BackupState | null, now: number): boolean {
  if (!s || !s.folder) return false;
  if (now - s.ok_at < BACKUP_EVERY_MS) return false;
  // failed lately: don't hammer a folder that isn't there
  return !(s.error && now - s.attempt_at < RETRY_AFTER_MS);
}

/** The last try failed (and a folder was chosen). */
export function backupFailing(s: BackupState | null): boolean {
  return !!s && !!s.folder && !!s.error && s.attempt_at >= s.ok_at;
}

const CHUNK = 256 * 1024;
let running: Promise<BackupState | null> | null = null;

/** Writes a backup now. Resolves to the new state (null outside the app). */
export function backupNow(): Promise<BackupState | null> {
  if (running) return running;
  running = (async () => {
    const n = app();
    if (!n || !n.saveBegin || !n.saveChunk || !n.backupEnd) return null;
    const name = backupName(Date.now());
    let id: string | null = null;
    try {
      const text = await backupText();
      id = n.saveBegin(name);
      if (id) {
        for (let i = 0; i < text.length; ) {
          let end = Math.min(text.length, i + CHUNK);
          const last = text.charCodeAt(end - 1);
          if (end < text.length && last >= 0xd800 && last <= 0xdbff) end++;
          if (!n.saveChunk(id, text.slice(i, end))) break;
          i = end;
        }
      }
    } catch {
      // the app reports the failure below
    }
    const s = parse(n.backupEnd(id ?? ""));
    window.dispatchEvent?.(new Event(BACKUP_EVENT));
    return s;
  })().finally(() => { running = null; });
  return running;
}

/** Opens the folder picker; resolves to the state once the user is back. */
export function pickBackupFolder(): Promise<BackupState | null> {
  const n = app();
  if (!n?.backupPickFolder) return Promise.resolve(null);
  return new Promise((resolve) => {
    (window as unknown as { __backupFolderPicked?: (s: string) => void }).__backupFolderPicked = (s) => {
      const st = parse(s);
      window.dispatchEvent?.(new Event(BACKUP_EVENT));
      resolve(st);
    };
    n.backupPickFolder!.call(n);
  });
}

/** Backs up when due, now and every few minutes while the app is open. */
export function startAutoBackup(): () => void {
  if (!app()) return () => {};
  const check = () => {
    if (document.visibilityState === "hidden") return;
    if (backupDue(readBackup(), Date.now())) void backupNow();
  };
  const first = setTimeout(check, 5_000); // after the app has settled
  const t = setInterval(check, 5 * 60_000);
  document.addEventListener("visibilitychange", check);
  return () => { clearTimeout(first); clearInterval(t); document.removeEventListener("visibilitychange", check); };
}
