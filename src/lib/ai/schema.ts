import { z } from "zod";

export const ExtractedSchema = z.object({
  carbs_g: z.number().nullable().optional(),
  fat_g: z.number().nullable().optional(),
  protein_g: z.number().nullable().optional(),
  activity: z.string().nullable().optional(),
  sleep: z.string().nullable().optional(),
  notes: z.string().nullable().optional(),
});

export const AIDecisionSchema = z.object({
  headline: z.string().min(1),
  rationale: z.string().min(1),
  suggested_units: z.number().nullable().optional(),
  suggested_carbs_g: z.number().nullable().optional(),
  extracted: ExtractedSchema.optional().default({}),
  ic_ratio_used: z.number().nullable().optional(),
  isf_used: z.number().nullable().optional(),
  notes: z.string().nullable().optional(),
});

export type AIDecision = z.infer<typeof AIDecisionSchema>;
export type Extracted = z.infer<typeof ExtractedSchema>;

export const DECISION_JSON_SCHEMA = {
  type: "object",
  required: ["headline", "rationale"],
  properties: {
    headline: { type: "string", description: "One-sentence next best action." },
    rationale: { type: "string", description: "Markdown explanation showing logic and math used." },
    suggested_units: { type: ["number", "null"], description: "Suggested insulin dose in units; null if none." },
    suggested_carbs_g: { type: ["number", "null"], description: "Carbs to consume now in grams; null if none." },
    extracted: {
      type: "object",
      properties: {
        carbs_g: { type: ["number", "null"] },
        fat_g: { type: ["number", "null"] },
        protein_g: { type: ["number", "null"] },
        activity: { type: ["string", "null"] },
        sleep: { type: ["string", "null"] },
        notes: { type: ["string", "null"] },
      },
    },
    ic_ratio_used: { type: ["number", "null"] },
    isf_used: { type: ["number", "null"] },
    notes: { type: ["string", "null"] },
  },
} as const;
