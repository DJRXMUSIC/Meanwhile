import test from "node:test";
import assert from "node:assert/strict";
import { setNative } from "./env";
import { copyTextNatively, everBridge, reportInsulinNatively, saveTextNatively } from "../src/lib/native";
import { createDictation, speechSupported } from "../src/lib/speech";

function recorder() {
  const files: Record<string, string[]> = {};
  let n = 0;
  return {
    files,
    native: {
      sgv: () => "[]",
      saveBegin: (name: string) => { const id = `id${++n}:${name}`; files[id] = []; return id; },
      saveChunk: (id: string, text: string) => { files[id].push(text); return true; },
      saveEnd: (id: string) => `Downloads/EverBridge/${id.split(":")[1]}`,
    },
  };
}

test("in a normal browser there is no app, and every helper says so", () => {
  setNative(undefined);
  assert.equal(everBridge(), null);
  assert.equal(saveTextNatively("a.json", "{}"), undefined);
  assert.equal(copyTextNatively("x"), false);
  setNative({ notTheApp: true });
  assert.equal(everBridge(), null);
});

test("a backup is handed to the app in pieces and arrives whole", () => {
  const r = recorder();
  setNative(r.native);
  // > 2 pieces, with an emoji sitting exactly on a piece boundary
  const CHUNK = 256 * 1024;
  const text = "a".repeat(CHUNK - 1) + "🍕" + "b".repeat(CHUNK) + "é" + "c".repeat(1000);
  const where = saveTextNatively("meanwhile-2026-11-16.json", text);
  assert.equal(where, "Downloads/EverBridge/meanwhile-2026-11-16.json");
  const chunks = Object.values(r.files)[0];
  assert.ok(chunks.length >= 3, `${chunks.length} pieces`);
  assert.equal(chunks.join(""), text);
  for (const c of chunks) {
    const last = c.charCodeAt(c.length - 1);
    assert.ok(!(last >= 0xd800 && last <= 0xdbff), "a piece ends in half an emoji");
  }
});

test("an empty file and a failed save are reported correctly", () => {
  const r = recorder();
  setNative(r.native);
  assert.equal(saveTextNatively("empty.json", ""), "Downloads/EverBridge/empty.json");

  setNative({ ...r.native, saveChunk: () => false });
  assert.equal(saveTextNatively("a.json", "{}"), null);
  setNative({ ...r.native, saveBegin: () => null });
  assert.equal(saveTextNatively("a.json", "{}"), null);
  setNative({ ...r.native, saveEnd: () => null });
  assert.equal(saveTextNatively("a.json", "{}"), null);
  setNative({ ...r.native, saveChunk: () => { throw new Error("bridge died"); } });
  assert.equal(saveTextNatively("a.json", "{}"), null);
  // an older app without the save functions: the browser way is used instead
  setNative({ sgv: () => "[]" });
  assert.equal(saveTextNatively("a.json", "{}"), undefined);
});

test("copying through the app", () => {
  let copied = "";
  setNative({ sgv: () => "[]", copyText: (t: string) => { copied = t; return true; } });
  assert.equal(copyTextNatively("key-123"), true);
  assert.equal(copied, "key-123");
  setNative({ sgv: () => "[]", copyText: () => { throw new Error("x"); } });
  assert.equal(copyTextNatively("k"), false);
});

test("voice input goes through the phone and comes back to the text box", () => {
  let started = 0;
  setNative({ sgv: () => "[]", startSpeech: () => { started++; } });
  assert.equal(speechSupported(), true);
  const events: string[] = [];
  const d = createDictation({
    onPartial: (t) => events.push(`partial:${t}`),
    onFinal: (t) => events.push(`final:${t}`),
    onError: (m) => events.push(`error:${m}`),
    onEnd: () => events.push("end"),
  });
  assert.ok(d);
  const reply = () => (globalThis as unknown as { __everbridgeSpeech: (t: string | null, e: string | null) => void }).__everbridgeSpeech;

  d.start();
  assert.equal(started, 1);
  reply()("I'm at 180 and eating now", null);
  assert.deepEqual(events, ["final:I'm at 180 and eating now", "end"]);

  events.length = 0;
  d.start();
  reply()(null, null); // cancelled: the mic button must still stop "listening"
  assert.deepEqual(events, ["end"]);

  events.length = 0;
  d.start();
  reply()(null, "Voice input isn't available on this phone");
  assert.deepEqual(events, ["error:Voice input isn't available on this phone", "end"]);
  d.stop(); // harmless
});

test("without the app or a browser speech engine, voice is reported unsupported", () => {
  setNative(undefined);
  assert.equal(speechSupported(), false);
  assert.equal(createDictation({}), null);
  setNative({ sgv: () => "[]" }); // an app too old to have voice
  assert.equal(speechSupported(), false);
});

test("recent doses and the insulin curve are reported to the app for the watch face", () => {
  const sent: string[] = [];
  setNative({ sgv: () => "[]", setInsulin: (json: string) => { sent.push(json); return null; } });
  const now = 1_790_000_000_000;
  const M = 60_000;
  reportInsulinNatively(
    [
      { ts: now - 30 * M, units: 3, kind: "bolus" },
      { ts: now - 60 * M, units: 20, kind: "basal" },
      { ts: now - 9 * 60 * M, units: 5, kind: "bolus" }, // past DIA + delay + an hour: left out
    ],
    5.5, 64, 15, now
  );
  assert.equal(sent.length, 1);
  const r = JSON.parse(sent[0]);
  assert.deepEqual(r, {
    dia: 5.5, peak: 64, delay: 15,
    doses: [
      { ts: now - 30 * M, units: 3, kind: "bolus" },
      { ts: now - 60 * M, units: 20, kind: "basal" },
    ],
  });

  // an older app without setInsulin, a failing one, and no app at all: nothing breaks
  setNative({ sgv: () => "[]" });
  reportInsulinNatively([], 5.5, 64, 15, now);
  setNative({ sgv: () => "[]", setInsulin: () => { throw new Error("bridge died"); } });
  reportInsulinNatively([], 5.5, 64, 15, now);
  setNative(undefined);
  reportInsulinNatively([], 5.5, 64, 15, now);
});
