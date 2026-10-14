import { test, expect, Browser, BrowserContext, Page } from "@playwright/test";

/**
 * Phase 274 FR-274-18 (DEB-1200, acceptance 18) — every control in Suggestions' Download dialog reaches its
 * handler, and a pick never moves the dialog.
 *
 * The e2e stack has no Seerr and the route renders without a gate, so the page's three calls are stubbed with
 * `page.route`: `GET /api/suggestions`, `GET /api/suggestions/request-options` and
 * `POST /api/suggestions/{id}/download`. The page renders server-pushed state only, so the stubs are the whole
 * server. Stand-in titles and viewer names only. The POSTs are counted: nothing here may reach a real Seerr.
 *
 * One login, then a context per viewport carrying its storage state (the login rate limiter is shared across
 * the whole run, like shell-layout.spec.ts).
 */
const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";

const PROFILES = Array.from({ length: 10 }, (_, i) => ({ id: i + 1, name: `Stand-in profile ${i + 1}` }));
const FILM = (id: number, title: string) => ({
  tmdb_id: id,
  title,
  year: 2001,
  cluster: "Stand-in group",
  because: [{ viewer: "Stand-in viewer", verb: "finished", titles: ["Stand-in earlier film"] }],
  viewers: 1,
  shown: true,
});
const PAGE = {
  built: true,
  built_at: 1_700_000_000,
  seerr_ok: true,
  finished_films: 3,
  viewers: [{ name: "Stand-in viewer", finished: 3 }],
  clusters: [{ name: "Stand-in group", shown: 2, more: 0, finished: 3 }],
  items: [FILM(101, "Stand-in film one"), FILM(102, "Stand-in film two")],
  dismissed_count: 0,
};
const OPTIONS = {
  seerr_ok: true,
  can_choose: true,
  servers: [
    {
      id: 7,
      name: "Stand-in Radarr",
      is_default: true,
      active_profile_id: 1,
      profiles: PROFILES,
      folders: ["/stand-in/films"],
      active_folder: "/stand-in/films",
    },
  ],
};

async function login(page: Page) {
  await page.goto("/login");
  await page.fill('[id="username"], input[name="username"], input[type="text"]', JF_USER);
  await page.fill('[id="password"], input[name="password"], input[type="password"]', JF_PASS);
  await page.click('button[type="submit"], button:has-text("Sign in"), button:has-text("Login")');
  await expect(page.locator('.statgrid, h1:has-text("Dashboard")').first()).toBeVisible({ timeout: 15_000 });
}

/** A page on the Suggestions route with the three calls stubbed; `posts` collects every download POST's body. */
async function openSuggestions(ctx: BrowserContext, posts: any[]): Promise<Page> {
  const page = await ctx.newPage();
  await page.route(/\/api\/suggestions\/summary/, (r) => r.fulfill({ json: { waiting: 2, new_count: 0, top_clusters: [] } }));
  await page.route(/\/api\/suggestions\/request-options/, (r) => r.fulfill({ json: OPTIONS }));
  await page.route(/\/api\/suggestions\/\d+\/download/, async (r) => {
    const id = Number(r.request().url().match(/suggestions\/(\d+)\/download/)![1]);
    posts.push({ id, ...(r.request().postDataJSON() ?? {}) });
    await r.fulfill({
      json: { ok: true, sentence: "Requested Stand-in film", item: { ...FILM(id, id === 101 ? "Stand-in film one" : "Stand-in film two"), state: "requested", requested_for: "Stand-in viewer" } },
    });
  });
  await page.route(/\/api\/suggestions(\?.*)?$/, (r) => r.fulfill({ json: PAGE }));
  await page.goto("/#/suggestions");
  await expect(page.locator('[data-tile="101"]')).toBeVisible({ timeout: 15_000 });
  return page;
}

const dialog = (p: Page) => p.locator("#sg-dlroot .sg-dl");
const row = (p: Page, id: number) => p.locator(`[data-dlprof="${id}"]`);
const scrollTop = (p: Page) => dialog(p).evaluate((el) => el.scrollTop);

async function openDialog(page: Page, tmdbId = 101) {
  await page.locator(`[data-dl="${tmdbId}"]`).tap();
  await expect(dialog(page)).toBeVisible();
  await expect(row(page, 10)).toHaveCount(1); // the options have arrived
}

const VIEWPORTS = [
  { name: "phone 412px", viewport: { width: 412, height: 700 }, mobile: true },
  { name: "desktop", viewport: { width: 1366, height: 800 }, mobile: false },
];

test.describe.serial("Suggestions — the Download dialog answers its controls (FR-274-18)", () => {
  let storage: Awaited<ReturnType<BrowserContext["storageState"]>>;

  test.beforeAll(async ({ browser }: { browser: Browser }) => {
    const ctx = await browser.newContext();
    await login(await ctx.newPage());
    storage = await ctx.storageState();
    await ctx.close();
  });

  for (const vp of VIEWPORTS) {
    test.describe(vp.name, () => {
      let ctx: BrowserContext;
      let posts: any[];
      let page: Page;

      test.beforeAll(async ({ browser }: { browser: Browser }) => {
        ctx = await browser.newContext({ storageState: storage, viewport: vp.viewport, hasTouch: true, isMobile: vp.mobile });
      });
      test.afterAll(async () => { await ctx.close(); });
      test.beforeEach(async () => {
        posts = [];
        page = await openSuggestions(ctx, posts);
      });
      test.afterEach(async () => { await page.close(); });

      test("1 — a pick selects, the primary follows, and the dialog does not scroll", async () => {
        await openDialog(page);
        // The dialog covers the viewport and the ten profiles overflow it.
        const back = await page.locator("#sg-dlroot .sg-dlb").boundingBox();
        expect(back).not.toBeNull();
        expect(back!.x).toBeCloseTo(0, 0);
        expect(back!.y).toBeCloseTo(0, 0);
        expect(back!.width).toBeCloseTo(vp.viewport.width, 0);
        expect(back!.height).toBeCloseTo(vp.viewport.height, 0);
        // Pre-selected: Seerr's active profile.
        await expect(row(page, 1)).toHaveAttribute("aria-checked", "true");

        const overflow = await dialog(page).evaluate((el) => (el.scrollHeight > el.clientHeight ? 1 : 0));
        if (vp.mobile) expect(overflow).toBe(1);
        await dialog(page).evaluate((el) => { el.scrollTop = el.scrollHeight; });
        const before = await scrollTop(page);
        if (vp.mobile) expect(before).toBeGreaterThan(0);

        await row(page, 10).tap();
        await expect(row(page, 10)).toHaveAttribute("aria-checked", "true");
        await expect(row(page, 10)).toHaveClass(/\bon\b/);
        await expect(row(page, 1)).toHaveAttribute("aria-checked", "false");
        await expect(page.locator("[data-dlgo]")).toContainText("Download in Stand-in profile 10");
        expect(await scrollTop(page)).toBe(before);

        await row(page, 9).tap();
        await expect(row(page, 9)).toHaveAttribute("aria-checked", "true");
        await expect(page.locator("[data-dlgo]")).toContainText("Download in Stand-in profile 9");
        expect(await scrollTop(page)).toBe(before);
        expect(posts).toHaveLength(0);
      });

      test("2 — Cancel, the backdrop and Escape each close it and send nothing", async () => {
        await openDialog(page);
        await page.locator("[data-dlcancel]").tap();
        await expect(page.locator("#sg-dlroot .sg-dl")).toHaveCount(0);

        await openDialog(page);
        await page.touchscreen.tap(4, 4); // the backdrop, outside the dialog
        await expect(page.locator("#sg-dlroot .sg-dl")).toHaveCount(0);

        await openDialog(page);
        await page.keyboard.press("Escape");
        await expect(page.locator("#sg-dlroot .sg-dl")).toHaveCount(0);

        // Give a stray request time to show up.
        await page.waitForTimeout(500);
        expect(posts).toHaveLength(0);
      });

      test("3 — the primary sends one POST with the picked profile and server, closes, and the tile says Requested", async () => {
        await openDialog(page);
        await row(page, 4).tap();
        await expect(row(page, 4)).toHaveAttribute("aria-checked", "true");
        await page.locator("[data-dlgo]").tap();

        await expect(page.locator("#sg-dlroot .sg-dl")).toHaveCount(0);
        await expect(page.locator('[data-tile="101"] .badge')).toHaveText("Requested");
        expect(posts).toHaveLength(1);
        expect(posts[0]).toMatchObject({ id: 101, server_id: 7, profile_id: 4 });
      });

      test("4 — opened and closed three times, one press is still one POST", async () => {
        for (let i = 0; i < 3; i++) {
          await openDialog(page);
          await page.locator("[data-dlcancel]").tap();
          await expect(page.locator("#sg-dlroot .sg-dl")).toHaveCount(0);
        }
        await openDialog(page);
        await page.locator("[data-dlgo]").tap();
        await expect(page.locator('[data-tile="101"] .badge')).toHaveText("Requested");
        await page.waitForTimeout(500);
        expect(posts).toHaveLength(1);
      });

      test("5 — leaving the page with the dialog open leaves no overlay on the next page", async () => {
        await openDialog(page);
        // The modal backdrop sits over the sidebar, so press the link the way a keyboard would reach it.
        await page.evaluate(() => (document.querySelector('a.nav[href="#/activity"]') as HTMLElement).click());
        await expect(page).toHaveURL(/#\/activity/);
        await expect(page.locator("#sg-dlroot")).toHaveCount(0);
        await expect(page.locator(".sg-dlb")).toHaveCount(0);
        expect(posts).toHaveLength(0);
      });
    });
  }
});
