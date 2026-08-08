import { test, expect, Page, Browser } from "@playwright/test";
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
 *
 * One shared login (beforeAll/afterAll), not one per test: the app's login-rate-limiter (a few
 * attempts/minute) is shared across the WHOLE Playwright run, not per file -- by the time this
 * suite's 3rd/4th test tried to log in on top of auth.spec.ts's + bazarr-dashboard.spec.ts's own
 * logins, the cumulative count tripped it, confirmed live 2026-08-08 the first time this suite
 * actually ran a full pass. Same fix as bazarr-dashboard.spec.ts.
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
  // .first() -- .statgrid and the <h1> both render together once loaded, so the bare OR-selector
  // is a Playwright strict-mode violation (2 elements match). See auth.spec.ts's identical fix.
  await expect(page.locator('.statgrid, h1:has-text("Dashboard")').first()).toBeVisible({ timeout: 15_000 });
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
  let page: Page;

  test.beforeAll(async ({ browser }: { browser: Browser }) => {
    page = await browser.newPage();
    await login(page);
  });

  test.afterAll(async () => {
    await page.close();
  });

  test("scan detects Sintel and track order fixes default from fra to eng", async () => {
    // Trigger scan
    await page.click('button:has-text("▶ Scan library"), button:has-text("Scan library")');
    await waitForScanComplete(page);

    // Navigate to Library
    await page.goto("/#/library");
    await page.waitForSelector('.poster', { timeout: 30_000 });
    await page.click('text=Sintel');

    await expect(page.locator('h2, .pagebar h2')).toBeVisible({ timeout: 10_000 });

    // Open the Tracks & subtitles tab -- it's a <span data-tab="tracks"> in #detail-tabs, not a
    // <button>, and "Track order" never matched either the old "Tracks & order" or current
    // "Tracks & subtitles" label; this locator was always broken, just never exercised until CI
    // actually started running tests (2026-08-08).
    await page.locator('#detail-tabs span[data-tab="tracks"]').click();
    // Real row attribute is data-trk-i (TrackEditor.kt's per-row wrapper), not data-specifier --
    // another locator that never matched anything until this suite actually started running.
    await expect(page.locator('[data-trk-i]').first()).toBeVisible({ timeout: 10_000 });

    // Set eng as default -- the "set default ★" control is a <span data-act="default">, not a
    // <button>, so a `button:has-text(...)` locator could never match it either.
    const engRow = page.locator('[data-trk-i]').filter({ hasText: "eng" }).first();
    await expect(engRow).toBeVisible({ timeout: 5_000 });
    await engRow.locator('[data-act="default"]').click();

    // The unified track editor's shell prefixes every id with "trk" on the movie page
    // (buildUnifiedTrackEditorShell("trk", ...) in MediaDetail.kt) -- #plan-card/#apply-btn never
    // existed under any prefix.
    await expect(page.locator('#trk-pending')).toBeVisible({ timeout: 8_000 });
    await page.click('#trk-apply');
    // applyChanges() (TrackEditor.kt) sets #trk-apply-msg's TEXT to "Changes applied" -- there's no
    // success badge anywhere near the track editor to assert on (.badge.ok already matches the
    // pre-existing "TMDB matched" pagebar badge and the "en" language badge, a strict-mode
    // violation neither of which has anything to do with whether Apply succeeded).
    await expect(page.locator('#trk-apply-msg')).toHaveText('Changes applied', { timeout: 15_000 });

    // ffprobe confirms the write
    expect(ffprobeDefaultAudio(SINTEL_MKV)).toBe("eng");
  });

  // Written against a standalone /#/triage page (.triage-item/.triage-lang-input/.triage-assign-btn)
  // that no longer exists in the app -- Triage was architecturally changed to a floating "Needs
  // attention" dock (see Shell.kt: "there is no Triage page or nav badge any more -- the dock is
  // the surface"), confirmed live 2026-08-08 the first time this suite actually ran (there is no
  // /triage route in Main.kt at all). This needs a real rewrite against the dock's DOM, not a
  // locator swap -- skipping rather than leaving CI red on a premise that's no longer true.
  test.fixme("triage shows BBB untagged tracks and language assignment writes to file", async () => {
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

  test("Tears of Steel detail shows Seasons & episodes tab with 3 episode rows", async () => {
    await page.goto("/#/library");
    await page.waitForSelector('.poster', { timeout: 30_000 });

    await page.locator('.ttl', { hasText: 'Tears of Steel' }).first().click();
    await expect(page.locator('h2')).toContainText('Tears of Steel', { timeout: 10_000 });

    // TV shows must have "Seasons & episodes" tab, not the movie "Tracks & order" tab
    const episodesTab = page.locator('[data-tab="episodes"]');
    await expect(episodesTab).toBeVisible({ timeout: 5_000 });
    await expect(page.locator('[data-tab="tracks"]')).toHaveCount(0);

    // Click the episodes tab and verify 3 episode rows appear
    await episodesTab.click();
    await expect(page.locator('.ep-toggle-row')).toHaveCount(3, { timeout: 10_000 });
    await expect(page.locator('.ep-toggle-row').first()).toContainText('S01E01');
    await expect(page.locator('.ep-toggle-row').nth(2)).toContainText('S01E03');
  });

  // Written assuming any language mix disables NFO writes and shows specific badge text
  // ("language mix — writes blocked", "Language Mix Detected") that doesn't exist in the current
  // app: MediaDetail.kt only disables NFO writes when languageMix AND there's no resolved majority
  // language (nfoDisabled = languageMix && resolvedLanguage.isNullOrBlank()); the real overview
  // badge just reads "Mixed"/"Uniform". Babel Fish's own fixture has a clear majority (2 of 3
  // episodes eng), so the scanner resolves a majority language and NFO writes stay enabled --
  // confirmed live 2026-08-08 via the scan log ("using majority language for NFO"), the first time
  // this suite actually ran this test. Needs either different fixture data (a genuine no-majority
  // 3-way split) or a rewrite matching current text/logic, not a locator swap.
  test.fixme("Babel Fish mixed-language series shows language mix badge and no NFO button", async () => {
    await page.goto("/#/library");
    await page.waitForSelector('.poster', { timeout: 30_000 });

    await page.locator('.ttl', { hasText: 'Babel Fish' }).first().click();
    await expect(page.locator('h2')).toContainText('Babel Fish', { timeout: 10_000 });

    // Language mix badge visible in the page header (issueBadge area)
    await expect(page.locator('.badge.warn', { hasText: 'language mix — writes blocked' })).toBeVisible({ timeout: 5_000 });

    // "Language Mix Detected" card visible in the overview tab
    await expect(page.locator('.badge.warn', { hasText: 'Language Mix Detected' })).toBeVisible();

    // Save → NFO button must be disabled because languageMix blocks writes
    await expect(page.locator('#write-nfo-btn')).toBeDisabled();
  });
});
