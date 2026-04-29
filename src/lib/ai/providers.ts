import Anthropic from "@anthropic-ai/sdk";
import { AIDecisionSchema, DECISION_JSON_SCHEMA, type AIDecision } from "./schema";
import { SYSTEM_PROMPT } from "./prompt";

export type ProviderName = "anthropic" | "openai" | "google";

export interface ProviderResult {
  decision: AIDecision;
  provider: ProviderName;
  model: string;
}

export interface ProviderOptions {
  systemPrompt: string;
  userPrompt: string;
  signal?: AbortSignal;
}

interface Provider {
  name: ProviderName;
  available(): boolean;
  call(opts: ProviderOptions): Promise<ProviderResult>;
}

class AnthropicProvider implements Provider {
  name: ProviderName = "anthropic";
  private client: Anthropic | null = null;
  private model = process.env.ANTHROPIC_MODEL || "claude-opus-4-7";
  private getClient() {
    if (!this.client) {
      const key = process.env.ANTHROPIC_API_KEY;
      if (!key) throw new Error("ANTHROPIC_API_KEY not set");
      this.client = new Anthropic({ apiKey: key });
    }
    return this.client;
  }
  available() { return !!process.env.ANTHROPIC_API_KEY; }

  async call({ systemPrompt, userPrompt, signal }: ProviderOptions): Promise<ProviderResult> {
    const client = this.getClient();
    const res = await client.messages.create(
      {
        model: this.model,
        max_tokens: 1500,
        system: [
          {
            type: "text",
            text: systemPrompt,
            cache_control: { type: "ephemeral" },
          },
        ],
        messages: [{ role: "user", content: userPrompt }],
        tools: [
          {
            name: "emit_decision",
            description: "Emit the final decision as structured JSON.",
            input_schema: DECISION_JSON_SCHEMA as unknown as Anthropic.Tool.InputSchema,
          },
        ],
        tool_choice: { type: "tool", name: "emit_decision" },
      },
      { signal }
    );

    const toolUse = res.content.find((b): b is Anthropic.ToolUseBlock => b.type === "tool_use");
    if (!toolUse) throw new Error("Anthropic returned no tool_use block");
    const decision = AIDecisionSchema.parse(toolUse.input);
    return { decision, provider: this.name, model: this.model };
  }
}

class OpenAIProvider implements Provider {
  name: ProviderName = "openai";
  private model = process.env.OPENAI_MODEL || "gpt-4o";
  available() { return !!process.env.OPENAI_API_KEY; }

  async call({ systemPrompt, userPrompt, signal }: ProviderOptions): Promise<ProviderResult> {
    const key = process.env.OPENAI_API_KEY;
    if (!key) throw new Error("OPENAI_API_KEY not set");
    const res = await fetch("https://api.openai.com/v1/chat/completions", {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${key}` },
      signal,
      body: JSON.stringify({
        model: this.model,
        temperature: 0.2,
        response_format: { type: "json_object" },
        messages: [
          { role: "system", content: systemPrompt + "\n\nReturn ONLY a JSON object matching the schema described in the user message." },
          { role: "user", content: userPrompt },
        ],
      }),
    });
    if (!res.ok) {
      const t = await res.text().catch(() => "");
      throw new Error(`OpenAI ${res.status}: ${t.slice(0, 300)}`);
    }
    const json = await res.json() as { choices: { message: { content: string } }[] };
    const raw = json.choices?.[0]?.message?.content ?? "{}";
    const parsed = JSON.parse(raw);
    const decision = AIDecisionSchema.parse(parsed);
    return { decision, provider: this.name, model: this.model };
  }
}

// Convert our JSON-schema constant to a Gemini-flavored schema (Gemini does
// not accept union types like ["number","null"] — it uses "nullable: true").
function toGeminiSchema(s: unknown): unknown {
  if (Array.isArray(s)) return s.map(toGeminiSchema);
  if (s && typeof s === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(s as Record<string, unknown>)) {
      if (k === "type" && Array.isArray(v)) {
        const types = v as string[];
        const nonNull = types.filter((t) => t !== "null");
        out.type = nonNull[0] ?? "string";
        if (types.includes("null")) out.nullable = true;
      } else {
        out[k] = toGeminiSchema(v);
      }
    }
    return out;
  }
  return s;
}

class GoogleProvider implements Provider {
  name: ProviderName = "google";
  private model = process.env.GEMINI_MODEL || "gemini-2.5-flash-lite";
  private thinkingBudget = Number(process.env.GEMINI_THINKING_BUDGET ?? -1); // -1 = dynamic max
  available() { return !!(process.env.GEMINI_API_KEY || process.env.GOOGLE_API_KEY); }

  async call({ systemPrompt, userPrompt, signal }: ProviderOptions): Promise<ProviderResult> {
    const key = process.env.GEMINI_API_KEY || process.env.GOOGLE_API_KEY;
    if (!key) throw new Error("GEMINI_API_KEY (or GOOGLE_API_KEY) not set");
    const url = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(this.model)}:generateContent`;
    const body = {
      systemInstruction: { parts: [{ text: systemPrompt }] },
      contents: [{ role: "user", parts: [{ text: userPrompt }] }],
      generationConfig: {
        temperature: 0.2,
        responseMimeType: "application/json",
        responseSchema: toGeminiSchema(DECISION_JSON_SCHEMA),
        thinkingConfig: { thinkingBudget: this.thinkingBudget },
      },
    };
    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": key },
      signal,
      body: JSON.stringify(body),
    });
    if (!res.ok) {
      const t = await res.text().catch(() => "");
      throw new Error(`Google ${res.status}: ${t.slice(0, 300)}`);
    }
    const json = await res.json() as {
      candidates?: { content?: { parts?: { text?: string }[] } }[];
    };
    const text = json.candidates?.[0]?.content?.parts?.map((p) => p.text ?? "").join("") ?? "";
    if (!text) throw new Error("Google returned empty response");
    const parsed = JSON.parse(text);
    const decision = AIDecisionSchema.parse(parsed);
    return { decision, provider: this.name, model: this.model };
  }
}

const REGISTRY: Record<ProviderName, Provider> = {
  anthropic: new AnthropicProvider(),
  openai: new OpenAIProvider(),
  google: new GoogleProvider(),
};

export function resolveOrder(): ProviderName[] {
  const primary = (process.env.AI_PRIMARY_PROVIDER as ProviderName) || "anthropic";
  const fallback = (process.env.AI_FALLBACK_PROVIDER as ProviderName) || "openai";
  const order: ProviderName[] = [];
  for (const n of [primary, fallback, "anthropic", "openai", "google"] as ProviderName[]) {
    if (!order.includes(n) && REGISTRY[n]?.available()) order.push(n);
  }
  return order;
}

export async function runDecision(
  userPrompt: string,
  opts: { signal?: AbortSignal; preferred?: ProviderName } = {}
): Promise<ProviderResult> {
  const order = opts.preferred && REGISTRY[opts.preferred]?.available()
    ? [opts.preferred, ...resolveOrder().filter((n) => n !== opts.preferred)]
    : resolveOrder();

  if (order.length === 0) {
    throw new Error("No AI provider configured. Set ANTHROPIC_API_KEY or OPENAI_API_KEY.");
  }

  let lastErr: unknown = null;
  for (const name of order) {
    try {
      return await REGISTRY[name].call({
        systemPrompt: SYSTEM_PROMPT,
        userPrompt,
        signal: opts.signal,
      });
    } catch (e) {
      lastErr = e;
    }
  }
  throw lastErr instanceof Error ? lastErr : new Error("All AI providers failed");
}
