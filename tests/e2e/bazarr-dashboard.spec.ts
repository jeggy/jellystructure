import { test, expect, Page, Browser } from "@playwright/test";

/**
 * Regression coverage for the phase-157 Bazarr dashboard-card bug report: "the Settings/media-detail
 * Bazarr surfaces work but the Dashboard summary card never appears." Root cause turned out to be a
 * stale browser tab, not a code bug (verified live 2026-08-08 by exercising BazarrService/BazarrClient
 * directly against the real config.toml, and by inspecting the served .wasm for the card markup) --
 * this test exists so a REAL regression in either half (backend connectivity plumbing or the
 * frontend's show/hide logic) fails CI instead of relying on manual verification again.
 *
 * Phase 285 replaced the summary card with one overview: Bazarr now reaches the Dashboard as the
 * *Subtitles* group -- its advisor's findings, read live from Bazarr's own settings -- and the group is
 * absent while Bazarr is off. The mock answers /api/system/settings as a fresh Bazarr (the hook off),
 * so a connected Bazarr always has one row to show. The rows are asserted on /api/dashboard, the one
 * endpoint the page renders: this file runs before any scan, and an empty library shows *Nothing scanned
 * yet* instead of rows (FR-285's first-run state) -- so the page's own chip is checked only once the
 * library holds something.
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

/** Phase 285 — the overview has loaded: its body no longer says Loading… (a row list, or an empty state). */
async function openDashboard(page: Page) {
  await page.goto("/#/dashboard");
  await expect(page.locator(".statgrid")).toBeVisible();
  await expect(page.locator("#ov-body")).not.toContainText("Loading", { timeout: 15_000 });
  await expect(page.locator("#ov-body")).not.toContainText("Couldn’t load the overview");
}

type Overview = { firstRun?: boolean; rows: { domain: string; label: string }[] };

/** The same /api/dashboard the page renders, with the page's own session. */
async function overview(page: Page): Promise<Overview> {
  const res = await page.request.get("/api/dashboard");
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  return { firstRun: body.first_run === true, rows: body.rows ?? [] };
}

test.describe.serial("Bazarr subtitles — the Dashboard's Subtitles group", () => {
  let page: Page;

  test.beforeAll(async ({ browser }: { browser: Browser }) => {
    page = await browser.newPage();
    await login(page);
  });

  test.afterAll(async () => {
    await page.close();
  });

  test("the Subtitles group is absent when Bazarr is disabled", async () => {
    await setBazarrEnabled(page, false);
    await saveSettings(page);

    await openDashboard(page);
    expect((await overview(page)).rows.filter((r) => r.domain === "subs")).toEqual([]);
    await expect(page.locator('#ov-body [data-chip="subs"]')).toHaveCount(0);
    await expect(page.locator("#ov-body .ov-dom", { hasText: "Subtitles" })).toHaveCount(0);
  });

  test("Bazarr's findings reach the Dashboard when it is enabled and reachable", async () => {
    await setBazarrEnabled(page, true);
    await page.fill("#bazarr-url", BAZARR_URL);
    await page.fill("#bazarr-key", BAZARR_API_KEY);

    // Prove connectivity BEFORE saving, exactly like an admin would in the real UI.
    await page.click("#bazarr-test-btn");
    await expect(page.locator("#chk-bazarr .badge.ok")).toBeVisible({ timeout: 15_000 });

    await saveSettings(page);

    await openDashboard(page);
    // The row is read live from the mock's /api/system/settings, so a regression where the backend stops
    // reaching Bazarr (or the overview stops asking it) fails here.
    const ov = await overview(page);
    const subs = ov.rows.filter((r) => r.domain === "subs");
    expect(subs.map((r) => r.label)).toContainEqual(expect.stringContaining("tell jellystructure when it places a subtitle"));
    if (!ov.firstRun) {
      const chip = page.locator('#ov-body [data-chip="subs"]');
      await expect(chip).toBeVisible({ timeout: 15_000 });
      await chip.click();
      await expect(page.locator("#ov-body .ov-row", { hasText: "tell jellystructure when it places a subtitle" })).toBeVisible();
    }

    // Clean up so this test doesn't leave Bazarr enabled for every other suite that runs after it.
    await setBazarrEnabled(page, false);
    await saveSettings(page);
  });
});
