"use client";

export function ModeToggle({
  mode,
  onChange,
}: {
  mode: "decide" | "learn";
  onChange: (m: "decide" | "learn") => void;
}) {
  const Btn = ({ id, label, hint }: { id: "decide" | "learn"; label: string; hint: string }) => {
    const active = mode === id;
    return (
      <button
        onClick={() => onChange(id)}
        className={`flex-1 rounded-xl px-3 py-2 text-left transition ${
          active ? "bg-accent/15 ring-1 ring-accent/50" : "bg-surface2/60 hover:bg-surface2"
        }`}
      >
        <div className={`text-sm font-medium ${active ? "text-ink" : "text-ink/80"}`}>{label}</div>
        <div className="text-[11px] text-muted">{hint}</div>
      </button>
    );
  };
  return (
    <div className="mx-4 mt-3 flex gap-2">
      <Btn id="decide" label="Decide" hint="Ask AI for next best action" />
      <Btn id="learn" label="Learn" hint="Log doses, no AI" />
    </div>
  );
}
