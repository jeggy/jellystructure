import { test, expect, Page } from "@playwright/test";

/**
 * DEB-5933 (DEB-5709, FR-4) — the Library page's held-back line. A full scan that finds Jellyfin no longer lists most of the library flags
 * none of it, and `/api/triage/count` says so in `heldBack`. The line shows that and nothing of its own: *Flag them missing* asks the server,
 * a 503 shows the server's sentence under the text, and a success makes the page read the count again — which no longer carries the hold.
 *
 * The count and the flag route are a stub over the real answers (the hold is in the backend's memory, set by a scan); what the server does
 * with a hold, a flag and a failed re-read is proved by `JellyfinReadFailureTest` (linuxX64Test).
 */
const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";
const READ_FAILED = "Scan stopped: Jellyfin's library could not be read, so nothing was marked missing. The next scan tries again.";

async function login(page: Page) {
  await page.goto("/login");
  await page.fill('input[type="text"]', JF_USER);
  await page.fill('input[type="password"]', JF_PASS);
  await page.click('button[type="submit"], button:has-text("Sign in"), button:has-text("Login")');
  await expect(page.locator('.statgrid, h1:has-text("Dashboard")').first()).toBeVisible({ timeout: 15_000 });
}

test("a held-back scan shows one line on the Library page, whatever the filter, and Flag them missing releases it", async ({ page }: { page: Page }) => {
  let hold: any = { at: Math.floor(Date.now() / 1000) - 5 * 60, gone: 12, held: 23 };
  let flagAnswers: { status: number; json: any }[] = [{ status: 503, json: { error: READ_FAILED } }, { status: 200, json: { flagged: 12 } }];
  const flagCalls: string[] = [];

  await page.route("**/api/triage/count", async (route) => {
    const real = await route.fetch();
    const body = await real.json();
    await route.fulfill({ response: real, json: hold ? { ...body, heldBack: hold } : body });
  });
  await page.route("**/api/triage/held-back/flag", async (route) => {
    flagCalls.push(route.request().method());
    const answer = flagAnswers.shift()!;
    if (answer.status === 200) hold = null;
    await route.fulfill({ status: answer.status, json: answer.json });
  });

  await login(page);
  await page.goto("/#/library");

  const note = page.locator("#lib-held-back-note");
  await expect(note).toBeVisible();
  // The Live TV lineup banner's markup and classes: no CSS of its own.
  await expect(note).toHaveClass(/\bnote\b/);
  await expect(note.locator(".badge.warn")).toHaveText("Held back");
  await expect(note.locator(".tiny")).toContainText("Jellyfin stopped listing 12 of 23 titles 5m ago. Viewers still see them. If they really were removed, flag them missing.");
  await expect(note.locator("button.btn.sm")).toHaveText("Flag them missing");

  // Whatever filter is chosen, the line stays.
  await page.locator("#f-attention").click();
  await expect(note).toBeVisible();
  await page.locator("#f-all").click();

  // A failed re-read: the server's sentence under the text, the hold still shown.
  await page.locator("#lib-held-back-flag").click();
  await expect(page.locator("#lib-held-back-error")).toHaveText(READ_FAILED);
  await expect(note).toBeVisible();

  // A good one: the page reads the count again, which no longer carries the hold.
  await page.locator("#lib-held-back-flag").click();
  await expect(note).toHaveCount(0);
  expect(flagCalls).toEqual(["POST", "POST"]);
});

test("no hold, no line", async ({ page }: { page: Page }) => {
  await page.route("**/api/triage/count", async (route) => {
    const real = await route.fetch();
    await route.fulfill({ response: real, json: { ...(await real.json()), heldBack: null } });
  });
  await login(page);
  await page.goto("/#/library");
  await expect(page.locator("#poster-grid")).toBeVisible();
  await expect(page.locator("#lib-held-back-note")).toHaveCount(0);
});
