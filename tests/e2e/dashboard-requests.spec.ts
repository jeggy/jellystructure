import { test, expect, Page, Browser } from "@playwright/test";

/**
 * DEB-2499 (FR-285-20) — the Dashboard's own requests do not queue behind each other.
 *
 * The slowness is injected in the browser (page.route holds a response back), so the test needs no slow
 * backend: if the page awaited the scan status, the stats or `POST /api/dashboard/seen` before painting
 * the overview, the list would arrive only after the hold and the 2.5 s bound below would fail.
 *
 * One login for the file (the app's login rate limiter, see bazarr-dashboard.spec.ts).
 */
const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";
const HOLD_MS = 6_000;

async function login(page: Page) {
  await page.goto("/login");
  await page.fill('input[type="text"]', JF_USER);
  await page.fill('input[type="password"]', JF_PASS);
  await page.click('button[type="submit"], button:has-text("Sign in"), button:has-text("Login")');
  await expect(page.locator('.statgrid, h1:has-text("Dashboard")').first()).toBeVisible({ timeout: 15_000 });
}

/** Answer [pattern] only after [HOLD_MS] — the request still reaches the server, the page just hears back late. */
async function hold(page: Page, pattern: string) {
  await page.route(pattern, async (route) => {
    const response = await route.fetch();
    await new Promise((r) => setTimeout(r, HOLD_MS));
    await route.fulfill({ response });
  });
}

test.describe.serial("the Dashboard page's requests run side by side", () => {
  let page: Page;

  test.beforeAll(async ({ browser }: { browser: Browser }) => {
    page = await browser.newPage();
    await login(page);
  });

  test.afterAll(async () => {
    await page.close();
  });

  test("the list paints while the scan status, the stats and the seen mark are all still outstanding", async () => {
    await hold(page, "**/api/scan/status");
    await hold(page, "**/api/stats");
    await hold(page, "**/api/dashboard/seen");

    // Login already landed on /#/dashboard, where a goto would only change the hash: reload so the page opens under the holds.
    const t0 = Date.now();
    await page.goto("/#/dashboard");
    await page.reload();
    await expect(page.locator(".statgrid")).toBeVisible();
    // The overview, answered from the server's held build, is on screen: a row list or an empty state, not Loading….
    await expect(page.locator("#ov-body")).not.toContainText("Loading", { timeout: 2_500 });
    expect(Date.now() - t0).toBeLessThan(HOLD_MS - 1_000);

    await page.unroute("**/api/scan/status");
    await page.unroute("**/api/stats");
    await page.unroute("**/api/dashboard/seen");
  });
});
