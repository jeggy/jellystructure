import { test, expect, Page, Browser } from "@playwright/test";

/**
 * Regression coverage for the phase-157 Bazarr dashboard-card bug report: "the Settings/media-detail
 * Bazarr surfaces work but the Dashboard summary card never appears." Root cause turned out to be a
 * stale browser tab, not a code bug (verified live 2026-08-08 by exercising BazarrService/BazarrClient
 * directly against the real config.toml, and by inspecting the served .wasm for the card markup) --
 * this test exists so a REAL regression in either half (backend connectivity plumbing or the
 * frontend's show/hide logic) fails CI instead of relying on manual verification again.
 *
 * Reads Jellyfin credentials from env so real creds are never committed (same convention as
 * auth.spec.ts). Bazarr credentials are optional -- the enabled/live-data step skips itself if
 * they're not provided, since a reachable Bazarr instance is an external dependency, not something
 * CI should need.
 *
 * Serial + one shared login: the app's login-rate-limiter (a few attempts/minute) means logging in
 * fresh per test trips it under any retry or re-run within the same window. Both scenarios share one
 * authenticated page instead.
 */
const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";
const BAZARR_URL = process.env.BAZARR_URL ?? "";
const BAZARR_API_KEY = process.env.BAZARR_API_KEY ?? "";

async function login(page: Page) {
  await page.goto("/login");
  await page.fill('input[type="text"]', JF_USER);
  await page.fill('input[type="password"]', JF_PASS);
  await page.click('button[type="submit"], button:has-text("Sign in"), button:has-text("Login")');
  // Both .statgrid and the <h1> render together once loaded — a plain OR-selector here is a
  // Playwright strict-mode violation (2 elements match), unlike auth.spec.ts's identical-looking
  // locator, which never proceeds past this same wait so it doesn't hit the second element.
  await expect(page.locator('.statgrid, h1:has-text("Dashboard")').first()).toBeVisible({ timeout: 15_000 });
}

async function openBazarrSettings(page: Page) {
  await page.goto("/#/settings?tab=downloads");
  await expect(page.locator("#sect-bazarr")).toBeVisible();
}

async function saveSettings(page: Page) {
  await page.click("#save-settings");
  // #settings-msg is the save-feedback element (success or error) -- wait for it rather than a
  // fixed timeout so this doesn't flake under load.
  await expect(page.locator("#settings-msg")).toBeVisible({ timeout: 10_000 });
}

async function setBazarrEnabled(page: Page, on: boolean) {
  await openBazarrSettings(page);
  const toggle = page.locator("#bazarr-enabled-toggle");
  const isOn = await toggle.evaluate((el) => el.classList.contains("on"));
  if (isOn !== on) await toggle.click();
}

test.describe.serial("Bazarr subtitles — Dashboard summary card", () => {
  let page: Page;

  test.beforeAll(async ({ browser }: { browser: Browser }) => {
    page = await browser.newPage();
    await login(page);
  });

  test.afterAll(async () => {
    await page.close();
  });

  test("card is hidden when Bazarr is disabled", async () => {
    await setBazarrEnabled(page, false);
    await saveSettings(page);

    await page.goto("/#/dashboard");
    await expect(page.locator(".statgrid")).toBeVisible();
    await expect(page.locator("#dash-subtitles-card")).toBeHidden();
  });

  test("card shows live data when Bazarr is enabled and reachable", async () => {
    test.skip(!BAZARR_URL || !BAZARR_API_KEY, "BAZARR_URL/BAZARR_API_KEY not set — skipping (needs a real reachable Bazarr instance)");

    await setBazarrEnabled(page, true);
    await page.fill("#bazarr-url", BAZARR_URL);
    await page.fill("#bazarr-key", BAZARR_API_KEY);

    // Prove connectivity BEFORE saving, exactly like an admin would in the real UI.
    await page.click("#bazarr-test-btn");
    await expect(page.locator("#chk-bazarr .badge.ok")).toBeVisible({ timeout: 15_000 });

    await saveSettings(page);

    await page.goto("/#/dashboard");
    await expect(page.locator(".statgrid")).toBeVisible();
    await expect(page.locator("#dash-subtitles-card")).toBeVisible({ timeout: 15_000 });
    // Not just "visible" -- assert the placeholder ("Loading…") resolved to real content, so a
    // regression where the card un-hides but never populates (e.g. a silently-failing overview()
    // call) still fails this test.
    await expect(page.locator("#dash-subtitles-body")).not.toContainText("Loading", { timeout: 15_000 });
    await expect(page.locator("#dash-subtitles-body")).toContainText("Wanted:");
    await expect(page.locator("#dash-subtitles-body")).toContainText("Providers:");

    // Clean up so this test doesn't leave Bazarr enabled for every other suite that runs after it.
    await setBazarrEnabled(page, false);
    await saveSettings(page);
  });
});
