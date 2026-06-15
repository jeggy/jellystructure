import { test, expect, Page } from "@playwright/test";
import { execSync } from "child_process";
import * as path from "path";

/**
 * End-to-end fixture repair test.
 *
 * Pre-conditions (set up by CI before this suite runs):
 *  - APP_URL points to a running Jellystructure instance
 *  - FIXTURE_DIR contains the output of scripts/build-fixtures.sh
 *  - The Sintel fixture has fra as the wrong default audio track
 *  - Jellystructure config maps the fixture movie dir as a library
 *
 * What this test does:
 *  1. Login
 *  2. Trigger a library scan
 *  3. Wait for scan to complete (WebSocket Finished event or polling)
 *  4. Navigate to Sintel's detail page
 *  5. Go to Track Order, set eng as default
 *  6. Apply — assert the UI shows success
 *  7. ffprobe the file and assert default track is now eng
 */

const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";
const FIXTURE_DIR = process.env.FIXTURE_DIR ?? "/tmp/jellystructure-fixtures";
const SINTEL_MKV = path.join(FIXTURE_DIR, "movies", "Sintel (2010)", "Sintel (2010).mkv");

async function login(page: Page) {
  await page.goto("/login");
  await page.fill('input[type="text"]', JF_USER);
  await page.fill('input[type="password"]', JF_PASS);
  await page.click('button[type="submit"], button:has-text("Sign in"), button:has-text("Login")');
  // After login the app sets hash to "#/" — wait for dashboard content to confirm success
  await expect(page.locator('.statgrid, h1:has-text("Dashboard")')).toBeVisible({ timeout: 15_000 });
}

async function waitForScanComplete(page: Page) {
  // First wait for the scan to actually start (button becomes disabled)
  await expect(page.locator('#dash-scan')).toBeDisabled({ timeout: 10_000 });
  // Then wait for the scan to finish (button re-enables with original text)
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

test.describe("Fixture repair", () => {
  test("scan detects Sintel and track order fixes default from fra to eng", async ({ page }) => {
    await login(page);

    // Trigger scan
    await page.click('button:has-text("▶ Scan library"), button:has-text("Scan library")');
    await waitForScanComplete(page);

    // Navigate to Library via hash URL so the SPA router renders the correct page
    await page.goto("/#/library");
    await page.click('text=Sintel');

    // Should be on media detail page
    await expect(page).toHaveURL(/\/media\//);

    // Click Track order button
    await page.click('text=Track order');
    await expect(page).toHaveURL(/\/track-order/);

    // The eng row "Set default" button — first audio row with lang=eng
    const engRow = page.locator('[data-specifier]').filter({ hasText: "eng" }).first();
    await expect(engRow).toBeVisible({ timeout: 5_000 });
    await engRow.locator('button:has-text("Set default")').click();

    // Plan should appear
    await expect(page.locator('#plan-card')).toBeVisible({ timeout: 8_000 });

    // Apply
    await page.click('#apply-btn');
    await expect(page.locator('.badge.ok')).toBeVisible({ timeout: 15_000 });

    // Verify via ffprobe that default is now eng
    const defaultLang = ffprobeDefaultAudio(SINTEL_MKV);
    expect(defaultLang).toBe("eng");
  });
});
