// The EverBridge Android app shows Meanwhile in its own window and offers the
// page a few things a browser can't: the readings straight from the bridge's
// store, saving a backup file, the phone's voice input, the clipboard, and
// the bridge's status screen. In a normal browser none of this exists and
// every helper here says so, so callers fall back to the web way.
//
// The page and the app update separately, so each function is checked for
// before use: an older app may not have a newer one.

export interface EverBridgeNative {
  /** Newest `count` readings as the JSON text /sgv.json returns; null on failure. */
  sgv(count: number): string | null;
  saveBegin?(name: string): string | null;
  saveChunk?(id: string, text: string): boolean;
  /** Where the file was saved (for display), or null on failure. */
  saveEnd?(id: string): string | null;
  copyText?(text: string): boolean;
  /** The result arrives as window.__everbridgeSpeech(text, error). */
  startSpeech?(): void;
  openStatus?(): void;
}

export function everBridge(): EverBridgeNative | null {
  if (typeof window === "undefined") return null;
  const n = (window as unknown as { EverBridgeNative?: EverBridgeNative }).EverBridgeNative;
  return n && typeof n.sgv === "function" ? n : null;
}

// Text crosses to the app in pieces: a backup can be tens of megabytes, and one
// giant string would need several copies of itself in memory on the way.
const SAVE_CHUNK = 256 * 1024;

// Saves a text file through the app. Returns where it was saved; null if the
// save failed; undefined if there is no app to save through.
export function saveTextNatively(name: string, text: string): string | null | undefined {
  const n = everBridge();
  if (!n || typeof n.saveBegin !== "function" || typeof n.saveChunk !== "function" || typeof n.saveEnd !== "function") {
    return undefined;
  }
  try {
    const id = n.saveBegin(name);
    if (!id) return null;
    for (let i = 0; i < text.length; ) {
      let end = Math.min(text.length, i + SAVE_CHUNK);
      // never cut a character that takes two UTF-16 units (emoji) in half
      const last = text.charCodeAt(end - 1);
      if (end < text.length && last >= 0xd800 && last <= 0xdbff) end++;
      if (!n.saveChunk(id, text.slice(i, end))) return null;
      i = end;
    }
    return n.saveEnd(id) || null;
  } catch {
    return null;
  }
}

export function copyTextNatively(text: string): boolean {
  const n = everBridge();
  try {
    return !!n && typeof n.copyText === "function" && n.copyText(text) === true;
  } catch {
    return false;
  }
}
