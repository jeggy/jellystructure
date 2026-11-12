import { test, expect, Browser, BrowserContext, Page } from "@playwright/test";

/**
 * DEB-2477 (DEB-2474, FR-1, FR-2.5, FR-9, FR-10, FR-16) — the Fetchers tab on the admin's AI page: the list, the open row with its
 * Keep control and the server's delete preview, *Site stopped* and *Resume site*, and *Not on TMDB yet*.
 *
 * The e2e stack has no AI key and no fetcher, so `GET /api/ai/page` and the `/api/autofetch*` routes are stubbed with `page.route`.
 * The page renders server-pushed state only, so the stubs are the whole server. Stand-in names only (`Royndin`, `Dagbók`,
 * `varpid.example.fo`); no library title.
 */
const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";

const RUN = (summary: string, lines: { kind: string; text: string }[] = []) => ({ startedAt: 1_800_000_000, finishedAt: 1_800_000_060, trigger: "schedule", status: "ok", summary, lines });
const RECIPE = [
  { key: "#", text: "recipe v1 · Dagbók · page only, no TMDB id · read-only here" },
  { key: "source", text: "https://varpid.example.fo/sendingar/dagbok", comment: "hosts fixed at save; nothing else may be named" },
  { key: "retention", text: "newest 12 · also delete once it leaves the page" },
];
const DAGBOK = {
  id: "f-dagbok", name: "Dagbók", pageUrl: "https://varpid.example.fo/sendingar/dagbok", site: "varpid.example.fo", pageOnly: true, seriesPath: "/tv/Dagbók", proven: false,
  schedule: "daily@04:00", enabled: true, retention: { mode: "newest", keep: 12, leaves_page: true }, state: "ok", stateReason: "", siteStopped: false, numbering: "date", running: false,
  lastRunAt: 1_800_000_060, nextRunAt: 1_800_086_400, lastRun: RUN("+1 · S2026E300 (1080p, 1.9 GB) · −1 by retention"), placed: 12,
  runs: [RUN("+1 · S2026E300 (1080p, 1.9 GB) · −1 by retention", [{ kind: "placed", text: "S2026E300 (1080p, 1.9 GB)" }, { kind: "deleted", text: "−1 · S2026E288 (2026-10-15) · newest 12 kept" }])],
  recipe: RECIPE, pattern: "By date", quality: "highest HLS", queued: 0,
};
const ROYNDIN = { ...DAGBOK, id: "f-royndin", name: "Royndin", tmdbId: 9000001, pageOnly: false, retention: { mode: "all", keep: 12, leaves_page: false }, numbering: "source", pattern: "title", runs: [RUN("no new episodes")], lastRun: RUN("no new episodes"), queued: 1 };
const QUEUED = { id: 7, fetcherId: "f-royndin", series: "Royndin", tmdbId: 9000001, season: 2026, episode: 3, code: "S2026E03", pageLabel: "Royndin 2026 · 3. partur", state: "queued", filedAt: 1_800_000_000, runtimeSec: 2652, title: "Royndin 2026 · 3. partur", airDate: "2026-03-10", stills: [372, 1118, 1865] };

type Server = { overview: unknown; patches: unknown[]; previews: unknown[]; resumes: string[]; submits: number[]; runs: string[] };

const base = (over: Partial<{ enabled: boolean; fetchers: unknown[]; sites: unknown[]; queue: unknown[] }> = {}) =>
  ({ enabled: true, fetchers: [DAGBOK, ROYNDIN], sites: [{ site: "varpid.example.fo", stopped: false, fetchers: 2 }], queue: [QUEUED], ...over });

async function login(page: Page) {
  await page.goto("/login");
  await page.fill('[id="username"], input[name="username"], input[type="text"]', JF_USER);
  await page.fill('[id="password"], input[name="password"], input[type="password"]', JF_PASS);
  await page.click('button[type="submit"], button:has-text("Sign in"), button:has-text("Login")');
  await expect(page.locator('.statgrid, h1:has-text("Dashboard")').first()).toBeVisible({ timeout: 15_000 });
}

async function newPage(ctx: BrowserContext, st: Server): Promise<Page> {
  const page = await ctx.newPage();
  await page.route(/\/api\/ai\/page$/, (r) => r.fulfill({ json: { tabs: ["support", "fetchers"] } }));
  await page.route(/\/api\/autofetch\/[^/]+\/retention\/preview$/, (r) => { st.previews.push(r.request().postDataJSON()); return r.fulfill({ json: { deletes: 4, keeps: 8, onPage: 12 } }); });
  await page.route(/\/api\/autofetch\/sites\/[^/]+\/resume$/, (r) => { st.resumes.push(r.request().url().split("/").slice(-2, -1)[0]); return r.fulfill({ json: { resumed: true } }); });
  await page.route(/\/api\/autofetch\/queue\/\d+\/submitted$/, (r) => { st.submits.push(7); return r.fulfill({ json: { submitted: true } }); });
  await page.route(/\/api\/autofetch\/queue\/\d+\/still\/\d+$/, (r) => r.fulfill({ status: 404 }));
  await page.route(/\/api\/autofetch\/[^/]+\/run$/, (r) => { st.runs.push(r.request().url()); return r.fulfill({ status: 202, json: { started: true } }); });
  await page.route(/\/api\/autofetch\/f-[a-z]+$/, (r) => { st.patches.push(r.request().postDataJSON()); return r.fulfill({ json: {} }); });
  await page.route(/\/api\/autofetch$/, (r) => r.fulfill({ json: st.overview }));
  return page;
}

const VIEWPORTS = [
  { name: "phone 412px", viewport: { width: 412, height: 800 }, mobile: true },
  { name: "desktop", viewport: { width: 1366, height: 900 }, mobile: false },
];

test.describe.serial("AI page — the Fetchers tab (DEB-2477)", () => {
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
      test.beforeEach(() => { st = { overview: base(), patches: [], previews: [], resumes: [], submits: [], runs: [] }; });
      test.afterEach(async () => { await page.close(); });

      const open = async () => {
        page = await newPage(ctx, st);
        await page.goto("/#/ai?tab=fetchers");
        await expect(page.locator("#ai-fetchers")).toBeVisible();
      };

      test("1 — the tab lists the fetchers in ai.css's own components, with the schedule and the keeps note", async () => {
        await open();
        const panel = page.locator("#ai-fetchers");
        await expect(panel).toHaveClass(/afx/);
        await expect(panel.locator(".af-tbl")).toBeVisible();
        expect(await panel.locator(".af-tbl").evaluate((el) => getComputedStyle(el).borderCollapse)).toBe("collapse");
        await expect(panel.locator("tr.af-row")).toHaveCount(2);
        await expect(panel.locator("tr.af-row").first()).toContainText("Dagbók");
        await expect(panel.locator("tr.af-row").first()).toContainText("Daily at 04:00");
        await expect(panel.locator("tr.af-row").first().locator(".sched .keep")).toHaveText("keeps newest 12");
        await expect(panel.locator("tr.af-row").nth(1).locator(".sched .keep")).toHaveCount(0);
        await expect(panel.locator("tr.af-row .badge.ok").first()).toHaveText("Up to date");
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
      });

      test("2 — the open row has the recipe read-only, the last runs with their lines, and Keep with the server's preview (FR-16.1)", async () => {
        await open();
        await page.locator("tr.af-row").first().click();
        const det = page.locator("tr.af-det");
        await expect(det.locator(".af-script")).toContainText("read-only here");
        await expect(det.locator(".af-sub.add")).toContainText("S2026E300");
        await expect(det.locator(".af-sub.del")).toContainText("newest 12 kept");
        await expect(det.locator(".af-keep .seg span.on")).toContainText("The newest");
        await expect(det.locator(".af-tick.on")).toBeVisible();
        await expect(det.locator("[data-af-save]")).toHaveClass(/af-dis/);
        await det.locator("[data-af-keepn]").fill("8");
        await det.locator("[data-af-keepn]").press("Tab");
        await expect(det.locator(".af-prev")).toContainText("Saving this deletes 4 episodes at the next run");
        expect(st.previews.at(-1)).toEqual({ retention: { mode: "newest", keep: 8, leaves_page: true } });
        await expect(det.locator(".hint").last()).toContainText("Keeps 8 of the 12 on the page");
        await det.locator("[data-af-save]").click();
        await expect.poll(() => st.patches.length).toBe(1);
        expect(st.patches[0]).toEqual({ retention: { mode: "newest", keep: 8, leaves_page: true } });
      });

      test("2b — DEB-4402: the Check is the shared schedule control — Mon + Tue is saved as the cron, and the 6 h floor holds Save", async () => {
        await open();
        await page.locator("tr.af-row").first().click();
        const det = page.locator("tr.af-det");
        const sch = det.locator('[data-sch-key="af:f-dagbok"]');
        // the old words open in the simple modes; a Check is never off
        await expect(sch.locator('[data-sch="unit"]')).toHaveValue("days");
        await expect(sch.locator('[data-sch="at"]')).toHaveValue("04:00");
        await expect(sch.locator('option[value="off"]')).toHaveCount(0);
        await expect(sch.locator(".sch-runs li")).toHaveCount(5, { timeout: 5_000 });
        await expect(det.locator("[data-af-save]")).toHaveClass(/af-dis/);
        // under the floor the server refuses, shows no runs, and Save is held with the reason
        await sch.locator('[data-sch="unit"]').selectOption("hours");
        await expect(sch.locator(".sch-err")).toContainText("6 hours", { timeout: 5_000 });
        await expect(sch.locator(".sch-runs")).toHaveCount(0);
        await expect(det.locator("[data-af-save]")).toHaveClass(/af-dis/);
        await expect(det.locator("[data-sch-held]")).toBeVisible();
        // Monday and Tuesday at 04:00
        await sch.locator('[data-sch="unit"]').selectOption("weeks");
        await sch.locator('[data-sch-day="2"]').click();
        await expect(sch.locator("[data-sch-day].on")).toHaveCount(2);
        await expect(sch.locator(".sch-runs li")).toHaveCount(5, { timeout: 5_000 });
        await expect(det.locator("[data-sch-held]")).toBeHidden();
        await expect(det.locator("[data-af-save]")).not.toHaveClass(/af-dis/);
        await det.locator("[data-af-save]").click();
        await expect.poll(() => st.patches.length).toBe(1);
        expect(st.patches[0]).toEqual({ schedule: "0 4 * * 1,2" });
      });

      test("3 — All episodes says nothing is ever deleted and shows no leaves-the-page tick", async () => {
        await open();
        await page.locator("tr.af-row").nth(1).click();
        const det = page.locator("tr.af-det");
        await expect(det.locator(".af-keep .seg span.on")).toHaveText("All episodes");
        await expect(det.locator("[data-af-leaves]")).toHaveCount(0);
        await expect(det).toContainText("Every episode stays. Nothing is ever deleted.");
      });

      test("4 — a stopped site is on every fetcher on it, with the band above the recipe and Resume site (FR-2.5)", async () => {
        const stopped = (f: object) => ({ ...f, siteStopped: true, siteStoppedReason: "the site answered 403 on a page that worked before", siteStoppedAt: 1_800_000_100 });
        st.overview = base({ fetchers: [stopped(DAGBOK), stopped(ROYNDIN)], sites: [{ site: "varpid.example.fo", stopped: true, reason: "x", stoppedAt: 1_800_000_100, fetchers: 2 }], queue: [] });
        await open();
        await expect(page.locator("tr.af-row .badge.bad")).toHaveCount(2);
        await expect(page.locator("tr.af-row").first()).toContainText("Site stopped");
        await expect(page.locator("tr.af-row [data-af-run]").first()).toHaveClass(/af-dis/);
        await page.locator("tr.af-row").first().click();
        const band = page.locator(".af-band");
        await expect(band).toContainText("Nothing runs on varpid.example.fo until you resume it.");
        await expect(band.locator(".mono").first()).toHaveText("403");
        const bandY = (await band.boundingBox())!.y;
        const recipeY = (await page.locator(".af-script").boundingBox())!.y;
        expect(bandY).toBeLessThan(recipeY);
        await band.locator("[data-af-resume]").click();
        await expect.poll(() => st.resumes).toEqual(["varpid.example.fo"]);
      });

      test("5 — Not on TMDB yet holds the page's own words, says what the page lacks, and Mark as submitted is a note", async () => {
        await open();
        const card = page.locator("#af-tmdb-card");
        await expect(card).toContainText("1 episode");
        await expect(card.locator(".tq-row")).toContainText("S2026E03");
        await expect(card.locator(".tq-row .badge").first()).toHaveText("source incomplete");
        await card.locator(".tq-row").click();
        await expect(card.locator(".tq-det")).toContainText("Royndin 2026 · 3. partur");
        await expect(card.locator(".tq-det")).toContainText("No overview found for this episode on the source page");
        await expect(card.locator(".tq-det")).toContainText("nothing is invented");
        await expect(card.locator(".tq-det")).toContainText("Three frames picked from the video");
        await expect(card.locator(".tq-still")).toHaveCount(3);
        await card.locator("[data-tq-submit]").click();
        await expect.poll(() => st.submits.length).toBe(1);
      });

      test("6 — Auto-fetch switched off keeps the tab and says why nothing runs", async () => {
        st.overview = base({ enabled: false });
        await open();
        await expect(page.locator("#ai-fetchers")).toContainText("Auto-fetch is switched off");
        await expect(page.locator("[data-af-runall]")).toHaveClass(/af-dis/);
      });

      test("7 — ai.css is served beside the other page stylesheets", async () => {
        page = await ctx.newPage();
        const res = await page.request.get("/ai.css");
        expect(res.status()).toBe(200);
        expect(res.headers()["content-type"]).toContain("text/css");
        expect(await res.text()).toContain(".afx .af-tbl");
      });
    });
  }
});
