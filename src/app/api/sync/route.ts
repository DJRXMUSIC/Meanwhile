import { NextResponse } from "next/server";
import { z } from "zod";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

// ---- Sync bundle shape -------------------------------------------------
// Matches src/lib/sync.ts SyncBundle. Rows are stored as opaque arrays
// keyed by table name. The server doesn't interpret row contents — it
// merges by row id and serves rows newer than the client's `since`.

const BundleRowsSchema = z
  .record(z.string(), z.array(z.unknown()))
  .default({});

const PushSchema = z.object({
  sync_key: z.string().min(8).max(128).regex(/^[A-Za-z0-9_-]+$/),
  since: z.number().int().nonnegative(),
  push: BundleRowsSchema,
});

const TABLE_NAMES = ["bg", "insulin", "decisions", "context", "profile"] as const;
type TableName = (typeof TABLE_NAMES)[number];

interface StoredBundle {
  rows: Record<TableName, Record<string, { ts: number; data: unknown }>>;
  updated_at: number;
}

function emptyStored(): StoredBundle {
  return {
    rows: { bg: {}, insulin: {}, decisions: {}, context: {}, profile: {} },
    updated_at: 0,
  };
}

// ---- Storage backend ---------------------------------------------------
// Netlify Functions ship with Blobs out of the box; locally (next dev)
// we fall back to an in-memory Map so dev and CI just work.

type Loader = () => Promise<StoredBundle>;
type Saver = (b: StoredBundle) => Promise<void>;

const memStore = new Map<string, StoredBundle>();

async function getBackend(key: string): Promise<{ load: Loader; save: Saver; backend: "blobs" | "memory" }> {
  const blobKey = `b/${key}`;

  // Detect Netlify environment. NETLIFY=true is set in Functions / build.
  if (process.env.NETLIFY === "true" || process.env.NETLIFY_BLOBS_CONTEXT) {
    try {
      const mod = (await import("@netlify/blobs")) as typeof import("@netlify/blobs");
      const store = mod.getStore({ name: "meanwhile-sync", consistency: "strong" });
      return {
        backend: "blobs",
        load: async () => {
          const v = await store.get(blobKey, { type: "json" });
          return (v as StoredBundle | null) ?? emptyStored();
        },
        save: async (b) => {
          await store.setJSON(blobKey, b);
        },
      };
    } catch {
      // Fall through to in-memory below.
    }
  }

  return {
    backend: "memory",
    load: async () => memStore.get(blobKey) ?? emptyStored(),
    save: async (b) => { memStore.set(blobKey, b); },
  };
}

// ---- Merge + project ---------------------------------------------------

interface RowLike {
  id?: number | string;
  ts?: number;
  updated_ts?: number;
}

function rowKey(row: unknown, fallback: number): string {
  const r = row as RowLike;
  if (r && (r.id != null)) return String(r.id);
  // Profile rows have id="current"; others always have a numeric id once
  // Dexie has assigned one. As a fallback (e.g. brand-new pushes from a
  // client) use the row's ts + an index disambiguator.
  return `_${r?.ts ?? 0}_${fallback}`;
}

function rowTs(row: unknown): number {
  const r = row as RowLike;
  return r?.updated_ts ?? r?.ts ?? 0;
}

export async function POST(req: Request) {
  let body: unknown;
  try { body = await req.json(); }
  catch { return NextResponse.json({ error: "Invalid JSON" }, { status: 400 }); }

  const parsed = PushSchema.safeParse(body);
  if (!parsed.success) {
    return NextResponse.json({ error: "Bad request", issues: parsed.error.issues }, { status: 400 });
  }
  const { sync_key, since, push } = parsed.data;

  const { load, save, backend } = await getBackend(sync_key);
  const bundle = await load();

  // Merge incoming rows, last-writer-wins by updated_ts (or ts as fallback).
  let pushed = 0;
  for (const table of TABLE_NAMES) {
    const incoming = push[table];
    if (!incoming || !Array.isArray(incoming)) continue;
    let i = 0;
    for (const row of incoming) {
      const key = rowKey(row, i++);
      const ts = rowTs(row);
      const prev = bundle.rows[table][key];
      if (!prev || (prev.ts ?? 0) <= ts) {
        bundle.rows[table][key] = { ts, data: row };
        pushed++;
      }
    }
  }
  bundle.updated_at = Math.max(bundle.updated_at, Date.now());
  await save(bundle);

  // Project back to the client: all rows newer than `since`, table-keyed.
  const pull: Record<TableName, unknown[]> = { bg: [], insulin: [], decisions: [], context: [], profile: [] };
  let pulled = 0;
  for (const table of TABLE_NAMES) {
    for (const entry of Object.values(bundle.rows[table])) {
      if (entry.ts > since) {
        pull[table].push(entry.data);
        pulled++;
      }
    }
  }

  return NextResponse.json({
    ok: true,
    backend,
    server_ts: Date.now(),
    pushed,
    pulled,
    pull,
  });
}

export async function GET() {
  return NextResponse.json({
    ok: true,
    hint: "POST { sync_key, since, push } to sync.",
  });
}
