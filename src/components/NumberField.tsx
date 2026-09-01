"use client";

import { useState } from "react";

// A numeric input you can actually type into.
//
// The obvious approach — a controlled input that parses and writes on every
// keystroke — breaks down as soon as the write is asynchronous or the value
// is validated, because a half-typed number is not the number the user
// means. Typing "90" passes through "9" first; coercing or bounds-checking
// that intermediate state rewrites the field underneath the caret, and any
// re-render before the write lands reverts what was typed.
//
// So: while the field is focused, the raw string is authoritative and
// nothing touches it. On blur or Enter it is parsed once and handed up.
// A blank or unparseable field is treated as an abandoned edit and falls
// back to the last committed value, which is the only case where this
// component overrides what is on screen — it exists so that clearing a box
// and walking away can't wipe the setting.
//
// No bounds. Whatever number is typed is the number that is stored.
export function NumberField({
  label,
  value,
  onCommit,
  step = 1,
}: {
  label: string;
  value: number;
  onCommit: (v: number) => void;
  step?: number;
}) {
  const [draft, setDraft] = useState<string | null>(null);

  const commit = () => {
    if (draft == null) return;
    const trimmed = draft.trim();
    const parsed = Number(trimmed);
    if (trimmed !== "" && Number.isFinite(parsed) && parsed !== value) {
      onCommit(parsed);
    }
    setDraft(null);
  };

  return (
    <label className="block">
      <span className="text-xs text-muted">{label}</span>
      <input
        type="number"
        inputMode="decimal"
        step={step}
        value={draft ?? String(value)}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === "Enter") e.currentTarget.blur();
          if (e.key === "Escape") {
            setDraft(null);
            e.currentTarget.blur();
          }
        }}
        className="num mt-1 w-full rounded-xl bg-surface2 px-3 py-2 outline-none ring-1 ring-white/5 focus:ring-accent/60"
      />
    </label>
  );
}
