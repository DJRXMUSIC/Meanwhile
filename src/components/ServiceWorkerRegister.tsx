"use client";

import { useEffect, useRef, useState } from "react";
import { requestPersistentStorage } from "@/lib/persistStorage";

const BUILD_ID = process.env.NEXT_PUBLIC_BUILD_ID || "dev";

export function ServiceWorkerRegister() {
  const [waiting, setWaiting] = useState<ServiceWorker | null>(null);
  // Set only when the user accepts the update, so the reload below fires
  // for that, and not for the ordinary first-ever controller handover.
  const reloadOnTakeover = useRef(false);

  useEffect(() => {
    if (typeof window === "undefined") return;

    // Ask the browser to mark our IndexedDB as persistent. Chrome
    // grants this for installed PWAs / engaged sites; once granted,
    // the DB survives cache clears and storage-pressure eviction.
    // Runs on every load so revocations are re-requested.
    requestPersistentStorage().catch(() => {});

    if (!("serviceWorker" in navigator)) return;
    if (process.env.NODE_ENV !== "production") return;

    const onControllerChange = () => {
      if (!reloadOnTakeover.current) return;
      reloadOnTakeover.current = false;
      window.location.reload();
    };
    navigator.serviceWorker.addEventListener("controllerchange", onControllerChange);

    // The ?v= carries the build id into the worker, which names its caches
    // after it. A new build is therefore a new script URL, which is what
    // makes the browser install it at all.
    navigator.serviceWorker
      .register(`/sw.js?v=${encodeURIComponent(BUILD_ID)}`)
      .then((reg) => {
        // A worker may already be waiting from an earlier visit.
        if (reg.waiting && navigator.serviceWorker.controller) {
          setWaiting(reg.waiting);
        }
        reg.addEventListener("updatefound", () => {
          const installing = reg.installing;
          if (!installing) return;
          installing.addEventListener("statechange", () => {
            // `controller` distinguishes an update from the first install —
            // on a first install there is nothing to interrupt.
            if (installing.state === "installed" && navigator.serviceWorker.controller) {
              setWaiting(installing);
            }
          });
        });
      })
      .catch(() => {});

    return () => {
      navigator.serviceWorker.removeEventListener("controllerchange", onControllerChange);
    };
  }, []);

  if (!waiting) return null;

  return (
    <div
      className="fixed inset-x-0 z-40 flex justify-center px-3 pointer-events-none"
      style={{ bottom: "calc(env(safe-area-inset-bottom) + 12px)" }}
      role="status"
    >
      <div className="pointer-events-auto flex items-center gap-3 rounded-full bg-surface ring-1 ring-accent/40 pl-4 pr-1.5 py-1.5 shadow-lg shadow-black/40">
        <span className="text-sm">New version available</span>
        <button
          onClick={() => {
            reloadOnTakeover.current = true;
            waiting.postMessage("SKIP_WAITING");
          }}
          className="rounded-full bg-accent text-white px-3 py-1.5 text-sm font-medium"
        >
          Reload
        </button>
      </div>
    </div>
  );
}
