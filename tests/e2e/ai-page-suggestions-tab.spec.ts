import { test, expect, Browser, BrowserContext, Page } from "@playwright/test";

/**
 * DEB-1253 (FR-1..FR-8) — Suggestions as a tab on the admin's AI page while an AI key is set, and each group's own
 * *Show {n} more* on both homes.
 *
 * The e2e stack has no AI key and no Seerr, so `GET /api/ai/page` and the `/api/suggestions*` routes are stubbed with
 * `page.route`. The page renders server-pushed state only, so the stubs are the whole server. Stand-in titles and
 * viewer names only. A request that gets no HTTP response (FR-7) is a stubbed route that aborts.
 *
 * One login, then a context per viewport carrying its storage state (the login rate limiter is shared across the
 * whole run, like shell-layout.spec.ts).
 */
const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";

const FILM = (id: number, title: string, cluster: string, shown: boolean) => ({
  tmdb_id: id,
  title,
  year: 2001,
  cluster,
  because: [{ viewer: "Stand-in viewer", verb: "finished", titles: ["Stand-in earlier film"] }],
  viewers: 1,
  shown,
});
// Group A has two weaker matches, group B none: the control appears on A alone.
const PAGE = {
  built: true,
  built_at: 1_700_000_000,
  seerr_ok: true,
  finished_films: 3,
  viewers: [{ name: "Stand-in viewer", finished: 3 }],
  clusters: [
    { name: "Stand-in group A", shown: 2, more: 2, finished: 3 },
    { name: "Stand-in group B", shown: 1, more: 0, finished: 2 },
  ],
  items: [
    FILM(101, "Stand-in film one", "Stand-in group A", true),
    FILM(102, "Stand-in film two", "Stand-in group A", true),
    FILM(105, "Stand-in film five", "Stand-in group B", true),
    FILM(103, "Stand-in film three", "Stand-in group A", false),
    FILM(104, "Stand-in film four", "Stand-in group A", false),
  ],
  dismissed_count: 1,
};
const DISMISSED = [{ tmdb_id: 201, title: "Stand-in dismissed film", year: 1999, reason: "not_interested", who: "Stand-in viewer", at: 1_700_000_000, in_seerr: true }];

type Server = { tabs: string[] | null; down: boolean; rebuilds: number; downloads: number; dismissals: number };

async function login(page: Page) {
  await page.goto("/login");
  await page.fill('[id="username"], input[name="username"], input[type="text"]', JF_USER);
  await page.fill('[id="password"], input[name="password"], input[type="password"]', JF_PASS);
  await page.click('button[type="submit"], button:has-text("Sign in"), button:has-text("Login")');
  await expect(page.locator('.statgrid, h1:has-text("Dashboard")').first()).toBeVisible({ timeout: 15_000 });
}

/** A page whose server is [st]: `tabs` null is a 404 from /api/ai/page (no key), `down` is no HTTP response at all. */
async function newPage(ctx: BrowserContext, st: Server): Promise<Page> {
  const page = await ctx.newPage();
  await page.route(/\/api\/ai\/page$/, (r) => (st.tabs ? r.fulfill({ json: { tabs: st.tabs } }) : r.fulfill({ status: 404 })));
  await page.route(/\/api\/suggestions\/summary/, (r) => r.fulfill({ json: { waiting: 2, new_count: 0, top_clusters: [] } }));
  await page.route(/\/api\/suggestions\/dismissed/, (r) => r.fulfill({ json: DISMISSED }));
  await page.route(/\/api\/suggestions\/rebuild/, (r) => { st.rebuilds++; return r.fulfill({ json: { queued: "true" } }); });
  await page.route(/\/api\/suggestions\/request-options/, (r) => r.fulfill({ json: { seerr_ok: true, can_choose: true, servers: [] } }));
  await page.route(/\/api\/suggestions\/\d+\/download/, (r) => { st.downloads++; return r.fulfill({ json: { ok: true, sentence: "Requested" } }); });
  await page.route(/\/api\/suggestions\/\d+\/dismiss/, (r) => { st.dismissals++; return r.fulfill({ json: { ok: true, sentence: "Dismissed" } }); });
  await page.route(/\/api\/suggestions(\?.*)?$/, (r) => r.fulfill({ json: PAGE }));
  // Last registered runs first: while the server is "down" every API call gets no response, whatever else stubs it.
  await page.route(/\/api\//, (r) => (st.down ? r.abort("connectionfailed") : r.fallback()));
  return page;
}

const hash = (p: Page) => p.evaluate(() => location.hash);
const historyLength = (p: Page) => p.evaluate(() => history.length);
const navLink = (p: Page, href: string) => p.locator(`.app-side a.nav[href="${href}"]`);
/** The sidebar link is "shown" when the shell has not hidden it with display:none (the phone's drawer is off-canvas). */
const navShown = (p: Page, href: string) => navLink(p, href).evaluate((el) => (el as HTMLElement).style.display !== "none");
/** The page Back should return to: the Dashboard, which does not rewrite its own URL once it has loaded. */
async function openDashboard(p: Page) {
  await p.goto("/#/dashboard");
  await expect(p.locator("#page-content > *").first()).toBeAttached();
  await expect(p.locator("#page-content")).not.toContainText("Loading…");
}
const tile = (p: Page, id: number) => p.locator(`[data-tile="${id}"]`);

const VIEWPORTS = [
  { name: "phone 412px", viewport: { width: 412, height: 700 }, mobile: true },
  { name: "desktop", viewport: { width: 1366, height: 800 }, mobile: false },
];

test.describe.serial("AI page — Suggestions as a tab (DEB-1253)", () => {
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
      let st: Server;
      let page: Page;

      test.beforeAll(async ({ browser }: { browser: Browser }) => {
        ctx = await browser.newContext({ storageState: storage, viewport: vp.viewport, hasTouch: vp.mobile, isMobile: vp.mobile });
      });
      test.afterAll(async () => { await ctx.close(); });
      test.beforeEach(() => { st = { tabs: ["suggestions"], down: false, rebuilds: 0, downloads: 0, dismissals: 0 }; });
      test.afterEach(async () => { await page.close(); });

      test("3 — key set: the sidebar has AI and no Suggestions, the palette likewise (FR-3)", async () => {
        page = await newPage(ctx, st);
        await page.goto("/#/dashboard");
        await expect.poll(() => navShown(page, "#/ai")).toBe(true);
        expect(await navShown(page, "#/suggestions")).toBe(false);
        // AI sits below Activity, above the Setup group, and carries no count.
        const labels = await page.locator(".app-side > a.nav").allTextContents();
        expect(labels.map((l) => l.trim())).toEqual(["Dashboard", "Library", "Suggestions", "Activity", "AI"]);
        await expect(navLink(page, "#/ai").locator(".count")).toHaveCount(0);
        await page.keyboard.press("Control+k");
        await expect(page.locator("#cmd-palette-list")).toContainText("Go to AI");
        await expect(page.locator("#cmd-palette-list")).not.toContainText("Go to Suggestions");
      });

      test("2 — no key, Seerr connected: Suggestions is its own page, #/ai and #/ai?tab= fall back (FR-1, FR-4)", async () => {
        st.tabs = null;
        page = await newPage(ctx, st);
        await page.goto("/#/dashboard");
        await expect.poll(() => navShown(page, "#/suggestions")).toBe(true);
        expect(await navShown(page, "#/ai")).toBe(false);
        await page.keyboard.press("Control+k");
        await expect(page.locator("#cmd-palette-list")).toContainText("Go to Suggestions");
        await expect(page.locator("#cmd-palette-list")).not.toContainText("Go to AI");
        await page.keyboard.press("Escape");

        await page.goto("/#/suggestions");
        await expect(tile(page, 101)).toBeVisible({ timeout: 15_000 });
        await expect(page.locator("#sg-root h1")).toHaveText("Suggestions");
        await expect(page.locator("[data-sg-host]")).toHaveCount(0);

        await page.evaluate(() => { location.hash = "#/ai?tab=suggestions"; });
        await expect.poll(() => hash(page)).toBe("#/suggestions");
        await expect(tile(page, 101)).toBeVisible();

        await page.evaluate(() => { location.hash = "#/ai"; });
        await expect.poll(() => hash(page)).toBe("#/dashboard");
      });

      test("4 — #/ai opens on Suggestions with the toolbar under the tab strip, word for word (FR-5, FR-6.1)", async () => {
        page = await newPage(ctx, st);
        await page.goto("/#/ai");
        await expect(tile(page, 101)).toBeVisible({ timeout: 15_000 });
        await expect.poll(() => hash(page)).toBe("#/ai?tab=suggestions");
        await expect(page.locator("#ai-root .pagebar h1")).toHaveText("AI");
        await expect(page.locator("#ai-root .pagebar a")).toHaveText("Key & model → Settings");
        await expect(page.locator("#ai-root .pagebar a")).toHaveAttribute("href", "#/settings?tab=ai");
        await expect(page.locator("#ai-tabs span")).toHaveText(["Suggestions"]);
        await expect(page.locator("#ai-tabs span.on")).toHaveText("Suggestions");
        // No Suggestions h1 in the tab: the word is the tab's label.
        await expect(page.locator("#ai-suggestions h1")).toHaveCount(0);
        const tb = page.locator("#ai-suggestions .sg-tb");
        await expect(tb).toBeVisible();
        await expect(tb).toContainText("Seerr connected");
        await expect(tb).toContainText("shown of 5");
        await expect(tb.locator("#sg-dlink")).toHaveText("Dismissed (1)");
        await expect(tb.locator("#sg-rebuild")).toContainText("Rebuild now");
        // The tab strip sits above the toolbar.
        const strip = (await page.locator("#ai-tabs").boundingBox())!;
        const bar = (await tb.boundingBox())!;
        expect(strip.y + strip.height).toBeLessThanOrEqual(bar.y);
        // The old address lands on the tab, and Back from there does not return to #/suggestions.
        await openDashboard(page);
        await page.evaluate(() => { location.hash = "#/suggestions"; });
        await expect.poll(() => hash(page)).toBe("#/ai?tab=suggestions");
        await expect(tile(page, 101)).toBeVisible();
        await page.goBack();
        await expect.poll(() => hash(page)).toBe("#/dashboard");
        await page.evaluate(() => { location.hash = "#/suggestions?view=dismissed"; });
        await expect.poll(() => hash(page)).toBe("#/ai?tab=suggestions&view=dismissed");
        await expect(page.locator("#ai-suggestions .sg-dtable")).toBeVisible();
        // A refresh keeps the tab and the view.
        await page.reload();
        await expect(page.locator("#ai-suggestions .sg-dtable")).toBeVisible({ timeout: 15_000 });
        expect(await hash(page)).toBe("#/ai?tab=suggestions&view=dismissed");
      });

      test("5 — Dismissed opens in place with no history entry; Close returns; Back leaves the page (FR-6.2)", async () => {
        page = await newPage(ctx, st);
        await openDashboard(page);
        await page.evaluate(() => { location.hash = "#/ai?tab=suggestions"; });
        await expect(tile(page, 101)).toBeVisible({ timeout: 15_000 });
        const len = await historyLength(page);
        await page.locator("#sg-dlink").click();
        expect(await hash(page)).toBe("#/ai?tab=suggestions&view=dismissed");
        await expect(page.locator("#sg-dlink")).toHaveClass(/\bon\b/);
        await expect(page.locator("#ai-suggestions .sg-dtable")).toBeVisible();
        await expect(page.locator("#ai-suggestions .sg-dtable")).toContainText("Stand-in dismissed film");
        await expect(page.locator("#sg-main")).toBeHidden();
        await expect(page.locator("#ai-suggestions .sg-tb")).toBeVisible();
        expect(await historyLength(page)).toBe(len);
        // Narrow: the table stacks (round 5's rule, scoped to the tab).
        if (vp.mobile) await expect(page.locator("#ai-suggestions .sg-dtable thead")).toBeHidden();

        await page.locator("[data-dclose]").click();
        expect(await hash(page)).toBe("#/ai?tab=suggestions");
        await expect(tile(page, 101)).toBeVisible();
        await expect(page.locator("#sg-dlink")).not.toHaveClass(/\bon\b/);
        expect(await historyLength(page)).toBe(len);

        await page.locator("#sg-dlink").click();
        await expect(page.locator("#ai-suggestions .sg-dtable")).toBeVisible();
        await page.goBack();
        await expect.poll(() => hash(page)).toBe("#/dashboard");
      });

      test("6 — each group has its own Show {n} more, and it adds that group's weaker matches only (FR-8)", async () => {
        page = await newPage(ctx, st);
        await page.goto("/#/ai");
        await expect(tile(page, 101)).toBeVisible({ timeout: 15_000 });
        await expectShowMore(page);
      });

      test("6b — the same on the no-key page (FR-8)", async () => {
        st.tabs = null;
        page = await newPage(ctx, st);
        await page.goto("/#/suggestions");
        await expect(tile(page, 101)).toBeVisible({ timeout: 15_000 });
        await expectShowMore(page);
        // No list-foot control and no fold line survive.
        await expect(page.locator("#sg-showmore, .sg-fold")).toHaveCount(0);
      });

      test("7 — losing the server shows the strip above the tabs and parks the three actions; it clears on its own (FR-7)", async () => {
        page = await newPage(ctx, st);
        await page.goto("/#/ai");
        await expect(tile(page, 101)).toBeVisible({ timeout: 15_000 });
        await expect(page.locator("#ai-offl")).toBeHidden();

        st.down = true;
        await page.locator("#sg-rebuild").click();
        await expect(page.locator("#ai-offl")).toBeVisible();
        await expect(page.locator("#ai-offl")).toContainText("Connection to this server lost · reconnecting.");
        const strip = (await page.locator("#ai-offl").boundingBox())!;
        const tabs = (await page.locator("#ai-tabs").boundingBox())!;
        expect(strip.y + strip.height).toBeLessThanOrEqual(tabs.y);
        for (const sel of ["[data-dl]", "[data-no]", "#sg-rebuild"]) {
          const pe = await page.locator(`#ai-suggestions ${sel}`).first().evaluate((el) => getComputedStyle(el).pointerEvents);
          expect(pe, sel).toBe("none");
        }
        // Even a forced press sends nothing.
        await page.locator('[data-dl="101"]').dispatchEvent("click");
        await page.locator("#sg-rebuild").dispatchEvent("click");
        expect(st.downloads).toBe(0);
        expect(st.rebuilds).toBe(0);
        await expect(page.locator("#sg-dlroot .sg-dl")).toHaveCount(0);

        st.down = false;
        await expect(page.locator("#ai-offl")).toBeHidden({ timeout: 12_000 });
        await expect(tile(page, 101)).toBeVisible();
        await page.locator("#sg-rebuild").click();
        await expect.poll(() => st.rebuilds).toBe(1);
      });
    });
  }

  test("5b — a key saved or cleared in Settings swaps the sidebar without a reload (FR-3)", async ({ browser }: { browser: Browser }) => {
    const ctx = await browser.newContext({ storageState: storage, viewport: VIEWPORTS[1].viewport });
    const st: Server = { tabs: null, down: false, rebuilds: 0, downloads: 0, dismissals: 0 };
    const page = await newPage(ctx, st);
    // Saving PUTs the config (and may PUT the metadata providers): stubbed, so the shared e2e stack's config is never written.
    let saves = 0;
    await page.route(/\/api\//, (r) => {
      if (r.request().method() !== "PUT") return r.fallback();
      if (/\/api\/config$/.test(r.request().url())) {
        saves++;
        st.tabs = saves % 2 === 1 ? ["suggestions"] : null; // the first save sets a key, the second clears it
      }
      return r.fulfill({ status: 204 });
    });
    try {
      await page.goto("/#/settings?tab=ai");
      await expect.poll(() => navShown(page, "#/suggestions")).toBe(true);
      expect(await navShown(page, "#/ai")).toBe(false);
      await page.click("#save-settings");
      await expect.poll(() => navShown(page, "#/ai")).toBe(true);
      expect(await navShown(page, "#/suggestions")).toBe(false);
      await page.click("#save-settings");
      await expect.poll(() => navShown(page, "#/suggestions")).toBe(true);
      expect(await navShown(page, "#/ai")).toBe(false);
    } finally {
      await ctx.close();
    }
  });
});

/** Group A has two weaker matches, group B none: A alone ends with a control, with its own count. */
async function expectShowMore(page: Page) {
  const groups = page.locator(".sg-bucket");
  await expect(groups).toHaveCount(2);
  // Each group is on the page once.
  await expect(page.locator(".sg-bh h3")).toHaveText(["Stand-in group A", "Stand-in group B"]);
  const a = groups.nth(0);
  const b = groups.nth(1);
  await expect(a.locator("[data-gmore]")).toHaveText("Show 2 more");
  await expect(a).toContainText("weaker matches from the same build");
  await expect(b.locator("[data-gmore]")).toHaveCount(0);
  await expect(tile(page, 103)).toHaveCount(0);

  await a.locator("[data-gmore]").click();
  // Exactly those tiles, at the end of that group's grid, after its shown tiles; the control goes.
  await expect(a.locator(".sg-tile")).toHaveCount(4);
  const ids = await a.locator(".sg-tile").evaluateAll((els) => els.map((e) => e.getAttribute("data-tile")));
  expect(ids).toEqual(["101", "102", "103", "104"]);
  await expect(a.locator("[data-gmore]")).toHaveCount(0);
  await expect(b.locator(".sg-tile")).toHaveCount(1);
  await expect(page.locator(".sg-bh h3")).toHaveText(["Stand-in group A", "Stand-in group B"]);
}
