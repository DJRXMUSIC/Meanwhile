"use client";

import { useEffect } from "react";
import { requestPersistentStorage } from "@/lib/persistStorage";

export function ServiceWorkerRegister() {
  useEffect(() => {
    if (typeof window === "undefined") return;

    // Ask the browser to mark our IndexedDB as persistent. Chrome
    // grants this for installed PWAs / engaged sites; once granted,
    // the DB survives cache clears and storage-pressure eviction.
    // Runs on every load so revocations are re-requested.
    requestPersistentStorage().catch(() => {});

    if (!("serviceWorker" in navigator)) return;
    if (process.env.NODE_ENV !== "production") return;
    navigator.serviceWorker.register("/sw.js").catch(() => {});
  }, []);
  return null;
}
