import { test, expect, type Page } from "@playwright/test";
import { fakeAvPlayInitScript, waitForAvplayCall } from "./helpers/screen-cast";

// DEB-4660 (DEB-4654 FR-7) — a title's page on the Tizen full app, driven with keyboard events through the REAL
// ravilo-screen.js bundle. The backend is stood in for by page.route, so this file proves what the page does with the
// answers it gets (the label from the playstate, the write-through that waits for the server, the next-up episode, a
// season switch, what a play carries to the player), not what the server says.

const SCREEN_URL = process.env.RAVILO_SCREEN_CAST_URL ?? "http://localhost:8092";
const API = "http://app:9505";

const card = (id: string, title: string, kind = "MOVIE") => ({
  id, kind, title, year: 2024, genre: null, rating: null,
  poster_url: `/api/tv/image/${id}-poster`, backdrop_url: `/api/tv/image/${id}-backdrop`,
});
const movieDetail = {
  card: card("m1", "Paper Moon"), synopsis: "A con artist and a girl cross the plains.", runtime: 90, cast: [{ id: "p1", name: "Someone", role: "Lead", image_url: null }], related: [],
  logo_url: "/api/tv/image/m1-logo", rating_badge: { region: "DK", code: "11", tier: 2 },
  segments: { intro_start_ms: 40_000, intro_end_ms: 50_000 },
};
const episode = (id: string, n: number, title: string) => ({ id, episode_number: n, title, runtime: 40, overview: null, still_url: `/api/tv/image/${id}-still` });
const seriesDetail = (extra: object = {}) => ({
  card: card("sr1", "Paper Series", "SERIES"), synopsis: "Two seasons of reels.", cast: [], related: [], logo_url: null,
  seasons: [
    { index: 1, name: "Season 1", episodes: [episode("s1e1", 1, "Opening Reel"), episode("s1e2", 2, "Second Reel"), episode("s1e3", 3, "Third Reel")] },
    { index: 2, name: "Season 2", episodes: [episode("s2e1", 1, "Return Reel")] },
  ],
  ...extra,
});
const state = (extra: object = {}) => ({ resume_ms: 0, played: false, played_pct: 0, favorite: false, ...extra });
const ticket = (itemId: string) => ({
  jellyfin_base_url: API, access_token: "", item_id: itemId, container: "ts", direct_play: false,
  hls_url: `${API}/hls/${itemId}/master.m3u8`, start_position_ms: 0, expires_at: 9_999_999_999,
});

type Seen = { starts: any[]; favorites: any[]; played: any[] };
type Opts = {
  home?: object; movie?: object | null; series?: object | null; playstate?: Record<string, object>;
  favorite?: (body: any) => Promise<{ status: number; body?: object }>;
  played?: (body: any) => Promise<{ status: number; body?: object }>;
};

/** Home with one tile (the movie or the series); OK on it is the page. */
async function boot(page: Page, opts: Opts = {}): Promise<Seen> {
  const seen: Seen = { starts: [], favorites: [], played: [] };
  const tile = opts.series !== undefined ? card("sr1", "Paper Series", "SERIES") : card("m1", "Paper Moon");
  const feed = { heroes: [], channels: [], rows: [{ id: "new", title: "New Arrivals", kind: "NEWLY_ADDED", items: [tile] }],
    tile_shape: "POSTER", hero_height_pct: 48, focus_detail: "none" };
  await page.addInitScript(fakeAvPlayInitScript);
  await page.addInitScript(() => {
    localStorage.setItem("ravilo.screen.serverUrl", "http://app:9505");
    localStorage.setItem("ravilo.screen.profiles", JSON.stringify([{ userId: "viewer", displayName: "Test Viewer", token: "test-token" }]));
  });
  const json = (route: any, status: number, body: object = {}) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
  await page.route(`${API}/**`, async (route) => {
    const req = route.request();
    const path = new URL(req.url()).pathname;
    const body = req.postData() ? JSON.parse(req.postData()!) : null;
    if (req.method() === "OPTIONS") return route.fulfill({ status: 204 });
    if (path === "/api/tv/home") return json(route, 200, opts.home ?? feed);
    if (path === "/api/tv/movie/m1") return opts.movie === null ? json(route, 404) : json(route, 200, opts.movie ?? movieDetail);
    if (path === "/api/tv/series/sr1") return opts.series ? json(route, 200, opts.series) : json(route, 404);
    if (path === "/api/tv/playstate") return json(route, 200, opts.playstate ?? {});
    if (path === "/api/tv/favorite") {
      seen.favorites.push(body);
      const answer = opts.favorite ? await opts.favorite(body) : { status: 200, body: state({ favorite: body.favorite }) };
      return json(route, answer.status, answer.body ?? {});
    }
    if (path === "/api/tv/played") {
      seen.played.push(body);
      const answer = opts.played ? await opts.played(body) : { status: 200, body: { [body.item_id]: state({ played: body.played }) } };
      return json(route, answer.status, answer.body ?? {});
    }
    if (path === "/api/tv/playback/start") { seen.starts.push(body); return json(route, 200, ticket(body.item_id)); }
    if (path.startsWith("/api/tv/playback/")) return route.fulfill({ status: 204 });
    if (path.startsWith("/api/tv/image/")) return route.fulfill({ status: 204 });
    return json(route, 404);
  });
  await page.goto(SCREEN_URL);
  await expect(page.locator("#shell-body .home-feed")).toBeVisible();
  return seen;
}

const open = async (page: Page) => {
  await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
  await page.keyboard.press("Enter");
};
/** OK on the tile, then wait for the page: the remote only has something to press once it has drawn. */
const openPage = async (page: Page) => {
  await open(page);
  await expect(page.locator("#detail-play")).toHaveClass(/focused/);
};
const label = (page: Page, id: string) => page.locator(`#${id}`);

test.describe("DEB-4660 — a title's page on the Samsung TV (FR-7)", () => {
  test("a movie: the header, the server's rating badge, no people row, and Play when nothing has been watched", async ({ page }) => {
    await boot(page);
    await openPage(page);
    await expect(page.locator(".detail-title")).toHaveText("Paper Moon");
    await expect(page.locator(".detail-meta")).toContainText("2024");
    await expect(page.locator(".detail-meta")).toContainText("90 min");
    await expect(page.locator(".detail-badge")).toHaveText("DK11");
    await expect(page.locator(".detail-overview")).toContainText("A con artist");
    await expect(label(page, "detail-play")).toHaveText("Play");
    await expect(label(page, "detail-play")).toHaveClass(/focused/);          // focus lands on the primary action
    await expect(page.locator("#detail-start-over")).toHaveCount(0);          // Start over only with a resume point
    await expect(label(page, "detail-list")).toHaveText("+ My List");
    await expect(label(page, "detail-watched")).toHaveText("Mark Watched");
    await expect(page.locator("#shell-body")).not.toContainText("Someone");   // no people row in v1
    await expect(page.locator("#shell-body")).not.toContainText(/cast|play on/i);
  });

  test("Resume says the time left from the playstate, and Start over appears with it", async ({ page }) => {
    const seen = await boot(page, { playstate: { m1: state({ resume_ms: 1_800_000, played_pct: 0.33 }) } });
    await openPage(page);
    await expect(label(page, "detail-play")).toHaveText("Resume · 60 min left");
    await expect(label(page, "detail-start-over")).toHaveText("Start over");

    // Resume asks for no position: the server's playstate decides where it starts.
    await page.keyboard.press("Enter");
    await waitForAvplayCall(page, "open");
    expect(seen.starts).toHaveLength(1);
    expect(seen.starts[0].item_id).toBe("m1");
    expect(seen.starts[0].start_position_ms ?? 0).toBe(0);
    expect(seen.starts[0].start_over ?? false).toBe(false);
  });

  test("Start over plays from 0 and says so", async ({ page }) => {
    const seen = await boot(page, { playstate: { m1: state({ resume_ms: 1_800_000 }) } });
    await openPage(page);
    await page.keyboard.press("ArrowRight");
    await expect(label(page, "detail-start-over")).toHaveClass(/focused/);
    await page.keyboard.press("Enter");
    await waitForAvplayCall(page, "open");
    expect(seen.starts[0]).toMatchObject({ item_id: "m1", start_position_ms: 0, start_over: true });
  });

  test("My List shows the server's answer: a refusal leaves it as it was, and the button does not flip first", async ({ page }) => {
    let release: () => void = () => {};
    const held = new Promise<void>((resolve) => { release = resolve; });
    let calls = 0;
    const seen = await boot(page, {
      favorite: async (body) => {
        calls++;
        if (calls === 1) { await held; return { status: 200, body: state({ favorite: true }) }; }
        return { status: 404 };                                   // the server refuses the second write
      },
    });
    await openPage(page);
    await page.keyboard.press("ArrowRight");
    await expect(label(page, "detail-list")).toHaveClass(/focused/);
    await page.keyboard.press("Enter");
    await expect.poll(() => seen.favorites.length).toBe(1);
    expect(seen.favorites[0]).toEqual({ item_id: "m1", favorite: true });
    await expect(label(page, "detail-list")).toHaveText("+ My List");   // still the old state while the server thinks
    release();
    await expect(label(page, "detail-list")).toHaveText("− My List");

    await page.keyboard.press("Enter");
    await expect.poll(() => seen.favorites.length).toBe(2);
    expect(seen.favorites[1]).toEqual({ item_id: "m1", favorite: false });
    await expect.poll(() => calls).toBe(2);
    await expect(label(page, "detail-list")).toHaveText("− My List");   // refused: unchanged
    await expect(label(page, "detail-list")).toHaveClass(/focused/);
  });

  test("Mark watched is the server's answer: Play Again, no Start over, and a button to mark it unwatched", async ({ page }) => {
    const seen = await boot(page, { playstate: { m1: state({ resume_ms: 600_000 }) } });
    await openPage(page);
    await expect(label(page, "detail-start-over")).toBeVisible();
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("ArrowRight");
    await expect(label(page, "detail-watched")).toHaveClass(/focused/);
    await page.keyboard.press("Enter");
    await expect.poll(() => seen.played.length).toBe(1);
    expect(seen.played[0]).toMatchObject({ item_id: "m1", played: true });
    await expect(label(page, "detail-watched")).toHaveText("✓ Watched");
    await expect(label(page, "detail-play")).toHaveText("Play Again");
    await expect(page.locator("#detail-start-over")).toHaveCount(0);
  });

  test("Back from the page returns to the tile it was opened from", async ({ page }) => {
    await boot(page);
    await openPage(page);
    await expect(label(page, "detail-play")).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
  });

  test("a title the server does not have says so, and Back leaves", async ({ page }) => {
    await boot(page, { movie: null });
    await open(page);
    await expect(page.locator("#shell-body")).toHaveText("This isn't here any more");
    await page.keyboard.press("Escape");
    await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
  });

  test("a series: the next-up episode is the primary action, and it is never a watched one", async ({ page }) => {
    // s1e1 is watched, s1e2 is next up: the server names it.
    const seen = await boot(page, {
      series: seriesDetail(),
      playstate: { sr1: state({ continue_episode_id: "s1e2" }), s1e1: state({ played: true }) },
    });
    await openPage(page);
    await expect(page.locator(".detail-title")).toHaveText("Paper Series");
    await expect(label(page, "detail-play")).toHaveText("Resume · S01E02");
    await expect(label(page, "detail-play")).toHaveClass(/focused/);
    await expect(page.locator("#detail-watched")).toHaveCount(0);            // marking a whole series is not in FR-7
    await expect(page.locator(".detail-ep")).toHaveCount(3);                  // opens on the season holding it
    await expect(page.locator("#detail-ep-0 .detail-ep-check")).toHaveText("✓");

    await page.keyboard.press("Enter");
    const opened = await waitForAvplayCall(page, "open");
    expect(String(opened.call[1])).toContain("/hls/s1e2/");
    expect(seen.starts.map((s) => s.item_id)).toEqual(["s1e2"]);
    await waitForAvplayCall(page, "play", opened.index);
    await page.keyboard.press("Enter");                                       // the overlay shows the episode's own lines
    await expect(page.locator("#ov-kicker")).toHaveText("S01E02");
    await expect(page.locator("#ov-showname")).toHaveText("Paper Series");
  });

  test("a series nobody started says Play and plays its first episode; with no pointer it skips watched ones", async ({ page }) => {
    const seen = await boot(page, { series: seriesDetail() });
    await openPage(page);
    await expect(label(page, "detail-play")).toHaveText("Play · S01E01");
    await page.keyboard.press("Enter");
    await waitForAvplayCall(page, "open");
    expect(seen.starts.map((s) => s.item_id)).toEqual(["s1e1"]);
  });

  test("a series with the first episode watched and no pointer offers the first unwatched one", async ({ page }) => {
    await boot(page, { series: seriesDetail(), playstate: { s1e1: state({ played: true }) } });
    await openPage(page);
    await expect(label(page, "detail-play")).toHaveText("Resume · S01E02");
  });

  test("a finished series offers Start over · S01E01 when the server can clear it", async ({ page }) => {
    const watched = Object.fromEntries(["s1e1", "s1e2", "s1e3", "s2e1"].map((id) => [id, state({ played: true })]));
    const seen = await boot(page, { series: seriesDetail({ start_over: true }), playstate: watched });
    await openPage(page);
    await expect(label(page, "detail-play")).toHaveText("Start over · S01E01");
    await page.keyboard.press("Enter");
    await waitForAvplayCall(page, "open");
    expect(seen.starts[0]).toMatchObject({ item_id: "s1e1", start_over: true });
  });

  test("a season pill switches the episodes, and an episode tile plays that episode with the one after it", async ({ page }) => {
    const seen = await boot(page, { series: seriesDetail(), playstate: { sr1: state({ continue_episode_id: "s1e2" }) } });
    await openPage(page);
    await expect(page.locator(".detail-seasons .detail-btn")).toHaveText(["Season 1", "Season 2"]);
    await expect(page.locator("#detail-season-1")).toHaveClass(/cur/);
    await expect(page.locator(".detail-ep .tile-title")).toHaveText(["Opening Reel", "Second Reel", "Third Reel"]);

    await page.keyboard.press("ArrowDown");                                   // Play -> the season strip
    await expect(page.locator("#detail-season-1")).toHaveClass(/focused/);
    await page.keyboard.press("ArrowRight");
    await expect(page.locator("#detail-season-2")).toHaveClass(/focused/);
    await page.keyboard.press("Enter");
    await expect(page.locator("#detail-season-2")).toHaveClass(/cur/);
    await expect(page.locator(".detail-ep .tile-title")).toHaveText(["Return Reel"]);
    await expect(page.locator("#detail-season-2")).toHaveClass(/focused/);    // the switch keeps focus on the pill

    await page.keyboard.press("ArrowLeft");
    await page.keyboard.press("Enter");
    await expect(page.locator(".detail-ep")).toHaveCount(3);
    await page.keyboard.press("ArrowDown");                                   // into the episodes
    await expect(page.locator("#detail-ep-0")).toHaveClass(/focused/);
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("Enter");                                       // Second Reel
    const opened = await waitForAvplayCall(page, "open");
    expect(String(opened.call[1])).toContain("/hls/s1e2/");
    expect(seen.starts.map((s) => s.item_id)).toEqual(["s1e2"]);
  });

  test("Back from a play returns to the page with focus on the button that played", async ({ page }) => {
    await boot(page, { series: seriesDetail() });
    await openPage(page);
    await page.keyboard.press("Enter");
    await waitForAvplayCall(page, "open");
    await expect(page.locator("#shell")).not.toHaveClass(/\bon\b/);
    for (let i = 0; i < 3 && !(await page.locator("#shell").evaluate((e) => e.classList.contains("on"))); i++) await page.keyboard.press("Escape");
    await expect(page.locator("#shell")).toHaveClass(/\bon\b/);
    await expect(label(page, "detail-play")).toHaveClass(/focused/);
    await expect(page.locator(".detail-title")).toHaveText("Paper Series");
  });
});

// DEB-4893 (DEB-4889 FR-19) — Android TV's audio and subtitle flag line, redrawn in DOM from the packed flags/<cc>.png.
test.describe("DEB-4889 FR-19 — a title's page shows its audio and subtitle languages as flags", () => {
  test.use({ viewport: { width: 1920, height: 1080 } });
  const flags = (page: Page, group: number) => page.locator(".detail-langs .lang-group").nth(group).locator("img.lang-flag");
  const loaded = (img: ReturnType<Page["locator"]>) => img.evaluateAll((els) => els.map((e) => (e as HTMLImageElement).naturalWidth));

  test("three audio flags, five subtitle flags and +2 for the seven subtitle languages (one has no flag), under the meta line", async ({ page }) => {
    await boot(page, { movie: { ...movieDetail,
      audio_languages: ["en", "dan", "fo"],
      subtitle_languages: ["en", "da", "fr", "de", "es", "it", "tam"] } });
    await openPage(page);
    await expect(page.locator(".detail-langs")).toHaveCount(1);
    await expect(page.locator(".detail-langs .lang-label")).toHaveText(["AUDIO", "SUBTITLES"]);
    await expect(page.locator(".detail-langs .lang-dot")).toHaveText("·");
    await expect(flags(page, 0)).toHaveCount(3);
    await expect(flags(page, 1)).toHaveCount(5);
    await expect(page.locator(".detail-langs .lang-more")).toHaveText(["+2"]);   // the sixth and seventh languages, the unmapped one included
    // The flags are packed in the widget (flags/<cc>.png), so each one really decoded.
    await expect.poll(async () => (await loaded(flags(page, 0))).every((w) => w > 0)).toBe(true);
    await expect.poll(async () => (await loaded(flags(page, 1))).every((w) => w > 0)).toBe(true);
    expect(await flags(page, 0).evaluateAll((els) => els.map((e) => (e as HTMLImageElement).getAttribute("src")))).toEqual(["flags/gb.png", "flags/dk.png", "flags/fo.png"]);
    // Between the meta line and the overview, and not a stop for the D-pad.
    expect(await page.locator(".detail-meta + .detail-langs + .detail-overview").count()).toBe(1);
    await expect(page.locator(".detail-langs .shell-target")).toHaveCount(0);
    const size = await flags(page, 0).first().evaluate((el) => { const r = el.getBoundingClientRect(); const s = getComputedStyle(el); return [r.width, r.height, s.borderTopLeftRadius, s.borderTopWidth]; });
    expect(size).toEqual([39, 27, "3px", "1px"]);
  });

  test("five and five flags with both +N fit the header's column and never clip", async ({ page }) => {
    const many = ["en", "da", "fr", "de", "es", "it", "pt", "nl"];
    await boot(page, { movie: { ...movieDetail, audio_languages: many, subtitle_languages: many } });
    await openPage(page);
    await expect(flags(page, 0)).toHaveCount(5);
    await expect(flags(page, 1)).toHaveCount(5);
    await expect(page.locator(".detail-langs .lang-more")).toHaveText(["+3", "+3"]);
    const fit = await page.evaluate(() => {
      const line = document.querySelector(".detail-langs") as HTMLElement;
      const rect = line.getBoundingClientRect();
      const last = Array.from(line.querySelectorAll(".lang-more")).pop()!.getBoundingClientRect();
      const column = (document.querySelector(".detail-head") as HTMLElement).getBoundingClientRect().width * 0.62;
      return { overflow: line.scrollWidth - line.clientWidth, lastRight: last.right, lineRight: rect.right, lineWidth: rect.width, column };
    });
    expect(fit.overflow).toBeLessThanOrEqual(0);
    expect(fit.lastRight).toBeLessThanOrEqual(fit.lineRight);
    expect(fit.lineWidth).toBeLessThanOrEqual(fit.column + 1);
  });

  test("a group of only unmapped languages still shows its label and +N, and an empty group is left out", async ({ page }) => {
    await boot(page, { movie: { ...movieDetail, audio_languages: [], subtitle_languages: ["tam", "tel"] } });
    await openPage(page);
    await expect(page.locator(".detail-langs .lang-label")).toHaveText(["SUBTITLES"]);
    await expect(page.locator(".detail-langs img.lang-flag")).toHaveCount(0);
    await expect(page.locator(".detail-langs .lang-more")).toHaveText(["+2"]);
    await expect(page.locator(".detail-langs .lang-dot")).toHaveCount(0);
  });

  test("a series shows the line too; with no languages there is no line and no space for one", async ({ page }) => {
    await boot(page, { series: seriesDetail({ audio_languages: ["en"], subtitle_languages: ["da"] }) });
    await openPage(page);
    await expect(page.locator(".detail-langs .lang-label")).toHaveText(["AUDIO", "SUBTITLES"]);
    await expect(flags(page, 0)).toHaveCount(1);
    await expect(flags(page, 1)).toHaveCount(1);
  });

  test("a title with no languages has no line", async ({ page }) => {
    await boot(page, { series: seriesDetail() });
    await openPage(page);
    await expect(page.locator(".detail-langs")).toHaveCount(0);
    await expect(page.locator(".detail-meta + .detail-overview")).toHaveCount(1);
  });
});
