import { test, expect, Page, Browser } from "@playwright/test";

/**
 * Phase 304, amendment 2026-10-12 (FR-304-8 / FR-304-9) — *Playing now* on Users & devices: the
 * *Ended today* fold, closed on every load, and the count that says what is actually playing rather
 * than how many sessions exist. Both come from the owner's prod screenshot on DEB-757, where the
 * header read "4 playing" over one playing row and three *is offline · paused* ones, with today's
 * ended sessions expanded underneath.
 *
 * The session list is stubbed, not produced: a live `playback_session` needs a real Ravilo client
 * reporting playback from a real device, which this stack has none of, and the two requirements are
 * entirely about what the admin page DRAWS over the list it is handed (the amendment is explicit
 * that no backend behaviour changes). `/ws` is routed to a socket that never speaks for the same
 * reason — a `playback_sessions` push from the real server would replace the stub mid-test.
 *
 * Serial + one shared login: the app's login-rate-limiter (5 attempts/60s) is shared across the
 * whole Playwright run, so this file logs in once (same convention as bazarr-dashboard.spec.ts and
 * shell-layout.spec.ts). The reload case re-uses the session cookie, which survives a reload.
 */
const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";

const NOW = Date.now();

// One playing session, two paused-because-offline ones, two ended today. Titles/owners are
// deliberately synthetic — this repository is public (scripts/check-deanonymization.sh).
function sessionList() {
  const base = {
    ownerId: "u-fixture",
    kind: "film",
    targetKind: "ravilo",
    targetIcon: "tv",
    durationMs: 5_400_000,
    positionAt: NOW,
    createdAt: NOW - 20 * 60_000,
    updatedAt: NOW,
    events: [],
    ops: [],
  };
  return {
    serverNowMs: NOW,
    householdControl: false,
    sessions: [
      {
        ...base, id: "ses-live-1", revision: 1, ownerName: "Fixture Owner A",
        title: "Fixture Film One", targetId: "t-1", targetName: "Fixture Screen 1",
        state: "playing", positionMs: 600_000,
      },
      {
        ...base, id: "ses-live-2", revision: 1, ownerName: "Fixture Owner B",
        title: "Fixture Film Two", targetId: "t-2", targetName: "Fixture Screen 2",
        state: "paused", offline: true, positionMs: 300_000,
      },
      {
        ...base, id: "ses-live-3", revision: 1, ownerName: "Fixture Owner C",
        title: "Fixture Film Three", targetId: "t-3", targetName: "Fixture Screen 3",
        state: "paused", offline: true, positionMs: 120_000,
      },
      {
        ...base, id: "ses-ended-1", revision: 2, ownerName: "Fixture Owner A",
        title: "Fixture Film Ended One", targetId: "t-1", targetName: "Fixture Screen 1",
        state: "paused", positionMs: 5_400_000, endedAt: NOW - 90 * 60_000, endReason: "finished",
      },
      {
        ...base, id: "ses-ended-2", revision: 2, ownerName: "Fixture Owner B",
        title: "Fixture Film Ended Two", targetId: "t-2", targetName: "Fixture Screen 2",
        state: "paused", positionMs: 1_200_000, endedAt: NOW - 30 * 60_000, endReason: "stopped",
      },
    ],
  };
}

async function login(page: Page) {
  await page.goto("/login");
  await page.fill('input[type="text"]', JF_USER);
  await page.fill('input[type="password"]', JF_PASS);
  await page.click('button[type="submit"], button:has-text("Sign in"), button:has-text("Login")');
  await expect(page.locator('.statgrid, h1:has-text("Dashboard")').first()).toBeVisible({ timeout: 15_000 });
}

async function openUsersPage(page: Page) {
  await page.goto("/#/ravilo-users");
  // The fold header is the last thing the section paints, so waiting for it waits for the stub to
  // have arrived and been drawn.
  await expect(page.locator("tr.ses-fold")).toBeVisible({ timeout: 15_000 });
}

test.describe.serial("Playing now — Ended today fold and the playing count", () => {
  let page: Page;

  test.beforeAll(async ({ browser }: { browser: Browser }) => {
    page = await browser.newPage();
    // Routes outlive a reload, so they are installed once here.
    await page.routeWebSocket("**/ws", () => { /* accepted, never speaks: no server-pushed list */ });
    await page.route("**/api/tv/admin/playback/sessions", (route) =>
      route.fulfill({ contentType: "application/json", body: JSON.stringify(sessionList()) }));
    await login(page);
    await openUsersPage(page);
  });

  test.afterAll(async () => {
    await page.close();
  });

  // FR-304-8 — closed on load: the header carries the count, the ended rows are not in the table.
  test("Ended today is closed on load and says how many ended", async () => {
    await expect(page.locator("tr.ses-fold")).toHaveText("▸ Ended today · 2");
    await expect(page.locator("tr.ses-fold .ses-fg")).toHaveText("▸");
    await expect(page.locator('tr.ses-r[data-ses="ses-ended-1"]')).toHaveCount(0);
    await expect(page.locator('tr.ses-r[data-ses="ses-ended-2"]')).toHaveCount(0);
    await expect(page.getByText("Fixture Film Ended One")).toHaveCount(0);
    // The three live rows are unaffected by the fold.
    await expect(page.locator("#ses-list tr.ses-r")).toHaveCount(3);
  });

  // FR-304-8 — a click opens it, across the row's full width, and the glyph turns.
  test("a click on the header shows the ended rows and turns the glyph", async () => {
    await page.locator("tr.ses-fold td").click();
    await expect(page.locator("tr.ses-fold .ses-fg")).toHaveText("▾");
    await expect(page.locator("tr.ses-fold")).toHaveText("▾ Ended today · 2");
    await expect(page.locator('tr.ses-r[data-ses="ses-ended-1"]')).toBeVisible();
    await expect(page.locator('tr.ses-r[data-ses="ses-ended-2"]')).toBeVisible();
    await expect(page.locator("#ses-list tr.ses-r")).toHaveCount(5);
  });

  // FR-304-8 — the open choice lasts the visit only: never stored, so a reload is closed again.
  test("a reload closes it again", async () => {
    await page.reload();
    await expect(page.locator("tr.ses-fold")).toBeVisible({ timeout: 15_000 });
    await expect(page.locator("tr.ses-fold .ses-fg")).toHaveText("▸");
    await expect(page.locator('tr.ses-r[data-ses="ses-ended-1"]')).toHaveCount(0);
    await expect(page.locator("#ses-list tr.ses-r")).toHaveCount(3);
  });

  // FR-304-8 — and a fresh visit to the page is a fresh load: leaving and coming back closes it too
  // (the open flag lives beside `pnOpen`, which outlives a route change on its own).
  test("leaving the page and coming back closes it again", async () => {
    await page.locator("tr.ses-fold td").click();
    await expect(page.locator("tr.ses-fold .ses-fg")).toHaveText("▾");
    await page.goto("/#/dashboard");
    await expect(page.locator("#ses-list")).toHaveCount(0);
    await openUsersPage(page);
    await expect(page.locator("tr.ses-fold .ses-fg")).toHaveText("▸");
    await expect(page.locator("#ses-list tr.ses-r")).toHaveCount(3);
  });

  // FR-304-9 — one playing, two offline-paused: "1 playing · 2 paused", never "3 playing".
  test("the count says what is playing and what is paused", async () => {
    await expect(page.locator("#ses-count")).toHaveText("1 playing · 2 paused");
  });
});
