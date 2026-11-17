// Builds Meanwhile's pages for the EverBridge Android app (npm run build:app)
// and puts them where the app picks them up: android/app/src/main/assets/web.
//
// The app has no server, so this is a static export, and the API routes
// (src/app/api) can't be part of it: they are moved aside for the build and
// always moved back, even if the build fails or is interrupted. A leftover
// from a run that was killed outright is restored at the start of the next.
import { execSync } from "node:child_process";
import { cpSync, existsSync, renameSync, rmSync, writeFileSync } from "node:fs";

const API = "src/app/api";
const ASIDE = "src/.api-aside-during-app-build";
const OUT = "out";
const DEST = "android/app/src/main/assets/web";

function restore() {
  if (existsSync(ASIDE)) {
    if (existsSync(API)) throw new Error(`both ${API} and ${ASIDE} exist; resolve by hand`);
    renameSync(ASIDE, API);
  }
}

restore();
for (const sig of ["SIGINT", "SIGTERM"]) process.on(sig, () => { restore(); process.exit(1); });

renameSync(API, ASIDE);
try {
  rmSync(OUT, { recursive: true, force: true });
  execSync("next build", { stdio: "inherit", env: { ...process.env, EVERBRIDGE_APP: "1" } });
} finally {
  restore();
}

for (const f of ["index.html", "settings/index.html", "profile/index.html", "404.html"]) {
  if (!existsSync(`${OUT}/${f}`)) throw new Error(`${OUT}/${f} is missing: the export didn't produce Meanwhile's pages`);
}
// replace what was there, so nothing from an older build is left behind
rmSync(DEST, { recursive: true, force: true });
cpSync(OUT, DEST, { recursive: true });
rmSync(`${DEST}/sw.js`, { force: true }); // the website's offline cache; the app has its pages already
writeFileSync(`${DEST}/BUILD`, `${process.env.GITHUB_SHA || "local"}\n`);
console.log(`Meanwhile pages built into ${DEST}`);
