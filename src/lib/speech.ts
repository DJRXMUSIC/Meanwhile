"use client";

// Voice input. Inside the EverBridge app this is the phone's own voice dialog
// (an Android WebView has no Web Speech API); in a browser it is a minimal
// wrapper around the Web Speech API. Falls back gracefully when neither exists.

import { everBridge, type EverBridgeNative } from "./native";

type SpeechRecognitionLike = {
  lang: string;
  interimResults: boolean;
  continuous: boolean;
  start: () => void;
  stop: () => void;
  abort: () => void;
  onresult: ((e: SpeechRecognitionEventLike) => void) | null;
  onerror: ((e: { error?: string }) => void) | null;
  onend: (() => void) | null;
};

interface SpeechRecognitionEventLike {
  results: { length: number;[i: number]: { isFinal: boolean; length: number;[j: number]: { transcript: string } } };
}

interface RecognitionCtor {
  new (): SpeechRecognitionLike;
}

function getCtor(): RecognitionCtor | null {
  if (typeof window === "undefined") return null;
  const w = window as unknown as { SpeechRecognition?: RecognitionCtor; webkitSpeechRecognition?: RecognitionCtor };
  return w.SpeechRecognition || w.webkitSpeechRecognition || null;
}

function nativeSpeech(): EverBridgeNative | null {
  const n = everBridge();
  return n && typeof n.startSpeech === "function" ? n : null;
}

export function speechSupported(): boolean {
  return !!nativeSpeech() || !!getCtor();
}

export interface DictationHandle {
  start: () => void;
  stop: () => void;
}

export function createDictation(handlers: {
  onPartial?: (text: string) => void;
  onFinal?: (text: string) => void;
  onError?: (msg: string) => void;
  onEnd?: () => void;
}): DictationHandle | null {
  const native = nativeSpeech();
  if (native) {
    // One result per start: what was heard, an error, or neither (cancelled).
    // The phone's dialog shows the words as they come, so there are no partials.
    return {
      start: () => {
        (window as unknown as { __everbridgeSpeech?: (text: string | null, error: string | null) => void })
          .__everbridgeSpeech = (text, error) => {
            if (error) handlers.onError?.(error);
            else if (text) handlers.onFinal?.(text);
            handlers.onEnd?.();
          };
        try {
          native.startSpeech!();
        } catch {
          handlers.onError?.("speech error");
          handlers.onEnd?.();
        }
      },
      stop: () => { /* the phone's dialog closes itself */ },
    };
  }

  const Ctor = getCtor();
  if (!Ctor) return null;
  const rec = new Ctor();
  rec.lang = navigator.language || "en-US";
  rec.interimResults = true;
  rec.continuous = false;

  rec.onresult = (e) => {
    let interim = "";
    let final = "";
    for (let i = 0; i < e.results.length; i++) {
      const r = e.results[i];
      const txt = r[0]?.transcript ?? "";
      if (r.isFinal) final += txt; else interim += txt;
    }
    if (interim) handlers.onPartial?.(interim);
    if (final) handlers.onFinal?.(final);
  };
  rec.onerror = (e) => handlers.onError?.(e.error ?? "speech error");
  rec.onend = () => handlers.onEnd?.();

  return {
    start: () => { try { rec.start(); } catch { /* already started */ } },
    stop: () => { try { rec.stop(); } catch { /* not started */ } },
  };
}
