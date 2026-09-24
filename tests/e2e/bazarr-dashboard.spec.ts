import { test, expect, Page, Browser } from "@playwright/test";

/**
 * Regression coverage for the phase-157 Bazarr dashboard-card bug report: "the Settings/media-detail
 * Bazarr surfaces work but the Dashboard summary card never appears." Root cause turned out to be a
 * stale browser tab, not a code bug (verified live 2026-08-08 by exercising BazarrService/BazarrClient
 * directly against the real config.toml, and by inspecting the served .wasm for the card markup) --
 * this test exists so a REAL regression in either half (backend connectivity plumbing or the
 * frontend's show/hide logic) fails CI instead of relying on manual verification again.
 *
 * Everything here is mocked, same as the rest of this suite -- tests/mock-bazarr/server.js, wired up
 * via docker-compose.test.yml as `bazarr-mock` alongside jellyfin-mock/tmdb-mock. No real external
 * service is ever required to run this file. Reads BAZARR_URL/BAZARR_API_KEY/JELLYFIN_USER/PASS from
 * env (same convention as auth.spec.ts) purely so a local run against a differently-wired stack can
 * override them -- the defaults below already match the mock's own defaults.
 *
 * Serial + one shared login: the app's login-rate-limiter (a few attempts/minute) means logging in
 * fresh per test trips it under any retry or re-run within the same window. Both scenarios share one
 * authenticated page instead.
 */
const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";
const BAZARR_URL = process.env.BAZARR_URL ?? "http://bazarr-mock:6767";
const BAZARR_API_KEY = process.env.BAZARR_API_KEY ?? "mock-bazarr-key";

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
  // #settings-msg is the save-feedback element -- wait for it rather than a fixed timeout so this
  // doesn't flake under load, and insist on the SUCCESS badge: a rejected save (400, e.g. a fixture
  // value Phase 227's validation refuses) also shows a message, and once let this suite believe Bazarr
  // had been enabled when it had not (2026-09-24).
  await expect(page.locator("#settings-msg .badge.ok")).toBeVisible({ timeout: 10_000 });
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
