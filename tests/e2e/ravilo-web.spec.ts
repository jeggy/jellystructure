import { test, expect } from "@playwright/test";

// Regression coverage for Ravilo web actually booting — added 2026-08-17 after a live crash on
// every load: "org_jetbrains_skiko_node_RenderNodeContextKt_RenderNodeContext_1nMake is not a
// function". Root cause: Kotlin/Wasm's generated bootstrap entry awaits the app's own wasm
// instantiation but never waits for skiko's separately-loaded (and larger, so typically slower)
// wasm before calling the app's entry point — a genuine race, not something specific to this
// project's code. Fixed via a webpack loader (ravilo-web/webpack.config.d/await-skiko.js) that
// patches the generated bootstrap to await skiko's own readiness signal first. This test is the
// regression guard for that fix.
//
// Deliberately does NOT poll the Compose canvas's bounding box or take a screenshot — an earlier
// version did, and it hung the whole CI job for over an hour (cancelled manually, no error, no
// timeout ever fired) rather than failing cleanly. Headless Chromium's WebGL/compositor behavior
// on a real CI runner (no GPU, uncertain software-rendering support) is evidently unreliable in
// ways this project's own local Docker testing didn't surface — verified locally there (10
// passed including this test, real headed-Chromium screenshot under xvfb showing a working
// sign-in screen) but NOT safe to trust blindly for CI, where a hang costs a stuck job forever
// rather than a clean failure. A bounded wait + page-error check is the whole test: it catches
// the actual regression (a JS exception during boot) without depending on canvas rendering ever
// actually completing in this environment.
test("Ravilo web boots without a JS crash", async ({ page }) => {
  test.setTimeout(30_000); // hard backstop — this test must never be able to hang the job

  const pageErrors: string[] = [];
  page.on("pageerror", (err) => pageErrors.push(err.message));

  await page.goto(process.env.RAVILO_WEB_URL ?? "http://localhost:8082", {
    waitUntil: "domcontentloaded",
    timeout: 10_000,
  });

  // Fixed, bounded wait for the wasm app to finish booting — not an unbounded poll on anything
  // that touches rendering. Long enough for wasm compilation + skiko's own (larger) wasm to load
  // on a slow CI runner; short enough that a real hang still fails this test in well under the
  // 30s hard timeout above.
  await page.waitForTimeout(15_000);

  expect(pageErrors).toEqual([]);
});
