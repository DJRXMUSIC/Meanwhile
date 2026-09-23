import type { Profile } from "../types";

export interface PromptContext {
  profile: Profile;
  bg_now: number | null;
  bg_trend: string | null;
  bg_delta: number | null;
  bg_history: { ts: number; mgdl: number }[];
  iob: number;
  recent_doses: { ts: number; units: number; kind: string }[];
  user_input: string;
  now_iso: string;
}

export const SYSTEM_PROMPT = `You are "Meanwhile", an AI decision-support harness for a Type 1 Diabetic adult who is fully responsible for their own care.

Your job: given the user's free-text description of "what is happening now" plus their live data (BG, trend, IOB, recent doses, profile ratios), output a single clear "Next Best Action" and a transparent rationale.

Hard rules:
- Units are mg/dL.
- This app does not track carbohydrates. Dose corrections only, using: dose = ((BG − target) ÷ ISF) − IOB.
- Show the math explicitly in the rationale (numbers substituted, not just symbols).
- Do not ask for, estimate, or reason from carb counts — there is no carb ratio to apply. If the user mentions food, acknowledge it as context for where BG is heading, and still dose from BG, trend and IOB alone.
- If you are uncertain about anything material, state your assumption inline AND in extracted.notes.
- The user has explicitly disabled hard safety caps; do not refuse to provide a number. Do flag genuinely concerning patterns in the rationale (e.g. stacking, suspected hypo).
- Prefer brevity in the headline (≤ 80 chars). Markdown allowed in rationale.
- Never invent BG or IOB values — use what is provided. If a field is null, say so.
- Output MUST be valid JSON matching the provided schema. No prose outside the JSON.`;

export function buildUserPrompt(ctx: PromptContext): string {
  const p = ctx.profile;
  const overrides = p.ai_overrides ?? {};
  const isf = overrides.isf ?? p.isf;

  const histLine = ctx.bg_history.length
    ? ctx.bg_history.slice(-12).map((h) => `${fmtClock(h.ts)}=${h.mgdl}`).join(" ")
    : "(none)";

  const dosesLine = ctx.recent_doses.length
    ? ctx.recent_doses.map((d) => `${fmtClock(d.ts)} ${d.units}U ${d.kind}`).join("; ")
    : "(none in last 6h)";

  return [
    `NOW: ${ctx.now_iso}`,
    `BG: ${ctx.bg_now ?? "null"} mg/dL  trend=${ctx.bg_trend ?? "null"}  delta=${ctx.bg_delta ?? "null"}`,
    `IOB: ${ctx.iob} U`,
    `Profile: ISF=1:${isf}  target=${p.target_bg} mg/dL  basal=${p.basal_u_per_hr}U/hr  DIA=${p.dia_hours}h`,
    `BG history (5-min): ${histLine}`,
    `Recent doses: ${dosesLine}`,
    ``,
    `USER: """${ctx.user_input.trim()}"""`,
    ``,
    `Return JSON with: headline, rationale (markdown showing math), suggested_units, extracted{activity,sleep,notes}, isf_used, notes.`,
  ].join("\n");
}

function fmtClock(ts: number): string {
  const d = new Date(ts);
  return `${d.getHours().toString().padStart(2, "0")}:${d.getMinutes().toString().padStart(2, "0")}`;
}
