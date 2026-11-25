import { test, expect, type Page } from "@playwright/test";
import { fakeAvPlayInitScript, waitForAvplayCall } from "./helpers/screen-cast";

// DEB-5648 (DEB-5640, FR-50) — on the Samsung, a Continue Watching tile that holds focus for 2 s warms what its page's Play would
// play, so a viewer who opens the page and presses Play at once still starts warm. The real ravilo-screen.js bundle on Playwright's
// fake clock, the backend stood in for by page.route: this proves what the app posts and when, not what the server does with it.

const SCREEN_URL = process.env.RAVILO_SCREEN_CAST_URL ?? "http://localhost:8092";
const API = "http://app:9505";

const card = (id: string, title: string, kind = "MOVIE") => ({
  id, kind, title, year: 2024, genre: null, rating: null,
  poster_url: `/api/tv/image/${id}-poster`, backdrop_url: `/api/tv/image/${id}-backdrop`,
});
const movieDetail = (id: string) => ({ card: card(id, "Paper Moon"), synopsis: "A con artist and a girl cross the plains.", runtime: 90, cast: [], related: [] });
const episode = (id: string, n: number) => ({ id, episode_number: n, title: `Reel ${n}`, runtime: 40, overview: null, still_url: `/api/tv/image/${id}-still` });
const seriesDetail = {
  card: card("sr1", "Paper Series", "SERIES"), synopsis: "Reels.", cast: [], related: [], logo_url: null,
  seasons: [{ index: 1, name: "Season 1", episodes: [episode("s1e1", 1), episode("s1e2", 2), episode("s1e3", 3)] }],
};
const state = (extra: object = {}) => ({ resume_ms: 0, played: false, played_pct: 0, favorite: false, ...extra });
const ticket = (itemId: string) => ({
  jellyfin_base_url: API, access_token: "", item_id: itemId, container: "ts", direct_play: false,
  hls_url: `${API}/hls/${itemId}/master.m3u8`, start_position_ms: 0, expires_at: 9_999_999_999,
});

// Row 0 is Continue Watching (a film, a series, a film), row 1 a row of newly added titles.
const feed = {
  heroes: [], channels: [], tile_shape: "POSTER", hero_height_pct: 48, focus_detail: "none",
  rows: [
    { id: "continue", title: "Keep Watching", kind: "CONTINUE", items: [card("m1", "Paper Moon"), card("sr1", "Paper Series", "SERIES"), card("m2", "Copper River")] },
    { id: "new", title: "New Arrivals", kind: "NEWLY_ADDED", items: [card("n1", "Harbor Lights")] },
  ],
};

type Seen = {
  prewarms: any[]; cancels: any[]; starts: any[];
  seriesReads: number; playstateReads: string[][];
  playstate: Record<string, object>;
};

async function boot(page: Page): Promise<Seen> {
  const seen: Seen = {
    prewarms: [], cancels: [], starts: [], seriesReads: 0, playstateReads: [],
    playstate: { sr1: state({ continue_episode_id: "s1e2" }) },
  };
  await page.routeWebSocket(/\/api\/tv\/events/, () => {});
  await page.addInitScript(fakeAvPlayInitScript);
  await page.addInitScript(() => {
    localStorage.setItem("ravilo.screen.serverUrl", "http://app:9505");
    localStorage.setItem("ravilo.screen.profiles", JSON.stringify([{ userId: "viewer", displayName: "Test Viewer", token: "test-token" }]));
  });
  const json = (route: any, status: number, body: object = {}) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
  await page.route(`${API}/**`, async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const body = req.postData() ? JSON.parse(req.postData()!) : null;
    if (req.method() === "OPTIONS") return route.fulfill({ status: 204 });
    switch (url.pathname) {
      case "/api/tv/home": return json(route, 200, feed);
      case "/api/tv/movie/m1": return json(route, 200, movieDetail("m1"));
      case "/api/tv/movie/m2": return json(route, 200, movieDetail("m2"));
      case "/api/tv/series/sr1": seen.seriesReads++; return json(route, 200, seriesDetail);
      case "/api/tv/playstate": seen.playstateReads.push((url.searchParams.get("ids") ?? "").split(",")); return json(route, 200, seen.playstate);
      case "/api/tv/playback/prewarm": seen.prewarms.push(body); return json(route, 200, { status: "warm" });
      case "/api/tv/playback/prewarm/cancel": seen.cancels.push(body); return route.fulfill({ status: 204 });
      case "/api/tv/playback/start": seen.starts.push(body); return json(route, 200, ticket(body.item_id));
    }
    if (url.pathname.startsWith("/api/tv/playback/")) return route.fulfill({ status: 204 });
    if (url.pathname.startsWith("/api/tv/image/")) return route.fulfill({ status: 204 });
    return json(route, 404);
  });
  await page.clock.install();
  await page.goto(SCREEN_URL);
  await expect(page.locator("#shell-body .home-feed")).toBeVisible();
  await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
  return seen;
}

const settle = (page: Page) => page.waitForTimeout(300);   // real time, for a mock's answer to reach the page
const focusSeries = async (page: Page) => {
  await page.keyboard.press("ArrowRight");
  await expect(page.locator("#home-row-0-tile-1")).toHaveClass(/focused/);
};

test.describe("DEB-5640 FR-50 — a Continue Watching tile warms what its page's Play would play", () => {
  test("a film tile focused 2 s posts one prewarm for its id; 1.5 s posts none", async ({ page }) => {
    const seen = await boot(page);
    await page.clock.fastForward(1_500);
    await settle(page);
    expect(seen.prewarms, "nothing before 2 s").toEqual([]);
    await page.clock.fastForward(600);
    await expect.poll(() => seen.prewarms.length).toBe(1);
    expect(seen.prewarms[0].item_id).toBe("m1");
    expect(seen.prewarms[0].capabilities).toBeTruthy();
    await settle(page);
    expect(seen.prewarms).toHaveLength(1);
    expect(seen.cancels).toEqual([]);
  });

  test("focus leaving a tile before 2 s posts nothing at all, not even a cancel", async ({ page }) => {
    const seen = await boot(page);
    await page.clock.fastForward(1_500);
    await page.keyboard.press("ArrowRight");   // the series tile; its own 2 s starts here
    await page.clock.fastForward(1_500);
    await page.keyboard.press("ArrowLeft");
    await page.clock.fastForward(1_500);
    await settle(page);
    expect(seen.prewarms).toEqual([]);
    expect(seen.cancels).toEqual([]);
    expect(seen.seriesReads, "the series' reads start only after the dwell").toBe(0);
  });

  test("a series tile reads the series and its playstate after 2 s, then warms the episode its page would play", async ({ page }) => {
    const seen = await boot(page);
    await focusSeries(page);
    await page.clock.fastForward(1_500);
    await settle(page);
    expect(seen.seriesReads).toBe(0);
    expect(seen.playstateReads).toEqual([]);
    await page.clock.fastForward(600);
    await expect.poll(() => seen.prewarms.length).toBe(1);
    expect(seen.prewarms[0].item_id).toBe("s1e2");
    expect(seen.seriesReads).toBe(1);
    expect(seen.playstateReads).toHaveLength(1);
    expect(seen.playstateReads[0]).toContain("sr1");
  });

  test("a tile in another row posts none", async ({ page }) => {
    const seen = await boot(page);
    await page.keyboard.press("ArrowDown");
    await expect(page.locator("#home-row-1-tile-0")).toHaveClass(/focused/);
    await page.clock.fastForward(5_000);
    await settle(page);
    expect(seen.prewarms).toEqual([]);
    expect(seen.cancels).toEqual([]);
  });

  test("focus off a warmed tile posts the cancel at once, and the next tile starts its own 2 s", async ({ page }) => {
    const seen = await boot(page);
    await page.clock.fastForward(2_100);
    await expect.poll(() => seen.prewarms.length).toBe(1);
    await page.keyboard.press("ArrowDown");
    await expect.poll(() => seen.cancels).toEqual([{ item_id: "m1" }]);   // with no clock moved: at once
    await settle(page);
    await page.clock.fastForward(60_000);
    await settle(page);
    expect(seen.prewarms, "a tile left posts no more").toHaveLength(1);
  });

  test("OK on a warmed film tile keeps the hold: the page ready posts no cancel and no second prewarm, and Play cancels nothing", async ({ page }) => {
    const seen = await boot(page);
    await page.clock.fastForward(2_100);
    await expect.poll(() => seen.prewarms.length).toBe(1);
    await page.keyboard.press("Enter");
    await expect(page.locator("#detail-play")).toHaveClass(/focused/);
    await settle(page);
    await page.clock.fastForward(2_100);
    await settle(page);
    expect(seen.cancels).toEqual([]);
    expect(seen.prewarms, "no second dwell, no new post").toHaveLength(1);
    await page.keyboard.press("Enter");
    await waitForAvplayCall(page, "open");
    await settle(page);
    expect(seen.starts).toHaveLength(1);
    expect(seen.cancels, "Play adopts the job").toEqual([]);
  });

  test("OK before the 2 s are up: the dwell is not restarted by the page, and one prewarm follows at 2 s from the focus", async ({ page }) => {
    const seen = await boot(page);
    await focusSeries(page);
    await page.clock.fastForward(1_000);
    await page.keyboard.press("Enter");
    await expect(page.locator("#detail-play")).toHaveClass(/focused/);
    await settle(page);
    expect(seen.prewarms).toEqual([]);
    await page.clock.fastForward(1_100);
    await expect.poll(() => seen.prewarms.map((p) => p.item_id)).toEqual(["s1e2"]);
    await settle(page);
    expect(seen.cancels).toEqual([]);
    await page.clock.fastForward(2_100);
    await settle(page);
    expect(seen.prewarms, "the page did not warm it again").toHaveLength(1);
  });

  test("a page whose Play is another episode cancels the first and warms the second 2 s later", async ({ page }) => {
    const seen = await boot(page);
    await focusSeries(page);
    await page.clock.fastForward(2_100);
    await expect.poll(() => seen.prewarms.map((p) => p.item_id)).toEqual(["s1e2"]);
    // The server moved on between the tile's read and the page's: the first episode is the one in progress now.
    seen.playstate = { sr1: state({ continue_episode_id: "s1e1" }), s1e1: state({ resume_ms: 5_000, played_pct: 0.1 }) };
    await page.keyboard.press("Enter");
    await expect(page.locator("#detail-play")).toHaveClass(/focused/);
    await expect.poll(() => seen.cancels).toEqual([{ item_id: "s1e2" }]);
    await settle(page);
    expect(seen.prewarms, "the 2 s starts again for the new one").toHaveLength(1);
    await page.clock.fastForward(2_100);
    await expect.poll(() => seen.prewarms.map((p) => p.item_id)).toEqual(["s1e2", "s1e1"]);
  });

  test("Back from the page to Home cancels, and the tile still in focus warms again after 2 s", async ({ page }) => {
    const seen = await boot(page);
    await page.clock.fastForward(2_100);
    await expect.poll(() => seen.prewarms.length).toBe(1);
    await page.keyboard.press("Enter");
    await expect(page.locator("#detail-play")).toHaveClass(/focused/);
    await page.keyboard.press("Escape");
    await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
    await expect.poll(() => seen.cancels).toEqual([{ item_id: "m1" }]);
    await settle(page);
    await page.clock.fastForward(2_100);
    await expect.poll(() => seen.prewarms.length).toBe(2);
  });
});
