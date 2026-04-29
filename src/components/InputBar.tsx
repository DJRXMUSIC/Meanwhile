"use client";

import { useEffect, useRef, useState } from "react";
import { createDictation, speechSupported, type DictationHandle } from "@/lib/speech";

export function InputBar({
  onSubmit,
  disabled,
}: {
  onSubmit: (text: string) => void;
  disabled?: boolean;
}) {
  const [text, setText] = useState("");
  const [partial, setPartial] = useState("");
  const [listening, setListening] = useState(false);
  const [supported] = useState<boolean>(() => typeof window !== "undefined" && speechSupported());
  const dictRef = useRef<DictationHandle | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    if (!supported) return;
    dictRef.current = createDictation({
      onPartial: (t) => setPartial(t),
      onFinal: (t) => {
        setText((prev) => (prev ? prev + " " : "") + t.trim());
        setPartial("");
      },
      onError: () => { setPartial(""); setListening(false); },
      onEnd: () => { setPartial(""); setListening(false); },
    });
  }, [supported]);

  useEffect(() => {
    const ta = textareaRef.current;
    if (!ta) return;
    ta.style.height = "auto";
    ta.style.height = Math.min(ta.scrollHeight, 200) + "px";
  }, [text, partial]);

  const toggleMic = () => {
    if (!dictRef.current) return;
    if (listening) {
      dictRef.current.stop();
      setListening(false);
    } else {
      setListening(true);
      dictRef.current.start();
    }
  };

  const submit = () => {
    const t = (text + (partial ? " " + partial : "")).trim();
    if (!t || disabled) return;
    onSubmit(t);
    setText("");
    setPartial("");
    if (listening) dictRef.current?.stop();
  };

  return (
    <div className="px-4 pb-[calc(env(safe-area-inset-bottom)+12px)] pt-2 sticky bottom-0 bg-gradient-to-t from-bg via-bg to-transparent">
      <div className="rounded-3xl bg-surface ring-1 ring-white/10 p-2 flex items-end gap-2">
        <button
          aria-label={listening ? "Stop listening" : "Start dictation"}
          onClick={toggleMic}
          disabled={!supported || disabled}
          className={`shrink-0 size-12 rounded-full grid place-items-center text-xl transition
            ${listening ? "bg-accent text-white mic-listening" : "bg-surface2 text-ink hover:bg-surface2/80"}
            disabled:opacity-30`}
        >
          {listening ? "■" : "🎙"}
        </button>
        <textarea
          ref={textareaRef}
          value={text + (partial ? (text ? " " : "") + partial : "")}
          onChange={(e) => { setText(e.target.value); setPartial(""); }}
          onKeyDown={(e) => {
            if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) { e.preventDefault(); submit(); }
          }}
          placeholder={listening ? "Listening…" : "What's happening now?"}
          rows={1}
          disabled={disabled}
          className="flex-1 resize-none bg-transparent outline-none text-base placeholder:text-muted py-2 px-1 max-h-[200px]"
        />
        <button
          onClick={submit}
          disabled={disabled || !(text + partial).trim()}
          className="shrink-0 h-12 px-4 rounded-2xl bg-accent text-white font-medium disabled:opacity-30 active:scale-[0.98] transition"
        >
          {disabled ? "…" : "Ask"}
        </button>
      </div>
      {!supported && (
        <div className="text-[11px] text-muted text-center mt-2">Voice dictation isn't supported on this browser — type instead.</div>
      )}
    </div>
  );
}
