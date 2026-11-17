"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { IN_APP_BUILD } from "@/lib/native";
import {
  activeSnooze,
  clock,
  onAlertsChanged,
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
  // a snooze still on, or one pressed since the app started (not an old one from before)
  const snoozedAt = alerts?.snoozed_at && (activeSnooze(alerts) || alerts.snoozed_at >= startedAt.current)
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

  const snooze = onNow ? activeSnooze(alerts) : null;

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
      {snooze ? (
        // in the title's place, the same height: nothing below moves
        <Link
          href="/alerts"
          className="flex items-center gap-1 min-w-0 h-7 px-2 rounded-full bg-warn/15 ring-1 ring-warn/50 text-[12px] leading-none"
          aria-label={`${snooze.name} snoozed until ${clock(snooze.until)}`}
        >
          <span className="font-semibold truncate">{snooze.name}</span>
          <span className="num text-muted shrink-0">{clock(snooze.until)}</span>
        </Link>
      ) : (
        <Link href="/" className="flex items-center gap-2 min-w-0">
          <div className="size-7 shrink-0 rounded-lg bg-accent/20 ring-1 ring-accent/40 grid place-items-center text-accent">◐</div>
          <span className="font-medium tracking-tight truncate">Meanwhile</span>
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
