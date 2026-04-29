import { NextResponse } from "next/server";
import { z } from "zod";
import { buildUserPrompt, type PromptContext } from "@/lib/ai/prompt";
import { runDecision } from "@/lib/ai/providers";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const RequestSchema = z.object({
  user_input: z.string().min(1),
  context: z.object({
    profile: z.any(),
    bg_now: z.number().nullable(),
    bg_trend: z.string().nullable(),
    bg_delta: z.number().nullable(),
    bg_history: z.array(z.object({ ts: z.number(), mgdl: z.number() })),
    iob: z.number(),
    cob: z.number(),
    recent_doses: z.array(z.object({ ts: z.number(), units: z.number(), kind: z.string() })),
    recent_carbs: z.array(z.object({ ts: z.number(), g: z.number(), desc: z.string().optional() })),
  }),
  preferred_provider: z.enum(["anthropic", "openai", "google", "auto"]).optional(),
});

export async function POST(req: Request) {
  let body: unknown;
  try { body = await req.json(); } catch { return NextResponse.json({ error: "Invalid JSON" }, { status: 400 }); }

  const parsed = RequestSchema.safeParse(body);
  if (!parsed.success) {
    return NextResponse.json({ error: "Bad request", issues: parsed.error.issues }, { status: 400 });
  }
  const { user_input, context, preferred_provider } = parsed.data;

  const promptCtx: PromptContext = {
    ...context,
    profile: context.profile,
    user_input,
    now_iso: new Date().toISOString(),
  };
  const userPrompt = buildUserPrompt(promptCtx);

  try {
    const result = await runDecision(userPrompt, {
      preferred: preferred_provider && preferred_provider !== "auto" ? preferred_provider : undefined,
    });
    return NextResponse.json({
      decision: result.decision,
      provider: result.provider,
      model: result.model,
    });
  } catch (e) {
    const msg = e instanceof Error ? e.message : "Unknown error";
    return NextResponse.json({ error: msg }, { status: 500 });
  }
}
