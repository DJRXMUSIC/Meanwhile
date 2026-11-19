"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { IN_APP_BUILD } from "@/lib/native";
import {
  activeSnooze,
  alertsApp,
  alertsOff,
  onAlertsChanged,
  quietSummary,
  readState,
  shouldReturnToNow,
  SHOW_NOW,
  type AlertsState,
} from "@/lib/alerts";

export function TopBar() {
  const path = usePathname();
  const router = useRouter();
  const onNow = path === "/" || path === "";
  const [alerts, setAlerts] = useState<AlertsState | null>(null);

  // In the app: the alerts' state, for the snooze pill and for going back to Now.
  useEffect(() => {
    if (!IN_APP_BUILD) return;
    const load = () => setAlerts(readState());
    load();
    const off = onAlertsChanged(load);
    const t = setInterval(load, 10_000);
    return () => { off(); clearInterval(t); };
  }, []);

  // After a snooze, two minutes without a touch: back to Now, showing the present.
  const lastTouch = useRef(0);
  const handled = useRef(0);
  const startedAt = useRef(Number.MAX_SAFE_INTEGER);
  useEffect(() => { startedAt.current = Date.now(); }, []);
  // a snooze (or alerts turned off) still on, or one since the app started (not an old one from before)
  const snoozedAt = alerts?.snoozed_at && (activeSnooze(alerts) || alertsOff(alerts).length > 0
    || alerts.snoozed_at >= startedAt.current)
    ? alerts.snoozed_at : 0;
  useEffect(() => {
    if (!IN_APP_BUILD) return;
    const touched = () => { lastTouch.current = Date.now(); };
    const events = ["pointerdown", "keydown", "wheel", "touchstart"] as const;
    events.forEach((e) => window.addEventListener(e, touched, { passive: true, capture: true }));
    const check = () => {
      if (!shouldReturnToNow(snoozedAt, lastTouch.current, Date.now(), handled.current)) return;
      handled.current = snoozedAt;
      if (!onNow) router.replace("/");
      window.dispatchEvent(new Event(SHOW_NOW));
    };
    check();
    const t = setInterval(check, 5_000);
    document.addEventListener("visibilitychange", check);
    return () => {
      events.forEach((e) => window.removeEventListener(e, touched, { capture: true }));
      clearInterval(t);
      document.removeEventListener("visibilitychange", check);
    };
  }, [snoozedAt, onNow, router]);

  // On Now: what's snoozed or off, in the title's place. A tap turns it all back on, here.
  const quiet = onNow ? quietSummary(alerts) : null;
  const turnBackOn = () => {
    const a = alertsApp();
    if (!a || !alerts) return;
    if (activeSnooze(alerts)) a.alertsUnsnooze?.();
    for (const o of alertsOff(alerts)) a.alertsDisable?.(o.which, 0);
    // show it gone straight away; the app's state follows within a moment
    setAlerts({ ...alerts, active: null, config: { ...alerts.config, settings: { ...alerts.config.settings,
      alerts_disabled_until: 0, low_alerts_disabled_until: 0, high_alerts_disabled_until: 0 } } });
  };

  const tab = (href: string, label: string) => {
    // the app's pages are exported with a trailing slash ("/alerts/")
    const active = path === href || path === `${href}/`;
    return (
      <Link
        href={href}
        className={`px-2 py-1.5 rounded-full text-[13px] transition ${
          active ? "bg-surface2 text-ink ring-accent" : "text-muted hover:text-ink"
        }`}
      >
        {label}
      </Link>
    );
  };
  return (
    <header
      className="sticky top-0 z-20 flex items-center justify-between gap-1.5 px-3 py-3 bg-bg/80 backdrop-blur"
      style={{ paddingTop: "calc(env(safe-area-inset-top) + 12px)" }}
    >
      {quiet ? (
        // in the title's place, the same height: nothing below moves
        <button
          onClick={turnBackOn}
          className="flex items-center gap-1.5 min-w-0 h-7 px-2.5 rounded-full bg-warn/15 ring-1 ring-warn/50 text-[12px] leading-none"
          aria-label={`${quiet}. Tap to turn alerts back on`}
        >
          <span className="font-semibold truncate">{quiet}</span>
          <span className="text-muted shrink-0">· Turn on</span>
        </button>
      ) : (
        <Link href="/" className="flex items-center gap-2 min-w-0">
          {/* the app icon (public/icon.svg) */}
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src="/icon.svg" alt="" className="size-7 shrink-0 rounded-lg" />
          <span className="font-medium tracking-tight truncate">Meanwhile v6</span>
        </Link>
      )}
      <nav className="flex items-center gap-0.5 shrink-0">
        {tab("/", "Now")}
        {IN_APP_BUILD && tab("/alerts", "Alerts")}
        {tab("/profile", "Profile")}
        {tab("/settings", "Settings")}
      </nav>
    </header>
  );
}
