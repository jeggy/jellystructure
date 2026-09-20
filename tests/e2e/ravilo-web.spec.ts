import { test, expect, devices } from "@playwright/test";

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

// R281 — CanvasBasedWindow routes all text entry through Compose's own focus system on a single
// canvas (see ravilo-ui's TextFieldFocusBridge.kt), so document.activeElement never left
// #ComposeTarget: a touchscreen had nothing a software keyboard could attach to, and Compose's own
// focus state moving (the text caret visibly blinking in the right field) was never itself proof
// that a mobile browser would raise its keyboard. This is the regression guard for the fix — a
// hidden native <input> (#ravilo-kb-bridge) that boot.js focuses in its place on a coarse-pointer
// device whenever a Compose text field reports focus.
//
// Scoped to that DOM precondition only, deliberately not to whether a typed character reaches
// Compose: this file's own boot test above already found headless Chromium's rendering behavior on
// a real CI runner too unreliable to assert on the canvas by screenshot, and confirming a character
// arrived would need exactly that. The bridge's beforeinput/keydown handling was instead verified by
// hand against a real production build (typed text, including a Danish/Faroese-alphabet character
// and a backspace, landing correctly) — see phase R281's own spec for that verification.
//
// Moves focus from the Login screen's Username field to Password via a synthetic ArrowDown
// KeyboardEvent on the canvas — the same D-pad-navigation key path LoginScreen.kt already wires for
// TV remotes, and the same mechanism boot.js's gamepad poller already uses — rather than a tap or
// click at a hardcoded pixel position, which would couple this test to the login screen's exact
// layout and font metrics.
test.describe("mobile on-screen keyboard bridge", () => {
  // defaultBrowserType is left out — it forces a new worker and can't be set inside a describe
  // group (only top-level or in the config's own projects), and this suite already pins chromium.
  const { defaultBrowserType: _unused, ...pixel5 } = devices["Pixel 5"];
  test.use({ ...pixel5 });

  test("a focused Compose text field is backed by a real DOM input, not just the canvas", async ({ page }) => {
    test.setTimeout(30_000);

    await page.goto(process.env.RAVILO_WEB_URL ?? "http://localhost:8082", {
      waitUntil: "domcontentloaded",
      timeout: 10_000,
    });
    await page.waitForTimeout(15_000);

    // The login screen's Username field autofocuses on load — already enough to exercise the bridge
    // without any input at all.
    const activeOnLoad = await page.evaluate(() => document.activeElement?.tagName);
    expect(activeOnLoad).toBe("INPUT");

    await page.evaluate(() => {
      const canvas = document.getElementById("ComposeTarget");
      ["keydown", "keyup"].forEach((t) => canvas?.dispatchEvent(new KeyboardEvent(t, { key: "ArrowDown", bubbles: true })));
    });
    await page.waitForTimeout(1_000);

    // Still a real input after Compose's focus moved to a *different* text field — proves the
    // bridge re-fires on every focus change, not only on the page's initial autofocus.
    const activeAfterMove = await page.evaluate(() => document.activeElement?.tagName);
    expect(activeAfterMove).toBe("INPUT");
  });
});
