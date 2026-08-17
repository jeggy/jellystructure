import { test, expect } from "@playwright/test";

// Regression coverage for Ravilo web actually booting — added 2026-08-17 after a live crash on
// every load: "org_jetbrains_skiko_node_RenderNodeContextKt_RenderNodeContext_1nMake is not a
// function". Root cause: Kotlin/Wasm's generated bootstrap entry awaits the app's own wasm
// instantiation but never waits for skiko's separately-loaded (and larger, so typically slower)
// wasm before calling the app's entry point — a genuine race, not something specific to this
// project's code. Fixed via a webpack loader (ravilo-web/webpack.config.d/await-skiko.js) that
// patches the generated bootstrap to await skiko's own readiness signal first. This test is the
// regression guard for that fix, not a full app walkthrough — Ravilo web has no Jellyfin/TMDB
// config in this stack (see docker-compose.test.yml's ravilo-web service), so it only ever reaches
// its own login screen; that's enough to prove the wasm boots and Compose actually renders.
const RAVILO_WEB_URL = process.env.RAVILO_WEB_URL ?? "http://localhost:8082";

test("Ravilo web boots without crashing and renders its sign-in screen", async ({ page }) => {
  const pageErrors: string[] = [];
  page.on("pageerror", (err) => pageErrors.push(err.message));

  await page.goto(RAVILO_WEB_URL, { waitUntil: "domcontentloaded" });

  // The Compose canvas starts at the browser's default 300x150 until the wasm app actually boots
  // and resizes it to fill the viewport -- a real, cheap signal that Compose is up and rendering,
  // not just that the HTML shell loaded. Generous timeout: first-load wasm compilation + skiko's
  // own (larger) wasm both have to finish before this happens.
  await expect
    .poll(
      async () => {
        const box = await page.locator("#ComposeTarget").boundingBox();
        return box ? Math.round(box.width) : 0;
      },
      { timeout: 20_000, message: "Compose canvas never resized off its 300x150 default" },
    )
    .toBeGreaterThan(300);

  // The actual regression: a real page-level JS exception (uncaught error / rejected promise)
  // anywhere during boot. Checked after the poll above so a genuine crash (which stops the canvas
  // from ever resizing) surfaces as this specific, readable assertion instead of just the poll's
  // generic timeout message.
  expect(pageErrors).toEqual([]);

  // No DOM-text assertion here on purpose -- this is a Compose Canvas app (WebGL/Skia painting
  // pixels onto #ComposeTarget), not DOM-based UI. "Sign in" is real, visibly rendered content
  // (confirmed via a manual screenshot while writing this test) but it is pixels, not accessible
  // DOM text, so getByText()/getByRole() can never find it -- confirmed live: it fails with
  // "element(s) not found" even while the exact text is plainly visible on screen. The
  // canvas-resize poll above is the correct, meaningful signal for this rendering technology: it
  // only passes once Compose has actually laid out and started painting a real frame.
});
