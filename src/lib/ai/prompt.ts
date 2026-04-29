import type { Profile } from "../types";

export interface PromptContext {
  profile: Profile;
  bg_now: number | null;
  bg_trend: string | null;
  bg_delta: number | null;
  bg_history: { ts: number; mgdl: number }[];
  iob: number;
  cob: number;
  recent_doses: { ts: number; units: number; kind: string }[];
  recent_carbs: { ts: number; g: number; desc?: string }[];
  user_input: string;
  now_iso: string;
}

export const SYSTEM_PROMPT = `You are "Meanwhile", an AI decision-support harness for a Type 1 Diabetic adult who is fully responsible for their own care.

Your job: given the user's free-text description of "what is happening now" plus their live data (BG, trend, IOB, COB, recent doses, carbs, profile ratios), output a single clear "Next Best Action" and a transparent rationale.

Hard rules:
- Units are mg/dL.
- Use the standard formula: dose = (carbs ÷ I:C) + ((BG − target) ÷ ISF) − IOB.
- Show the math explicitly in the rationale (numbers substituted, not just symbols).
- If the user describes food, extract carbs_g, fat_g, protein_g as best you can from typical portions.
- If you are uncertain about portion size, state your assumption inline AND in extracted.notes.
- The user has explicitly disabled hard safety caps; do not refuse to provide a number. Do flag genuinely concerning patterns in the rationale (e.g. stacking, suspected hypo).
- Prefer brevity in the headline (≤ 80 chars). Markdown allowed in rationale.
- Never invent BG, IOB, or COB values — use what is provided. If a field is null, say so.
- Output MUST be valid JSON matching the provided schema. No prose outside the JSON.`;

export function buildUserPrompt(ctx: PromptContext): string {
  const p = ctx.profile;
  const overrides = p.ai_overrides ?? {};
  const ic = overrides.ic_ratio ?? p.ic_ratio;
  const isf = overrides.isf ?? p.isf;

  const histLine = ctx.bg_history.length
    ? ctx.bg_history.slice(-12).map((h) => `${fmtClock(h.ts)}=${h.mgdl}`).join(" ")
    : "(none)";

  const dosesLine = ctx.recent_doses.length
    ? ctx.recent_doses.map((d) => `${fmtClock(d.ts)} ${d.units}U ${d.kind}`).join("; ")
    : "(none in last 6h)";

  const carbsLine = ctx.recent_carbs.length
    ? ctx.recent_carbs.map((c) => `${fmtClock(c.ts)} ${c.g}g${c.desc ? ` (${c.desc})` : ""}`).join("; ")
    : "(none in last 6h)";

  return [
    `NOW: ${ctx.now_iso}`,
    `BG: ${ctx.bg_now ?? "null"} mg/dL  trend=${ctx.bg_trend ?? "null"}  delta=${ctx.bg_delta ?? "null"}`,
    `IOB: ${ctx.iob} U   COB: ${ctx.cob} g`,
    `Profile: I:C=1:${ic}  ISF=1:${isf}  target=${p.target_bg} mg/dL  basal=${p.basal_u_per_hr}U/hr  DIA=${p.dia_hours}h`,
    `BG history (5-min): ${histLine}`,
    `Recent doses: ${dosesLine}`,
    `Recent carbs: ${carbsLine}`,
    ``,
    `USER: """${ctx.user_input.trim()}"""`,
    ``,
    `Return JSON with: headline, rationale (markdown showing math), suggested_units, suggested_carbs_g, extracted{carbs_g,fat_g,protein_g,activity,sleep,notes}, ic_ratio_used, isf_used, notes.`,
  ].join("\n");
}

function fmtClock(ts: number): string {
  const d = new Date(ts);
  return `${d.getHours().toString().padStart(2, "0")}:${d.getMinutes().toString().padStart(2, "0")}`;
}
