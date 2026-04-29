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
        className={`px-3 py-1.5 rounded-full text-sm transition ${
          active ? "bg-surface2 text-ink ring-accent" : "text-muted hover:text-ink"
        }`}
      >
        {label}
      </Link>
    );
  };
  return (
    <header className="sticky top-0 z-20 flex items-center justify-between px-4 pt-[env(safe-area-inset-top)] py-3 bg-bg/80 backdrop-blur">
      <Link href="/" className="flex items-center gap-2">
        <div className="size-7 rounded-lg bg-accent/20 ring-1 ring-accent/40 grid place-items-center text-accent">◐</div>
        <span className="font-medium tracking-tight">Meanwhile</span>
      </Link>
      <nav className="flex items-center gap-1">
        {tab("/", "Now")}
        {tab("/profile", "Profile")}
        {tab("/settings", "Settings")}
      </nav>
    </header>
  );
}
