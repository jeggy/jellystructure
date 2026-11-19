import { test, expect, type Page } from "@playwright/test";
import { fakeAvPlayInitScript, waitForAvplayCall } from "./helpers/screen-cast";

// DEB-5024 (FR-24, FR-25) — the Samsung TV measures its path and reports what each item went through, driven through the
// REAL ravilo-screen.js bundle with the fake AVPlay (helpers/screen-cast.ts), whose buffering, `onevent` and streaming
// properties the test fires itself. The backend is stood in for by page.route, as in ravilo-screen-detail.spec.ts, so this
// file proves what the app SENDS (the reports, the probe), not what the server stores.

const SCREEN_URL = process.env.RAVILO_SCREEN_CAST_URL ?? "http://localhost:8092";
const API = "http://app:9505";

const card = { id: "m1", kind: "MOVIE", title: "Paper Moon", year: 2024, genre: null, rating: null, poster_url: "/api/tv/image/m1-poster", backdrop_url: "/api/tv/image/m1-backdrop" };
const movieDetail = { card, synopsis: "A con artist and a girl cross the plains.", runtime: 90, cast: [], related: [], logo_url: null };
const feed = { heroes: [], channels: [], rows: [{ id: "new", title: "New Arrivals", kind: "NEWLY_ADDED", items: [card] }],
  tile_shape: "POSTER", hero_height_pct: 48, focus_detail: "none" };
const START_VARIANT_BPS = 4_000_000;
const ticket = {
  jellyfin_base_url: API, access_token: "", item_id: "m1", container: "ts", direct_play: false,
  hls_url: `${API}/hls/m1/master.m3u8`, start_position_ms: 0, expires_at: 9_999_999_999, start_variant_bps: START_VARIANT_BPS,
};

type Seen = { qoe: any[]; probes: string[] };

async function boot(page: Page): Promise<Seen> {
  const seen: Seen = { qoe: [], probes: [] };
  await page.addInitScript(fakeAvPlayInitScript);
  await page.addInitScript(() => {
    localStorage.setItem("ravilo.screen.serverUrl", "http://app:9505");
    localStorage.setItem("ravilo.screen.profiles", JSON.stringify([{ userId: "viewer", displayName: "Test Viewer", token: "test-token" }]));
  });
  const json = (route: any, status: number, body: object = {}) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
  await page.route(`${API}/**`, async (route) => {
    const req = route.request();
    const path = new URL(req.url()).pathname;
    if (req.method() === "OPTIONS") return route.fulfill({ status: 204 });
    if (path === "/api/tv/home") return json(route, 200, feed);
    if (path === "/api/tv/movie/m1") return json(route, 200, movieDetail);
    if (path === "/api/tv/playstate") return json(route, 200, {});
    if (path === "/api/tv/probe") { seen.probes.push(req.method()); return route.fulfill({ status: 204 }); }   // measured within 24 h
    if (path === "/api/tv/playback/qoe") { seen.qoe.push(JSON.parse(req.postData()!)); return route.fulfill({ status: 204 }); }
    if (path === "/api/tv/playback/start") return json(route, 200, ticket);
    if (path.startsWith("/api/tv/playback/")) return route.fulfill({ status: 204 });
    if (path.startsWith("/api/tv/image/")) return route.fulfill({ status: 204 });
    return json(route, 404);
  });
  await page.goto(SCREEN_URL);
  await expect(page.locator("#shell-body .home-feed")).toBeVisible();
  return seen;
}

/** OK on the tile, then on Play once the page has drawn; returns when AVPlay is playing. */
async function openAndPlay(page: Page) {
  await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
  await page.keyboard.press("Enter");
  await expect(page.locator("#detail-play")).toHaveClass(/focused/);
  await play(page);
}

async function play(page: Page) {
  const from = (await page.evaluate(() => (window as any).__avplayCalls.length)) as number;
  await page.keyboard.press("Enter");
  const opened = await waitForAvplayCall(page, "open", from);
  await waitForAvplayCall(page, "play", opened.index);
  // The fake's playhead ticks every 250 ms: the first frame is the second tick. A fixed wait, because the app's own clock
  // (Date.now) is what times a stall — there is no DOM signal for "a first frame happened" to wait on.
  await page.waitForTimeout(1_000);
}

const avplay = (page: Page) => ({
  wait: async (ms: number) => {
    await page.evaluate(() => (window as any).__avplay.bufferStart());
    await page.waitForTimeout(ms);
    await page.evaluate(() => (window as any).__avplay.bufferComplete());
  },
  property: (name: string, value: string) => page.evaluate(([n, v]) => (window as any).__avplay.setProperty(n, v), [name, value]),
  event: (type: string, data?: string) => page.evaluate(([t, d]) => (window as any).__avplay.event(t, d), [type, data]),
});

test.describe("DEB-5024 FR-25 — the TV reports what each item went through", () => {
  test("a 2 s wait mid-play is one stall; a seek's wait is none; a stop posts the final report and the next item starts from zero", async ({ page }) => {
    test.setTimeout(120_000);
    const seen = await boot(page);
    const ap = avplay(page);
    await openAndPlay(page);

    // A seek's wait is the viewer's own: no report, no count.
    await page.keyboard.press("ArrowRight");
    await waitForAvplayCall(page, "seekTo");
    await ap.wait(1_500);
    await page.waitForTimeout(500);
    expect(seen.qoe, "a seek's wait is not a stall").toEqual([]);

    // Past the first 10 s (and past the seek's window) a 2 s wait is a `mid` stall, reported when it ends.
    await page.waitForTimeout(10_000);
    await ap.property("CURRENT_BANDWIDTH", "1200000");
    await ap.wait(2_000);
    await expect.poll(() => seen.qoe.length, { timeout: 10_000 }).toBe(1);
    const report = seen.qoe[0];
    expect(report).toMatchObject({ item_id: "m1", per_item: true, rebuffer_count: 1, start_variant_bps: START_VARIANT_BPS });
    expect(report.rebuffer_ms).toBeGreaterThanOrEqual(1_900);
    expect(report.first_frame_ms).toBeGreaterThan(0);
    expect(report.stalls).toHaveLength(1);
    expect(report.stalls[0]).toMatchObject({ phase: "mid", cause: "buffer_empty", buffered_ms: 0, variant_bps: 1_200_000 });
    expect(report.stalls[0].after_first_frame_ms).toBeGreaterThanOrEqual(10_000);
    expect(report.stalls[0].duration_ms).toBeGreaterThanOrEqual(1_900);
    expect(report.stalls[0].position_ms).toBeGreaterThan(0);

    // Back with nothing up stops: the item's final report goes out with the same counts.
    await page.keyboard.press("Escape");
    await expect.poll(() => seen.qoe.length, { timeout: 10_000 }).toBe(2);
    expect(seen.qoe[1]).toMatchObject({ item_id: "m1", per_item: true, rebuffer_count: 1 });

    // The next item starts from zero, and an uneventful one reports zero.
    await expect(page.locator("#detail-play")).toHaveClass(/focused/);
    await play(page);
    await page.keyboard.press("Escape");
    await expect.poll(() => seen.qoe.length, { timeout: 10_000 }).toBe(3);
    expect(seen.qoe[2].per_item).toBe(true);
    expect(seen.qoe[2].rebuffer_count ?? 0).toBe(0);
    expect(seen.qoe[2].rebuffer_ms ?? 0).toBe(0);
    expect(seen.qoe[2].stalls ?? []).toEqual([]);
    expect(seen.qoe[2].first_frame_ms).toBeGreaterThan(0);
  });

  test("a wait under a second is nothing; one within 10 s of the first frame is a `start` stall; a rung switch posts at once", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page);
    const ap = avplay(page);
    await openAndPlay(page);

    await ap.wait(400);
    await page.waitForTimeout(500);
    expect(seen.qoe, "a wait under 1 s is not a stall").toEqual([]);

    await ap.property("CURRENT_BANDWIDTH", "2000000");
    await ap.wait(1_500);
    await expect.poll(() => seen.qoe.length, { timeout: 10_000 }).toBe(1);
    expect(seen.qoe[0]).toMatchObject({ per_item: true, rebuffer_count: 1 });
    expect(seen.qoe[0].stalls[0]).toMatchObject({ phase: "start", variant_bps: 2_000_000 });

    // A step down from the ticket's start rung: a report at the switch, with the new rung; the resolution change that
    // comes with it is the same switch.
    await ap.property("CURRENT_BANDWIDTH", "800000");
    await ap.event("PLAYER_MSG_BITRATE_CHANGE", "800000");
    await expect.poll(() => seen.qoe.length, { timeout: 10_000 }).toBe(2);
    expect(seen.qoe[1]).toMatchObject({ per_item: true, variant_switches_down: 1, variant_bandwidth_bps: 800_000 });
    expect(seen.qoe[1].variant_switches_up ?? 0).toBe(0);
    await ap.event("PLAYER_MSG_RESOLUTION_CHANGED", "1280x720");
    await page.waitForTimeout(500);
    expect(seen.qoe).toHaveLength(2);
  });

  for (const [error, status] of [["PLAYER_ERROR_CONNECTION_FAILED", -1], ["HTTP 503 from the origin", 503]] as const) {
    test(`a start with no picture reports start_failure_status ${status} (${error})`, async ({ page }) => {
      test.setTimeout(60_000);
      const seen = await boot(page);
      await page.evaluate((e) => { (window as any).__avplay.failPrepareWith = e; }, error);
      await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
      await page.keyboard.press("Enter");
      await expect(page.locator("#detail-play")).toHaveClass(/focused/);
      await page.keyboard.press("Enter");
      await expect.poll(() => seen.qoe.length, { timeout: 10_000 }).toBe(1);
      expect(seen.qoe[0]).toMatchObject({ item_id: "m1", per_item: true, start_failure_status: status });
      expect(seen.qoe[0].first_frame_ms).toBeUndefined();
    });
  }
});

test.describe("DEB-5024 FR-24 — the TV measures its path", () => {
  test("a title's page calls GET /api/tv/probe, and nothing waits on it", async ({ page }) => {
    const seen = await boot(page);
    expect(seen.probes, "the home page measures nothing").toEqual([]);
    await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
    await page.keyboard.press("Enter");
    await expect(page.locator("#detail-play")).toHaveClass(/focused/);
    await expect.poll(() => seen.probes, { timeout: 10_000 }).toEqual(["GET"]);
  });
});
