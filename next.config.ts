import type { NextConfig } from "next";

// A value that changes on every deploy. The service worker is registered as
// /sw.js?v=<buildId>, which does two things: the changed script URL makes
// the browser treat it as a new worker, and the worker names its caches
// after the same value so the previous build's caches are dropped on
// activate. Prefers the host's commit ref when there is one so repeat
// builds of the same commit stay stable.
const buildId =
  process.env.NEXT_PUBLIC_BUILD_ID ||
  process.env.COMMIT_REF ||               // Netlify
  process.env.VERCEL_GIT_COMMIT_SHA ||
  Date.now().toString(36);

// EVERBRIDGE_APP=1 (npm run build:app, scripts/build-app-web.mjs) builds the
// pages that go inside the EverBridge Android app: plain files in out/, no
// server. The API routes are set aside for that build; the app calls them on
// the Meanwhile server instead (see lib/native.ts apiUrl).
const app = process.env.EVERBRIDGE_APP === "1";

const nextConfig: NextConfig = {
  reactStrictMode: true,
  env: { NEXT_PUBLIC_BUILD_ID: buildId, NEXT_PUBLIC_EVERBRIDGE_APP: app ? "1" : "" },
  experimental: {
    optimizePackageImports: ["dexie", "dexie-react-hooks"],
  },
  ...(app ? { output: "export" as const, trailingSlash: true } : {}),
  ...(app ? {} : { headers }),
};

async function headers() {
  return [
    {
      source: "/sw.js",
      headers: [
        { key: "Content-Type", value: "application/javascript; charset=utf-8" },
        { key: "Cache-Control", value: "no-cache, no-store, must-revalidate" },
        { key: "Service-Worker-Allowed", value: "/" },
      ],
    },
  ];
}

export default nextConfig;
