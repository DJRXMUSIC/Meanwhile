export type Trend =
  | "DoubleUp" | "SingleUp" | "FortyFiveUp"
  | "Flat"
  | "FortyFiveDown" | "SingleDown" | "DoubleDown"
  | "NotComputable" | "RateOutOfRange" | "None";

export interface BgReading {
  id?: number;
  ts: number;          // ms since epoch
  mgdl: number;
  trend: Trend;
  delta?: number;      // mg/dL since previous
  source: "xdrip" | "manual" | "import";
  raw?: unknown;
}

export interface InsulinDose {
  id?: number;
  ts: number;            // administration time (may be backdated)
  units: number;
  kind: "bolus" | "correction" | "basal";
  note?: string;
  // analytics fields — non-breaking, optional on legacy rows.
  entered_at?: number;   // wall-clock time the user actually tapped log
  source?: "quick" | "custom" | "ai" | "manual" | "import";
  backdated_min?: number; // entered_at - ts in minutes (0 if live)
}

export interface CarbEntry {
  id?: number;
  ts: number;
  carbs_g: number;
  fat_g?: number;
  protein_g?: number;
  description?: string;
  absorption_min?: number; // default 180
}

export interface Decision {
  id?: number;
  ts: number;
  user_input: string;
  bg_at_time?: number;
  iob_at_time?: number;
  cob_at_time?: number;
  headline: string;
  rationale: string;          // markdown
  suggested_units?: number;
  suggested_carbs_g?: number;
  extracted?: {
    carbs_g?: number;
    fat_g?: number;
    protein_g?: number;
    activity?: string;
    sleep?: string;
    notes?: string;
  };
  math?: Record<string, number | string>;
  provider: string;
  model: string;
  outcome_bg_60?: number;
  outcome_bg_120?: number;
  outcome_bg_180?: number;
}

export interface ContextEntry {
  id?: number;
  ts: number;
  kind: "sleep" | "exercise" | "stress" | "illness" | "menstrual" | "alcohol" | "note";
  value: string;
}

export type ThemeName = "default" | "midnight" | "forest" | "sunset" | "mono" | "solar";

export const THEMES: { id: ThemeName; label: string; swatch: string }[] = [
  { id: "default",  label: "Aurora",   swatch: "#7c5cff" },
  { id: "midnight", label: "Midnight", swatch: "#5cd0ff" },
  { id: "forest",   label: "Forest",   swatch: "#84dc74" },
  { id: "sunset",   label: "Sunset",   swatch: "#ffa854" },
  { id: "mono",     label: "Mono",     swatch: "#f0f0f0" },
  { id: "solar",    label: "Solar",    swatch: "#dcc846" },
];

export interface Profile {
  id: "current";
  ic_ratio: number;       // grams of carb covered by 1U
  isf: number;            // mg/dL drop per 1U
  basal_u_per_hr: number;
  dia_hours: number;      // duration of insulin action
  peak_min?: number;      // time-to-peak in minutes (Loop default 75)
  delay_min?: number;     // pre-action lag where IOB stays at 100% (default 15)
  target_bg: number;
  tir_low?: number;       // time-in-range lower bound (default 70)
  tir_high?: number;      // time-in-range upper bound (default 160)
  ai_overrides?: {
    ic_ratio?: number;
    isf?: number;
    notes?: string;
    updated_ts?: number;
  };
  units: "mgdl";
  xdrip_url?: string;     // local xDrip+ web service URL
  ai_provider?: "anthropic" | "openai" | "google" | "auto";
  mode?: "decide" | "learn"; // persistent operating mode
  theme?: ThemeName;
  // Once-daily long-acting basal reminder
  daily_basal_enabled?: boolean;
  daily_basal_units?: number;     // default 20
  daily_basal_time?: string;      // "HH:MM" — interpreted in `daily_basal_tz`
  daily_basal_tz?: string;        // IANA name; default "America/New_York"
  schema_version?: number;   // for one-shot migrations
  updated_ts: number;
}

export const DEFAULT_PROFILE: Profile = {
  id: "current",
  ic_ratio: 10,
  isf: 40,
  basal_u_per_hr: 0.7,
  dia_hours: 6,            // Loop default for rapid-acting analogs
  peak_min: 75,
  delay_min: 15,
  target_bg: 110,
  tir_low: 70,
  tir_high: 160,
  units: "mgdl",
  xdrip_url: "http://127.0.0.1:17580",
  ai_provider: "auto",
  mode: "decide",
  theme: "default",
  daily_basal_enabled: true,
  daily_basal_units: 20,
  daily_basal_time: "18:30",
  daily_basal_tz: "America/New_York",
  schema_version: 3,
  updated_ts: Date.now(),
};
