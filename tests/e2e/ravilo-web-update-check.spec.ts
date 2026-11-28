import { test, expect, type Page } from "@playwright/test";

// DEB-5851 (FR-8, FR-9, FR-10) — a Ravilo Web tab left open asks its service worker for a newer build, and a hidden,
// idle tab with a build waiting reloads itself onto the same page. Only boot.js is under test: the app bundle is
// blocked, `navigator.serviceWorker` is a stand-in whose registration counts `update()` calls and can hold a waiting
// worker, and Playwright's clock drives the hours. `document.visibilityState` is driven by hand, as headless Chromium
// never reports a page as hidden. The real service worker (a second build's sw.js taking over) is the lab's and QA's.
const WEB_URL = process.env.RAVILO_WEB_URL ?? "http://localhost:8082";
const MIN = 60_000, HOUR = 60 * MIN;

async function bootOnly(page: Page, opts: { waiting: boolean }) {
  await page.clock.install({ time: new Date("2026-11-28T10:00:00Z") });
  await page.route("**/ravilo.js", (route) => route.abort());
  await page.addInitScript((waiting: boolean) => {
    const w = window as any;
    w.__hidden = false;
    Object.defineProperty(document, "visibilityState", { configurable: true, get: () => (w.__hidden ? "hidden" : "visible") });
    w.__setHidden = (h: boolean) => { w.__hidden = h; document.dispatchEvent(new Event("visibilitychange")); };
    // Counts live across a reload in sessionStorage, which is also what boot.js's once-per-build flag uses.
    const bump = (k: string) => sessionStorage.setItem(k, String(Number(sessionStorage.getItem(k) ?? "0") + 1));
    w.__skip = 0;
    const reg: any = {
      installing: null,
      waiting: waiting ? { postMessage: (m: string) => { if (m === "SKIP_WAITING") bump("t_skip"); } } : null,
      update: () => { bump("t_updates"); return Promise.resolve(); },
      addEventListener: () => {},
    };
    Object.defineProperty(navigator, "serviceWorker", {
      configurable: true,
      value: { controller: {}, register: () => Promise.resolve(reg), getRegistration: () => Promise.resolve(reg) },
    });
    if (waiting) w.__raviloUpdateAvailable = true;
  }, opts.waiting);
  await page.goto(WEB_URL, { waitUntil: "load", timeout: 10_000 });
  await expect.poll(() => page.evaluate(() => typeof (navigator.serviceWorker as any).register), { timeout: 10_000 }).toBe("function");
  await page.clock.runFor(1_000); // the registration's promise settles
}

const count = (page: Page, key: string) => page.evaluate((k) => Number(sessionStorage.getItem(k) ?? "0"), key);
const setHidden = (page: Page, h: boolean) => page.evaluate((v) => (window as any).__setHidden(v), h);

test.describe("Ravilo web checks for a new build while it is open (FR-8)", () => {
  test("a tab that stays visible asks its worker once every 6 hours, and nothing else", async ({ page }) => {
    await bootOnly(page, { waiting: false });
    await page.clock.runFor(5 * HOUR + 50 * MIN);
    expect(await count(page, "t_updates"), "not before 6 h").toBe(0);
    await page.clock.runFor(15 * MIN);
    expect(await count(page, "t_updates")).toBe(1);
    await page.clock.runFor(6 * HOUR);
    expect(await count(page, "t_updates")).toBe(2);
  });

  test("becoming visible asks when at least an hour has passed since the last ask, not sooner", async ({ page }) => {
    await bootOnly(page, { waiting: false });
    await setHidden(page, true);
    await page.clock.runFor(20 * MIN);
    await setHidden(page, false);
    expect(await count(page, "t_updates"), "20 minutes: no ask").toBe(0);
    await setHidden(page, true);
    await page.clock.runFor(45 * MIN);
    await setHidden(page, false);
    expect(await count(page, "t_updates"), "over an hour since the page loaded: one ask").toBe(1);
    await setHidden(page, true);
    await page.clock.runFor(5 * MIN);
    await setHidden(page, false);
    expect(await count(page, "t_updates"), "an ask a moment ago: no second").toBe(1);
  });
});

test.describe("a hidden, idle tab reloads itself onto a waiting build (FR-10)", () => {
  test("hidden 30 minutes with a build waiting and no player: it reloads once, and never again for that build", async ({ page }) => {
    await bootOnly(page, { waiting: true });
    let loads = 0;
    page.on("load", () => { loads++; });
    await setHidden(page, true);
    await page.clock.runFor(29 * MIN);
    expect(loads, "not at 29 minutes").toBe(0);
    await page.clock.runFor(2 * MIN);
    await expect.poll(() => loads, { timeout: 10_000 }).toBe(1);
    expect(await count(page, "t_skip"), "the waiting worker was told to take over").toBe(1);
    expect(new URL(page.url()).pathname, "the same page").toBe(new URL(WEB_URL).pathname);
    // The build is still "waiting" in this stand-in, so a second reload would be the loop the flag exists to stop.
    await setHidden(page, true);
    await page.clock.runFor(2 * HOUR);
    expect(loads, "once for one build").toBe(1);
  });

  test("never with a player open", async ({ page }) => {
    await bootOnly(page, { waiting: true });
    let loads = 0;
    page.on("load", () => { loads++; });
    await page.evaluate(() => { (window as any).__raviloPlayerOpen = true; });
    await setHidden(page, true);
    await page.clock.runFor(2 * HOUR);
    expect(loads).toBe(0);
    expect(await count(page, "t_skip")).toBe(0);
  });

  test("never while visible", async ({ page }) => {
    await bootOnly(page, { waiting: true });
    let loads = 0;
    page.on("load", () => { loads++; });
    await page.clock.runFor(2 * HOUR);
    expect(loads).toBe(0);
  });

  test("with no build waiting, a hidden tab is left alone", async ({ page }) => {
    await bootOnly(page, { waiting: false });
    let loads = 0;
    page.on("load", () => { loads++; });
    await setHidden(page, true);
    await page.clock.runFor(2 * HOUR);
    expect(loads).toBe(0);
  });
});
