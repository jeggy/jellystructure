import { test, expect, Page, Browser } from "@playwright/test";

/**
 * DEB-1664 (FR-5, FR-6, FR-9.1) — the Scan dialog behind every door: two panes, Find new & changed locked, the re-check switch, parts
 * disabled with their reason, what is unticked remembered on every page, the 412 px drill-in, and the toast that names the skip.
 *
 * The POST that starts a scan is answered here by the test (a 202 with the same shape the server sends) so that no real run starts and
 * the specs after this one find an idle server; what the server does with the body is proved by `ScanDialogTest` (linuxX64Test), and the
 * real GET and the real 404 are asked of the server below. Serial, one shared login (the login rate limiter), like settings-processing.spec.ts.
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

/** What the server would have answered, so a started "scan" is a toast and nothing else. Records the bodies it was sent. */
async function fakeStart(page: Page, bodies: any[]) {
  await page.route("**/api/processing/scan", async (route) => {
    if (route.request().method() !== "POST") return route.continue();
    const body = JSON.parse(route.request().postData() ?? "{}");
    bodies.push(body);
    const skipped: string[] = body.skip?.length ? ["File health"] : [];
    await route.fulfill({
      status: 202,
      contentType: "application/json",
      body: JSON.stringify({
        status: "started", jobId: "scan-test", library: body.library ?? "all", skipped,
        skippedText: skipped.join(", "),
        skippedNote: skipped.length ? "You unticked it. It stays unticked until you tick it again." : "",
      }),
    });
  });
}

const dialog = (page: Page) => page.locator("#scan-dialog-scrim");
const row = (page: Page, name: string) => dialog(page).locator(".sd-two .pick", { hasText: name }).first();

test.describe.serial("Scan dialog (DEB-1664, FR-5)", () => {
  let page: Page;

  test.beforeAll(async ({ browser }: { browser: Browser }) => {
    page = await browser.newPage();
    await login(page);
  });

  test.beforeEach(async () => {
    await page.unroute("**/api/processing/scan").catch(() => {});
    await page.goto("/");
    await page.evaluate(() => { localStorage.removeItem("js-processing-skip"); localStorage.removeItem("js-pipeline-skip"); });
    await page.reload();
    await expect(page.locator("#dash-scan")).toBeVisible({ timeout: 15_000 });
  });

  test.afterAll(async () => {
    await page.close();
  });

  test("the server answers the dialog and the scoped one, and a library it does not know is a 404", async () => {
    const res = await page.request.get("/api/processing/scan");
    expect(res.status()).toBe(200);
    const m = await res.json();
    expect(m.features.map((f: any) => f.id)).toEqual(["find", "metadata", "segments", "health", "subtitles", "suggestions", "music", "audiobooks", "direct_play"]);
    expect(m.features[0].locked).toBe(true);
    expect(m.title).toBe("Scan library");
    expect((await page.request.get("/api/processing/scan?library=no-such-library")).status()).toBe(404);
    expect((await page.request.post("/api/processing/scan", { data: { library: "no-such-library" } })).status()).toBe(404);
  });

  test("▶ Scan library opens the two panes with Find new & changed ticked and locked", async () => {
    await page.click("#dash-scan");
    await expect(dialog(page)).toBeVisible();
    await expect(dialog(page)).toHaveClass(/\bpfx\b/);
    await expect(dialog(page).locator(".sd-two .pick")).toHaveCount(9);
    await expect(dialog(page).locator("h2")).toHaveText("Scan library");
    const find = row(page, "Find new & changed");
    await expect(find.locator(".tick")).toHaveClass(/\block\b/);
    await expect(find.locator(".c")).toHaveText("always");
    await find.locator(".tick").click({ force: true });
    await expect(find.locator(".tick")).toHaveClass(/\block\b/);
    // the legend says what queues work means, once, with a way to Activity
    await expect(dialog(page).locator(".sd-legend")).toContainText("A part that queues work is not finished when the scan ends.");
    await expect(dialog(page).locator(".sd-legend a")).toHaveAttribute("href", "#/activity");
    // the pane on the right is the chosen feature's parts
    await row(page, "File health").click();
    await expect(dialog(page).locator(".right .sd-pane-head h3")).toHaveText("File health");
    await expect(dialog(page).locator(".right .sd-one")).toHaveCount(3);
  });

  test("Cancel starts nothing and says nothing about it", async () => {
    const bodies: any[] = [];
    await fakeStart(page, bodies);
    await page.click("#dash-scan");
    await expect(dialog(page)).toBeVisible();
    await dialog(page).locator('[data-act="cancel"]').click();
    await expect(dialog(page)).toHaveCount(0);
    expect(bodies).toHaveLength(0);
    await expect(page.locator(".pf-toast")).toHaveCount(0);
    await page.click("#dash-scan");
    await page.keyboard.press("Escape");
    await expect(dialog(page)).toHaveCount(0);
    expect(bodies).toHaveLength(0);
  });

  test("the full face opens the same dialog with Re-check everything on", async () => {
    await page.locator("#dash-scan-split .menu-btn").click();
    await page.click("#dash-scan-full");
    await expect(dialog(page)).toBeVisible();
    const sw = dialog(page).locator('[data-act="recheck"]');
    await expect(sw).toHaveClass(/\bon\b/);
    await expect(dialog(page).locator(".pf-all")).toContainText("every title in the library is read again");
    await expect(dialog(page).locator(".sd-top .pf-note")).toContainText("read again");
    await sw.click();
    await expect(sw).not.toHaveClass(/\bon\b/);
    await expect(dialog(page).locator(".pf-all")).toContainText("This is the normal scan.");
  });

  test("a part that cannot run is shown disabled with its reason and cannot be ticked", async () => {
    await page.click("#dash-scan");
    await row(page, "File health").click();
    // the fixture install has no measuring service, so Sound in step is part of the list and cannot be ticked
    const sound = dialog(page).locator(".right .sd-one", { hasText: "Sound in step with the picture" });
    await expect(sound).toHaveAttribute("aria-disabled", "true");
    await expect(sound.locator(".tick")).toHaveClass(/\bdis\b/);
    await expect(sound).toContainText("The measuring service is not set up. It cannot be ticked for one scan either.");
    await sound.click();
    await expect(sound.locator(".tick")).toHaveClass(/\bdis\b/);
    // it still counts among the three, so the counts stay honest
    await expect(row(page, "File health").locator(".c")).toContainText("2 of 3");
    // a feature with nothing mapped is listed and says what to do
    await expect(row(page, "Music").locator(".c")).toContainText("No library mapped");
    await row(page, "Music").click();
    await expect(dialog(page).locator(".right .pf-note")).toContainText("No music library is mapped");
    await expect(row(page, "Music").locator(".tick")).toHaveClass(/\bdis\b/);
  });

  test("unticking a part starts the scan without it, the toast names the skip and every page remembers it", async () => {
    const bodies: any[] = [];
    await fakeStart(page, bodies);
    await page.click("#dash-scan");
    await row(page, "File health").locator(".tick").click();
    await expect(row(page, "File health").locator(".tick")).not.toHaveClass(/\bon\b/);
    await expect(row(page, "File health").locator(".c")).toContainText("0 of 3");
    await dialog(page).locator('[data-act="start"]').click();
    await expect(dialog(page)).toHaveCount(0);
    expect(bodies).toHaveLength(1);
    expect(bodies[0].skip).toEqual(expect.arrayContaining(["verify_files", "check_track_lengths"]));
    expect(bodies[0].recheckAll ?? false).toBe(false);
    const toast = page.locator(".pf-toast").first();
    await expect(toast).toContainText("Scan started · skipped File health");
    await expect(toast).toContainText("It stays unticked until you tick it again.");

    // another page: the Library page's dialog shows the same untick, and says so
    await page.goto("/#/library");
    await expect(page.locator("#scan-btn")).toBeVisible();
    await page.click("#scan-btn");
    await expect(row(page, "File health").locator(".tick")).not.toHaveClass(/\bon\b/);
    await expect(row(page, "File health").locator(".c")).toContainText("0 of 3");
    await row(page, "File health").locator(".tick").click();   // tick it again
    await expect(row(page, "File health").locator(".tick")).toHaveClass(/\bon\b/);
    await dialog(page).locator('[data-act="start"]').click();
    await expect(dialog(page)).toHaveCount(0);
    expect(bodies[1].skip ?? []).not.toContain("verify_files");
    expect(await page.evaluate(() => localStorage.getItem("js-processing-skip"))).not.toContain("verify_files");
  });

  test("what the earlier dialog remembered is read once, as the same ids", async () => {
    await page.evaluate(() => localStorage.setItem("js-pipeline-skip", "check_subtitles"));
    await page.click("#dash-scan");
    await row(page, "Subtitles").click();
    await expect(row(page, "Subtitles").locator(".c")).toContainText("1 of 2");
  });

  test("the palette's Start full scan and Dashboard's Start new scan open it too", async () => {
    await page.keyboard.press("Control+k");
    await page.fill("#cmd-palette-input", "full scan");
    await page.locator(".cmdp-item", { hasText: "Start full scan" }).first().click();
    await expect(dialog(page)).toBeVisible();
    await expect(dialog(page).locator('[data-act="recheck"]')).toHaveClass(/\bon\b/);
    await dialog(page).locator('[data-act="cancel"]').click();
    await expect(dialog(page)).toHaveCount(0);
  });

  test("a library card's Scan opens it for that library and lists what is not there", async () => {
    await page.goto("/#/settings?tab=libraries");
    await page.reload();
    const scans = page.locator(".lib-scan-btn");
    await expect(page.locator("#sect-scanning")).toBeVisible();
    test.skip((await scans.count()) === 0, "no library is mapped in this install");
    await scans.first().click();
    await expect(dialog(page)).toBeVisible();
    await expect(dialog(page).locator("h2")).toHaveText(/^Scan .+/);
    await expect(dialog(page).locator("h2")).not.toHaveText("Scan library");
    await expect(dialog(page).locator(".pill-row .chip")).toBeVisible();
    // nothing is hidden: nine rows, and the ones that are not in this library say so
    await expect(dialog(page).locator(".sd-two .pick")).toHaveCount(9);
    await expect(dialog(page).locator(".sd-two .pick.off .c").first()).toBeVisible();
    await dialog(page).locator('[data-act="cancel"]').click();
  });

  for (const theme of ["dark", "light"]) {
    test(`412 px is a drill-in that holds in the ${theme} theme`, async () => {
      await page.evaluate((t) => document.documentElement.setAttribute("data-theme", t), theme);
      await page.setViewportSize({ width: 412, height: 900 });
      try {
        await page.click("#dash-scan");
        await expect(dialog(page).locator(".sd-two.one")).toBeVisible();
        await expect(dialog(page).locator(".sd-two .right")).toHaveCount(0);
        // a feature with parts has a chevron; one without has nothing to open
        await expect(row(page, "Metadata & artwork").locator(".go")).toHaveText("›");
        await expect(row(page, "Audiobooks").locator(".go")).toHaveCount(0);
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
        expect(await dialog(page).locator(".pf-dlg").evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true);
        await row(page, "File health").click();
        await expect(dialog(page).locator(".sd-back")).toBeVisible();
        await expect(dialog(page).locator("h2")).toHaveText("File health");
        await expect(dialog(page).locator(".sd-one-pane .sd-one")).toHaveCount(3);
        await dialog(page).locator(".sd-one-pane .sd-one").first().click();   // untick Read files end to end
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
        await dialog(page).locator('[data-act="back"]').click();
        await expect(dialog(page).locator("h2")).toHaveText("Scan library");
        // Back keeps what was ticked in the parts
        await expect(row(page, "File health").locator(".c")).toContainText("1 of 3");
        await dialog(page).locator('[data-act="cancel"]').click();
      } finally {
        await page.setViewportSize({ width: 1280, height: 720 });
        await page.evaluate(() => document.documentElement.removeAttribute("data-theme"));
      }
    });
  }
});
