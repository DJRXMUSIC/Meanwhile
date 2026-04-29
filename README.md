# Meanwhile — AI-Powered T1D Harness

A lightning-fast PWA decision-support harness for Type 1 Diabetes. The home screen forces a single interaction: **what is happening now?** Voice or type. The AI extracts food, considers BG/IOB/COB, and returns a clear *Next Best Action* with transparent math.

## Architecture

| Concern | Choice | Why |
| --- | --- | --- |
| App shell | Next.js 15 (App Router) + React 19 | SSR-fast first paint, single deploy target |
| UI | Tailwind, system fonts | No webfont round-trip, instant render |
| Local store | IndexedDB via Dexie | Offline-first, fast queries, multi-tab safe |
| AI | Pluggable: Anthropic (Claude) primary, OpenAI fallback | Redundancy across vendors |
| BG source | xDrip+ local web service polled every 60s | Direct, no cloud middleman |
| PWA | Hand-rolled service worker (`/sw.js`) | Cache shell, never cache AI calls |
| Sync | Generic `POST /sync` to user's own endpoint | Privacy: bring-your-own backend |

```
src/
  app/
    layout.tsx              ← global shell
    page.tsx                ← Home: BG/IOB/COB tiles + mic/text + DecisionCard
    profile/page.tsx        ← Ratios, TIR, AI refinement, history
    settings/page.tsx       ← xDrip URL, AI provider, sync, manual BG, import/export
    api/ai/decide/route.ts  ← Server: provider abstraction + JSON tool call
    api/bg/proxy/route.ts   ← Optional xDrip+ CORS proxy
  components/               ← StatTiles, Sparkline, InputBar, DecisionCard, ...
  lib/
    db.ts                   ← Dexie schema + helpers
    types.ts                ← Domain types + DEFAULT_PROFILE
    insulin.ts              ← IOB (bilinear), COB (linear w/ fat/protein extension), suggestDose
    xdrip.ts                ← Local poller + manualBg
    refine.ts               ← Outcome attachment + I:C/ISF nudge from BG@2h
    sync.ts                 ← Bundle build / push / pull / export / import
    speech.ts               ← Web Speech API wrapper
    useLiveData.ts          ← React hooks: live BG/IOB/COB, xDrip polling
    ai/
      prompt.ts             ← System + user prompt builders
      providers.ts          ← Anthropic + OpenAI + auto-fallback
      schema.ts             ← Zod + JSON schema for AI output
```

## Setup

```bash
npm install
cp .env.example .env.local
# fill in ANTHROPIC_API_KEY (and/or OPENAI_API_KEY)
npm run dev
```

Visit http://localhost:3000. The profile is seeded with **I:C 1:10**, **ISF 1:40**, **target 110 mg/dL**.

### xDrip+ setup

1. xDrip+ → Settings → Inter-app settings → enable **Local web service**.
2. Default URL: `http://127.0.0.1:17580`. For cross-device use the phone's LAN IP.
3. In Meanwhile → Settings, paste the URL and tap **Test connection**.
4. Polling runs every 60 s while the app is open.

## Insulin math

`dose = (carbs ÷ I:C) + ((BG − target) ÷ ISF) − IOB`

- **IOB** uses a bilinear curve over DIA (default 4 h, peak at 30% of DIA).
- **COB** is linear over a 180-min window, extended by fat (+0.6 min/g) and protein (+0.4 min/g).
- The full substituted formula is shown in every Decision card.

## Safety

The user has explicitly opted out of hard caps. The AI may flag concerning patterns but will not refuse to give a number. **Verify every dose before delivering.** This software is not a medical device.

## Data

Everything lives in IndexedDB. **Settings → Sync** lets you point at any HTTP endpoint that accepts a `SyncBundle` and returns one — bring-your-own-backend. **Export** dumps the full DB to JSON; **Import** merges it back.
