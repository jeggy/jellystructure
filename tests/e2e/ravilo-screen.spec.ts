import { test, expect } from "@playwright/test";

// Phase 247 (FR-247-2) — the actual ravilo-screen.js bundle (R264/R269), loaded from its own real HTTP
// origin (never the backend's — that's the whole point: a sideloaded Tizen widget never runs on the
// backend's own origin either), driven through R269's setup screen exactly as a household would. This
// is the test that reproduces the phase's headline finding end to end in the product's own code, not
// just via curl/raw headers (see ravilo-screen-cors.spec.ts for the header-level regression coverage).
//
// `app`'s CORS_ALLOWED_ORIGINS in docker-compose.test.yml deliberately does NOT include ravilo-screen's
// origin — a real household never could add it either, since a sideloaded widget has no conventional
// https://<host> origin an admin would know to type in (see the spec's "What is wrong" section). So the
// setup screen's own health probe is expected to fail here today, and this test asserts on the exact,
// real, misleading symptom that produces: the setup screen never advances, and its hint text becomes
// indistinguishable from "you typed the wrong address" — even though the address is exactly right.

const SCREEN_URL = process.env.RAVILO_SCREEN_URL ?? "http://localhost:8083";
const APP_URL_FROM_SCREEN = process.env.APP_URL_FROM_SCREEN ?? "http://app:9505";

// ReceiverCore.kt's English "setup_not_found" string, verbatim (curly apostrophe included) — the
// headless browser's reported navigator.language is "en-US" in both this stack's Docker image and a
// local run, which Screen.kt's main() strips to "en" with no server yet to override it (R269 FR-R269-9).
const SETUP_NOT_FOUND_EN = "There’s no Ravilo server at that address";

test("a sideloaded screen pointed at the real backend gets stuck on setup (phase 247)", async ({ page }) => {
  test.setTimeout(30_000); // hard backstop — this must never hang the job (see ravilo-web.spec.ts)

  const pageErrors: string[] = [];
  page.on("pageerror", (err) => pageErrors.push(err.message));

  await page.goto(SCREEN_URL, { waitUntil: "domcontentloaded", timeout: 10_000 });

  // No server address is stored yet ⇒ Screen.kt's main() shows #setup (R269's one exception to the
  // no-navigation rule). Note #idle's "on" class is baked into index.html's static markup (a JS-off
  // fallback) and stays present the whole time regardless of app state — show() never touches #setup or
  // clears #idle's default, so #setup's own "on" class (added only by runServerSetup(), never present
  // in the raw HTML) is the one reliable signal of "still on the setup screen" here.
  await expect(page.locator("#setup")).toHaveClass(/\bon\b/);

  const hint = page.locator("#setupHint");

  await page.locator("#setupHost").fill(APP_URL_FROM_SCREEN);
  await page.locator("#setupConnect").click();

  // tryConnect() sets the hint to "setup_trying" immediately, then awaits the /api/health probe.
  // Auto-retrying expect (not a fixed sleep) — resolves as soon as the real network round trip
  // actually finishes, whatever its outcome.
  //
  // The finding: setup never advances (CORS refused the probe before it could ever return true), the
  // hint text is word-for-word indistinguishable from "you typed the wrong address" even though the
  // address is exactly right (runServerSetup()'s catch-all in Screen.kt treats a CORS failure and a
  // genuinely wrong address identically — see the phase's spec), and the address the household typed
  // is kept on screen, not cleared.
  await expect(page.locator("#setup")).toHaveClass(/\bon\b/);
  await expect(hint).toHaveText(SETUP_NOT_FOUND_EN, { timeout: 15_000 });
  await expect(page.locator("#setupHost")).toHaveValue(APP_URL_FROM_SCREEN);

  expect(pageErrors).toEqual([]);
});
