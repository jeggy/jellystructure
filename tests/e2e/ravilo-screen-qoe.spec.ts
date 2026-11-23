import { test, expect, type Page } from "@playwright/test";
import { fakeAvPlayInitScript, openPickerFromChrome, waitForAvplayCall } from "./helpers/screen-cast";

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

type Seen = { qoe: any[]; probes: string[]; starts: any[]; restreams: any[]; order: string[] };

/** `restreamAnswer`: what /api/tv/playback/restream answers with (default: the same single stream, as the server's DEB-4541 FR-1 would not). */
async function boot(page: Page, restreamAnswer: object = ticket, startTicket: object = ticket, restreamDelayMs = 0, qoeDelayMs = 0): Promise<Seen> {
  const seen: Seen = { qoe: [], probes: [], starts: [], restreams: [], order: [] };
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
    if (path === "/api/tv/playback/qoe") {
      const report = JSON.parse(req.postData()!);
      if (qoeDelayMs) await new Promise((r) => setTimeout(r, qoeDelayMs));
      seen.qoe.push(report); seen.order.push("qoe");
      return route.fulfill({ status: 204 });
    }
    if (path === "/api/tv/playback/start") { seen.starts.push(JSON.parse(req.postData() ?? "{}")); return json(route, 200, startTicket); }
    if (path === "/api/tv/playback/restream") {
      seen.restreams.push(JSON.parse(req.postData() ?? "{}")); seen.order.push("restream");
      if (restreamDelayMs) await new Promise((r) => setTimeout(r, restreamDelayMs));
      return json(route, 200, restreamAnswer);
    }
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
  /** DEB-5024 (FR-32) — AVPlay holds its position, says PLAYING and fires nothing, for [ms]. */
  freeze: async (ms: number) => {
    await page.evaluate(() => (window as any).__avplay.hold());
    await page.waitForTimeout(ms);
    await page.evaluate(() => (window as any).__avplay.release());
  },
  /** DEB-5024 (FR-34) — for [ms], AVPlay holds its position for 0.6 s of every second: about 0.4 s of play per second, in holds under FR-32's 1 s. */
  crawl: (ms: number) => page.evaluate((total) => new Promise<void>((done) => {
    const av = (window as any).__avplay;
    const end = Date.now() + total;
    const cycle = () => {
      if (Date.now() >= end) { av.release(); done(); return; }
      av.hold();
      setTimeout(() => { av.release(); setTimeout(cycle, 400); }, 600);
    };
    cycle();
  }), ms),
  rate: (r: number) => page.evaluate((v) => (window as any).__avplay.setRate(v), r),
});

test.describe("DEB-5024 FR-25 — the TV reports what each item went through", () => {
  test("a 2 s wait mid-play is one stall; a seek's wait is none; a stop posts the final report and the next item starts from zero", async ({ page }) => {
    test.setTimeout(120_000);
    const seen = await boot(page);
    const ap = avplay(page);
    await openAndPlay(page);

    // A seek's wait is the viewer's own: no report, no count.
    await page.keyboard.press("ArrowRight");      // DEB-5025: the first press moves focus Play → +30s; OK seeks
    await page.keyboard.press("Enter");
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

test.describe("DEB-5024 FR-26 — the TV negotiates as an adaptive player", () => {
  test("the start declares hls_adaptive, and ADAPTIVE_INFO is set before prepareAsync when the ticket has a start rung", async ({ page }) => {
    const seen = await boot(page);
    await openAndPlay(page);
    expect(seen.starts).toHaveLength(1);
    expect(JSON.stringify(seen.starts[0])).toContain('"hls_adaptive":true');
    const calls = await page.evaluate(() => (window as any).__avplayCalls as Array<[string, ...unknown[]]>);
    const info = calls.findIndex((c) => c[0] === "setStreamingProperty" && c[1] === "ADAPTIVE_INFO");
    const prepare = calls.findIndex((c) => c[0] === "prepareAsync");
    expect(calls[info]).toEqual(["setStreamingProperty", "ADAPTIVE_INFO", `STARTBITRATE=${START_VARIANT_BPS}`]);
    expect(info).toBeGreaterThanOrEqual(0);
    expect(info).toBeLessThan(prepare);
  });

  test("a ticket with no start rung sets no ADAPTIVE_INFO", async ({ page }) => {
    const { start_variant_bps: _drop, ...bare } = ticket;
    await boot(page, bare, bare);
    await openAndPlay(page);
    const calls = await page.evaluate(() => (window as any).__avplayCalls as Array<[string, ...unknown[]]>);
    expect(calls.filter((c) => c[0] === "setStreamingProperty")).toEqual([]);
  });
});

test.describe("DEB-5394 FR-37 — the start's three spans, and no start-buffer call", () => {
  /** The console lines the app writes for a start, read from the page the way the emulator's inspector would. */
  const spanLines = (page: Page) => {
    const lines: string[] = [];
    page.on("console", (m) => { if (m.text().includes("start-spans")) lines.push(m.text()); });
    return lines;
  };

  test("a start logs one line with the item id and the three spans, and AVPlay is given no buffer call", async ({ page }) => {
    const lines = spanLines(page);
    await boot(page);
    await openAndPlay(page);
    await expect.poll(() => lines.length).toBe(1);
    const calls = await page.evaluate(() => (window as any).__avplayCalls as Array<[string, ...unknown[]]>);
    expect(calls.filter((c) => c[0] === "setBufferingParam")).toEqual([]);
    expect(lines[0]).toMatch(/^Ravilo screen: start-spans item=m1 request=\d+ open_to_prepared=\d+ prepared_to_first_frame=\d+$/);
  });
});

test.describe("DEB-5024 FR-27 — a stall with no lower rung asks for one", () => {
  test("two stalls on a single-rung stream make exactly one restream; a same-stream answer makes no second", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page);
    const ap = avplay(page);
    await openAndPlay(page);
    await ap.property("AVAILABLE_BITRATE", "4000000");
    await ap.property("CURRENT_BANDWIDTH", "4000000");

    await ap.wait(1_500);
    await expect.poll(() => seen.qoe.length, { timeout: 10_000 }).toBe(1);
    expect(seen.restreams, "one short stall is not yet the trigger").toHaveLength(0);

    await ap.wait(1_500);   // the second stall inside 2 minutes
    await expect.poll(() => seen.restreams.length, { timeout: 10_000 }).toBe(1);
    // FR-25's report went out first, with both stalls in it.
    expect(seen.qoe.length).toBeGreaterThanOrEqual(2);
    expect(seen.qoe[1]).toMatchObject({ item_id: "m1", per_item: true, rebuffer_count: 2 });
    // The answer opens as any restream does: AVPlay closed, then opened again.
    const calls = await page.evaluate(() => (window as any).__avplayCalls as Array<[string, ...unknown[]]>);
    expect(calls.filter((c) => c[0] === "open")).toHaveLength(2);
    expect(calls.filter((c) => c[0] === "close").length).toBeGreaterThanOrEqual(1);
    await waitForAvplayCall(page, "play", calls.map((c) => c[0]).lastIndexOf("open"));

    // The answer was the same single stream: more stalls, no second ask.
    await page.waitForTimeout(1_000);
    await ap.wait(1_500);
    await ap.wait(1_500);
    await page.waitForTimeout(1_000);
    expect(seen.restreams).toHaveLength(1);
  });

  test("DEB-5364: a stall's restream is asked only after its report has been answered, so the server has the hold when it negotiates", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page, ticket, ticket, 0, 1_500);   // every report takes 1.5 s to be answered
    const ap = avplay(page);
    await openAndPlay(page);
    await ap.property("AVAILABLE_BITRATE", "4000000");
    await ap.property("CURRENT_BANDWIDTH", "4000000");

    await ap.wait(4_500);   // one stall of 4 s or more asks at once
    await expect.poll(() => seen.restreams.length, { timeout: 15_000 }).toBe(1);
    // The report that carries the stall was answered before the restream reached the server.
    expect(seen.order.indexOf("qoe")).toBeGreaterThanOrEqual(0);
    expect(seen.order.indexOf("qoe")).toBeLessThan(seen.order.indexOf("restream"));
    expect(seen.qoe[0].stalls[0]).toMatchObject({ cause: "buffer_empty" });
  });

  test("one stall of 4 s or more asks at once; with a lower rung in the master it does not ask", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page);
    const ap = avplay(page);
    await openAndPlay(page);
    await ap.property("AVAILABLE_BITRATE", "4000000,1500000,600000");
    await ap.property("CURRENT_BANDWIDTH", "4000000");
    await ap.wait(4_200);
    await expect.poll(() => seen.qoe.length, { timeout: 10_000 }).toBe(1);
    await page.waitForTimeout(500);
    expect(seen.restreams, "a lower rung exists: AVPlay steps down on its own").toHaveLength(0);

    await ap.property("CURRENT_BANDWIDTH", "600000");   // now on the lowest
    await ap.wait(4_200);
    await expect.poll(() => seen.restreams.length, { timeout: 10_000 }).toBe(1);
  });

  test("when AVPlay does not answer AVAILABLE_BITRATE there is no lower rung", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page);
    await openAndPlay(page);
    await avplay(page).wait(4_200);
    await expect.poll(() => seen.restreams.length, { timeout: 10_000 }).toBe(1);
  });
});

const audioTicket = {
  ...ticket,
  audio: [
    { index: 1, language: "eng", label: "English", is_default: true },
    { index: 2, language: "dan", label: "Dansk", is_default: false },
  ],
};

test.describe("DEB-5024 FR-32 — a position that stops while playing is a stall, and a long one restreams", () => {
  test("a 2 s hold with the path above the rung is one `mid` stall, frozen_with_buffer, counted in frozen_count; no restream", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page);
    const ap = avplay(page);
    await openAndPlay(page);
    await page.waitForTimeout(10_000);                     // past the first 10 s: a `mid` stall
    await ap.property("CURRENT_BANDWIDTH", "6000000");     // above the ticket's 4 Mb/s start rung
    await ap.freeze(2_200);
    await expect.poll(() => seen.qoe.length, { timeout: 10_000 }).toBe(1);
    const report = seen.qoe[0];
    expect(report).toMatchObject({ item_id: "m1", per_item: true, rebuffer_count: 1, frozen_count: 1 });
    expect(report.frozen_ms).toBeGreaterThanOrEqual(1_900);
    expect(report.stalls).toHaveLength(1);
    expect(report.stalls[0]).toMatchObject({ phase: "mid", cause: "frozen_with_buffer", variant_bps: 6_000_000, rung_bps: START_VARIANT_BPS });
    expect(report.stalls[0].duration_ms).toBeGreaterThanOrEqual(1_900);
    expect(report.stalls[0].position_ms).toBeGreaterThan(0);
    await page.waitForTimeout(1_000);
    expect(seen.restreams, "2 s is not 5 s").toHaveLength(0);
  });

  test("with the path below the rung the same hold is `buffer_empty`, and not a frozen one", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page);
    const ap = avplay(page);
    await openAndPlay(page);
    await page.waitForTimeout(10_000);
    await ap.property("CURRENT_BANDWIDTH", "1200000");
    await ap.freeze(2_200);
    await expect.poll(() => seen.qoe.length, { timeout: 10_000 }).toBe(1);
    expect(seen.qoe[0]).toMatchObject({ rebuffer_count: 1 });
    expect(seen.qoe[0].frozen_count ?? 0).toBe(0);
    expect(seen.qoe[0].stalls[0]).toMatchObject({ phase: "mid", cause: "buffer_empty", variant_bps: 1_200_000, rung_bps: START_VARIANT_BPS });
  });

  test("a stall AVPlay announced is counted once, not again by the position watch", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page);
    const ap = avplay(page);
    await openAndPlay(page);
    await page.waitForTimeout(10_000);
    await ap.wait(2_200);                                  // onbufferingstart, a held position, onbufferingcomplete
    await expect.poll(() => seen.qoe.length, { timeout: 10_000 }).toBe(1);
    await page.waitForTimeout(1_000);
    expect(seen.qoe).toHaveLength(1);
    expect(seen.qoe[0]).toMatchObject({ rebuffer_count: 1 });
    expect(seen.qoe[0].frozen_count ?? 0).toBe(0);
    expect(seen.qoe[0].stalls).toHaveLength(1);
  });

  test("a 5 s hold makes exactly one restream bounded by the rung, and a second hold on the item makes none", async ({ page }) => {
    test.setTimeout(90_000);
    const seen = await boot(page, ticket, ticket, 1_500);  // the answer takes 1.5 s: the recovery is a stop that lasted
    const ap = avplay(page);
    await openAndPlay(page);
    await page.waitForTimeout(10_000);
    await ap.property("CURRENT_BANDWIDTH", "6000000");
    await page.evaluate(() => (window as any).__avplay.hold());
    await expect.poll(() => seen.restreams.length, { timeout: 10_000 }).toBe(1);
    expect(seen.restreams[0]).toMatchObject({ item_id: "m1", below_bandwidth_bps: START_VARIANT_BPS });
    // The buffering view covers the freeze, and FR-25's report (the stall, with its counts) went out before the new stream.
    await expect(page.locator("#buffering")).toHaveClass(/\bon\b/);
    expect(seen.qoe.length).toBeGreaterThanOrEqual(1);
    expect(seen.qoe[0].stalls[0]).toMatchObject({ cause: "frozen_with_buffer" });
    expect(seen.qoe[0].stalls[0].duration_ms).toBeGreaterThanOrEqual(4_500);
    // The answer opens as any restream does, and plays: the recovery entry is the time between the ask and that movement.
    const calls = await page.evaluate(() => (window as any).__avplayCalls as Array<[string, ...unknown[]]>);
    await waitForAvplayCall(page, "open", calls.map((c) => c[0]).lastIndexOf("open") + 1 || 1);
    await expect.poll(() => seen.qoe.some((r) => (r.stalls ?? []).some((s: any) => s.cause === "recovery")), { timeout: 10_000 }).toBe(true);
    const recovery = seen.qoe.flatMap((r) => r.stalls ?? []).find((s: any) => s.cause === "recovery");
    expect(recovery).toMatchObject({ reason: "frozen", rung_bps: START_VARIANT_BPS });
    expect(recovery.duration_ms).toBeGreaterThanOrEqual(1_000);
    await expect(page.locator("#buffering")).not.toHaveClass(/\bon\b/);

    // The restream freezes too: the buffering view stays, the stall is reported when it ends, and nothing restreams again.
    await page.waitForTimeout(3_000);                      // past the new stream's first frame and the quiet window
    await ap.freeze(6_500);
    await expect.poll(() => seen.qoe.filter((r) => r.frozen_count === 2).length, { timeout: 10_000 }).toBeGreaterThan(0);
    expect(seen.restreams, "a second freeze on the same item does not restream again").toHaveLength(1);
  });

  test("a pause, a seek and an audio restream make no stall", async ({ page }) => {
    test.setTimeout(120_000);
    const seen = await boot(page, audioTicket, audioTicket);
    const ap = avplay(page);
    await openAndPlay(page);
    await page.waitForTimeout(10_000);
    await ap.property("CURRENT_BANDWIDTH", "6000000");

    // A pause: the position stands still for 3 s, and AVPlay says PAUSED.
    await page.keyboard.press("Enter");
    await expect(page.locator("#overlay")).toHaveClass(/\bon\b/);
    await page.waitForTimeout(3_000);
    await page.keyboard.press("Enter");                    // OK on Play: plays again
    await page.waitForTimeout(3_000);

    // A seek: AVPlay holds its position for a moment while it answers, inside the 2 s the app does not look.
    await page.keyboard.press("ArrowRight");
    await page.evaluate(() => (window as any).__avplay.hold());
    await page.keyboard.press("Enter");
    await waitForAvplayCall(page, "seekTo");
    await page.waitForTimeout(1_500);
    await page.evaluate(() => (window as any).__avplay.release());
    await page.waitForTimeout(3_000);

    // An audio pick is a restream; the stream that opens in its place holds its position for 1.5 s on the way.
    await openPickerFromChrome(page);
    await expect(page.locator("#picker")).toHaveClass(/\bon\b/);
    await expect(page.locator("#pk-rows .pkr")).toHaveCount(2);   // no subtitles and two audio: it opens on Audio, English (playing) and Dansk
    await page.keyboard.press("ArrowDown");
    const from = ((await page.evaluate(() => (window as any).__avplayCalls.length)) as number);
    await page.keyboard.press("Enter");
    await waitForAvplayCall(page, "open", from, 20_000);
    await page.evaluate(() => (window as any).__avplay.hold());
    await page.waitForTimeout(1_500);
    await page.evaluate(() => (window as any).__avplay.release());
    await page.waitForTimeout(4_000);

    expect(seen.restreams, "the audio pick is the one restream").toHaveLength(1);
    expect(seen.qoe, "no stall, so no report").toEqual([]);
  });
});

test.describe("DEB-5024 FR-34 — play that falls behind real time is a stall", () => {
  test("0.4 s of play per second, in 0.6 s holds, is a `buffer_empty` stall of about 6 s per 10 s window, and no frozen one", async ({ page }) => {
    test.setTimeout(90_000);
    const seen = await boot(page);
    const ap = avplay(page);
    await openAndPlay(page);
    await page.waitForTimeout(3_000);
    await ap.crawl(20_000);
    await expect.poll(() => seen.qoe.at(-1)?.stalls?.length ?? 0, { timeout: 10_000 }).toBeGreaterThanOrEqual(2);
    const last = seen.qoe.at(-1);
    const stalls = last.stalls.filter((s: any) => s.cause === "buffer_empty");
    expect(stalls.slice(0, 2)).toHaveLength(2);
    for (const stall of stalls.slice(0, 2)) {
      // 10 s of wall, 4 s of advance = 6 s; the fake's 250 ms ticks make a 0.4 s release one or two ticks, so 4 s to 6 s of advance
      expect(stall.duration_ms).toBeGreaterThanOrEqual(4_000);
      expect(stall.duration_ms).toBeLessThanOrEqual(7_500);
    }
    expect(last.rebuffer_count).toBeGreaterThanOrEqual(2);
    expect(last.rebuffer_ms).toBeGreaterThanOrEqual(9_000);
    expect(last.frozen_count ?? 0, "a hold under a second is not a freeze").toBe(0);
  });

  test("a stream at 0.9× posts nothing", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page);
    const ap = avplay(page);
    await openAndPlay(page);
    await ap.rate(0.9);
    await page.waitForTimeout(23_000);
    expect(seen.qoe, "90 % of real time is above the 70 % line").toEqual([]);
  });

  test("a pause inside the window posts nothing", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page);
    await openAndPlay(page);
    await page.waitForTimeout(3_000);
    await page.keyboard.press("Enter");                    // pause
    await expect(page.locator("#overlay")).toHaveClass(/\bon\b/);
    await page.waitForTimeout(5_000);                      // half a window of no advance
    await page.keyboard.press("Enter");                    // play again
    await page.waitForTimeout(14_000);
    expect(seen.qoe, "the pause and the 2 s after it are not counted").toEqual([]);
  });
});
