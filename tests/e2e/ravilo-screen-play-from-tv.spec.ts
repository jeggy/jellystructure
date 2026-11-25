import { test, expect, type Page } from "@playwright/test";
import { fakeAvPlayInitScript, avplayCalls, waitForAvplayCall, openPickerFromChrome } from "./helpers/screen-cast";

// DEB-4661 (DEB-4654 FR-9, FR-10) — the Tizen app plays from its own remote, and a phone can still send a title to it.
// Driven through the REAL ravilo-screen.js bundle with the fake AVPlay of phase 248. The backend is stood in for by
// page.route, and the events socket by a fake that the test pushes frames into: this file proves what the TV does with
// the answers it gets (resume, progress, stop, auto-advance, Back, the refusal copy), not what the server says. The
// server's own routes have their own specs.

const SCREEN_URL = process.env.RAVILO_SCREEN_CAST_URL ?? "http://localhost:8092";
const API = "http://app:9505";

const card = (id: string, title: string) => ({
  id, kind: "MOVIE", title, year: 2024, genre: null, rating: null,
  poster_url: `/api/tv/image/${id}-poster`, backdrop_url: `/api/tv/image/${id}-backdrop`,
});
const feed = {
  heroes: [], channels: [],
  rows: [{ id: "new", title: "New Arrivals", kind: "NEWLY_ADDED", items: [card("ep-1", "Paper Moon")] }],
  tile_shape: "POSTER", hero_height_pct: 48, focus_detail: "none",
};
const ticket = (itemId: string, startMs: number) => ({
  jellyfin_base_url: API, access_token: "", item_id: itemId, container: "ts", direct_play: false,
  hls_url: `${API}/hls/${itemId}/master.m3u8`, start_position_ms: startMs, expires_at: 9_999_999_999,
});
const sessionLoad = (kind: string, id: string, extra: object = {}) =>
  ({ type: "session_load", session_id: "ps-1", kind, items: [id], index: 0, start_ms: 0, title: "Paper Moon", ...extra });
const playItem = (id: string, extra: object = {}) => ({ type: "play_item", jellyfin_id: id, kind: "movie", title: "Paper Moon", ...extra });

type Seen = { progress: any[]; stops: any[]; starts: any[]; remotePlays: any[]; statuses: any[]; restreams: any[] };

/** A fake events socket the test pushes frames into, installed before the bundle runs. */
function fakeSocketInitScript() {
  const sockets: any[] = [];
  (window as any).__pushEvent = (frame: object) => {
    for (const s of sockets) if (s.readyState === 1) s.emit("message", new MessageEvent("message", { data: JSON.stringify(frame) }));
  };
  (window as any).__socketCount = () => sockets.filter((s) => s.readyState === 1).length;
  class FakeSocket extends EventTarget {
    static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
    url: string; readyState = 0; binaryType = "blob"; protocol = ""; extensions = ""; bufferedAmount = 0;
    onopen: any = null; onmessage: any = null; onclose: any = null; onerror: any = null;
    constructor(url: string) {
      super();
      this.url = url;
      sockets.push(this);
      setTimeout(() => { this.readyState = 1; this.emit("open", new Event("open")); }, 0);
    }
    emit(type: string, ev: Event) { this.dispatchEvent(ev); (this as any)["on" + type]?.(ev); }
    send(data: string) { ((window as any).__sent ??= []).push(data); }
    close() { this.readyState = 3; this.emit("close", new Event("close")); }
  }
  (window as any).WebSocket = FakeSocket;
}

const seriesCard = { ...card("series-1", "Paper Series"), kind: "SERIES" };
const episode = (id: string, n: number, title: string) => ({ id, episode_number: n, title, runtime: 40, overview: null, still_url: null });
const seriesDetail = {
  card: seriesCard, synopsis: null, cast: [], related: [], logo_url: "/api/tv/image/series-1-logo",
  seasons: [
    { index: 0, name: "Specials", episodes: [episode("sp-1", 1, "Behind the Reel")] },
    { index: 1, name: "Season 1", episodes: [episode("s1e1", 1, "Opening Reel"), episode("s1e2", 2, "Second Reel")] },
  ],
};

async function boot(page: Page, opts: {
  start?: (itemId: string, body?: any) => Promise<{ status: number; body: object }>; series?: object | null; continueEpisode?: string; refuseRemotePlay?: boolean;
  /** DEB-4932 — what the server answers a restream with (a track switch or a burn-in); unset = the route does not exist (404). */
  restream?: (body: any) => object;
  /** DEB-4932 — extra localStorage entries, set before the bundle runs. */
  storage?: Record<string, string>;
} = {}): Promise<Seen> {
  const seen: Seen = { progress: [], stops: [], starts: [], remotePlays: [], statuses: [], restreams: [] };
  await page.addInitScript(fakeAvPlayInitScript);
  await page.addInitScript(fakeSocketInitScript);
  await page.addInitScript((storage) => {
    for (const [k, v] of Object.entries(storage)) localStorage.setItem(k, v);
  }, opts.storage ?? {});
  await page.addInitScript(() => {
    localStorage.setItem("ravilo.screen.serverUrl", "http://app:9505");
    localStorage.setItem("ravilo.screen.profiles", JSON.stringify([{ userId: "viewer", displayName: "Test Viewer", token: "test-token" }]));
  });
  const json = (route: any, status: number, body: object) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
  await page.route(`${API}/**`, async (route) => {
    const req = route.request();
    const path = new URL(req.url()).pathname;
    const body = req.postData() ? JSON.parse(req.postData()!) : null;
    if (req.method() === "OPTIONS") return route.fulfill({ status: 204 });
    if (path === "/api/tv/home") return json(route, 200, opts.series === undefined ? feed
      : { ...feed, rows: [{ ...feed.rows[0], items: [seriesCard] }] });
    // DEB-4832 — a series' detail, and the series' own entry in the playstate (its Continue Watching episode).
    if (path === "/api/tv/series/series-1") return opts.series ? json(route, 200, opts.series) : json(route, 404, {});
    if (path === "/api/tv/playstate") return json(route, 200, opts.continueEpisode
      ? { "series-1": { resume_ms: 0, played: false, played_pct: 0, continue_episode_id: opts.continueEpisode } } : {});
    if (path === "/api/tv/playback/start") {
      seen.starts.push(body);
      const answer = opts.start ? await opts.start(body.item_id, body) : { status: 200, body: ticket(body.item_id, 42_000) };
      return json(route, answer.status, answer.body);
    }
    if (path === "/api/tv/playback/restream") {
      seen.restreams.push(body);
      return opts.restream ? json(route, 200, opts.restream(body)) : json(route, 404, {});
    }
    if (path.startsWith("/api/tv/subtitle/")) return route.fulfill({ status: 200, contentType: "text/vtt", body: "WEBVTT\n\n00:00.000 --> 99:00.000\nHej\n" });
    if (path === "/api/tv/playback/progress") { seen.progress.push({ ...body, at: Date.now() }); return route.fulfill({ status: 204 }); }
    if (path === "/api/tv/playback/stop") { seen.stops.push(body); return route.fulfill({ status: 204 }); }
    if (path === "/api/tv/playback/status") { seen.statuses.push(body); return route.fulfill({ status: 204 }); }
    if (path === "/api/remote/play") {
      seen.remotePlays.push(body);
      if (opts.refuseRemotePlay) return route.fulfill({ status: 403, contentType: "application/json", body: "{}" });   // what a Tizen 5.0 widget gets (DEB-4719)
      // The backend's answer to a play aimed at this very device: the same push a phone's play makes.
      await route.fulfill({ status: 202 });
      await page.evaluate((frame) => (window as any).__pushEvent(frame), playItem(body.jellyfin_item_id));
      return;
    }
    // A movie's detail: where its intro is, and its logo. An episode's id is not one, so it gets the 404 below.
    if (path === "/api/tv/movie/ep-1") return json(route, 200, {
      card: card("ep-1", "Paper Moon"), synopsis: null, runtime: 90, cast: [], related: [],
      logo_url: "/api/tv/image/ep-1-logo", segments: { intro_start_ms: 40_000, intro_end_ms: 50_000 },
    });
    if (path.startsWith("/api/tv/image/")) return route.fulfill({ status: 204 });
    return json(route, 404, {});
  });
  await page.goto(SCREEN_URL);
  await expect(page.locator("#shell-body .home-feed")).toBeVisible();
  await expect.poll(() => page.evaluate(() => (window as any).__socketCount())).toBeGreaterThan(0);
  return seen;
}

const shellOn = (page: Page) => expect(page.locator("#shell")).toHaveClass(/\bon\b/);
const shellOff = (page: Page) => expect(page.locator("#shell")).not.toHaveClass(/\bon\b/);

test.describe("DEB-4661 — Ravilo on a Samsung TV plays from its own remote", () => {
  test("FR-9: OK on a title starts it, resumes from the server, reports every 10 s, stops and returns on Back", async ({ page }) => {
    test.setTimeout(90_000);
    const seen = await boot(page);
    await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);

    await page.keyboard.press("Enter");                      // the title's page
    await expect(page.locator(".detail-title")).toHaveText("Paper Moon");
    await expect(page.locator("#shell-body")).not.toContainText(/cast|play on/i);
    await page.keyboard.press("Enter");                      // Play

    // Asked by the TV itself (DEB-5503: no longer hls_only): no round trip through /api/remote/play, which
    // a Tizen 5.0 widget is refused (DEB-4719).
    const opened = await waitForAvplayCall(page, "open");
    expect(String(opened.call[1])).toContain("/hls/ep-1/");
    expect(seen.remotePlays).toHaveLength(0);
    expect(seen.starts).toHaveLength(1);
    expect(seen.starts[0].item_id).toBe("ep-1");
    expect(seen.starts[0].start_position_ms ?? 0).toBe(0);   // the server's playstate decides where it resumes
    // DEB-5503 (FR-43) — AVPlay plays a file that fits; an HLS-only start is the Chromecast receiver's limit, not this app's.
    expect(JSON.stringify(seen.starts[0])).not.toContain("\"hls_only\":true");
    await shellOff(page);

    // Resumes from the server's playstate: the ticket said 42 s.
    const seek = await waitForAvplayCall(page, "seekTo", opened.index);
    expect(seek.call[1]).toBe(42_000);
    await waitForAvplayCall(page, "play", opened.index);
    // The movie's own segment markers, read for a start the TV made: Skip Intro is offered inside the intro (40–50 s).
    await expect(page.locator("#skip")).toHaveClass(/\bon\b/, { timeout: 5_000 });

    // Progress every 10 s, from the resume point on.
    await expect.poll(() => seen.progress.length, { timeout: 25_000, intervals: [500] }).toBeGreaterThanOrEqual(1);
    expect(seen.progress[0]).toMatchObject({ item_id: "ep-1" });
    expect(seen.progress[0].is_paused ?? false).toBe(false);
    expect(seen.progress[0].position_ms).toBeGreaterThanOrEqual(42_000);

    // Back: with the chrome up it hides it, with nothing up it stops. A stop on exit, at the position it reached.
    for (let i = 0; i < 3 && !(await page.locator("#shell").evaluate((e) => e.classList.contains("on"))); i++) await page.keyboard.press("Escape");
    await shellOn(page);
    expect(seen.stops).toHaveLength(1);
    expect(seen.stops[0]).toMatchObject({ item_id: "ep-1" });
    expect(seen.stops[0].position_ms).toBeGreaterThanOrEqual(42_000);

    // The page it was started from, with focus on the item that played; Back again is the tile.
    await expect(page.locator(".detail-title")).toHaveText("Paper Moon");
    await page.keyboard.press("Escape");
    await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
  });

  test("FR-9: the next-up countdown plays the next episode on the TV itself, through its own device", async ({ page }) => {
    test.setTimeout(90_000);
    const seen = await boot(page);
    await page.evaluate((frame) => (window as any).__pushEvent(frame), playItem("ep-1", {
      next_id: "ep-2", next_title: "Second Reel", next_kicker: "S1 · E2",
      segments: { credits_start_ms: 43_000 },
    }));
    await waitForAvplayCall(page, "play");
    await expect(page.locator("#nextup")).toHaveClass(/\bon\b/, { timeout: 15_000 });
    await expect(page.locator("#nu-t")).toContainText("Second Reel");
    await expect.poll(() => seen.remotePlays.length, { timeout: 20_000 }).toBe(1);
    expect(seen.remotePlays[0]).toMatchObject({ jellyfin_item_id: "ep-2" });
    expect(String(seen.remotePlays[0].device_id)).toMatch(/^screen-/);
    // The item it leaves is stopped first, so the progress the viewer made is kept; then the push for ep-2 starts.
    expect(seen.stops[0]).toMatchObject({ item_id: "ep-1" });
    await expect.poll(() => seen.starts.map((s) => s.item_id), { timeout: 10_000 }).toEqual(["ep-1", "ep-2"]);
  });

  test("DEB-4865: a TV-started episode's next-up card plays the next one on the TV, though /api/remote/play is refused", async ({ page }) => {
    test.setTimeout(90_000);
    const withCredits = (e: object, segments: object) => ({ ...e, segments });
    const detail = {
      ...seriesDetail,
      seasons: [{ index: 1, name: "Season 1", episodes: [
        withCredits(episode("s1e1", 1, "Opening Reel"), { credits_start_ms: 43_000 }),
        withCredits(episode("s1e2", 2, "Second Reel"), { credits_start_ms: 43_000 }),
        episode("s1e3", 3, "Third Reel"),
      ] }],
    };
    const seen = await boot(page, { series: detail, refuseRemotePlay: true });
    await page.keyboard.press("Enter");                      // the series' page
    await expect(page.locator("#detail-play")).toBeVisible();
    await page.keyboard.press("Enter");                      // Play: s1e1, with s1e2 and s1e3 after it
    await waitForAvplayCall(page, "play");
    await expect(page.locator("#nextup")).toHaveClass(/\bon\b/, { timeout: 15_000 });
    await expect(page.locator("#nu-t")).toContainText("Second Reel");
    // The countdown starts s1e2 itself: a start for it reaches the server, the refused route is never asked, and no failure shows.
    await expect.poll(() => seen.starts.map((s) => s.item_id), { timeout: 20_000 }).toEqual(["s1e1", "s1e2"]);
    expect(seen.remotePlays).toHaveLength(0);
    expect(seen.stops[0]).toMatchObject({ item_id: "s1e1" });
    await expect(page.locator("#failed")).not.toHaveClass(/\bon\b/);
    // And the card on s1e2 names the episode after it, the way a phone's push would have.
    await expect(page.locator("#nextup")).toHaveClass(/\bon\b/, { timeout: 15_000 });
    await expect(page.locator("#nu-t")).toContainText("Third Reel");
  });

  test("DEB-5025 (FR-28, FR-29): Next is on the control bar only when the next episode is known, and OK on it starts that episode", async ({ page }) => {
    test.setTimeout(90_000);
    const detail = {
      ...seriesDetail,
      seasons: [{ index: 1, name: "Season 1", episodes: [episode("s1e1", 1, "Opening Reel"), episode("s1e2", 2, "Second Reel"), episode("s1e3", 3, "Third Reel")] }],
    };
    const seen = await boot(page, { series: detail, refuseRemotePlay: true, start: async (itemId) => ({ status: 200, body: ticket(itemId, 0) }) });
    await page.keyboard.press("Enter");                      // the series' page
    await expect(page.locator("#detail-play")).toBeVisible();
    await page.keyboard.press("Enter");                      // Play: s1e1, with s1e2 and s1e3 after it
    await waitForAvplayCall(page, "play");
    await page.keyboard.press("ArrowDown");                  // hidden: ↓ only reveals
    await expect(page.locator("#overlay")).toHaveClass(/\bon\b/);
    await expect(page.locator("#ov-play")).toHaveClass(/\bfoc\b/);
    await expect(page.locator("#ov-next")).toBeVisible();
    await expect(page.locator("#ov-next")).toContainText(">>");
    await page.keyboard.press("ArrowRight");                 // +30s
    await page.keyboard.press("ArrowRight");                 // Audio & subtitles
    await page.keyboard.press("ArrowRight");                 // Next
    await expect(page.locator("#ov-next")).toHaveClass(/\bfoc\b/);
    await page.keyboard.press("ArrowRight");                 // the end of the order: nothing
    await expect(page.locator("#ov-next")).toHaveClass(/\bfoc\b/);
    await page.keyboard.press("Enter");
    await expect.poll(() => seen.starts.map((s) => s.item_id), { timeout: 10_000 }).toEqual(["s1e1", "s1e2"]);
    expect(seen.stops[0]).toMatchObject({ item_id: "s1e1" });
    await waitForAvplayCall(page, "play", (await avplayCalls(page)).filter((c) => c[0] === "open").length);
    await expect(page.locator("#overlay"), "a new episode starts with the controls hidden").not.toHaveClass(/\bon\b/);
  });

  test("DEB-4874: a start made over an open stream closes it first — the fake AVPlay throws INVALID_STATE on an open() over one, as the device does", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page);
    await page.evaluate((frame) => (window as any).__pushEvent(frame), playItem("ep-1"));
    const first = await waitForAvplayCall(page, "play");
    await page.evaluate((frame) => (window as any).__pushEvent(frame), playItem("ep-2", { title: "Second Reel" }));   // a phone's push, mid-stream
    const second = await waitForAvplayCall(page, "open", first.index + 1);
    expect(String(second.call[1])).toContain("/hls/ep-2/");
    await waitForAvplayCall(page, "play", second.index);
    // A close sits between the two opens, and nothing about the second start failed.
    const names = (await avplayCalls(page)).map((c) => c[0]);
    expect(names.slice(first.index, second.index)).toContain("close");
    await expect(page.locator("#failed")).not.toHaveClass(/\bon\b/);
    expect(seen.starts.map((s) => s.item_id)).toEqual(["ep-1", "ep-2"]);
  });

  test("DEB-4874: a start whose open() fails says so and reports no stop at a length — the episode that never played is not marked watched", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page);
    await page.evaluate((frame) => (window as any).__pushEvent(frame), playItem("ep-1"));
    await waitForAvplayCall(page, "play");
    await page.evaluate(() => { (window as any).webapis.avplay.open = () => { throw new Error("PLAYER_ERROR_INVALID_STATE"); }; });
    await page.evaluate((frame) => (window as any).__pushEvent(frame), playItem("ep-2", { title: "Second Reel" }));
    await expect(page.locator("#failed")).toHaveClass(/\bon\b/, { timeout: 10_000 });
    // ep-2's session is closed at 0; nothing is ever reported for it at the 60 s the fake stream is long.
    await expect.poll(() => seen.stops.filter((s) => s.item_id === "ep-2").map((s) => s.position_ms)).toEqual([0]);
    await page.waitForTimeout(2_000);
    expect(seen.stops.filter((s) => s.item_id === "ep-2" && s.position_ms > 0)).toEqual([]);
  });

  test("FR-10: a phone's play takes over the page the viewer is on, and the end returns to it", async ({ page }) => {
    await boot(page);
    await page.keyboard.press("ArrowDown").catch(() => {});
    const focusedBefore = await page.locator("#shell-body .shell-target.focused").getAttribute("id");
    await page.evaluate((frame) => (window as any).__pushEvent(frame), playItem("ep-1"));
    await waitForAvplayCall(page, "play");
    await shellOff(page);
    // The phone's own remote: Stop, in the capitalised wire shape.
    await page.evaluate(() => (window as any).__pushEvent({ type: "playstate_command", command: "Stop" }));
    await shellOn(page);
    await expect(page.locator("#shell-body .home-feed")).toBeVisible();
    await expect(page.locator("#shell-body .shell-target.focused")).toHaveAttribute("id", focusedBefore!);
    // FR-10: nothing on the TV sends to another device.
    await expect(page.locator("#shell")).not.toContainText(/play on|cast/i);
  });

  test("DEB-4808 (FR-10): a phone's Play on reaches the TV as a session_load, and the film starts as a push does", async ({ page }) => {
    const seen = await boot(page);
    await page.evaluate((frame) => (window as any).__pushEvent(frame), sessionLoad("film", "ep-1", { start_ms: 30_000 }));
    await waitForAvplayCall(page, "play");
    await shellOff(page);
    expect(seen.starts.map((b) => b.item_id)).toEqual(["ep-1"]);
    expect(seen.remotePlays).toEqual([]);
    await page.evaluate(() => (window as any).__pushEvent({ type: "playstate_command", command: "Stop" }));
    await shellOn(page);
    await expect(page.locator("#shell-body .home-feed")).toBeVisible();
  });

  test("DEB-4808 (FR-10): a session_load of a kind the TV does not play is refused on the session, and nothing starts", async ({ page }) => {
    const seen = await boot(page);
    await page.evaluate((frame) => (window as any).__pushEvent(frame), sessionLoad("music", "song-1"));
    await expect.poll(() => page.evaluate(() => ((window as any).__sent ?? []).map((f: string) => JSON.parse(f))))
      .toContainEqual({ type: "session_load_failed", id: "ps-1", kind: "music" });
    expect(seen.starts).toEqual([]);
    await shellOn(page);
  });

  test("FR-9: a refused start from the TV says why without \"from your phone\"; one from a phone still does", async ({ page }) => {
    test.setTimeout(60_000);
    await boot(page, { start: async () => ({ status: 500, body: {} }) });
    await page.keyboard.press("Enter");
    await expect(page.locator("#detail-play")).toBeVisible();   // the page has drawn: OK is now Play
    await page.keyboard.press("Enter");
    await expect(page.locator("#failed")).toHaveClass(/\bon\b/, { timeout: 15_000 });
    await expect(page.locator("#failed-t")).toHaveText("That didn’t start");
    await expect(page.locator("#failed-s")).toHaveText("Press Back and try again.");
    await expect(page.locator("#failed-s")).not.toContainText(/phone/i);
    // Back leaves the refusal at once and lands on the page the start came from.
    await page.keyboard.press("Escape");
    await shellOn(page);
    await expect(page.locator(".detail-title")).toHaveText("Paper Moon");

    await page.evaluate((frame) => (window as any).__pushEvent(frame), playItem("ep-9"));
    await expect(page.locator("#failed")).toHaveClass(/\bon\b/, { timeout: 15_000 });
    await expect(page.locator("#failed-s")).toHaveText("Try again from your phone.");
  });

  test("FR-9: Back while a start is still being negotiated gives it up, and the late answer never plays", async ({ page }) => {
    test.setTimeout(60_000);
    let release: () => void = () => {};
    const held = new Promise<void>((resolve) => { release = resolve; });
    const seen = await boot(page, { start: async (id) => { await held; return { status: 200, body: ticket(id, 0) }; } });
    await page.keyboard.press("Enter");
    await expect(page.locator("#detail-play")).toBeVisible();   // the page has drawn: OK is now Play
    await page.keyboard.press("Enter");
    await expect(page.locator("#loading")).toHaveClass(/\bon\b/, { timeout: 10_000 });
    // The loading screen is drawn before the start leaves the page; a Back ahead of it opens no session, so there is
    // nothing to close. This test is about the one that was already asked for and is still being answered.
    await expect.poll(() => seen.starts.length, { timeout: 10_000 }).toBe(1);
    await page.keyboard.press("Escape");
    await shellOn(page);
    release();
    // The server did open a session for it: it is closed again, and the player never opens a stream.
    await expect.poll(() => seen.stops.length, { timeout: 10_000 }).toBe(1);
    expect(seen.stops[0]).toMatchObject({ item_id: "ep-1" });
    expect((await avplayCalls(page)).some((c) => c[0] === "open")).toBe(false);
  });

  test("DEB-4866: a title on our own encoder (a server-relative hls_url) reaches AVPlay as an absolute URL", async ({ page }) => {
    test.setTimeout(60_000);
    // What the backend's encoder path hands out: a path, not a URL. A bare path is PLAYER_ERROR_INVALID_URI on a Tizen set.
    await boot(page, { start: async (id) => ({ status: 200, body: { ...ticket(id, 0), hls_url: `/api/tv/stream/${id}/master.m3u8` } }) });
    await page.keyboard.press("Enter");                      // the title's page
    await expect(page.locator(".detail-title")).toHaveText("Paper Moon");
    await page.keyboard.press("Enter");                      // Play
    const opened = await waitForAvplayCall(page, "open");
    expect(String(opened.call[1])).toBe(`${API}/api/tv/stream/ep-1/master.m3u8`);
  });

  test("DEB-4832: OK on a series' tile plays an episode, never the series id", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page, { series: seriesDetail, continueEpisode: "s1e2" });
    await page.keyboard.press("Enter");                      // the series' page (DEB-4660)
    await expect(page.locator(".detail-title")).toHaveText("Paper Series");
    await page.keyboard.press("Enter");                      // Play
    const opened = await waitForAvplayCall(page, "open");
    expect(String(opened.call[1])).toContain("/hls/s1e2/");
    expect(seen.starts.map((s) => s.item_id)).toEqual(["s1e2"]);   // Continue Watching's episode, not "series-1"
    await waitForAvplayCall(page, "play", opened.index);
    await page.keyboard.press("Enter");                      // the overlay (pause) shows the episode's own line
    await expect(page.locator("#ov-kicker")).toHaveText("S01E02");
    await expect(page.locator("#ov-showname")).toHaveText("Paper Series");
  });

  test("DEB-4832: a series nobody started plays its first counted episode", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page, { series: seriesDetail });
    await page.keyboard.press("Enter");
    await expect(page.locator("#detail-play")).toBeVisible();   // the page has drawn: OK is now Play
    await page.keyboard.press("Enter");
    await waitForAvplayCall(page, "open");
    expect(seen.starts.map((s) => s.item_id)).toEqual(["s1e1"]);
  });

  test("DEB-4832: a series whose detail does not come says so on its page and never starts the series id", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page, { series: null });
    await page.keyboard.press("Enter");
    await expect(page.locator("#shell-body")).toHaveText("This isn't here any more");
    await page.keyboard.press("Enter");
    expect(seen.starts).toHaveLength(0);
    expect((await avplayCalls(page)).some((c) => c[0] === "open")).toBe(false);
  });

  // DEB-4899 (amends DEB-4654 FR-9) — the audio and subtitles picker leads with the language, as Android TV's does: its flag and its
  // own name, then the codec and channels. The owner's photo of the UE55RU7445 was five rows reading "E-AC-3 5.1", with the focused
  // one light grey on a white pill. Five same-codec tracks in five languages, and a sixth that is a second English version.
  test("DEB-4899: the picker names each track by its language with its flag, and the focused row is readable", async ({ page }) => {
    test.setTimeout(90_000);
    const track = (index: number, language: string, label: string, isDefault = false) =>
      ({ index, language, label, codec: "eac3", channels: 6, is_default: isDefault, external: false });
    const sub = (index: number, language: string, label: string, forced = false) =>
      ({ index, language, label, forced, is_default: false, url: `/api/tv/subtitle/${index}.vtt`, delivery_method: "external" });
    await boot(page, {
      start: async (itemId) => ({
        status: 200,
        body: {
          ...ticket(itemId, 0),
          audio: [
            track(1, "dan", "E-AC-3 Atmos 5.1", true), track(2, "eng", "E-AC-3 5.1"), track(3, "deu", "E-AC-3 5.1"),
            track(4, "swe", "E-AC-3 5.1"), track(5, "nor", "E-AC-3 5.1"), track(6, "eng", "Commentary"),
          ],
          subtitles: [sub(7, "dan", "Dansk"), sub(8, "fra", "Français - SDH"), sub(9, "eng", "English forced", true)],
        },
      }),
    });
    await page.keyboard.press("Enter");                      // the title's page
    await expect(page.locator(".detail-title")).toHaveText("Paper Moon");
    await page.keyboard.press("Enter");                      // Play
    await waitForAvplayCall(page, "open");

    await openPickerFromChrome(page);                        // Audio & subtitles → the picker, on Subtitles
    await expect(page.locator("#picker")).toHaveClass(/\bon\b/);
    const rows = page.locator("#pk-rows .pkr");
    const names = () => rows.evaluateAll((els) => els.map((e) => (e.querySelector(".l")?.firstChild as Text | null)?.textContent ?? ""));
    expect((await names()).slice(0, 4), "Off, then every subtitle language by name").toEqual(["Off", "Dansk", "Français", "English"]);
    await expect(rows.nth(2).locator("i")).toHaveText("SDH · Sound described");

    await page.keyboard.press("ArrowRight");                 // the Audio tab
    await expect(page.locator("#pk-tabs b.on")).toHaveCount(1);
    await expect(rows).toHaveCount(5);                       // one row per language
    expect(await names()).toEqual(["Dansk", "English", "Deutsch", "Svenska", "Norsk"]);
    const flags = await rows.evaluateAll((els) => els.map((e) => {
      const img = e.querySelector("img.pkr-flag") as HTMLImageElement | null;
      return img ? { src: img.getAttribute("src"), loaded: img.naturalWidth > 0 } : null;
    }));
    expect(flags, "each flag is the packed PNG, and it loaded").toEqual(["dk", "gb", "de", "se", "no"].map((cc) => ({ src: `flags/${cc}.png`, loaded: true })));

    await expect(rows.nth(0).locator("i")).toHaveText("E-AC-3 Atmos 5.1");   // the secondary line
    await expect(rows.nth(2).locator("i")).toHaveText("E-AC-3 5.1");
    await expect(rows.nth(1).locator(".rt")).toContainText("2");             // English has two versions: it opens a second level

    // The focused row is dark text on the light pill with real contrast; the 2019 set showed light grey on white.
    const contrast = await page.evaluate(() => {
      const lum = (css: string) => {
        const [r, g, b] = (css.match(/[\d.]+/g) ?? []).slice(0, 3).map((v) => {
          const c = Number(v) / 255;
          return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
        });
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
      };
      const ratio = (a: string, b: string) => (Math.max(lum(a), lum(b)) + 0.05) / (Math.min(lum(a), lum(b)) + 0.05);
      const foc = document.querySelector("#pk-rows .pkr.foc") as HTMLElement;
      const back = getComputedStyle(foc).backgroundColor;
      return { label: ratio(getComputedStyle(foc).color, back), second: ratio(getComputedStyle(foc.querySelector("i")!).color, back) };
    });
    expect(contrast.label, "the focused row's label").toBeGreaterThanOrEqual(12);
    expect(contrast.second, "the focused row's second line").toBeGreaterThanOrEqual(7);

    await page.keyboard.press("ArrowDown");                  // English → its two versions, told apart
    await page.keyboard.press("Enter");
    await expect(page.locator("#pk-head")).toHaveText("English");
    expect(await names()).toEqual(["E-AC-3 5.1", "Commentary"]);
  });
});

// DEB-4932 (DEB-4801 FR-23) — the TV remembers a viewer's audio and subtitle pick per profile and series, and a next episode
// starts in it. Same harness as above: the real bundle, the fake AVPlay, the server stood in for by page.route — so what is
// asserted is what the TV asks the server for (the `audio_language` of a start, the stream of a restream) and what it shows.
test.describe("DEB-4932 — the TV starts an episode in the audio and subtitle the viewer last chose", () => {
  const aTrack = (index: number, language: string, label: string, isDefault = false) =>
    ({ index, language, label, codec: "eac3", channels: 6, is_default: isDefault, external: false });
  const sTrack = (index: number, language: string, label: string, isDefault = false, forced = false) =>
    ({ index, language, label, forced, is_default: isDefault, url: `/api/tv/subtitle/${index}.vtt`, delivery_method: "external" });
  const withCredits = (e: object) => ({ ...e, segments: { credits_start_ms: 43_000 } });
  const binge = {
    ...seriesDetail,
    seasons: [{ index: 1, name: "Season 1", episodes: [
      withCredits(episode("s1e1", 1, "Opening Reel")), withCredits(episode("s1e2", 2, "Second Reel")), episode("s1e3", 3, "Third Reel"),
    ] }],
  };
  /** English (the file's default) and Danish audio, an English (default) and a Danish text subtitle. The server honours `audio_language`. */
  const bingeTicket = (itemId: string, body: any) => {
    const dan = body?.audio_language === "dan";
    return {
      ...ticket(itemId, itemId === "s1e1" ? 42_000 : 0),
      audio: [aTrack(1, "eng", "English", true), aTrack(2, "dan", "Dansk")], audio_stream_index: dan ? 2 : 1,
      subtitles: [sTrack(3, "eng", "English", true), sTrack(4, "dan", "Dansk")],
    };
  };
  const bingeServer = {
    series: binge, refuseRemotePlay: true,
    start: async (itemId: string, body: any) => ({ status: 200, body: bingeTicket(itemId, body) }),
    restream: (body: any) => ({ ...ticket(body.item_id, body.position_ms), audio: bingeTicket(body.item_id, {}).audio, subtitles: bingeTicket(body.item_id, {}).subtitles, audio_stream_index: body.audio_stream_index }),
  };
  const selectedRows = (page: Page) => page.locator("#pk-rows .pkr.sel .l");

  /** The series' page, then Play: the first counted episode. */
  async function playSeries(page: Page) {
    await page.keyboard.press("Enter");
    await expect(page.locator("#detail-play")).toBeVisible();
    await page.keyboard.press("Enter");
  }

  /** Series page → Play s1e1 → its picker on [tab] (the Audio tab is one ArrowRight away). */
  async function playFirstAndOpenPicker(page: Page, audioTab: boolean) {
    await page.keyboard.press("Enter");
    await expect(page.locator("#detail-play")).toBeVisible();
    await page.keyboard.press("Enter");
    await waitForAvplayCall(page, "open");
    await openPickerFromChrome(page);
    await expect(page.locator("#picker")).toHaveClass(/\bon\b/);
    if (audioTab) await page.keyboard.press("ArrowRight");
  }
  /** Let the countdown start the next episode, and wait until it is open in AVPlay. */
  async function advanceToNext(page: Page, seen: Seen) {
    const opensBefore = (await avplayCalls(page)).filter((c) => c[0] === "open").length;
    await expect.poll(() => seen.starts.map((s) => s.item_id), { timeout: 30_000 }).toEqual(["s1e1", "s1e2"]);
    await expect.poll(async () => (await avplayCalls(page)).filter((c) => c[0] === "open").length, { timeout: 15_000 }).toBeGreaterThan(opensBefore);
  }
  const openPickerOn = async (page: Page, audioTab: boolean) => {
    await openPickerFromChrome(page);
    await expect(page.locator("#picker")).toHaveClass(/\bon\b/);
    if (audioTab) await page.keyboard.press("ArrowRight");
  };

  test("FR-23: a Danish audio pick on one episode starts the next episode in Danish, and the picker shows it selected", async ({ page }) => {
    test.setTimeout(120_000);
    const seen = await boot(page, bingeServer);
    await playFirstAndOpenPicker(page, true);
    expect(seen.starts[0].audio_language ?? null, "nothing is remembered yet: the first start asks for no language").toBeNull();
    await expect(selectedRows(page)).toContainText(["English"]);
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("Enter");                      // Dansk: a pick, and a restream to it
    await expect.poll(() => seen.restreams.length).toBe(1);
    expect(seen.restreams[0]).toMatchObject({ item_id: "s1e1", audio_stream_index: 2 });

    await advanceToNext(page, seen);
    expect(seen.starts[1], "the next episode's start asks for the remembered language").toMatchObject({ item_id: "s1e2", audio_language: "dan" });
    await openPickerOn(page, true);
    await expect(selectedRows(page)).toContainText(["Dansk"]);
    expect(seen.restreams, "the ticket already carried Danish: no second restream").toHaveLength(1);
  });

  test("FR-23: a Danish subtitle pick carries to the next episode over the file's English default", async ({ page }) => {
    test.setTimeout(120_000);
    const seen = await boot(page, bingeServer);
    await playFirstAndOpenPicker(page, false);
    await expect(selectedRows(page)).toContainText(["English"]);   // the file's default, as today
    await page.keyboard.press("ArrowDown");                      // → Dansk (Off, English, Dansk)
    await page.keyboard.press("Enter");
    await advanceToNext(page, seen);
    await openPickerOn(page, false);
    await expect(selectedRows(page)).toContainText(["Dansk"]);
    expect(seen.restreams, "a text subtitle is drawn, never restreamed").toHaveLength(0);
  });

  test("FR-23: Off is a choice — the next episode shows no subtitle even where the file flags a default one", async ({ page }) => {
    test.setTimeout(120_000);
    const seen = await boot(page, bingeServer);
    await playFirstAndOpenPicker(page, false);
    await expect(selectedRows(page)).toContainText(["English"]);
    await page.keyboard.press("ArrowUp");                        // → Off
    await page.keyboard.press("Enter");
    await advanceToNext(page, seen);
    await openPickerOn(page, false);
    await expect(selectedRows(page)).toContainText(["Off"]);
  });

  test("FR-23: a picture subtitle that matches is burned in once, before the stream opens", async ({ page }) => {
    test.setTimeout(120_000);
    const pgs = (index: number, language: string, label: string) => ({ index, language, label, forced: false, is_default: false, url: null, delivery_method: "encode" });
    const seen = await boot(page, {
      series: binge, refuseRemotePlay: true,
      storage: { "ravilo.screen.tracks.viewer": JSON.stringify({ global: { sub: "dan" }, series: {} }) },
      start: async (itemId) => ({ status: 200, body: { ...ticket(itemId, 42_000), audio: [aTrack(1, "eng", "English", true)], subtitles: [pgs(5, "dan", "Dansk")] } }),
      restream: (body) => ({ ...ticket(body.item_id, body.position_ms), audio: [aTrack(1, "eng", "English", true)], subtitles: [pgs(5, "dan", "Dansk")], burned_subtitle_index: body.subtitle_stream_index, audio_stream_index: 1 }),
    });
    await playSeries(page);
    await waitForAvplayCall(page, "open");
    expect(seen.restreams).toHaveLength(1);
    expect(seen.restreams[0]).toMatchObject({ item_id: "s1e1", subtitle_stream_index: 5, position_ms: 42_000 });
    expect((await avplayCalls(page)).filter((c) => c[0] === "open"), "one open, on the restreamed stream").toHaveLength(1);
  });

  test("FR-23: the series' choice wins over the global one; a phone's push has no series and uses the global one", async ({ page }) => {
    test.setTimeout(120_000);
    const seen = await boot(page, {
      ...bingeServer,
      storage: { "ravilo.screen.tracks.viewer": JSON.stringify({ global: { audio: "dan" }, series: { "series-1": { audio: "eng" } } }) },
    });
    await playSeries(page);                          // Play from the series' page: its own choice, English
    await waitForAvplayCall(page, "open");
    expect(seen.starts[0]).toMatchObject({ item_id: "s1e1", audio_language: "eng" });
    await page.evaluate((frame) => (window as any).__pushEvent(frame), playItem("ep-1"));   // a phone's push: no series id
    await expect.poll(() => seen.starts.map((s) => s.item_id), { timeout: 15_000 }).toEqual(["s1e1", "ep-1"]);
    expect(seen.starts[1]).toMatchObject({ item_id: "ep-1", audio_language: "dan" });
  });

  test("FR-23: a series language that is not in this file falls to the global one, with one switch", async ({ page }) => {
    test.setTimeout(120_000);
    const seen = await boot(page, {
      ...bingeServer,
      storage: { "ravilo.screen.tracks.viewer": JSON.stringify({ global: { audio: "dan" }, series: { "series-1": { audio: "swe" } } }) },
    });
    await playSeries(page);
    await waitForAvplayCall(page, "open");
    expect(seen.starts[0]).toMatchObject({ audio_language: "swe" });
    expect(seen.restreams, "the file has no Swedish, so the global Danish is switched to, once").toHaveLength(1);
    expect(seen.restreams[0]).toMatchObject({ audio_stream_index: 2 });
  });

  test("FR-23: another profile's choice is not this profile's, and a removed profile's memory goes with it", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page, {
      ...bingeServer,
      storage: { "ravilo.screen.tracks.someone-else": JSON.stringify({ global: { audio: "dan" }, series: {} }) },
    });
    await playSeries(page);
    await waitForAvplayCall(page, "open");
    expect(seen.starts[0].audio_language ?? null, "viewer has chosen nothing: the file's default").toBeNull();
    await openPickerFromChrome(page);                            // the picker, on Subtitles: English is selected
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("Enter");                          // a pick by `viewer`: Dansk
    expect(await page.evaluate(() => localStorage.getItem("ravilo.screen.tracks.viewer"))).toContain("dan");
    expect(await page.evaluate(() => localStorage.getItem("ravilo.screen.tracks.someone-else")), "the other profile's entry is untouched").toContain("dan");
  });

  test("FR-23: a profile the server signs out takes its remembered choice with it", async ({ page }) => {
    test.setTimeout(60_000);
    await boot(page, {
      series: binge,
      start: async () => ({ status: 401, body: {} }),
      storage: { "ravilo.screen.tracks.viewer": JSON.stringify({ global: { audio: "dan" }, series: {} }) },
    });
    await playSeries(page);
    await expect.poll(() => page.evaluate(() => localStorage.getItem("ravilo.screen.tracks.viewer"))).toBeNull();
  });
});

// DEB-5652 (DEB-5640 FR-54) — the player's episode rail, Android TV's: an Episodes chip under the transport row, ↓ opens the season's
// cards on the playing one, OK on another starts it as playNext() starts nextId. Same harness as above (the mock harness lives in this file;
// the real-backend ravilo-screen-player.spec.ts has no series page to start an episode from). The look on the set is the QA ticket's.
test.describe("DEB-5652 — the Samsung player's episode rail (FR-54)", () => {
  const season = {
    ...seriesDetail,
    seasons: [{ index: 1, name: "Season 1", episodes: [
      episode("s1e1", 1, "Opening Reel"), episode("s1e2", 2, "Second Reel"), episode("s1e3", 3, "Third Reel"),
    ] }],
  };
  const server = { series: season, refuseRemotePlay: true, start: async (itemId: string) => ({ status: 200, body: ticket(itemId, 0) }) };
  const cards = (page: Page) => page.locator("#rl-track .rl-card");
  const focusedCard = (page: Page) => page.locator("#rl-track .rl-card.foc");

  /** The series' page, Play (s1e1, with s1e2 and s1e3 after it), then ↓ to put the controls up. */
  async function playAndRaiseControls(page: Page) {
    await page.keyboard.press("Enter");
    await expect(page.locator("#detail-play")).toBeVisible();
    await page.keyboard.press("Enter");
    await waitForAvplayCall(page, "play");
    await page.keyboard.press("ArrowDown");
    await expect(page.locator("#overlay")).toHaveClass(/\bon\b/);
  }

  test("an episode started from its page shows the chip 12 px under the transport row; ↓ opens the rail on the playing card", async ({ page }) => {
    test.setTimeout(90_000);
    await boot(page, server);
    await playAndRaiseControls(page);
    const chip = page.locator("#ov-chip");
    await expect(chip).toBeVisible();
    await expect(page.locator("#ov-chip-t")).toHaveText("Episodes");
    const row = (await page.locator("#ov-row").boundingBox())!;
    const box = (await chip.boundingBox())!;
    expect(box.y - (row.y + row.height), "the chip sits 12 px under the row").toBeCloseTo(12, 0);
    expect(box.height, "a 36 dp pill at FR-28's factor").toBeCloseTo(72, 0);

    await page.keyboard.press("ArrowDown");                  // ↓ from the transport row (Play is focused)
    await expect(page.locator("#rail")).toHaveClass(/\bon\b/);
    await expect(page.locator("#rl-season")).toHaveText("Season 1");
    await expect(page.locator("#rl-hint")).toContainText("↵");
    await expect(cards(page)).toHaveCount(3);
    await expect(focusedCard(page).locator(".rl-t")).toContainText("Opening Reel");
    await expect(focusedCard(page)).toHaveClass(/\bcur\b/);
    await expect(focusedCard(page).locator(".rl-now")).toHaveText("NOW PLAYING");
    await expect(focusedCard(page).locator(".rl-d")).toHaveText("40m");
    await expect(page.locator("#overlay"), "the controls stay up while the rail is open").toHaveClass(/\bon\b/);
    await expect(chip, "the chip gives way to the rail").toBeHidden();
    // The rail is not a seek, and nothing plays while it opens.
    expect((await avplayCalls(page)).some((c) => c[0] === "seekTo" || c[0] === "pause")).toBe(false);
  });

  test("→ then OK stops the current item and starts the chosen one, with the episode after it as next", async ({ page }) => {
    test.setTimeout(90_000);
    const seen = await boot(page, server);
    await playAndRaiseControls(page);
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("ArrowRight");
    await expect(focusedCard(page).locator(".rl-t")).toContainText("Second Reel");
    await expect(focusedCard(page)).not.toHaveClass(/\bcur\b/);
    await page.keyboard.press("Enter");
    await expect.poll(() => seen.starts.map((s) => s.item_id), { timeout: 10_000 }).toEqual(["s1e1", "s1e2"]);
    expect(seen.stops[0]).toMatchObject({ item_id: "s1e1" });
    expect(seen.remotePlays).toHaveLength(0);
    await expect(page.locator("#rail")).not.toHaveClass(/\bon\b/);
    await waitForAvplayCall(page, "play", (await avplayCalls(page)).filter((c) => c[0] === "open").length);
    // s1e2 carries s1e3 as its next, and the rail follows the playing episode.
    await page.keyboard.press("ArrowDown");
    await expect(page.locator("#ov-next")).toContainText(">>");
    await page.keyboard.press("ArrowDown");
    await expect(focusedCard(page).locator(".rl-t")).toContainText("Second Reel");
    await expect(focusedCard(page)).toHaveClass(/\bcur\b/);
  });

  test("OK on the playing card, Back and ↑ close the rail onto the transport row and stop nothing", async ({ page }) => {
    test.setTimeout(90_000);
    const seen = await boot(page, server);
    await playAndRaiseControls(page);
    for (const close of ["Enter", "Escape", "ArrowUp"]) {
      await page.keyboard.press("ArrowDown");
      await expect(page.locator("#rail")).toHaveClass(/\bon\b/);
      await page.keyboard.press(close);
      await expect(page.locator("#rail")).not.toHaveClass(/\bon\b/);
      await expect(page.locator("#overlay")).toHaveClass(/\bon\b/);
      await expect(page.locator("#ov-play"), "focus is on the transport row").toHaveClass(/\bfoc\b/);
      await expect(page.locator("#ov-chip")).toBeVisible();
    }
    expect(seen.starts.map((s) => s.item_id)).toEqual(["s1e1"]);
    expect(seen.stops).toHaveLength(0);
  });

  test("a play from a phone's push has no chip and ↓ opens nothing", async ({ page }) => {
    test.setTimeout(60_000);
    await boot(page, server);
    await page.evaluate((frame) => (window as any).__pushEvent(frame), playItem("ep-1"));
    await waitForAvplayCall(page, "play");
    await page.keyboard.press("ArrowDown");
    await expect(page.locator("#overlay")).toHaveClass(/\bon\b/);
    await expect(page.locator("#ov-chip")).toBeHidden();
    await page.keyboard.press("ArrowDown");
    await expect(page.locator("#rail")).not.toHaveClass(/\bon\b/);
    await expect(page.locator("#ov-play")).toHaveClass(/\bfoc\b/);
  });

  test("with the picker open the chip is hidden and ↓ does not open the rail", async ({ page }) => {
    test.setTimeout(90_000);
    await boot(page, server);
    await playAndRaiseControls(page);
    await expect(page.locator("#ov-chip")).toBeVisible();
    await openPickerFromChrome(page);
    await expect(page.locator("#picker")).toHaveClass(/\bon\b/);
    await expect(page.locator("#ov-chip")).toBeHidden();
    await page.keyboard.press("ArrowDown");
    await expect(page.locator("#rail")).not.toHaveClass(/\bon\b/);
  });
});
