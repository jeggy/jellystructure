import { test, expect, Page, Browser } from "@playwright/test";

/**
 * DEB-1664 (FR-4, FR-14.1) — Settings ▸ Processing replaces the pipeline configurator: a tab right after Libraries,
 * the household *Wait while someone is watching* card, nine rows from `GET /api/processing`, and the moved controls.
 * The Libraries tab no longer carries the pipeline card or *Make files play directly*.
 *
 * Serial + one shared login (the login rate limiter), like seerr-card-hint.spec.ts. Every change is put back.
 */
const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";

async function login(page: Page) {
  await page.goto("/login");
  await page.fill('input[type="text"]', JF_USER);
  await page.fill('input[type="password"]', JF_PASS);
  await page.click('button[type="submit"], button:has-text("Sign in"), button:has-text("Login")');
  await expect(page.locator('.statgrid, h1:has-text("Dashboard")').first()).toBeVisible({ timeout: 15_000 });
}

async function openProcessing(page: Page) {
  await page.goto("/#/settings?tab=processing");
  await expect(page.locator("#sect-processing")).toBeVisible();
  await expect(page.locator("#sect-processing .pf-row")).toHaveCount(9, { timeout: 15_000 });
}

async function save(page: Page) {
  await page.click("#save-settings");
  await expect(page.locator("#settings-msg")).toContainText("Saved", { timeout: 15_000 });
}

test.describe.serial("Settings ▸ Processing (DEB-1664, FR-4)", () => {
  let page: Page;

  test.beforeAll(async ({ browser }: { browser: Browser }) => {
    page = await browser.newPage();
    await login(page);
  });

  test.afterAll(async () => {
    await page.close();
  });

  test("the stylesheet is served", async () => {
    const res = await page.request.get("/processing.css");
    expect(res.status()).toBe(200);
    expect(res.headers()["content-type"] ?? "").toContain("css");
  });

  test("the tab sits right after Libraries and lists the nine features", async () => {
    await page.goto("/#/settings");
    const tabs = await page.locator(".settings-nav-item span:first-child").allTextContents();
    expect(tabs.indexOf("Processing")).toBe(tabs.indexOf("Libraries") + 1);
    await openProcessing(page);
    const names = await page.locator("#sect-processing .pf-name").allTextContents();
    expect(names.map((n) => n.trim()).slice(0, 3)).toEqual([
      expect.stringContaining("Find new & changed"),
      expect.stringContaining("Metadata & artwork"),
      expect.stringContaining("Intro & credits"),
    ]);
    // Find's switch is shown on and disabled, with its reason (FR-11).
    const find = page.locator("#sect-processing .pf-row").first().locator(".toggle");
    await expect(find).toHaveClass(/on/);
    await expect(find).toHaveClass(/dis/);
    await expect(find).toHaveAttribute("title", /\S/);
    // the wait card says what is true (FR-13.4)
    await expect(page.locator("#sect-processing")).toContainText("Scans wait while a TV is playing. A title you refresh yourself never waits.");
    await expect(page.locator("#defer-while-playing-toggle")).toBeVisible();
  });

  test("the Libraries tab no longer carries the pipeline card or Make files play directly (FR-14.1)", async () => {
    await page.goto("/#/settings?tab=libraries");
    await expect(page.locator("#sect-scanning")).toBeVisible();
    for (const id of ["#pipe-enable", "#pipe-fields", "#pipe-canvas", "#pipe-cron", "#sect-filefix"]) {
      await expect(page.locator(id)).toHaveCount(0);
    }
    await expect(page.locator("#sect-scanning")).not.toContainText("Add step");
  });

  test("a part switched off and saved is still off after a reload, and the drift option survives a save", async () => {
    await openProcessing(page);
    await page.locator("#sect-processing .pf-row", { hasText: "Metadata & artwork" }).locator(".pf-head").click();
    const imdb = page.locator('#sect-processing .toggle[data-part="sync_imdb_ratings"]');
    const reassert = page.locator('#sect-processing .toggle[data-opt="auto_reassert"]');
    const imdbWas = await imdb.evaluate((el) => el.classList.contains("on"));
    const reassertWas = await reassert.evaluate((el) => el.classList.contains("on"));
    if (!reassertWas) await reassert.click();
    await imdb.click();
    await expect(page.locator("#pf-dirty")).toBeVisible();
    await save(page);

    await openProcessing(page);
    await page.locator("#sect-processing .pf-row", { hasText: "Metadata & artwork" }).locator(".pf-head").click();
    const imdbNow = await page.locator('#sect-processing .toggle[data-part="sync_imdb_ratings"]').evaluate((el) => el.classList.contains("on"));
    expect(imdbNow).toBe(!imdbWas);
    // a second save that touches nothing here keeps it (the admin's model round-trips auto_reassert)
    await save(page);
    await openProcessing(page);
    await page.locator("#sect-processing .pf-row", { hasText: "Metadata & artwork" }).locator(".pf-head").click();
    await expect(page.locator('#sect-processing .toggle[data-opt="auto_reassert"]')).toHaveClass(/\bon\b/);

    // put both back
    const imdb2 = page.locator('#sect-processing .toggle[data-part="sync_imdb_ratings"]');
    if ((await imdb2.evaluate((el) => el.classList.contains("on"))) !== imdbWas) await imdb2.click();
    const re2 = page.locator('#sect-processing .toggle[data-opt="auto_reassert"]');
    if ((await re2.evaluate((el) => el.classList.contains("on"))) !== reassertWas) await re2.click();
    await save(page);
  });

  test("Find new & changed's picker is validated before it can be saved (phase 166)", async () => {
    await openProcessing(page);
    await page.locator("#sect-processing .pf-row").first().locator(".pf-head").click();
    await page.selectOption('[data-find="mode"]', "custom");
    await page.fill('[data-find="cron"]', "not a cron");
    await expect(page.locator("#pf-find-check .badge.bad")).toBeVisible({ timeout: 5_000 });
    await expect(page.locator("#save-settings")).toBeDisabled();
    await page.fill('[data-find="cron"]', "0 */1 * * *");
    await expect(page.locator("#pf-find-check .badge.ok")).toBeVisible({ timeout: 5_000 });
    await expect(page.locator("#save-settings")).toBeEnabled();
    await page.reload();
  });

  for (const theme of ["dark", "light"]) {
    test(`412 px holds in the ${theme} theme`, async () => {
      await page.setViewportSize({ width: 412, height: 900 });
      await page.evaluate((t) => { localStorage.setItem("js-theme", t); document.documentElement.setAttribute("data-theme", t); }, theme);
      await openProcessing(page);
      await page.locator("#sect-processing .pf-row").nth(3).locator(".pf-head").click();
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      expect(overflow).toBeLessThanOrEqual(0);
      await page.setViewportSize({ width: 1280, height: 900 });
    });
  }
});
