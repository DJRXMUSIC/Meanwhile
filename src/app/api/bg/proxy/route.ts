import { NextResponse } from "next/server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

// Optional CORS proxy for xDrip+ when the device hosting xDrip+ is on the same
// network as the Next.js server but the browser's CORS prevents direct fetch.
// The browser passes ?base=<xdrip-url>&count=N.
export async function GET(req: Request) {
  const url = new URL(req.url);
  const base = url.searchParams.get("base");
  const count = url.searchParams.get("count") ?? "24";
  if (!base) return NextResponse.json({ error: "missing base" }, { status: 400 });
  try {
    const r = await fetch(`${base.replace(/\/$/, "")}/sgv.json?count=${count}`, { cache: "no-store" });
    if (!r.ok) return NextResponse.json({ error: `upstream ${r.status}` }, { status: 502 });
    const json = await r.json();
    return NextResponse.json(json, { headers: { "Cache-Control": "no-store" } });
  } catch (e) {
    return NextResponse.json({ error: (e as Error).message }, { status: 502 });
  }
}
