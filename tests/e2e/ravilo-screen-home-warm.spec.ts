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

// DEB-5697 (DEB-5685, FR-61) — Home opens from the last feed it drew. The real ravilo-screen.js bundle, the backend stood in for by
// page.route: a reload with /api/tv/home held shows the saved tiles before any skeleton, and what the network answers replaces them in place.
test.describe("DEB-5685 FR-61 — Home opens from the last feed it drew", () => {
  const homeOf = (...names: string[]) => ({
    heroes: [], channels: [], tile_shape: "POSTER", hero_height_pct: 48, focus_detail: "none",
    rows: [{ id: "new", title: "New Arrivals", kind: "NEWLY_ADDED", items: names.map((n) => card(`id-${n}`, `Title ${n}`)) }],
  });
  const SAVED_KEY = (userId: string) => `ravilo.screen.homeSnapshot.${userId}`;

  type Net = { mode: "answer" | "hold" | "fail"; feed: object; reads: number; release: (() => void)[] };

  /** Home answers, holds until [Net.release] runs, or fails with a 500. Profiles are set once, so a reload keeps whatever the test left in storage. */
  async function open(page: Page, profiles = [{ userId: "viewer", displayName: "Test Viewer", token: "test-token" }]): Promise<Net> {
    const net: Net = { mode: "answer", feed: homeOf("a", "b"), reads: 0, release: [] };
    await page.routeWebSocket(/\/api\/tv\/events/, () => {});
    await page.addInitScript((p) => {
      if (!sessionStorage.getItem("seeded")) localStorage.setItem("ravilo.screen.serverUrl", "http://app:9505");   // once: Change server's reload must reach setup
      sessionStorage.setItem("seeded", "1");
      if (localStorage.getItem("ravilo.screen.profiles") == null) localStorage.setItem("ravilo.screen.profiles", JSON.stringify(p));
      // Counts every skeleton ever added to the page, so "before any skeleton" is a count, not a moment.
      (window as any).__skeletons = 0;
      new MutationObserver((records) => records.forEach((r) => r.addedNodes.forEach((n) => {
        if (n instanceof HTMLElement && (n.matches(".skeleton") || n.querySelector(".skeleton"))) (window as any).__skeletons++;
      }))).observe(document, { childList: true, subtree: true });
    }, profiles);
    await page.route(`${API}/**`, async (route) => {
      const url = new URL(route.request().url());
      if (route.request().method() === "OPTIONS") return route.fulfill({ status: 204 });
      if (url.pathname === "/api/tv/home") {
        net.reads++;
        if (net.mode === "hold") await new Promise<void>((resolve) => net.release.push(resolve));
        if (net.mode === "fail") return route.fulfill({ status: 500, contentType: "application/json", body: "{}" });
        return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(net.feed) });
      }
      if (url.pathname.startsWith("/api/tv/image/")) return route.fulfill({ status: 204 });
      return route.fulfill({ status: 404, contentType: "application/json", body: "{}" });
    });
    return net;
  }
  const titles = (page: Page) => page.locator("#shell-body .home-tile .tile-title");
  const skeletons = (page: Page) => page.evaluate(() => (window as any).__skeletons as number);
  const stored = (page: Page, userId = "viewer") => page.evaluate((k) => localStorage.getItem(k), SAVED_KEY(userId));

  test("a reload with Home held shows the saved tiles before any skeleton, and the answer replaces them in place", async ({ page }) => {
    const net = await open(page);
    await page.goto(SCREEN_URL);
    await expect(titles(page)).toHaveText(["Title a", "Title b"]);
    await expect.poll(() => stored(page)).not.toBeNull();   // the feed that drew from the network is saved
    expect(await skeletons(page)).toBeGreaterThan(0);       // the first start had nothing saved: it drew the skeleton

    net.mode = "hold"; net.feed = homeOf("x", "a", "b"); net.reads = 0;
    await page.reload();
    await expect(titles(page)).toHaveText(["Title a", "Title b"]);   // the saved feed, while the read is out
    await expect.poll(() => net.reads).toBe(1);
    await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
    expect(await skeletons(page), "no skeleton before the saved feed drew").toBe(0);
    await expect(page.locator("#shell-saved")).toBeHidden();       // the read is out, not failed

    net.mode = "answer"; net.release.splice(0).forEach((r) => r());
    await expect(titles(page)).toHaveText(["Title x", "Title a", "Title b"]);
    expect(await skeletons(page), "the answer replaced the feed in place: no skeleton").toBe(0);
    await expect.poll(async () => JSON.stringify(JSON.parse((await stored(page))!).feed.rows[0].items.length)).toBe("3");
  });

  test("a held then failed read shows home.showing_saved, and it goes when an answer draws", async ({ page }) => {
    await page.clock.install();
    const net = await open(page);
    await page.goto(SCREEN_URL);
    await expect(titles(page)).toHaveText(["Title a", "Title b"]);
    await expect.poll(() => stored(page)).not.toBeNull();

    net.mode = "hold"; net.reads = 0;
    await page.reload();
    await expect(titles(page)).toHaveText(["Title a", "Title b"]);
    await expect.poll(() => net.reads).toBe(1);
    await expect(page.locator("#shell-saved")).toBeHidden();
    net.mode = "fail"; net.release.splice(0).forEach((r) => r());
    await expect(page.locator("#shell-saved")).toBeVisible();
    await expect(page.locator("#shell-saved")).toHaveText("Showing saved content — trying to reconnect");
    await expect(titles(page)).toHaveText(["Title a", "Title b"]);   // the saved feed stays
    await expect(page.locator("#shell-body .load-failure")).toHaveCount(0);

    net.mode = "answer"; net.feed = homeOf("c", "a", "b");
    await page.clock.fastForward(5_100);                               // FR-59's quiet ask behind a failure
    await expect(titles(page)).toHaveText(["Title c", "Title a", "Title b"]);
    await expect(page.locator("#shell-saved")).toBeHidden();
    expect(await skeletons(page)).toBe(0);
  });

  test("a feed saved over 7 days ago is not drawn: the skeleton comes first", async ({ page }) => {
    const net = await open(page);
    await page.goto(SCREEN_URL);
    await expect(titles(page)).toHaveText(["Title a", "Title b"]);
    await expect.poll(() => stored(page)).not.toBeNull();
    await page.evaluate((k) => {
      const saved = JSON.parse(localStorage.getItem(k)!);
      saved.savedAtEpochMs = Date.now() - 8 * 24 * 3600 * 1000;
      localStorage.setItem(k, JSON.stringify(saved));
    }, SAVED_KEY("viewer"));
    net.mode = "hold";
    await page.reload();
    await expect(page.locator("#shell-body .skeleton")).toBeVisible();
    await expect(titles(page)).toHaveCount(0);
    net.mode = "answer"; net.release.splice(0).forEach((r) => r());
    await expect(titles(page)).toHaveText(["Title a", "Title b"]);
  });

  test("a second profile does not see the first's snapshot", async ({ page }) => {
    const net = await open(page);
    await page.goto(SCREEN_URL);
    await expect(titles(page)).toHaveText(["Title a", "Title b"]);
    await expect.poll(() => stored(page)).not.toBeNull();
    await page.evaluate(() => localStorage.setItem("ravilo.screen.profiles", JSON.stringify([
      { userId: "viewer", displayName: "Test Viewer", token: "test-token" }, { userId: "other", displayName: "Other Viewer", token: "other-token" }])));
    net.mode = "hold"; net.feed = homeOf("o1");
    await page.reload();
    await expect(page.locator("#profile-picker")).toHaveClass(/\bon\b/);
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("Enter");                                // the second profile
    await expect(page.locator("#shell")).toHaveClass(/\bon\b/);
    await expect(page.locator("#shell-body .skeleton")).toBeVisible();
    await expect(titles(page)).toHaveCount(0);                         // not the first profile's tiles
    net.mode = "answer"; net.release.splice(0).forEach((r) => r());
    await expect(titles(page)).toHaveText(["Title o1"]);
    await expect.poll(() => stored(page, "other")).not.toBeNull();
    expect(JSON.parse((await stored(page))!).feed.rows[0].items).toHaveLength(2);   // and its own save left the first's alone
  });

  test("a play from a snapshot tile opens the title's page", async ({ page }) => {
    const net = await open(page);
    await page.route(`${API}/api/tv/movie/id-a`, (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(movieDetail("id-a")) }));
    await page.goto(SCREEN_URL);
    await expect(titles(page)).toHaveText(["Title a", "Title b"]);
    await expect.poll(() => stored(page)).not.toBeNull();
    net.mode = "hold";
    await page.reload();
    await expect(titles(page)).toHaveText(["Title a", "Title b"]);
    await page.keyboard.press("Enter");
    await expect(page.locator("#shell-body .detail")).toBeVisible();
  });

  test("signing the profile out removes its snapshot key", async ({ page }) => {
    await open(page);
    await page.goto(SCREEN_URL);
    await expect(titles(page)).toHaveText(["Title a", "Title b"]);
    await expect.poll(() => stored(page)).not.toBeNull();
    await page.keyboard.press("ArrowUp");
    for (let i = 0; i < 4; i++) await page.keyboard.press("ArrowRight");
    await page.keyboard.press("Enter");
    await expect(page.locator("#profile-menu")).toHaveClass(/\bon\b/);
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("Enter");                                // Sign out
    await expect(page.locator("#shell")).not.toHaveClass(/\bon\b/);
    expect(await stored(page)).toBeNull();
  });

  test("Change server removes every snapshot", async ({ page }) => {
    await open(page, [{ userId: "viewer", displayName: "Test Viewer", token: "test-token" }, { userId: "other", displayName: "Other Viewer", token: "other-token" }]);
    await page.goto(SCREEN_URL);
    await page.keyboard.press("Enter");                                // the picker's first profile
    await expect(titles(page)).toHaveText(["Title a", "Title b"]);
    await expect.poll(() => stored(page)).not.toBeNull();
    await page.evaluate(([from, to]) => localStorage.setItem(to, localStorage.getItem(from)!), [SAVED_KEY("viewer"), SAVED_KEY("other")]);   // a second profile's, as a switch would have left it
    await page.keyboard.press("ArrowUp");
    for (let i = 0; i < 4; i++) await page.keyboard.press("ArrowRight");
    await page.keyboard.press("Enter");
    await expect(page.locator("#profile-menu")).toHaveClass(/\bon\b/);
    await page.keyboard.press("ArrowDown");                            // Switch profile -> Settings
    await page.keyboard.press("Enter");
    await expect(page.locator("#set-server-note")).toBeVisible();
    for (let i = 0; i < 3; i++) await page.keyboard.press("ArrowDown");
    await expect(page.locator("#shell .shell-target.focused")).toHaveAttribute("id", "set-change-server");
    await page.keyboard.press("Enter");
    await expect(page.locator("#setup")).toHaveClass(/\bon\b/);
    expect(await stored(page)).toBeNull();
    expect(await stored(page, "other")).toBeNull();
  });
});
