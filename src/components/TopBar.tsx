"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

export function TopBar() {
  const path = usePathname();
  const tab = (href: string, label: string) => {
    const active = path === href;
    return (
      <Link
        href={href}
        className={`px-2.5 py-1.5 rounded-full text-[13px] transition ${
          active ? "bg-surface2 text-ink ring-accent" : "text-muted hover:text-ink"
        }`}
      >
        {label}
      </Link>
    );
  };
  return (
    <header
      className="sticky top-0 z-20 flex items-center justify-between gap-2 px-3 py-3 bg-bg/80 backdrop-blur"
      style={{ paddingTop: "calc(env(safe-area-inset-top) + 12px)" }}
    >
      <Link href="/" className="flex items-center gap-2 min-w-0">
        <div className="size-7 shrink-0 rounded-lg bg-accent/20 ring-1 ring-accent/40 grid place-items-center text-accent">◐</div>
        <span className="font-medium tracking-tight truncate">Meanwhile</span>
      </Link>
      <nav className="flex items-center gap-0.5 shrink-0">
        {tab("/", "Now")}
        {tab("/profile", "Profile")}
        {tab("/settings", "Settings")}
      </nav>
    </header>
  );
}
