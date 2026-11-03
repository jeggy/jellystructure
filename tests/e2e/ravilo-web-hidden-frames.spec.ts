import { test, expect, type Page } from "@playwright/test";

// R376 (FR-R376-4i, amendment 2026-10-30, DEB-2998) — a hidden tab keeps its film, so whatever the player does by itself
// (the next-up countdown, the start of the next episode) has to go on too, and those need Compose to make frames.
// Compose's frames come from requestAnimationFrame, which a browser does not run for a hidden page. boot.js therefore
// feeds the page's rAF callbacks from a ~1 Hz timer while a player is up AND the page is hidden, and at no other time.
//
// Headless Chromium never reports a page as hidden, so this stands in for the browser's half: it replaces the NATIVE
// requestAnimationFrame with one that never fires (what a hidden page's is) before boot.js binds it, and drives
// document.visibilityState itself. The app bundle is blocked — only boot.js is under test, and the shim is the one
// thing in it that can be proved without a film. The real thing in a hidden tab is the lab's and QA's.
const WEB_URL = process.env.RAVILO_WEB_URL ?? "http://localhost:8082";

async function bootOnly(page: Page) {
  await page.route("**/ravilo.js", (route) => route.abort());
  await page.addInitScript(() => {
    const w = window as any;
    w.__native = new Map<number, FrameRequestCallback>();
    let n = 0;
    w.requestAnimationFrame = (cb: FrameRequestCallback) => { w.__native.set(++n, cb); return n; };
    w.cancelAnimationFrame = (id: number) => { w.__native.delete(id); };
    w.__hidden = false;
    Object.defineProperty(document, "visibilityState", { configurable: true, get: () => (w.__hidden ? "hidden" : "visible") });
    w.__setHidden = (h: boolean) => { w.__hidden = h; document.dispatchEvent(new Event("visibilitychange")); };
    // a frame loop that counts itself, the way Compose's clock asks for one frame at a time
    w.__frames = 0;
    w.__loop = () => { w.requestAnimationFrame(() => { w.__frames++; w.__loop(); }); };
  });
  await page.goto(WEB_URL, { waitUntil: "domcontentloaded", timeout: 10_000 });
  await expect.poll(() => page.evaluate(() => typeof (window as any).raviloHiddenFrames), { timeout: 10_000 }).toBe("object");
}

const frames = (page: Page) => page.evaluate(() => (window as any).__frames as number);

test.describe("Ravilo web feeds a hidden page's frames (FR-R376-4i)", () => {
  test("hidden with a player up: the frame loop goes on at about 1 Hz, not at full rate", async ({ page }) => {
    test.setTimeout(30_000);
    await bootOnly(page);
    await page.evaluate(() => { (window as any).raviloHiddenFrames.player(1); (window as any).__loop(); });
    expect(await frames(page), "visible: the native clock is what runs, and here it never fires").toBe(0);
    await page.evaluate(() => (window as any).__setHidden(true));
    await page.waitForTimeout(4_500);
    const n = await frames(page);
    expect(n, "frames came from the timer").toBeGreaterThanOrEqual(3);
    expect(n, "and about once a second, never at full rate").toBeLessThanOrEqual(6);
  });

  test("a frame asked for while visible is not lost when the page then hides", async ({ page }) => {
    test.setTimeout(30_000);
    await bootOnly(page);
    await page.evaluate(() => { (window as any).raviloHiddenFrames.player(1); (window as any).__loop(); });
    // the request is now with the (never-firing) native clock; hiding must take it back
    await page.evaluate(() => (window as any).__setHidden(true));
    await expect.poll(() => frames(page), { timeout: 5_000 }).toBeGreaterThanOrEqual(1);
  });

  test("hidden with no player up: nothing is fed, exactly as before", async ({ page }) => {
    test.setTimeout(30_000);
    await bootOnly(page);
    await page.evaluate(() => { (window as any).__setHidden(true); (window as any).__loop(); });
    await page.waitForTimeout(3_000);
    expect(await frames(page)).toBe(0);
  });

  test("a visible page is never fed by the timer", async ({ page }) => {
    test.setTimeout(30_000);
    await bootOnly(page);
    await page.evaluate(() => { (window as any).raviloHiddenFrames.player(1); (window as any).__loop(); });
    await page.waitForTimeout(3_000);
    expect(await frames(page)).toBe(0);
  });

  test("the player going away stops the feed, and returning hands the frames back to the browser", async ({ page }) => {
    test.setTimeout(30_000);
    await bootOnly(page);
    await page.evaluate(() => { (window as any).raviloHiddenFrames.player(1); (window as any).__setHidden(true); (window as any).__loop(); });
    await expect.poll(() => frames(page), { timeout: 5_000 }).toBeGreaterThanOrEqual(1);
    await page.evaluate(() => (window as any).raviloHiddenFrames.player(-1));
    const stopped = await frames(page);
    await page.waitForTimeout(2_500);
    expect(await frames(page), "no player up: the timer is off").toBe(stopped);
    const state = await page.evaluate(() => (window as any).raviloHiddenFrames.state());
    expect(state.feeding).toBe(false);
    expect(state.held).toBe(0);
    // back on screen: the page's own clock runs again, so what is queued with it is what the browser will run
    await page.evaluate(() => (window as any).__setHidden(false));
    const queued = await page.evaluate(() => (window as any).__native.size);
    expect(queued, "the loop's pending frame is with the browser again").toBeGreaterThanOrEqual(1);
  });

  test("a cancelled frame request never runs, held or not", async ({ page }) => {
    test.setTimeout(30_000);
    await bootOnly(page);
    const ran = await page.evaluate(async () => {
      const w = window as any;
      w.raviloHiddenFrames.player(1);
      w.__setHidden(true);
      let hit = 0;
      const id = w.requestAnimationFrame(() => { hit++; });
      w.cancelAnimationFrame(id);
      await new Promise((r) => setTimeout(r, 2_500));
      return hit;
    });
    expect(ran).toBe(0);
  });

  // boot.js's own gamepad poller is a requestAnimationFrame loop too (it re-fires pad presses as key events), and it
  // goes through the same shim: it must go on at full rate while visible, be fed by the timer while a player is up and
  // the page is hidden, and be back at full rate on return. Unlike the tests above this one leaves the native clock real.
  test("the gamepad poller keeps running across a hide and a return", async ({ page }) => {
    test.setTimeout(30_000);
    await page.route("**/ravilo.js", (route) => route.abort());
    await page.addInitScript(() => {
      const w = window as any;
      w.__polls = 0;
      navigator.getGamepads = () => { w.__polls++; return [] as any; };
      w.__hidden = false;
      Object.defineProperty(document, "visibilityState", { configurable: true, get: () => (w.__hidden ? "hidden" : "visible") });
      w.__setHidden = (h: boolean) => { w.__hidden = h; document.dispatchEvent(new Event("visibilitychange")); };
    });
    await page.goto(WEB_URL, { waitUntil: "domcontentloaded", timeout: 10_000 });
    await expect.poll(() => page.evaluate(() => typeof (window as any).raviloHiddenFrames), { timeout: 10_000 }).toBe("object");
    const polls = () => page.evaluate(() => (window as any).__polls as number);
    await page.evaluate(() => window.dispatchEvent(new Event("gamepadconnected")));
    await page.waitForTimeout(1_000);
    const visible = await polls();
    expect(visible, "visible: one poll a frame").toBeGreaterThan(10);

    await page.evaluate(() => { (window as any).raviloHiddenFrames.player(1); (window as any).__setHidden(true); });
    const atHide = await polls();
    await page.waitForTimeout(3_500);
    const hidden = (await polls()) - atHide;
    expect(hidden, "hidden with a player up: still polling, from the timer").toBeGreaterThanOrEqual(2);
    expect(hidden, "and about once a second").toBeLessThanOrEqual(8);

    await page.evaluate(() => { (window as any).__setHidden(false); });
    const atReturn = await polls();
    await page.waitForTimeout(1_000);
    expect((await polls()) - atReturn, "back on screen: one poll a frame again").toBeGreaterThan(10);
  });
});
