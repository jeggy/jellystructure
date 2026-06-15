import { test, expect, Page } from "@playwright/test";
import { execSync } from "child_process";
import * as path from "path";

/**
 * End-to-end fixture repair suite — runs serially so each test can rely on
 * server-side state (media.json) written by previous tests.
 *
 * Pre-conditions (set up by CI before this suite runs):
 *  - APP_URL points to a running Jellystructure instance
 *  - FIXTURE_DIR contains the output of scripts/build-fixtures.sh
 *  - Sintel: fra is wrong default audio track (must be fixed to eng)
 *  - Big Buck Bunny: 2 untagged audio tracks (→ triage queue)
 *  - Jellystructure config maps the fixture movie dir as a library
 */

const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";
const FIXTURE_DIR = process.env.FIXTURE_DIR ?? "/tmp/jellystructure-fixtures";
const SINTEL_MKV = path.join(FIXTURE_DIR, "movies", "Sintel (2010)", "Sintel (2010).mkv");
const BBB_MKV = path.join(FIXTURE_DIR, "movies", "Big Buck Bunny (2008)", "Big Buck Bunny (2008).mkv");

async function login(page: Page) {
  await page.goto("/login");
  await page.fill('input[type="text"]', JF_USER);
  await page.fill('input[type="password"]', JF_PASS);
  await page.click('button[type="submit"], button:has-text("Sign in"), button:has-text("Login")');
  await expect(page.locator('.statgrid, h1:has-text("Dashboard")')).toBeVisible({ timeout: 15_000 });
}

async function waitForScanComplete(page: Page) {
  await expect(page.locator('#dash-scan')).toBeDisabled({ timeout: 10_000 });
  await expect(page.locator('#dash-scan')).toBeEnabled({ timeout: 120_000 });
  await expect(page.locator('#dash-scan')).toHaveText('▶ Scan library');
}

function ffprobeDefaultAudio(mkv: string): string {
  const out = execSync(
    `ffprobe -v quiet -print_format json -show_streams "${mkv}"`,
  ).toString();
  const streams = JSON.parse(out).streams as Array<{
    codec_type: string;
    tags?: { language?: string };
    disposition?: { default?: number };
  }>;
  const audio = streams.filter((s) => s.codec_type === "audio");
  const def = audio.find((s) => s.disposition?.default === 1);
  return def?.tags?.language ?? "(none)";
}

function ffprobeAudioLangs(mkv: string): string[] {
  const out = execSync(
    `ffprobe -v quiet -print_format json -show_streams "${mkv}"`,
  ).toString();
  const streams = JSON.parse(out).streams as Array<{
    codec_type: string;
    tags?: { language?: string };
  }>;
  return streams
    .filter((s) => s.codec_type === "audio")
    .map((s) => s.tags?.language ?? "");
}

test.describe.serial("Fixture suite", () => {
  test("scan detects Sintel and track order fixes default from fra to eng", async ({ page }) => {
    await login(page);

    // Trigger scan
    await page.click('button:has-text("▶ Scan library"), button:has-text("Scan library")');
    await waitForScanComplete(page);

    // Navigate to Library
    await page.goto("/#/library");
    await page.waitForSelector('.poster', { timeout: 30_000 });
    await page.click('text=Sintel');

    await expect(page.locator('h2, .pagebar h2')).toBeVisible({ timeout: 10_000 });

    // Open Track order tab
    await page.locator('button:has-text("Track order")').click();
    await expect(page.locator('[data-specifier]').first()).toBeVisible({ timeout: 10_000 });

    // Set eng as default
    const engRow = page.locator('[data-specifier]').filter({ hasText: "eng" }).first();
    await expect(engRow).toBeVisible({ timeout: 5_000 });
    await engRow.locator('button:has-text("Set default")').click();

    await expect(page.locator('#plan-card')).toBeVisible({ timeout: 8_000 });
    await page.click('#apply-btn');
    await expect(page.locator('.badge.ok')).toBeVisible({ timeout: 15_000 });

    // ffprobe confirms the write
    expect(ffprobeDefaultAudio(SINTEL_MKV)).toBe("eng");
  });

  test("triage shows BBB untagged tracks and language assignment writes to file", async ({ page }) => {
    await login(page);
    await page.goto("/#/triage");

    // BBB should appear with 2 untagged audio tracks (scan state from test 1)
    const bbbItem = page.locator('.triage-item').filter({ hasText: 'Big Buck Bunny' }).first();
    await expect(bbbItem).toBeVisible({ timeout: 15_000 });
    await expect(bbbItem.locator('.triage-lang-input')).toHaveCount(2, { timeout: 5_000 });

    // Fill language for the first untagged track and assign
    const firstInput = bbbItem.locator('.triage-lang-input').first();
    const firstAssignBtn = bbbItem.locator('.triage-assign-btn').first();
    await firstInput.fill('eng');
    await firstAssignBtn.click();

    // After assignment, the first track is removed from the untagged list — BBB shows 1 remaining
    await expect(bbbItem.locator('.triage-lang-input')).toHaveCount(1, { timeout: 10_000 });

    // ffprobe confirms the language tag was written to the MKV
    const langs = ffprobeAudioLangs(BBB_MKV);
    expect(langs[0]).toBe("eng");
  });
});
