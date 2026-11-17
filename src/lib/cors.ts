import { NextResponse } from "next/server";

// The EverBridge Android app carries Meanwhile's pages with it and serves them
// from this address (Android reserves the host for exactly that; it is never a
// real site). Its AI and sync requests come here from that origin, so these
// routes answer it. No other site is allowed.
export const APP_ORIGIN = "https://appassets.androidplatform.net";

function corsHeaders(req: Request): Record<string, string> {
  if (req.headers.get("origin") !== APP_ORIGIN) return {};
  return {
    "Access-Control-Allow-Origin": APP_ORIGIN,
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "content-type",
    "Access-Control-Max-Age": "600",
    Vary: "Origin",
  };
}

// Adds the headers to a route's response (when the request is from the app).
export function withCors(req: Request, res: NextResponse): NextResponse {
  for (const [k, v] of Object.entries(corsHeaders(req))) res.headers.set(k, v);
  return res;
}

// The browser's preflight before a cross-origin POST with a JSON body.
export function preflight(req: Request): NextResponse {
  return new NextResponse(null, { status: 204, headers: corsHeaders(req) });
}
