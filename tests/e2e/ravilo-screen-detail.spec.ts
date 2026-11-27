import { test, expect, type Page } from "@playwright/test";
import { fakeAvPlayInitScript, waitForAvplayCall } from "./helpers/screen-cast";

// DEB-4660 (DEB-4654 FR-7) — a title's page on the Tizen full app, driven with keyboard events through the REAL
// ravilo-screen.js bundle. The backend is stood in for by page.route, so this file proves what the page does with the
// answers it gets (the label from the playstate, the write-through that waits for the server, the next-up episode, a
// season switch, what a play carries to the player), not what the server says.

// DEB-5698 (FR-64) — one case reads `gc()` to see that a covered page's nodes are let go; the flag changes nothing else.
test.use({ launchOptions: { args: ["--js-flags=--expose-gc"] } });

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

type Seen = {
  starts: any[]; favorites: any[]; played: any[];
  // DEB-5050 (FR-31) — what the page asked `getPlaystate` (one entry per request, the `ids` it sent), what the mock answers next
  // (swap `playstate` / `playstateStatus` between reads), a gate a test holds a read on, and the events socket's push.
  reads: string[][]; inFlight: number; maxInFlight: number;
  playstate: Record<string, object>; playstateStatus: number; gate: Promise<void> | null;
  push: (message: object) => void;
  // DEB-5396 (DEB-5394, FR-35) — each `/prewarm` and `/prewarm/cancel` body the page posted, and what the mock answers to a prewarm.
  prewarms: any[]; cancels: any[]; prewarmAnswer: object | number;
  // DEB-5649 (FR-51) — every `/api/tv/movie/*` and `/api/tv/series/*` path the page asked for.
  titles: string[];
};
type Opts = {
  home?: object; movie?: object | null; series?: object | null; playstate?: Record<string, object>;
  favorite?: (body: any) => Promise<{ status: number; body?: object }>;
  played?: (body: any) => Promise<{ status: number; body?: object }>;
  /** DEB-5396 — the page runs on Playwright's fake clock, so a test says when 2 s and 30 s have passed. */
  fakeClock?: boolean;
  /** DEB-5578 — answer image requests with a real 1x1 PNG, so a backdrop or logo fires `load` (the default is an empty 204). */
  images?: boolean;
  /** DEB-5649 — more title pages the mock serves, by path (`/api/tv/movie/m2`), for a *More like this* tile to open. */
  pages?: Record<string, object>;
  /** DEB-5696 (FR-60) — what the film's page is answered, one entry per read (then the usual answer): a status, or "hang" for a read that never answers. */
  movieAnswers?: (number | "hang")[];
};
const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==", "base64");

/** Home with one tile (the movie or the series); OK on it is the page. */
async function boot(page: Page, opts: Opts = {}): Promise<Seen> {
  const sockets: any[] = [];
  const seen: Seen = {
    starts: [], favorites: [], played: [], reads: [], inFlight: 0, maxInFlight: 0,
    playstate: opts.playstate ?? {}, playstateStatus: 200, gate: null,
    push: (message) => sockets.forEach((ws) => ws.send(JSON.stringify(message))),
    prewarms: [], cancels: [], prewarmAnswer: { status: "warm" }, titles: [],
  };
  // The events socket is accepted and says nothing until a test pushes.
  await page.routeWebSocket(/\/api\/tv\/events/, (ws) => { sockets.push(ws); });
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
    if (/^\/api\/tv\/(movie|series)\//.test(path)) seen.titles.push(path);
    if (opts.pages?.[path]) return json(route, 200, opts.pages[path]);
    if (path === "/api/tv/movie/m1" && opts.movieAnswers?.length) {
      const answer = opts.movieAnswers.shift()!;
      if (answer === "hang") return new Promise(() => {});
      if (answer !== 200) return json(route, answer);
    }
    if (path === "/api/tv/movie/m1") return opts.movie === null ? json(route, 404) : json(route, 200, opts.movie ?? movieDetail);
    if (path === "/api/tv/series/sr1") return opts.series ? json(route, 200, opts.series) : json(route, 404);
    if (path === "/api/tv/playstate") {
      seen.reads.push((new URL(req.url()).searchParams.get("ids") ?? "").split(","));
      seen.maxInFlight = Math.max(seen.maxInFlight, ++seen.inFlight);
      try { await seen.gate; } finally { seen.inFlight--; }
      return json(route, seen.playstateStatus, seen.playstateStatus === 200 ? seen.playstate : {});
    }
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
    if (path === "/api/tv/playback/prewarm") {
      seen.prewarms.push(body);
      return typeof seen.prewarmAnswer === "number" ? json(route, seen.prewarmAnswer) : json(route, 200, seen.prewarmAnswer);
    }
    if (path === "/api/tv/playback/prewarm/cancel") { seen.cancels.push(body); return route.fulfill({ status: 204 }); }
    if (path === "/api/tv/playback/start") { seen.starts.push(body); return json(route, 200, ticket(body.item_id)); }
    if (path.startsWith("/api/tv/playback/")) return route.fulfill({ status: 204 });
    if (path.startsWith("/api/tv/image/")) return opts.images ? route.fulfill({ status: 200, contentType: "image/png", body: PNG }) : route.fulfill({ status: 204 });
    return json(route, 404);
  });
  if (opts.fakeClock) await page.clock.install();
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
  test("a movie: the header, the server's rating badge, the cast row, and Play when nothing has been watched", async ({ page }) => {
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
    await expect(page.locator("#shell-body .detail-cast")).toContainText("Someone");   // DEB-5631 (FR-7 amended): the page's one cast entry
    await expect(page.locator("#shell-body")).not.toContainText(/play on/i);            // and still no cast button (a TV never shows one)
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
    await expect(page.locator("#shell-body .load-failure-title")).toHaveText("This isn't here any more");
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

// DEB-5050 (DEB-5047, FR-31) — Android TV's FR-R351-7 on the Samsung's title page: it reads the playstate again after a play and
// when the events socket says something changed, keeps what it has when the read fails, and never reads in parallel.
test.describe("DEB-5047 FR-31 — a title's page reads its state again", () => {
  const nth = (page: Page, i: number) => page.locator(`#detail-ep-${i}`);

  test("Back from a play whose playstate now marks the episode watched shows its ✓, and the button names the next episode", async ({ page }) => {
    const seen = await boot(page, { series: seriesDetail(), playstate: {} });
    await openPage(page);
    await expect(label(page, "detail-play")).toHaveText("Play · S01E01");
    await expect(page.locator(".detail-ep-check")).toHaveCount(0);
    expect(seen.reads).toHaveLength(1);

    await page.keyboard.press("Enter");
    await waitForAvplayCall(page, "open");
    // The viewer watched S01E01 on the TV: the server's playstate now says so.
    seen.playstate = { sr1: state({ continue_episode_id: "s1e2" }), s1e1: state({ played: true }) };
    for (let i = 0; i < 3 && !(await page.locator("#shell").evaluate((e) => e.classList.contains("on"))); i++) await page.keyboard.press("Escape");
    await expect(page.locator("#shell")).toHaveClass(/\bon\b/);

    await expect(nth(page, 0).locator(".detail-ep-check")).toHaveText("✓");
    await expect(label(page, "detail-play")).toHaveText("Resume · S01E02");
    await expect(label(page, "detail-play")).toHaveClass(/focused/);
    expect(seen.reads).toHaveLength(2);
    expect(seen.reads[1]).toEqual(["sr1", "s1e1", "s1e2", "s1e3", "s2e1"]);       // the series and every episode
  });

  test("a home_changed push while the page is open redraws it without moving focus or the season pill", async ({ page }) => {
    const seen = await boot(page, { series: seriesDetail(), playstate: {} });
    await openPage(page);
    await page.keyboard.press("ArrowDown");                                         // Play -> the season strip
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("Enter");                                             // Season 2
    await expect(page.locator("#detail-season-2")).toHaveClass(/cur/);
    await expect(page.locator("#detail-season-2")).toHaveClass(/focused/);
    const before = seen.reads.length;
    // FR-31 — a re-read draws over the page as it stands: no skeleton is ever added from here on.
    await page.evaluate(() => {
      (window as any).__skeletons = 0;
      new MutationObserver((records) => records.forEach((r) => r.addedNodes.forEach((n) => {
        if (n instanceof HTMLElement && (n.matches(".skeleton") || n.querySelector(".skeleton"))) (window as any).__skeletons++;
      }))).observe(document.getElementById("shell-body")!, { childList: true, subtree: true });
    });

    // A phone marked the whole of season 1 watched.
    seen.playstate = { sr1: state({ continue_episode_id: "s2e1" }), s1e1: state({ played: true }), s1e2: state({ played: true }), s1e3: state({ played: true }) };
    seen.push({ type: "home_changed", rev: 7 });
    await expect(label(page, "detail-play")).toHaveText("Resume · S02E01");
    expect(seen.reads.length).toBe(before + 1);
    await expect(page.locator("#detail-season-2")).toHaveClass(/focused/);          // focus did not move
    await expect(page.locator("#detail-season-2")).toHaveClass(/cur/);              // the page did not jump to the new primary's season
    await expect(page.locator(".detail-ep .tile-title")).toHaveText(["Return Reel"]);

    // `playstate_changed` asks for a read too.
    seen.playstate = { ...seen.playstate, s2e1: state({ played: true }) };
    seen.push({ type: "playstate_changed", patch: {} });
    await expect(page.locator("#detail-ep-0 .detail-ep-check")).toHaveText("✓");
    expect(seen.reads.length).toBe(before + 2);
    expect(await page.evaluate(() => (window as any).__skeletons)).toBe(0);
  });

  test("a re-read that answers 500, or {}, leaves every ✓ and the button as they were", async ({ page }) => {
    const seen = await boot(page, {
      series: seriesDetail(),
      playstate: { sr1: state({ continue_episode_id: "s1e2" }), s1e1: state({ played: true }) },
    });
    await openPage(page);
    await expect(label(page, "detail-play")).toHaveText("Resume · S01E02");
    await expect(nth(page, 0).locator(".detail-ep-check")).toHaveText("✓");

    seen.playstateStatus = 500;
    seen.push({ type: "home_changed", rev: 1 });
    await expect.poll(() => seen.reads.length).toBe(2);
    seen.playstateStatus = 200;
    seen.playstate = {};
    seen.push({ type: "home_changed", rev: 2 });
    await expect.poll(() => seen.reads.length).toBe(3);

    await expect(label(page, "detail-play")).toHaveText("Resume · S01E02");
    await expect(nth(page, 0).locator(".detail-ep-check")).toHaveText("✓");
    await expect(label(page, "detail-play")).toHaveClass(/focused/);
  });

  test("a push during a read causes one more read after it, not two in parallel", async ({ page }) => {
    const seen = await boot(page, { series: seriesDetail(), playstate: {} });
    await openPage(page);
    expect(seen.reads).toHaveLength(1);

    let release: () => void = () => {};
    seen.gate = new Promise<void>((resolve) => { release = resolve; });
    seen.push({ type: "home_changed", rev: 1 });
    await expect.poll(() => seen.reads.length).toBe(2);                              // the read is in flight, held
    seen.push({ type: "home_changed", rev: 2 });                                     // three more pushes while it is
    seen.push({ type: "playstate_changed", patch: {} });
    seen.push({ type: "home_changed", rev: 3 });
    await page.waitForTimeout(400);
    expect(seen.reads).toHaveLength(2);                                              // nothing started beside it

    seen.playstate = { sr1: state({ continue_episode_id: "s1e2" }), s1e1: state({ played: true }) };
    seen.gate = null;
    release();
    await expect(label(page, "detail-play")).toHaveText("Resume · S01E02");
    await expect.poll(() => seen.reads.length).toBe(3);                              // exactly one more
    await page.waitForTimeout(400);
    expect(seen.reads).toHaveLength(3);
    expect(seen.maxInFlight).toBe(1);
  });

  // DEB-5578 (DEB-5576, FR-31 amended) — the server pushes `playstate_changed` every ~20 s whether or not anything changed.
  test("a re-read that answers what the page holds draws nothing: three pushes, three reads, no mutation", async ({ page }) => {
    const held = { sr1: state({ continue_episode_id: "s1e2" }), s1e1: state({ played: true }) };
    const seen = await boot(page, { series: seriesDetail({ logo_url: "/api/tv/image/sr1-logo" }), playstate: held, images: true });
    await openPage(page);
    await expect(page.locator(".detail-title")).toHaveClass(/hidden/);               // the logo loaded
    await page.evaluate(() => {
      const w = window as any;
      w.__mutations = 0;
      w.__observer = new MutationObserver((records) => { w.__mutations += records.length; });
      w.__observer.observe(document.getElementById("shell-body")!, { childList: true, attributes: true, characterData: true, subtree: true });
    });
    const before = seen.reads.length;
    for (let i = 1; i <= 3; i++) {
      seen.push({ type: "playstate_changed", patch: {} });
      await expect.poll(() => seen.reads.length).toBe(before + i);                    // one at a time, each answered
    }
    await page.waitForTimeout(300);
    expect(seen.reads.length).toBe(before + 3);
    expect(await page.evaluate(() => { const w = window as any; return w.__mutations + w.__observer.takeRecords().length; })).toBe(0);
  });

  test("a redraw that does happen keeps the backdrop, the logo, the scroll, the rail, focus and the season pill", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 540 });
    const seen = await boot(page, { series: seriesDetail({ logo_url: "/api/tv/image/sr1-logo" }), playstate: { sr1: state({ continue_episode_id: "s1e1" }) }, images: true });
    await openPage(page);
    await expect(page.locator(".detail-title")).toHaveClass(/hidden/);
    await page.keyboard.press("ArrowDown");                                         // Play -> the season strip
    await page.keyboard.press("ArrowDown");                                         // -> the episode rail
    await expect(page.locator("#detail-ep-0")).toHaveClass(/focused/);
    await page.keyboard.press("ArrowRight");
    await expect(page.locator("#detail-ep-1")).toHaveClass(/focused/);
    const scroll = () => page.evaluate(() => (document.getElementById("shell-body") as HTMLElement).scrollTop);
    const rail = () => page.evaluate(() => { const t = document.querySelector(".rail-track") as HTMLElement; return [t.style.transform, t.getAttribute("data-tx"), (t.parentElement as HTMLElement).scrollLeft]; });
    const scrolled = await scroll();
    const railBefore = await rail();
    expect(scrolled).toBeGreaterThan(0);                                            // the rail is what shows; the head is scrolled away
    await page.evaluate(() => {
      const w = window as any;
      w.__backdrop = document.querySelector(".detail-backdrop img");
      w.__logo = document.querySelector(".detail-logo");
      w.__title = document.querySelector(".detail-title");
      w.__page = document.querySelector(".detail");
      w.__titleShown = false;
      new MutationObserver(() => { if (!(document.querySelector(".detail-title") as HTMLElement).classList.contains("hidden")) w.__titleShown = true; })
        .observe(document.getElementById("shell-body")!, { childList: true, attributes: true, subtree: true });
    });

    seen.playstate = { sr1: state({ continue_episode_id: "s1e1" }), s1e2: state({ played: true }) };
    seen.push({ type: "playstate_changed", patch: {} });
    await expect(page.locator("#detail-ep-1 .detail-ep-check")).toHaveText("✓");
    expect(await page.evaluate(() => {
      const w = window as any;
      return { page: document.querySelector(".detail") !== w.__page,                   // the page WAS redrawn ...
        backdrop: document.querySelector(".detail-backdrop img") === w.__backdrop,     // ... and these are the same nodes
        logo: document.querySelector(".detail-logo") === w.__logo,
        hidden: document.querySelector(".detail-title")!.classList.contains("hidden"),
        shown: w.__titleShown, fading: document.querySelector(".detail-backdrop img")!.classList.contains("loaded") };
    })).toEqual({ page: true, backdrop: true, logo: true, hidden: true, shown: false, fading: true });
    expect(await scroll()).toBe(scrolled);
    expect(await rail()).toEqual(railBefore);
    await expect(page.locator("#detail-ep-1")).toHaveClass(/focused/);
    await expect(page.locator("#detail-season-1")).toHaveClass(/cur/);
  });

  // DEB-5755 (FR-31 amended) — `.rail-track` transitions its transform, so a redrawn track handed the kept one slid in from 0.
  test("a redraw on a slid rail puts the track at its kept place on the first frame, with no transform transition running", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 540 });
    const eps = Array.from({ length: 12 }, (_, i) => episode(`s1e${i + 1}`, i + 1, `Reel ${i + 1}`));
    const seen = await boot(page, { series: seriesDetail({ seasons: [{ index: 1, name: "Season 1", episodes: eps }, { index: 2, name: "Season 2", episodes: [episode("s2e1", 1, "Return Reel")] }] }), playstate: { sr1: state({ continue_episode_id: "s1e1" }) } });
    await openPage(page);
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("ArrowDown");
    for (let i = 1; i <= 9; i++) await page.keyboard.press("ArrowRight");
    await expect(page.locator("#detail-ep-9")).toHaveClass(/focused/);
    const track = () => page.evaluate(() => { const t = document.querySelector(".detail-episodes .rail-track") as HTMLElement; return { tf: t.style.transform, computed: getComputedStyle(t).transform }; });
    await expect.poll(async () => (await track()).computed).not.toBe("none");      // the rail has slid
    await expect.poll(async () => (await track()).computed).not.toBe("matrix(1, 0, 0, 1, 0, 0)");
    await page.waitForTimeout(400);                                                  // the slide has finished
    const kept = await track();
    // A recorder that reads the new track on the first frame after the redraw (a MutationObserver runs before any frame).
    await page.evaluate(() => {
      const w = window as any;
      w.__first = null;
      const root = document.getElementById("shell-body")!;
      const obs = new MutationObserver(() => {
        const t = root.querySelector(".detail-episodes .rail-track") as HTMLElement | null;
        if (!t || w.__first) return;
        requestAnimationFrame(() => { w.__first = { computed: getComputedStyle(t).transform, running: t.getAnimations().length }; });
      });
      obs.observe(root, { childList: true, subtree: true });
    });
    seen.playstate = { sr1: state({ continue_episode_id: "s1e1" }), s1e2: state({ played: true }) };
    seen.push({ type: "playstate_changed", patch: {} });
    await expect(page.locator("#detail-ep-1 .detail-ep-check")).toHaveText("✓");
    await expect.poll(() => page.evaluate(() => (window as any).__first)).not.toBeNull();
    expect(await page.evaluate(() => (window as any).__first)).toEqual({ computed: kept.computed, running: 0 });
    expect((await track()).tf).toBe(kept.tf);
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
    expect(await page.locator(".detail-meta + .detail-langs + .detail-synopsis").count()).toBe(1);
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
    await expect(page.locator(".detail-meta + .detail-synopsis")).toHaveCount(1);
  });
});

// DEB-5396 (DEB-5394, FR-35) — Android TV's DetailPrewarm on the Samsung: after 2 s on a title's page the app asks the server to start
// the encode Play would use, keeps it warm while the page shows, and stops it at once when the page is left without Play. The page
// runs on the fake clock, so "2 s" and "30 s" are what the test says they are.
test.describe("DEB-5394 FR-35 — a title's page starts the encode Play would use", () => {
  const settle = (page: Page) => page.waitForTimeout(300);   // real time, for the mock's answer to reach the page

  test("a film held 2 s posts one prewarm for it with the start's capabilities, and nothing sooner", async ({ page }) => {
    const seen = await boot(page, { fakeClock: true });
    await openPage(page);
    await page.clock.fastForward(1_500);
    await settle(page);
    expect(seen.prewarms, "nothing before 2 s").toEqual([]);
    await page.clock.fastForward(600);
    await expect.poll(() => seen.prewarms.length).toBe(1);
    expect(seen.prewarms[0].item_id).toBe("m1");

    // The start's own capabilities: Play now, and the two bodies carry the same ones.
    await page.keyboard.press("Enter");
    await expect.poll(() => seen.starts.length).toBe(1);
    expect(seen.prewarms[0].capabilities).toEqual(seen.starts[0].capabilities);
    expect(seen.prewarms).toHaveLength(1);
  });

  test("a warm answer is posted again at 30 s and at 60 s", async ({ page }) => {
    const seen = await boot(page, { fakeClock: true });
    await openPage(page);
    await page.clock.fastForward(2_100);
    await expect.poll(() => seen.prewarms.length).toBe(1);
    await settle(page);
    await page.clock.fastForward(30_000);
    await expect.poll(() => seen.prewarms.length).toBe(2);
    await settle(page);
    await page.clock.fastForward(30_000);
    await expect.poll(() => seen.prewarms.length).toBe(3);
    expect(seen.prewarms.map((p) => p.item_id)).toEqual(["m1", "m1", "m1"]);
  });

  for (const [name, answer] of [["none", { status: "none", reason: "copy" }], ["direct", { status: "direct" }], ["a 404 from an older server", 404]] as const) {
    test(`${name} is not posted again, and leaving then posts no cancel`, async ({ page }) => {
      const seen = await boot(page, { fakeClock: true });
      seen.prewarmAnswer = answer;
      await openPage(page);
      await page.clock.fastForward(2_100);
      await expect.poll(() => seen.prewarms.length).toBe(1);
      await settle(page);
      await page.clock.fastForward(90_000);
      await settle(page);
      expect(seen.prewarms).toHaveLength(1);
      await page.keyboard.press("Escape");
      await expect(page.locator("#shell-body .home-feed")).toBeVisible();
      await settle(page);
      expect(seen.cancels, "nothing is running to stop").toEqual([]);
    });
  }

  test("Back before the 2 s posts nothing at all; Back after it posts the cancel at once", async ({ page }) => {
    const seen = await boot(page, { fakeClock: true });
    await openPage(page);
    await page.clock.fastForward(1_000);
    await page.keyboard.press("Escape");
    await expect(page.locator("#shell-body .home-feed")).toBeVisible();
    await page.clock.fastForward(5_000);
    await settle(page);
    expect(seen.prewarms).toEqual([]);
    expect(seen.cancels).toEqual([]);

    await openPage(page);
    await page.clock.fastForward(2_100);
    await expect.poll(() => seen.prewarms.length).toBe(1);
    await page.keyboard.press("Escape");
    await expect.poll(() => seen.cancels).toEqual([{ item_id: "m1" }]);   // with no clock moved: at once
    await settle(page);
    await page.clock.fastForward(60_000);
    await settle(page);
    expect(seen.prewarms, "a page left posts no more").toHaveLength(1);
  });

  test("Play does not cancel: the server adopts the job, and nothing is posted when the player returns before 2 s", async ({ page }) => {
    const seen = await boot(page, { fakeClock: true });
    await openPage(page);
    await page.clock.fastForward(2_100);
    await expect.poll(() => seen.prewarms.length).toBe(1);
    await page.keyboard.press("Enter");
    await waitForAvplayCall(page, "open");
    await page.clock.fastForward(5_000);
    await settle(page);
    expect(seen.cancels, "leaving by Play cancels nothing").toEqual([]);
    expect(seen.prewarms, "and nothing keeps posting under the player").toHaveLength(1);
  });

  test("the app hidden cancels at once; a series warms its primary episode with the series' remembered audio", async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem("ravilo.screen.tracks.viewer", JSON.stringify({ series: { sr1: { audio: "fra" } } }));
    });
    const seen = await boot(page, { series: seriesDetail(), playstate: {}, fakeClock: true });
    await openPage(page);
    await page.clock.fastForward(2_100);
    await expect.poll(() => seen.prewarms.length).toBe(1);
    expect(seen.prewarms[0].item_id).toBe("s1e1");
    expect(seen.prewarms[0].audio_language).toBe("fra");

    await page.evaluate(() => {
      Object.defineProperty(document, "hidden", { configurable: true, get: () => true });
      document.dispatchEvent(new Event("visibilitychange"));
    });
    await expect.poll(() => seen.cancels).toEqual([{ item_id: "s1e1" }]);
  });

  test("a re-read that moves the primary episode cancels the old one and warms the new one after 2 s; a season switch does not move it", async ({ page }) => {
    const seen = await boot(page, { series: seriesDetail(), playstate: {}, fakeClock: true });
    await openPage(page);
    await page.clock.fastForward(2_100);
    await expect.poll(() => seen.prewarms.map((p) => p.item_id)).toEqual(["s1e1"]);

    // Season 2 is a different list on the page, not a different Play: the primary button still plays S01E01.
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("Enter");
    await expect(page.locator("#detail-season-2")).toHaveClass(/cur/);
    await expect(page.locator("#detail-ep-0 .detail-ep-code")).toContainText("S02");
    await settle(page);
    expect(seen.cancels).toEqual([]);
    expect(seen.prewarms).toHaveLength(1);

    // FR-31's re-read: the first episode was watched elsewhere, so Play is now S01E02.
    seen.playstate = { sr1: state({ continue_episode_id: "s1e2" }), s1e1: state({ played: true }) };
    seen.push({ type: "home_changed", rev: 1 });
    await expect.poll(() => seen.cancels).toEqual([{ item_id: "s1e1" }]);
    await settle(page);
    expect(seen.prewarms, "the 2 s starts again for the new one").toHaveLength(1);
    await page.clock.fastForward(2_100);
    await expect.poll(() => seen.prewarms.map((p) => p.item_id)).toEqual(["s1e1", "s1e2"]);
  });
});

// DEB-5631 (DEB-5621 FR-7 amended) — a title's page draws `detail.cast` as a row of faces: Android TV's CastCircle in DOM.
test.describe("DEB-5631 FR-7 — a title's page shows its cast", () => {
  const TMDB = "https://img.test";
  const people = [
    { id: "101", name: "Ada Quill", role: "Lead", image_url: `${TMDB}/ada.jpg` },
    { id: "102", name: "Bo Marsh", role: "Director", image_url: null },
    { id: "103", name: "Cleo", role: null, image_url: `${TMDB}/cleo.jpg` },
  ];
  /** The faces' photos are absolute TMDB URLs, loaded by a plain <img> with no token: one answers, one is a 404. */
  const photos = async (page: Page) => {
    await page.route(`${TMDB}/ada.jpg`, (route) => route.fulfill({ status: 200, contentType: "image/png", body: PNG }));
    await page.route(`${TMDB}/cleo.jpg`, (route) => route.fulfill({ status: 404 }));
  };

  test("three entries draw three faces with name and role; no photo and a 404'd photo show the initials", async ({ page }) => {
    await photos(page);
    await boot(page, { movie: { ...movieDetail, cast: people } });
    await openPage(page);
    await expect(page.locator(".detail-cast .home-heading")).toHaveText("Cast & Crew");
    await expect(page.locator(".detail-face")).toHaveCount(3);
    await expect(page.locator(".detail-face .face-name")).toHaveText(["Ada Quill", "Bo Marsh", "Cleo"]);
    await expect(page.locator(".detail-face .face-role")).toHaveText(["Lead", "Director"]);        // a face without a role has no role line
    await expect(page.locator("#detail-cast-2 .face-role")).toHaveCount(0);
    // The photo that loads covers the initials; the person with no photo has no <img> at all; the 404 never fades in.
    await expect(page.locator("#detail-cast-0 img")).toHaveClass(/loaded/);
    await expect(page.locator("#detail-cast-0 img")).toHaveAttribute("src", `${TMDB}/ada.jpg`);
    await expect(page.locator("#detail-cast-1 img")).toHaveCount(0);
    await expect(page.locator("#detail-cast-1 .face-initials")).toHaveText("BM");
    await expect(page.locator("#detail-cast-1 .face-initials")).toBeVisible();
    await expect(page.locator("#detail-cast-2 img")).toHaveAttribute("src", `${TMDB}/cleo.jpg`);
    await expect(page.locator("#detail-cast-2 img")).not.toHaveClass(/loaded/);
    await expect(page.locator("#detail-cast-2 img")).toHaveCSS("opacity", "0");
    await expect(page.locator("#detail-cast-2 .face-initials")).toHaveText("C");
  });

  test("an empty cast draws no row and no heading", async ({ page }) => {
    await boot(page, { movie: { ...movieDetail, cast: [] } });
    await openPage(page);
    await expect(page.locator(".detail-cast")).toHaveCount(0);
    await expect(page.locator("#shell-body")).not.toContainText("Cast & Crew");
  });

  test("a film: Down from the actions focuses the first face, Right moves along, Up returns to the actions", async ({ page }) => {
    await photos(page);
    await boot(page, { movie: { ...movieDetail, cast: people } });
    await openPage(page);
    await page.keyboard.press("ArrowDown");
    await expect(page.locator("#detail-cast-0")).toHaveClass(/focused/);
    await page.keyboard.press("ArrowRight");
    await expect(page.locator("#detail-cast-1")).toHaveClass(/focused/);
    await page.keyboard.press("ArrowRight");
    await expect(page.locator("#detail-cast-2")).toHaveClass(/focused/);
    await page.keyboard.press("ArrowRight");
    await expect(page.locator("#detail-cast-2")).toHaveClass(/focused/);        // the end of the rail
    await expect(page.locator(".shell-target.focused")).toHaveCount(1);
    await page.keyboard.press("ArrowUp");
    await expect(page.locator(".detail-btn.focused")).toHaveCount(1);
  });

  test("a series: the cast follows the episodes; Down from them reaches it and Up comes back to them", async ({ page }) => {
    await photos(page);
    await boot(page, { series: seriesDetail({ cast: people }) });
    await openPage(page);
    // The page's order is the actions, the season pills, the episodes, then the cast.
    const after = await page.evaluate(() => !!(document.querySelector(".detail-episodes")!.compareDocumentPosition(document.querySelector(".detail-cast")!) & Node.DOCUMENT_POSITION_FOLLOWING));
    expect(after).toBe(true);
    // Play, the season pills, an episode, its watched toggle (DEB-5651, FR-53), then the cast.
    for (let i = 0; i < 4; i++) await page.keyboard.press("ArrowDown");
    await expect(page.locator("#detail-cast-0")).toHaveClass(/focused/);
    await page.keyboard.press("ArrowUp");
    await expect(page.locator(".detail-ep-toggle.focused")).toHaveCount(1);
  });
});

// DEB-5649 (DEB-5640 FR-51) — a title's page ends in Android TV's More like this row.
test.describe("DEB-5649 FR-51 — a title's page ends in More like this", () => {
  test.use({ viewport: { width: 1920, height: 1080 } });   // the set's own screen: the lab's trap lived in its pixels
  const related = [card("m2", "Moon Two"), card("m3", "Moon Three"), card("sr9", "Related Series", "SERIES")];
  const people = [{ id: "101", name: "Ada Quill", role: "Lead", image_url: null }];
  const otherMovie = { ...movieDetail, card: card("m2", "Moon Two"), related: [], cast: [] };
  const otherSeries = seriesDetail({ card: card("sr9", "Related Series", "SERIES") });

  test("a film with three related cards draws the row last, headed More Like This, after the cast row", async ({ page }) => {
    await boot(page, { movie: { ...movieDetail, cast: people, related } });
    await openPage(page);
    await expect(page.locator(".detail-related .home-heading")).toHaveText("More Like This");
    await expect(page.locator(".detail-related .home-tile")).toHaveCount(3);
    await expect(page.locator(".detail-related .tile-title")).toHaveText(["Moon Two", "Moon Three", "Related Series"]);
    await expect(page.locator(".detail-related .home-tile").first()).toHaveClass(/tile-poster/);
    // It is the page's last row: the cast row comes before it and nothing follows.
    const order = await page.evaluate(() => {
      const kids = Array.from(document.querySelector(".detail")!.children).map((e) => e.className);
      return [kids.findIndex((c) => c.includes("detail-cast")), kids.findIndex((c) => c.includes("detail-related")), kids.length];
    });
    expect(order[0]).toBeGreaterThan(0);
    expect(order[1]).toBe(order[0] + 1);
    expect(order[1]).toBe(order[2] - 1);
  });

  test("with no cast the row follows the overview", async ({ page }) => {
    await boot(page, { movie: { ...movieDetail, cast: [], related } });
    await openPage(page);
    await expect(page.locator(".detail-cast")).toHaveCount(0);
    await expect(page.locator(".detail-related .home-tile")).toHaveCount(3);
  });

  test("a series: the row follows the episodes; Down from them reaches it and Up comes back", async ({ page }) => {
    await boot(page, { series: seriesDetail({ related }) });
    await openPage(page);
    const after = await page.evaluate(() => !!(document.querySelector(".detail-episodes")!.compareDocumentPosition(document.querySelector(".detail-related")!) & Node.DOCUMENT_POSITION_FOLLOWING));
    expect(after).toBe(true);
    for (let i = 0; i < 4; i++) await page.keyboard.press("ArrowDown");
    await expect(page.locator("#detail-related-0")).toHaveClass(/focused/);
    await page.keyboard.press("ArrowUp");
    await expect(page.locator(".detail-ep-toggle.focused")).toHaveCount(1);
  });

  test("a film whose cast faces rest a pixel apart: Down from a face reaches the related row, never a neighbouring face, and Up returns to a face", async ({ page }) => {
    // The lab's trap (Tizen 5, DEB-5673): faces with different label heights rest a pixel or two apart, so a neighbour's centre was
    // "below" the focused face's and beat the related row 250 px down: Down walked the cast. The rail here rests the second face
    // 2 px lower; a neighbour in the same row is never the row below.
    const faces = [
      { id: "101", name: "Ada Quill", role: "Lead", image_url: null },
      { id: "102", name: "Bo Marsh", role: "Director", image_url: null },
      { id: "103", name: "Cleo", role: null, image_url: null },
    ];
    await boot(page, { movie: { ...movieDetail, cast: faces, related } });
    await openPage(page);
    await page.addStyleTag({ content: "#detail-cast-1 { margin-top: 2px; }" });
    await page.keyboard.press("ArrowDown");
    await expect(page.locator("#detail-cast-0")).toHaveClass(/focused/);
    // from each face, Down leaves the cast row for a related tile and Up comes back to a face
    for (const key of ["", "ArrowRight", "ArrowRight"]) {
      if (key) await page.keyboard.press(key);
      await page.keyboard.press("ArrowDown");
      await expect(page.locator(".detail-related .shell-target.focused")).toHaveCount(1);
      await page.keyboard.press("ArrowUp");
      await expect(page.locator(".detail-cast .shell-target.focused")).toHaveCount(1);
    }
    await page.keyboard.press("ArrowDown");
    await expect(page.locator(".detail-related .shell-target.focused")).toHaveCount(1);
    const box = await page.locator(".detail-related .shell-target.focused").boundingBox();
    expect(box!.y + box!.height).toBeLessThanOrEqual(page.viewportSize()!.height);   // the row scrolls onto the screen
    await page.keyboard.press("ArrowUp");
    await expect(page.locator(".detail-cast .shell-target.focused")).toHaveCount(1);
  });

  test("a film: Down from the actions reaches the first tile, Right moves along, Up returns to the actions", async ({ page }) => {
    await boot(page, { movie: { ...movieDetail, cast: [], related } });
    await openPage(page);
    await page.keyboard.press("ArrowDown");
    await expect(page.locator("#detail-related-0")).toHaveClass(/focused/);
    await page.keyboard.press("ArrowRight");
    await expect(page.locator("#detail-related-1")).toHaveClass(/focused/);
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("ArrowRight");
    await expect(page.locator("#detail-related-2")).toHaveClass(/focused/);        // the end of the rail
    await expect(page.locator(".shell-target.focused")).toHaveCount(1);
    await page.keyboard.press("ArrowUp");
    await expect(page.locator(".detail-btn.focused")).toHaveCount(1);
  });

  test("OK on a film's tile requests that title and draws its page; Back returns to the same tile", async ({ page }) => {
    const seen = await boot(page, { movie: { ...movieDetail, cast: [], related }, pages: { "/api/tv/movie/m2": otherMovie, "/api/tv/series/sr9": otherSeries } });
    await openPage(page);
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("ArrowRight");
    await expect(page.locator("#detail-related-1")).toHaveClass(/focused/);
    await page.keyboard.press("ArrowLeft");
    await page.keyboard.press("Enter");
    await expect(page.locator(".detail-title")).toHaveText("Moon Two");
    expect(seen.titles).toContain("/api/tv/movie/m2");
    await expect(page.locator("#detail-play")).toHaveClass(/focused/);
    await page.keyboard.press("Escape");
    await expect(page.locator(".detail-title")).toHaveText("Paper Moon");
    await expect(page.locator("#detail-related-0")).toHaveClass(/focused/);
  });

  test("OK on a series card requests the series, not a film", async ({ page }) => {
    const seen = await boot(page, { movie: { ...movieDetail, cast: [], related }, pages: { "/api/tv/movie/m2": otherMovie, "/api/tv/series/sr9": otherSeries } });
    await openPage(page);
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("Enter");
    await expect(page.locator(".detail-title")).toHaveText("Related Series");
    expect(seen.titles).toContain("/api/tv/series/sr9");
    await expect(page.locator(".detail-ep").first()).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(page.locator("#detail-related-2")).toHaveClass(/focused/);
  });

  test("an empty related draws no row and no heading", async ({ page }) => {
    await boot(page, { movie: { ...movieDetail, related: [] } });
    await openPage(page);
    await expect(page.locator(".detail-related")).toHaveCount(0);
    await expect(page.locator("#shell-body")).not.toContainText("More Like This");
  });
});

// DEB-5651 (DEB-5640 FR-53) — an episode tile says what it is, and each one can be marked watched.
test.describe("DEB-5651 FR-53 — episode tiles", () => {
  const long = "A very long overview that goes on and on about the reel, the projector, the audience in the dark and everything that happened after the lights came up again that night.";
  const rich = (extra: object = {}) => seriesDetail({
    seasons: [
      { index: 1, name: "Season 1", episodes: [
        { ...episode("s1e1", 1, "Opening Reel"), runtime: 42, air_date: "2020-03-05", overview: long },
        { ...episode("s1e2", 2, "Second Reel"), runtime: 0, overview: null },
        episode("s1e3", 3, "Third Reel"),
      ] },
      { index: 2, name: "Season 2", episodes: [episode("s2e1", 1, "Return Reel")] },
    ],
    ...extra,
  });
  const tile = (page: Page, n: number) => page.locator(`#detail-ep-${n}`);

  test("a tile shows its runtime and date on one line and an overview clamped to two lines; none of them is drawn without its fact", async ({ page }) => {
    await boot(page, { series: rich() });
    await openPage(page);
    await expect(tile(page, 0).locator(".detail-ep-facts")).toHaveText("42 min · Mar 5, 2020");
    const overview = tile(page, 0).locator(".detail-ep-overview");
    await expect(overview).toHaveText(long);
    const box = await overview.evaluate((e) => ({ h: e.getBoundingClientRect().height, line: parseFloat(getComputedStyle(e).lineHeight) }));
    expect(box.h).toBeLessThanOrEqual(box.line * 2 + 6);
    // A runtime of 0 and no date, no overview: no facts line, no overview.
    await expect(tile(page, 1).locator(".detail-ep-facts")).toHaveCount(0);
    await expect(tile(page, 1).locator(".detail-ep-overview")).toHaveCount(0);
  });

  test("the continue_episode_id tile wears the ribbon, unless it is in progress", async ({ page }) => {
    await boot(page, { series: rich(), playstate: { sr1: state({ continue_episode_id: "s1e2" }), s1e1: state({ played: true }) } });
    await openPage(page);
    await expect(page.locator(".detail-ep-ribbon")).toHaveCount(1);
    await expect(tile(page, 1).locator(".detail-ep-ribbon")).toHaveText("New Episode");
  });

  test("an in-progress continue episode wears no ribbon", async ({ page }) => {
    await boot(page, { series: rich(), playstate: { sr1: state({ continue_episode_id: "s1e2" }), s1e2: state({ resume_ms: 60_000, played_pct: 0.3 }) } });
    await openPage(page);
    await expect(page.locator(".detail-ep-ribbon")).toHaveCount(0);
  });

  test("the season line counts the overlay for the chosen season, and reads all-watched when every one is", async ({ page }) => {
    await boot(page, { series: rich(), playstate: { sr1: state(), s1e1: state({ played: true }), s1e2: state(), s2e1: state({ played: true }) } });
    await openPage(page);
    await expect(page.locator(".detail-ep-count")).toHaveText("1 of 3 episodes watched");
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("Enter");
    await expect(page.locator(".detail-ep-count")).toHaveText("All 1 episodes watched");
  });

  test("Down from a tile reaches its toggle, Right moves along the toggles, Up returns to the tile under it", async ({ page }) => {
    const seen = await boot(page, { series: rich(), playstate: { sr1: state(), s1e1: state({ played: true }) } });
    await openPage(page);
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("ArrowDown");
    await expect(tile(page, 0)).toHaveClass(/focused/);
    await page.keyboard.press("ArrowDown");
    await expect(page.locator("#detail-ep-toggle-0")).toHaveClass(/focused/);
    await expect(page.locator("#detail-ep-toggle-0")).toHaveText("✓ Watched");
    await expect(page.locator("#detail-ep-toggle-1")).toHaveText("Mark Watched");
    await page.keyboard.press("ArrowRight");
    await expect(page.locator("#detail-ep-toggle-1")).toHaveClass(/focused/);
    await page.keyboard.press("ArrowUp");
    await expect(tile(page, 1)).toHaveClass(/focused/);
    // The tile itself still plays its episode.
    await page.keyboard.press("Enter");
    await expect.poll(() => seen.starts.map((b) => b.item_id)).toEqual(["s1e2"]);
  });

  test("OK on a toggle writes the opposite for the file; the answer redraws the ✓ and the line, and the toggle did not flip first", async ({ page }) => {
    let release: () => void = () => {};
    const held = new Promise<void>((r) => { release = r; });
    const seen = await boot(page, {
      series: rich(), playstate: { sr1: state(), s1e1: state(), s1e2: state(), s1e3: state() },
      played: async (body) => { await held; return { status: 200, body: { [body.item_id]: state({ played: body.played }) } }; },
    });
    await openPage(page);
    await expect(page.locator(".detail-ep-count")).toHaveText("0 of 3 episodes watched");
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("ArrowDown");
    await expect(page.locator("#detail-ep-toggle-0")).toHaveClass(/focused/);
    await page.keyboard.press("Enter");
    await expect.poll(() => seen.played.length).toBe(1);
    expect(seen.played[0]).toEqual({ item_id: "s1e1", played: true, episode_ids: ["s1e1"] });
    // Nothing flips while the call is out.
    await expect(page.locator("#detail-ep-toggle-0")).toHaveText("Mark Watched");
    release();
    await expect(page.locator("#detail-ep-toggle-0")).toHaveText("✓ Watched");
    await expect(tile(page, 0).locator(".detail-ep-check")).toHaveCount(1);
    await expect(page.locator(".detail-ep-count")).toHaveText("1 of 3 episodes watched");
    // Focus stays on the toggle through the redraw; a second OK writes played: false.
    await expect(page.locator("#detail-ep-toggle-0")).toHaveClass(/focused/);
    await page.keyboard.press("Enter");
    await expect.poll(() => seen.played.length).toBe(2);
    expect(seen.played[1]).toEqual({ item_id: "s1e1", played: false, episode_ids: ["s1e1"] });
  });

  test("a refused call changes nothing", async ({ page }) => {
    const seen = await boot(page, { series: rich(), playstate: { sr1: state(), s1e1: state(), s1e2: state(), s1e3: state() }, played: async () => ({ status: 500 }) });
    await openPage(page);
    for (let i = 0; i < 3; i++) await page.keyboard.press("ArrowDown");
    await page.keyboard.press("Enter");
    await expect.poll(() => seen.played.length).toBe(1);
    await expect(page.locator("#detail-ep-toggle-0")).toHaveText("Mark Watched");
    await expect(page.locator(".detail-ep-count")).toHaveText("0 of 3 episodes watched");
    await expect(tile(page, 0).locator(".detail-ep-check")).toHaveCount(0);
  });
});

test.describe("DEB-5699 FR-56 — a title's header carries Android TV's facts, and the overview opens", () => {
  const long = Array.from({ length: 14 }, (_, i) => `Sentence number ${i + 1} of a long overview that runs on well past the clamp.`).join(" ");
  const film = (extra: object = {}) => ({ ...movieDetail, ...extra });
  const imdb = (rating: number, votes = 24_800) => ({ aggregate_rating: rating, vote_count: votes });

  test("a film with an IMDb rating of 7.84 shows 7.8 and 24.8K, labelled with imdb.a11y; 8 shows 8.0; none shows no chip", async ({ page }) => {
    await boot(page, { movie: film({ imdb_rating: imdb(7.84) }) });
    await openPage(page);
    await expect(page.locator(".detail-meta .detail-imdb-rating")).toHaveText("7.8");
    await expect(page.locator(".detail-meta .detail-imdb-votes")).toHaveText("24.8K");
    await expect(page.locator(".detail-imdb")).toHaveAttribute("aria-label", "IMDb 7.8 out of 10, 24.8K votes");
    // After the year, the runtime and the rating badge, in that order.
    const order = await page.locator(".detail-meta > *").evaluateAll((els) => els.map((e) => e.className));
    expect(order).toEqual(["detail-fact", "detail-fact", "detail-badge", "detail-imdb"]);
  });

  test("a whole rating prints one decimal, a small vote count is the count, and no rating draws no chip", async ({ page }) => {
    await boot(page, { movie: film({ imdb_rating: imdb(8, 950) }) });
    await openPage(page);
    await expect(page.locator(".detail-imdb-rating")).toHaveText("8.0");
    await expect(page.locator(".detail-imdb-votes")).toHaveText("950");
  });

  test("no IMDb rating draws no chip", async ({ page }) => {
    await boot(page, {});
    await openPage(page);
    await expect(page.locator(".detail-imdb")).toHaveCount(0);
  });

  test("a watched film shows the ✓ Watched chip after the IMDb chip; an unwatched one does not", async ({ page }) => {
    await boot(page, { movie: film({ imdb_rating: imdb(7.84) }), playstate: { m1: state({ played: true }) } });
    await openPage(page);
    await expect(page.locator(".detail-watched-chip")).toHaveText("✓ Watched");
    const order = await page.locator(".detail-meta > *").evaluateAll((els) => els.map((e) => e.className));
    expect(order.slice(-2)).toEqual(["detail-imdb", "detail-watched-chip"]);
  });

  test("an unwatched film has no ✓ chip", async ({ page }) => {
    await boot(page, { playstate: { m1: state() } });
    await openPage(page);
    await expect(page.locator(".detail-watched-chip")).toHaveCount(0);
  });

  test("five genres show the first three and +2, under the meta line; three show no +N; none draw no line", async ({ page }) => {
    await boot(page, { movie: film({ genres: ["Drama", "Comedy", "Crime", "Western", "Road"] }) });
    await openPage(page);
    await expect(page.locator(".detail-meta + .detail-genres")).toHaveCount(1);
    await expect(page.locator(".detail-genre-names")).toHaveText("Drama · Comedy · Crime");
    await expect(page.locator(".detail-genre-more")).toHaveText("+2");
    await expect(page.locator(".detail-genres .shell-target")).toHaveCount(0);
  });

  test("three genres show no +N and none draw no line", async ({ page }) => {
    await boot(page, { movie: film({ genres: ["Drama", "Comedy", "Crime"] }) });
    await openPage(page);
    await expect(page.locator(".detail-genre-names")).toHaveText("Drama · Comedy · Crime");
    await expect(page.locator(".detail-genre-more")).toHaveCount(0);
  });

  test("no genres draws no line", async ({ page }) => {
    await boot(page, {});
    await openPage(page);
    await expect(page.locator(".detail-genres")).toHaveCount(0);
  });

  test("a long overview is a focus target above the actions: Up reaches it, OK opens it to its full length and OK closes it", async ({ page }) => {
    await boot(page, { movie: film({ synopsis: long }) });
    await openPage(page);
    await page.keyboard.press("ArrowUp");
    await expect(page.locator("#detail-overview")).toHaveClass(/focused/);
    const text = page.locator(".detail-overview");
    const closed = (await text.boundingBox())!.height;
    await expect(page.locator(".detail-more")).toHaveText("▾ more");
    await page.keyboard.press("Enter");
    await expect(page.locator("#detail-overview")).toHaveClass(/open/);
    await expect(page.locator("#detail-overview")).toHaveClass(/focused/);
    await expect(page.locator(".detail-more")).toHaveText("▴ less");
    expect((await text.boundingBox())!.height).toBeGreaterThan(closed + 20);
    await page.keyboard.press("Enter");
    await expect(page.locator("#detail-overview")).not.toHaveClass(/open/);
    await expect(page.locator(".detail-more")).toHaveText("▾ more");
    expect((await text.boundingBox())!.height).toBeLessThanOrEqual(closed + 1);
    // Down returns to the actions.
    await page.keyboard.press("ArrowDown");
    await expect(page.locator("#detail-play")).toHaveClass(/focused/);
  });

  test("a short overview shows no chevron, and OK on it opens nothing", async ({ page }) => {
    await boot(page, {});
    await openPage(page);
    await page.keyboard.press("ArrowUp");
    await expect(page.locator("#detail-overview")).toHaveClass(/focused/);
    await expect(page.locator(".detail-more")).toBeHidden();
    const before = (await page.locator(".detail-overview").boundingBox())!.height;
    await page.keyboard.press("Enter");
    await expect(page.locator("#detail-overview")).toHaveClass(/open/);
    // It is toggled, and it fits either way: the text is the same size.
    expect((await page.locator(".detail-overview").boundingBox())!.height).toBe(before);
  });

  test("an about with a director and a country draws those two rows in aboutRows' order, before the cast row", async ({ page }) => {
    await boot(page, { movie: film({ about: { country: "DK", director: "A. Director" } }) });
    await openPage(page);
    await expect(page.locator(".detail-about .home-heading")).toHaveText("About");
    await expect(page.locator(".about-label")).toHaveText(["Director", "Country"]);
    await expect(page.locator(".about-value")).toHaveText(["A. Director", "Denmark"]);
    // Before the cast row, and text only: no focus target in it.
    await expect(page.locator(".detail-about + .detail-cast")).toHaveCount(1);
    await expect(page.locator(".detail-about .shell-target")).toHaveCount(0);
  });

  test("no about, or an about with no row, draws no block and no heading", async ({ page }) => {
    await boot(page, { movie: film({ about: {} }) });
    await openPage(page);
    await expect(page.locator(".detail-about")).toHaveCount(0);
  });

  test("a series: About follows the episodes, with the series' rows", async ({ page }) => {
    await boot(page, { series: seriesDetail({ about: { creator: "A. Creator", seasons: 2, episodes: 4, runtime_min: 40 } }) });
    await openPage(page);
    await expect(page.locator(".about-label")).toHaveText(["Runtime", "Created by", "Seasons"]);
    await expect(page.locator(".about-value").first()).toHaveText("40 min per episode");
    await expect(page.locator(".detail-episodes + .detail-about")).toHaveCount(1);
  });

  test("a series with next_airing shows the line under the meta line; a series without shows none", async ({ page }) => {
    await boot(page, { series: seriesDetail({ next_airing: { season: 2, episode: 4, title: "Fourth Reel", air_date: "2026-12-01" } }) });
    await openPage(page);
    await expect(page.locator(".detail-meta + .detail-next")).toHaveCount(1);
    await expect(page.locator(".detail-next-text")).toHaveText(/^Next episode · S02E04 “Fourth Reel” · airs (Tue 1 Dec|Dec 1, 2026)$/);   // DEB-5836 (FR-4) — a date, not the ISO string; the year form once the clock leaves 2026
  });

  test("a series with no next_airing has no line", async ({ page }) => {
    await boot(page, { series: seriesDetail() });
    await openPage(page);
    await expect(page.locator(".detail-next")).toHaveCount(0);
  });

  test("a series' IMDb chip and genres are drawn like a film's", async ({ page }) => {
    await boot(page, { series: seriesDetail({ imdb_rating: imdb(9.05, 1_200_000), genres: ["Drama", "Comedy", "Crime", "Western"] }) });
    await openPage(page);
    await expect(page.locator(".detail-imdb-rating")).toHaveText("9.1");
    await expect(page.locator(".detail-imdb-votes")).toHaveText("1.2M");
    await expect(page.locator(".detail-genre-more")).toHaveText("+1");
  });

  test("a season whose episodes are all watched has a ✓ on its pill, and one that is not has none", async ({ page }) => {
    await boot(page, { series: seriesDetail(), playstate: { sr1: state(), s1e1: state({ played: true }), s1e2: state({ played: true }), s1e3: state({ played: true }), s2e1: state() } });
    await openPage(page);
    await expect(page.locator("#detail-season-1 .detail-season-check")).toHaveText("✓");
    await expect(page.locator("#detail-season-2 .detail-season-check")).toHaveCount(0);
  });

  test("a season with one episode unwatched, or a page whose playstate has not arrived, has no ✓", async ({ page }) => {
    await boot(page, { series: seriesDetail(), playstate: { sr1: state(), s1e1: state({ played: true }), s1e2: state({ played: true }), s1e3: state() } });
    await openPage(page);
    await expect(page.locator(".detail-season-check")).toHaveCount(0);
  });
});

// DEB-5696 (DEB-5685 FR-60): a title's page says its cause and offers what fixes it, replacing DEB-4660's bare line, and its Retry loads again.
test.describe("a failed title's page says its cause (DEB-5685, FR-60)", () => {
  const failure = (page: Page) => page.locator("#shell-body .load-failure");

  test("500 then 200: the unreachable lines and Retry, and OK on Retry draws the page", async ({ page }) => {
    const seen = await boot(page, { movieAnswers: [500] });
    await open(page);
    await expect(failure(page).locator(".load-failure-title")).toHaveText("Couldn't reach the server");
    await expect(failure(page).locator(".load-failure-body")).toHaveText("Check the connection and try again.");
    await expect(page.locator("#shell-page-target")).toHaveText("Retry");
    await expect(page.locator("#shell-page-target")).toHaveClass(/focused/);
    expect(seen.titles.filter((t) => t === "/api/tv/movie/m1")).toHaveLength(1);
    await page.keyboard.press("Enter");
    await expect(page.locator(".detail-title")).toHaveText("Paper Moon");
    await expect(label(page, "detail-play")).toHaveClass(/focused/);
    await expect(failure(page)).toHaveCount(0);
    expect(seen.titles.filter((t) => t === "/api/tv/movie/m1")).toHaveLength(2);
  });

  test("a Retry that fails again shows the failure again, and Back is Back", async ({ page }) => {
    await boot(page, { movieAnswers: [500, 500] });
    await open(page);
    await expect(page.locator("#shell-page-target")).toHaveText("Retry");
    await page.keyboard.press("Enter");
    await expect(page.locator("#shell-page-target")).toHaveText("Retry");
    await expect(page.locator("#shell-page-target")).toHaveClass(/focused/);
    await page.keyboard.press("Escape");
    await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
  });

  test("404: the gone line and Back, no body and no Retry; OK on Back is Back", async ({ page }) => {
    await boot(page, { movie: null });
    await open(page);
    await expect(failure(page).locator(".load-failure-title")).toHaveText("This isn't here any more");
    await expect(failure(page).locator(".load-failure-body")).toHaveCount(0);
    await expect(page.locator("#shell-page-target")).toHaveText("Back");
    await page.keyboard.press("Enter");
    await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
    await expect(failure(page)).toHaveCount(0);
  });

  test("403: the forbidden lines and Back", async ({ page }) => {
    await boot(page, { movieAnswers: [403] });
    await open(page);
    await expect(failure(page).locator(".load-failure-title")).toHaveText("Not available on this profile");
    await expect(failure(page).locator(".load-failure-body")).toHaveText("This profile can't see this.");
    await expect(page.locator("#shell-page-target")).toHaveText("Back");
  });

  test("401: the reauth lines and Sign in, which opens the sign-in screen", async ({ page }) => {
    await boot(page, { movieAnswers: [401] });
    await open(page);
    await expect(failure(page).locator(".load-failure-title")).toHaveText("Sign in again");
    await expect(page.locator("#shell-page-target")).toHaveText("Sign in");
    await page.keyboard.press("Enter");
    await expect(page.locator("#signin")).toHaveClass(/\bon\b/);
    expect(await page.evaluate(() => localStorage.getItem("ravilo.screen.profiles"))).toBe("[]");
  });

  test("a read that never answers is the unreachable lines at the 10 s deadline (FR-58)", async ({ page }) => {
    await boot(page, { movieAnswers: ["hang"], fakeClock: true });
    await open(page);
    await expect(page.locator("#shell-body .skeleton")).toBeVisible();
    await page.clock.fastForward(11_000);
    await expect(failure(page).locator(".load-failure-title")).toHaveText("Couldn't reach the server");
    await expect(page.locator("#shell-page-target")).toHaveText("Retry");
  });
});

// DEB-5698 (DEB-5685 FR-64) — a long evening of browsing stays light: the Back stack is bounded, a covered page keeps its data and not its
// DOM, and only the page that shows reads its state again.
test.describe("DEB-5698 FR-64 — a long session stays light", () => {
  /** A chain of films: `m<k>` lists `m<k+1>` as its only related card, so OK on its tile follows the chain one page deeper. */
  const film = (k: number) => ({ ...movieDetail, card: card(`m${k}`, `Moon ${k}`), cast: [], related: k < 30 ? [card(`m${k + 1}`, `Moon ${k + 1}`)] : [] });
  const chain = (to: number) => Object.fromEntries(Array.from({ length: to - 1 }, (_, i) => [`/api/tv/movie/m${i + 2}`, film(i + 2)]));
  /** OK on the page's one related tile: the next film's page, over this one. */
  const follow = async (page: Page, k: number) => {
    await page.keyboard.press("ArrowDown");
    await expect(page.locator("#detail-related-0")).toHaveClass(/focused/);
    await page.keyboard.press("Enter");
    await expect(page.locator(".detail-title")).toHaveText(`Moon ${k}`);
    await expect(page.locator("#detail-play")).toHaveClass(/focused/);
  };

  test("20 More like this opens leave 12 routes: Back 11 times reaches Home", async ({ page }) => {
    await boot(page, { movie: film(1), pages: chain(30) });
    await openPage(page);
    for (let k = 2; k <= 21; k++) await follow(page, k);
    for (let back = 1; back <= 10; back++) {
      await page.keyboard.press("Escape");
      await expect(page.locator(".detail-title")).toHaveText(`Moon ${21 - back}`);
    }
    // The oldest ten pages were dropped: the one under Moon 11 is Home, not Moon 10.
    await expect(page.locator(".detail-title")).toHaveText("Moon 11");
    await page.keyboard.press("Escape");
    await expect(page.locator("#shell-body .home-feed")).toBeVisible();
    await expect(page.locator(".detail-title")).toHaveCount(0);
    await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
  });

  test.describe("covered pages let go of their nodes", () => {
    test("the page a route covers is collected; Back draws it again from its data", async ({ page }) => {
      const seen = await boot(page, { movie: film(1), pages: chain(30), images: true });
      await openPage(page);
      await expect(page.locator(".detail-backdrop img")).toBeVisible();
      // Moon 1's drawn page and its backdrop, watched weakly: nothing here keeps them alive.
      await page.evaluate(() => {
        (window as any).__page = new WeakRef(document.querySelector(".detail")!);
        (window as any).__backdrop = new WeakRef(document.querySelector(".detail-backdrop")!);
      });
      await follow(page, 2);
      await follow(page, 3);
      const titles = seen.titles.length;
      await expect.poll(async () => page.evaluate(async () => {
        await new Promise((resolve) => setTimeout(resolve, 50));
        (window as any).gc();
        return [(window as any).__page.deref() === undefined, (window as any).__backdrop.deref() === undefined];
      }), { timeout: 10_000 }).toEqual([true, true]);
      await page.keyboard.press("Escape");
      await page.keyboard.press("Escape");
      await expect(page.locator(".detail-title")).toHaveText("Moon 1");
      await expect(page.locator(".detail-backdrop img")).toBeVisible();
      expect(seen.titles.length).toBe(titles);                                         // drawn from the data it kept
    });
  });

  test("with 5 title pages stacked one home_changed reads the playstate once, and a covered page reads when it shows", async ({ page }) => {
    const seen = await boot(page, { movie: film(1), pages: chain(30) });
    await openPage(page);
    for (let k = 2; k <= 5; k++) await follow(page, k);
    const before = seen.reads.length;
    const titlesBefore = seen.titles.length;

    seen.push({ type: "home_changed", rev: 1 });
    await expect.poll(() => seen.reads.length).toBe(before + 1);
    expect(seen.reads[before]).toEqual(["m5"]);                                      // the page that shows, not the four under it
    await page.waitForTimeout(500);
    expect(seen.reads.length).toBe(before + 1);

    // Back onto Moon 4: it reads now that it shows, and draws from the data it held: no skeleton, no new page request.
    await page.evaluate(() => {
      (window as any).__skeletons = 0;
      new MutationObserver((records) => records.forEach((r) => r.addedNodes.forEach((n) => {
        if (n instanceof HTMLElement && (n.matches(".skeleton") || n.querySelector(".skeleton"))) (window as any).__skeletons++;
      }))).observe(document.getElementById("shell-body")!, { childList: true, subtree: true });
    });
    await page.keyboard.press("Escape");
    await expect(page.locator(".detail-title")).toHaveText("Moon 4");
    await expect.poll(() => seen.reads.length).toBe(before + 2);
    expect(seen.reads[before + 1]).toEqual(["m4"]);
    await expect(page.locator("#detail-related-0")).toHaveClass(/focused/);
    expect(await page.evaluate(() => (window as any).__skeletons)).toBe(0);
    expect(seen.titles.length).toBe(titlesBefore);                                   // its data was kept; nothing was asked for again
    // Moon 3 was marked stale by the same push and has not shown: nothing read it.
    expect(seen.reads.length).toBe(before + 2);
  });
});
