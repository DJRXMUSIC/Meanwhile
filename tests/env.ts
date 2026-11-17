// A browser-ish world for the library code under test: IndexedDB in memory,
// and `window` (the app's db() refuses to run without one).
import "fake-indexeddb/auto";
import Module from "node:module";
import path from "node:path";

// "@/lib/x" → src/lib/x, as in the app (tsconfig paths), for the compiled tests.
type Resolve = (request: string, ...rest: unknown[]) => string;
const M = Module as unknown as { _resolveFilename: Resolve };
const resolve = M._resolveFilename;
M._resolveFilename = function (this: unknown, request: string, ...rest: unknown[]) {
  const r = request.startsWith("@/") ? path.join(__dirname, "../src", request.slice(2)) : request;
  return resolve.call(this, r, ...rest);
};

const g = globalThis as unknown as Record<string, unknown>;
if (g.window === undefined) g.window = globalThis;

export function setNative(native: unknown): void {
  (globalThis as unknown as { EverBridgeNative?: unknown }).EverBridgeNative = native;
}

type FetchStub = (url: string) => Promise<{ ok: boolean; status: number; json: () => Promise<unknown>; text: () => Promise<string> }>;

// Replaces fetch; returns the list of URLs requested.
export function stubFetch(handler: (url: string) => unknown | Error): string[] {
  const calls: string[] = [];
  const f: FetchStub = async (url) => {
    calls.push(String(url));
    const r = handler(String(url));
    if (r instanceof Error) throw r;
    if (r === undefined) return { ok: false, status: 502, json: async () => ({}), text: async () => "" };
    return { ok: true, status: 200, json: async () => r, text: async () => JSON.stringify(r) };
  };
  (globalThis as unknown as { fetch: unknown }).fetch = f;
  return calls;
}

export const MIN = 60_000;
