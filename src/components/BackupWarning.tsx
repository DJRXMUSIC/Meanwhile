"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { BACKUP_EVENT, backupFailing, readBackup } from "@/lib/driveBackup";

// On Now, only while the cloud backup is failing: one line, to Settings.
export function BackupWarning() {
  const [failing, setFailing] = useState(false);
  useEffect(() => {
    const check = () => setFailing(backupFailing(readBackup()));
    check();
    window.addEventListener(BACKUP_EVENT, check);
    const t = setInterval(check, 60_000);
    return () => { window.removeEventListener(BACKUP_EVENT, check); clearInterval(t); };
  }, []);
  if (!failing) return null;
  return (
    <Link href="/settings" className="mx-3 rounded-xl bg-bad/10 ring-1 ring-bad/40 px-3 py-2 text-[13px] text-bad">
      Backup failed · open Settings
    </Link>
  );
}
