import { test, expect, Page, Browser } from "@playwright/test";

/**
 * Phase 282 (owner decision 2026-10-04) — the Seerr card carries one standing line about Seerr 3.5.0's
 * "Hide requested media": it only hides titles in Seerr's own pages, so Ravilo is unaffected. The line is
 * static: it shows before and after *Test connection*, whatever the test says, and the card makes no Seerr
 * call for it. The e2e stack has no Seerr, so a test against a URL nothing answers fails — the hint stays.
 *
 * Serial + one shared login (the login rate limiter), like bazarr-dashboard.spec.ts. Nothing is saved:
 * the Seerr toggle is put back as it was found.
 */
const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";
const HINT =
  "Seerr's \"Hide requested media\" only hides titles in Seerr's own pages; Ravilo's Request rows and suggestions still show them.";

async function login(page: Page) {
  await page.goto("/login");
  await page.fill('input[type="text"]', JF_USER);
  await page.fill('input[type="password"]', JF_PASS);
  await page.click('button[type="submit"], button:has-text("Sign in"), button:has-text("Login")');
  await expect(page.locator('.statgrid, h1:has-text("Dashboard")').first()).toBeVisible({ timeout: 15_000 });
}

test.describe.serial("Seerr card — the hide-requested hint (phase 282)", () => {
  let page: Page;
  let wasOn = false;
  const seerrTests: string[] = [];

  test.beforeAll(async ({ browser }: { browser: Browser }) => {
    page = await browser.newPage();
    page.on("request", (req) => {
      if (req.url().includes("/api/config/test-seerr")) seerrTests.push(req.url());
    });
    await login(page);
    await page.goto("/#/settings?tab=downloads");
    await expect(page.locator("#sect-seerr")).toBeVisible();
    const toggle = page.locator("#seerr-enabled-toggle");
    wasOn = await toggle.evaluate((el) => el.classList.contains("on"));
    if (!wasOn) await toggle.click();
  });

  test.afterAll(async () => {
    const toggle = page.locator("#seerr-enabled-toggle");
    const isOn = await toggle.evaluate((el) => el.classList.contains("on"));
    if (isOn !== wasOn) await toggle.click();
    await page.close();
  });

  test("the Seerr card always carries the hide-requested hint", async () => {
    const hint = page.locator("#sect-seerr #seerr-hide-hint");
    await expect(hint).toBeVisible();
    await expect(hint).toHaveText(HINT);
    // Under the connection result, where the version appears.
    const chkBox = await page.locator("#chk-seerr").boundingBox();
    const hintBox = await hint.boundingBox();
    expect(chkBox && hintBox && hintBox.y >= chkBox.y).toBeTruthy();
  });

  test("the hint makes no Seerr call", async () => {
    expect(seerrTests).toEqual([]);
  });

  test("the hint does not depend on the test result", async () => {
    await page.fill("#seerr-url", "http://127.0.0.1:9/");
    await page.fill("#seerr-key", "e2e-nothing-answers");
    await page.click("#seerr-test-btn");
    await expect(page.locator("#chk-seerr .badge.bad")).toBeVisible({ timeout: 30_000 });
    await expect(page.locator("#sect-seerr #seerr-hide-hint")).toHaveText(HINT);
  });
});
