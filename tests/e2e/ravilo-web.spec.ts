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

// R281 → R376 (FR-R376-1) — a touchscreen raises its keyboard only for a real, focused DOM input. R281 put a hidden
// bridge <input> in place of the canvas because CanvasBasedWindow had none; under ComposeViewport a focused Compose text
// field has one of its own (the viewport's backing input, inside #ComposeTarget's shadow root), so the bridge is gone.
// This guards the same precondition: on a coarse-pointer device, a focused text field is backed by a DOM input — on
// the page's initial autofocus and again after Compose's focus moves to another field.
//
// Moves focus from the Login screen's Username field to Password via a synthetic ArrowDown on the viewport's canvas —
// the same path boot.js's gamepad poller uses — rather than a tap at a hardcoded position.
test.describe("mobile on-screen keyboard", () => {
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

    const focusedTag = () => page.evaluate(() => {
      const root = document.getElementById("ComposeTarget")?.shadowRoot;
      return root?.activeElement?.tagName ?? null;
    });

    // The login screen's Username field autofocuses on load.
    expect(["INPUT", "TEXTAREA"]).toContain(await focusedTag());

    await page.evaluate(() => {
      const canvas = document.getElementById("ComposeTarget")?.shadowRoot?.querySelector("canvas");
      ["keydown", "keyup"].forEach((t) => canvas?.dispatchEvent(new KeyboardEvent(t, { key: "ArrowDown", bubbles: true })));
    });
    await page.waitForTimeout(1_000);

    expect(["INPUT", "TEXTAREA"]).toContain(await focusedTag());
  });
});

// R376 (FR-R376-1/-5) — the viewport's canvas lives in #ComposeTarget's shadow root, above the player's <video> layer
// (z-index 1 over 0), and the containers this browser opens were probed at boot (mp4 always).
test("ComposeViewport hosts the canvas and the containers are probed", async ({ page }) => {
  test.setTimeout(30_000);
  await page.goto(process.env.RAVILO_WEB_URL ?? "http://localhost:8082", { waitUntil: "domcontentloaded", timeout: 10_000 });
  await page.waitForTimeout(15_000);
  const r = await page.evaluate(() => {
    const host = document.getElementById("ComposeTarget");
    return {
      canvas: !!host?.shadowRoot?.querySelector("canvas"),
      z: host ? getComputedStyle(host).zIndex : null,
      containers: (window as any).__raviloContainers as string | undefined,
    };
  });
  expect(r.canvas).toBe(true);
  expect(r.z).toBe("1");
  expect(r.containers?.split(",")[0]).toBe("mp4");
});
