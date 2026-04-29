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
  ts: number;
  units: number;
  kind: "bolus" | "correction" | "basal";
  note?: string;
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

export interface Profile {
  id: "current";
  ic_ratio: number;       // grams of carb covered by 1U
  isf: number;            // mg/dL drop per 1U
  basal_u_per_hr: number;
  dia_hours: number;      // duration of insulin action
  target_bg: number;
  ai_overrides?: {
    ic_ratio?: number;
    isf?: number;
    notes?: string;
    updated_ts?: number;
  };
  units: "mgdl";
  xdrip_url?: string;     // local xDrip+ web service URL
  ai_provider?: "anthropic" | "openai" | "auto";
  updated_ts: number;
}

export const DEFAULT_PROFILE: Profile = {
  id: "current",
  ic_ratio: 10,
  isf: 40,
  basal_u_per_hr: 0.7,
  dia_hours: 4,
  target_bg: 110,
  units: "mgdl",
  xdrip_url: "http://127.0.0.1:17580",
  ai_provider: "auto",
  updated_ts: Date.now(),
};
