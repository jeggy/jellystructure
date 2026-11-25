import { test, expect, type APIRequestContext, type Page } from "@playwright/test";
import { fakeAvPlayInitScript, ownSignInBucket, avplayCalls, waitForAvplayCall, scannedItemId, openPickerFromChrome } from "./helpers/screen-cast";

// R264 (FR-R264-3/4) — the receiver-only TV app is a whole player driven by the TV remote, not only by the
// phone: the transport on the remote's own keys, the two-level audio & subtitles picker on the TV, and one
// plain sentence when a title does not start. Driven through the REAL ravilo-screen.js bundle with the
// fake AVPlay phase 248 built (the owner has no Samsung TV; only AVPlay itself is stood in for).
//
// The mock Jellyfin gives Big Buck Bunny two audio tracks (eng default, dan), an English text subtitle and
// a Danish PGS subtitle — see TRACKS_ITEM_* in its server.js and ravilo-screen-tracks.spec.ts.

const SCREEN_URL = process.env.RAVILO_SCREEN_CAST_URL ?? "http://localhost:8092";
const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";
const R252 = { "x-ravilo-platform": "phone", "x-ravilo-version": "9.99" };

test.describe("R264 — the TV remote drives the receiver's own player", () => {
  test("transport keys, the picker on the TV, and Back's two steps", async ({ page, request }) => {
    test.setTimeout(150_000);
    await page.addInitScript(fakeAvPlayInitScript);
    const pageErrors: string[] = [];
    page.on("pageerror", (err) => pageErrors.push(err.message));

    const { phoneToken, screenId } = await bootAndSignIn(page, request);
    const auth = { authorization: `Bearer ${phoneToken}` };
    const nowPlaying = async () => (await (await request.get(`/api/remote/devices/${screenId}`, { headers: auth })).json())?.now_playing ?? {};

    const itemId = await scannedItemId(request, phoneToken, "Big Buck Bunny", { username: JF_USER, password: JF_PASS });
    const play = await request.post("/api/remote/play", { headers: auth, data: { device_id: screenId, jellyfin_item_id: itemId, start_position_ms: 0 } });
    expect(play.status(), await play.text()).toBe(202);
    const opened = await waitForAvplayCall(page, "open");
    await waitForAvplayCall(page, "play", opened.index);
    await expect.poll(async () => (await nowPlaying()).loaded, { timeout: 15_000 }).toBe(true);

    // ── DEB-4845: AVPlay's plane is BEHIND the page — an opaque html/body hid it (black picture, sound). ──
    // Chromium cannot show Samsung's plane, so the test asserts the cause: nothing opaque is left over it.
    await expect(page.locator("html")).toHaveClass(/\bavplay-on\b/);
    const bg = await page.evaluate(() => [document.documentElement, document.body].map((e) => getComputedStyle(e).backgroundColor));
    expect(bg, "html and body must be transparent while AVPlay plays").toEqual(["rgba(0, 0, 0, 0)", "rgba(0, 0, 0, 0)"]);
    await expect(page.locator("#video")).toBeHidden();
    const rect = (await avplayCalls(page)).find((c) => c[0] === "setDisplayRect")!;
    expect(rect.slice(1), "the display rect is the page's own viewport").toEqual([0, 0, await page.evaluate(() => window.innerWidth), await page.evaluate(() => window.innerHeight)]);

    // ── Transport: OK pauses and shows the controls on Play (DEB-5025, FR-29); → moves focus, OK acts. ───
    const before = (await avplayCalls(page)).length;
    await page.keyboard.press("Enter");
    await waitForAvplayCall(page, "pause", before);
    await expect(page.locator("#overlay")).toHaveClass(/\bon\b/);
    await expect(page.locator("#ov-play")).toHaveClass(/\bfoc\b/);
    const seekFrom = (await avplayCalls(page)).length;
    await page.keyboard.press("ArrowRight");
    await expect(page.locator("#ov-f30")).toHaveClass(/\bfoc\b/);
    expect((await avplayCalls(page)).slice(seekFrom).some((c) => c[0] === "seekTo"), "→ moves focus; it does not seek").toBe(false);
    await page.keyboard.press("Enter");
    await waitForAvplayCall(page, "seekTo", seekFrom);

    // ── The picker: Audio & subtitles opens it on Subtitles, with Off, the languages and the size row. ──
    await page.keyboard.press("ArrowRight");
    await expect(page.locator("#ov-tracks")).toHaveClass(/\bfoc\b/);
    await page.keyboard.press("Enter");
    const picker = page.locator("#picker");
    await expect(picker).toHaveClass(/\bon\b/);
    await expect(picker, "the picker stays visible over AVPlay (DEB-4845)").toBeVisible();
    await expect(page.locator("html")).toHaveClass(/\bavplay-on\b/);
    const rows = page.locator("#pk-rows .pkr");
    await expect(rows.first()).toContainText(/\S/);
    const subtitleRows = await rows.count();
    expect(subtitleRows, "Off + at least one subtitle language + the size row").toBeGreaterThanOrEqual(3);
    await expect(page.locator("#pk-rows .pkr.pksz, #pk-rows .pkr .pksz")).toHaveCount(1);

    // Pick the first language after Off: a text subtitle is drawn by the receiver (R285) — the phone's
    // status says the same, because the picker and the remote share positions.
    await page.keyboard.press("ArrowDown");
    const focused = page.locator("#pk-rows .pkr.foc");
    await expect(focused).toHaveCount(1);
    await page.keyboard.press("Enter");
    await expect(picker).not.toHaveClass(/\bon\b/);
    await expect.poll(async () => (await nowPlaying()).selected_sub, { timeout: 10_000 }).toBeGreaterThanOrEqual(0);

    // ── Audio from the TV: → switches to the Audio tab, and a pick restreams with that track. ──────
    await openPickerFromChrome(page);
    await expect(picker).toHaveClass(/\bon\b/);
    await page.keyboard.press("ArrowRight");
    await expect(page.locator("#pk-tabs b.on")).toHaveCount(1);
    const audioRows = await rows.count();
    expect(audioRows).toBeGreaterThanOrEqual(2);
    const restreamFrom = (await avplayCalls(page)).length;
    // Focus the row that is not selected (the second language) and pick it.
    for (let i = 0; i < audioRows; i++) {
      const cls = (await rows.nth(i).getAttribute("class")) ?? "";
      if (!cls.includes("sel")) {
        const at = (await page.locator("#pk-rows .pkr").evaluateAll((els) => els.findIndex((e) => e.classList.contains("foc"))));
        for (let k = at; k < i; k++) await page.keyboard.press("ArrowDown");
        for (let k = at; k > i; k--) await page.keyboard.press("ArrowUp");
        break;
      }
    }
    await page.keyboard.press("Enter");
    const reopened = await waitForAvplayCall(page, "open", restreamFrom, 20_000);
    expect(String(reopened.call[1])).toContain("AudioStreamIndex=2");

    // ── Back: first press closes whatever is up, a press with nothing up stops and goes idle. ──────
    await openPickerFromChrome(page);
    await expect(picker).toHaveClass(/\bon\b/);
    await page.keyboard.press("Escape");
    await expect(picker).not.toHaveClass(/\bon\b/);
    await expect(page.locator("#overlay"), "closing the picker leaves the controls up, on Audio & subtitles").toHaveClass(/\bon\b/);
    await expect(page.locator("#ov-tracks")).toHaveClass(/\bfoc\b/);
    await page.keyboard.press("Escape");               // hides it
    await expect(page.locator("#overlay")).not.toHaveClass(/\bon\b/);
    await page.keyboard.press("Escape");               // stops
    await expect(page.locator("#idle")).toHaveClass(/\bon\b/, { timeout: 10_000 });
    await expect(page.locator("html")).not.toHaveClass(/\bavplay-on\b/);   // the page is opaque again off the stream

    expect(pageErrors, pageErrors.join("\n")).toEqual([]);
  });

  test("a title that cannot start says why, in one sentence, and goes back to idle", async ({ page, request }) => {
    test.setTimeout(120_000);
    await page.addInitScript(fakeAvPlayInitScript);
    const { phoneToken, screenId } = await bootAndSignIn(page, request, "e2e-player-phone-r264-b");
    const auth = { authorization: `Bearer ${phoneToken}` };
    // An id this library does not hold: the push goes out, the start is refused.
    const play = await request.post("/api/remote/play", { headers: auth, data: { device_id: screenId, jellyfin_item_id: "00000000000000000000000000000000", start_position_ms: 0 } });
    expect(play.status(), await play.text()).toBe(202);
    await expect(page.locator("#failed")).toHaveClass(/\bon\b/, { timeout: 20_000 });
    await expect(page.locator("#failed-t")).not.toHaveText("");
    // Never the old catch-all: this server is reachable.
    await expect(page.locator("#noserver")).not.toHaveClass(/\bon\b/);
  });
});

test.describe("DEB-5025 (FR-28–FR-30) — the Samsung player has Android TV's control bar and keys", () => {
  /** Big Buck Bunny playing on a freshly paired fake-AVPlay TV; the fake's stream is 60 s long, so a scrub step is 5 s. */
  async function playing(page: Page, request: APIRequestContext, phoneDevice: string) {
    await page.addInitScript(fakeAvPlayInitScript);
    const pageErrors: string[] = [];
    page.on("pageerror", (err) => pageErrors.push(err.message));
    const { phoneToken, screenId } = await bootAndSignIn(page, request, phoneDevice);
    const auth = { authorization: `Bearer ${phoneToken}` };
    const itemId = await scannedItemId(request, phoneToken, "Big Buck Bunny", { username: JF_USER, password: JF_PASS });
    const play = await request.post("/api/remote/play", { headers: auth, data: { device_id: screenId, jellyfin_item_id: itemId, start_position_ms: 0 } });
    expect(play.status(), await play.text()).toBe(202);
    const opened = await waitForAvplayCall(page, "open");
    await waitForAvplayCall(page, "play", opened.index);
    await expect.poll(async () => (await (await request.get(`/api/remote/devices/${screenId}`, { headers: auth })).json())?.now_playing?.loaded, { timeout: 15_000 }).toBe(true);
    await expect(page.locator("#overlay"), "a start has the controls hidden").not.toHaveClass(/\bon\b/);
    return { pageErrors };
  }
  const calls = async (page: Page, name: string, from: number) => (await avplayCalls(page)).slice(from).filter((c) => c[0] === name);

  test("OK on hidden controls pauses and shows them with Play focused; the bar is built once with its seven parts", async ({ page, request }) => {
    test.setTimeout(150_000);
    const { pageErrors } = await playing(page, request, "e2e-player-phone-5025-a");
    const from = (await avplayCalls(page)).length;
    await page.keyboard.press("Enter");
    await waitForAvplayCall(page, "pause", from);
    await expect(page.locator("#overlay")).toHaveClass(/\bon\b/);
    await expect(page.locator("#ov-play")).toHaveClass(/\bfoc\b/);
    await expect(page.locator("#ov-play")).toHaveText("▶");
    await expect(page.locator("#ov-title")).not.toHaveText("");
    await expect(page.locator("#ov-elapsed")).toHaveText(/\d/);
    await expect(page.locator("#ov-total")).toHaveText(/\d/);
    for (const id of ["ov-back", "ov-b10", "ov-f30", "ov-tracks"]) await expect(page.locator(`#${id}`)).toBeVisible();
    await expect(page.locator("#ov-next"), "no next episode is known for a film").toBeHidden();
    // OK on a shown Play toggles it again.
    await page.keyboard.press("Enter");
    await waitForAvplayCall(page, "play", from);
    await expect(page.locator("#ov-play")).toHaveText("❚❚");
    expect(pageErrors, pageErrors.join("\n")).toEqual([]);
  });

  test("→ then OK on a playing title seeks +30 s: the first → moves focus Play → +30s from hidden controls", async ({ page, request }) => {
    test.setTimeout(150_000);
    await playing(page, request, "e2e-player-phone-5025-b");
    const from = (await avplayCalls(page)).length;
    await page.keyboard.press("ArrowRight");
    await expect(page.locator("#overlay")).toHaveClass(/\bon\b/);
    await expect(page.locator("#ov-f30")).toHaveClass(/\bfoc\b/);
    expect(await calls(page, "seekTo", from), "→ from hidden moves focus; it does not seek (R350)").toHaveLength(0);
    await page.keyboard.press("Enter");
    const seek = await waitForAvplayCall(page, "seekTo", from);
    expect(Number(seek.call[1])).toBeGreaterThanOrEqual(30_000);
    await page.keyboard.press("ArrowLeft");                  // Play
    await page.keyboard.press("ArrowLeft");                  // −10s
    await expect(page.locator("#ov-b10")).toHaveClass(/\bfoc\b/);
    const back = (await avplayCalls(page)).length;
    await page.keyboard.press("Enter");
    const rewound = await waitForAvplayCall(page, "seekTo", back);
    expect(Number(rewound.call[1])).toBeGreaterThanOrEqual(20_000);
  });

  test("on the seek bar ← and → scrub, OK commits exactly one seek, and Back closes a scrub before it hides the controls", async ({ page, request }) => {
    test.setTimeout(150_000);
    await playing(page, request, "e2e-player-phone-5025-c");
    const from = (await avplayCalls(page)).length;
    await page.keyboard.press("ArrowUp");                    // hidden: acts, focus Play → the seek bar
    await expect(page.locator("#ov-seek")).toHaveClass(/\bfoc\b/);
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("ArrowRight");
    await expect(page.locator("#ov-seek")).toHaveClass(/\bscrub\b/);
    await expect(page.locator("#ov-scrub")).toHaveText(/\d/);
    expect(await calls(page, "seekTo", from), "a scrub seeks nothing until it is committed").toHaveLength(0);
    await page.keyboard.press("Enter");
    const seek = await waitForAvplayCall(page, "seekTo", from);
    expect(Number(seek.call[1]), "two steps of 5 s from the playhead").toBeGreaterThanOrEqual(10_000);
    expect(Number(seek.call[1])).toBeLessThanOrEqual(16_000);
    await expect(page.locator("#ov-seek")).not.toHaveClass(/\bscrub\b/);
    expect(await calls(page, "seekTo", from)).toHaveLength(1);
    // A second scrub, abandoned with Back: the controls stay up and nothing seeks.
    const next = (await avplayCalls(page)).length;
    await page.keyboard.press("ArrowLeft");
    await expect(page.locator("#ov-seek")).toHaveClass(/\bscrub\b/);
    await page.keyboard.press("Escape");
    await expect(page.locator("#ov-seek")).not.toHaveClass(/\bscrub\b/);
    await expect(page.locator("#overlay")).toHaveClass(/\bon\b/);
    expect(await calls(page, "seekTo", next)).toHaveLength(0);
    // ↓ from the bar goes to Play.
    await page.keyboard.press("ArrowDown");
    await expect(page.locator("#ov-play")).toHaveClass(/\bfoc\b/);
  });

  test("OK on Audio & subtitles opens the picker, and ↓ no longer does", async ({ page, request }) => {
    test.setTimeout(150_000);
    await playing(page, request, "e2e-player-phone-5025-d");
    await page.keyboard.press("ArrowDown");                  // hidden: ↓ only reveals
    await expect(page.locator("#overlay")).toHaveClass(/\bon\b/);
    await page.keyboard.press("ArrowDown");                  // the row: ↓ does nothing
    await expect(page.locator("#picker")).not.toHaveClass(/\bon\b/);
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("ArrowRight");
    await expect(page.locator("#ov-tracks")).toHaveClass(/\bfoc\b/);
    await page.keyboard.press("Enter");
    await expect(page.locator("#picker")).toHaveClass(/\bon\b/);
    await expect(page.locator("#overlay"), "the controls hold behind the picker").toHaveClass(/\bon\b/);
  });

  // DEB-5124 (DEB-5025, FR-29) — QA DEB-5076: a Tizen remote's media keys arrive as XF86* names and key codes. Playwright cannot
  // type those names, so the page gets the events a Tizen webview would raise.
  const tizenKey = (page: Page, key: string, keyCode: number) =>
    page.evaluate(([k, c]) => { window.dispatchEvent(new KeyboardEvent("keydown", { key: k as string, keyCode: c as number, bubbles: true })); }, [key, keyCode]);

  test("Tizen's media-key names and key codes act with focus on Audio & subtitles", async ({ page, request }) => {
    test.setTimeout(150_000);
    await playing(page, request, "e2e-player-phone-5124-a");
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("ArrowRight");
    await expect(page.locator("#ov-tracks")).toHaveClass(/\bfoc\b/);
    let from = (await avplayCalls(page)).length;
    await tizenKey(page, "XF86PlayBack", 10252);
    await waitForAvplayCall(page, "pause", from);
    from = (await avplayCalls(page)).length;
    await tizenKey(page, "XF86AudioPlay", 415);
    await waitForAvplayCall(page, "play", from);
    from = (await avplayCalls(page)).length;
    await tizenKey(page, "XF86AudioPause", 19);
    await waitForAvplayCall(page, "pause", from);
    from = (await avplayCalls(page)).length;
    await tizenKey(page, "Unidentified", 417);               // the fast-forward key code, whatever its name
    const seek = await waitForAvplayCall(page, "seekTo", from);
    expect(Number(seek.call[1])).toBeGreaterThanOrEqual(30_000);
    await expect(page.locator("#ov-tracks"), "media keys leave the focus where it was").toHaveClass(/\bfoc\b/);
  });

  test("the focused Play keeps its glyph visible, and captions rise above the bar while it is up", async ({ page, request }) => {
    test.setTimeout(150_000);
    await playing(page, request, "e2e-player-phone-5124-b");
    await page.evaluate(() => { document.getElementById("cues")!.classList.add("on"); document.getElementById("cues")!.textContent = "A caption"; });
    const cues = page.locator("#cues");
    const floor = (await cues.boundingBox())!;
    await page.keyboard.press("ArrowDown");
    await expect(page.locator("#ov-play")).toHaveClass(/\bfoc\b/);
    const colours = await page.locator("#ov-play").evaluate((n) => { const s = getComputedStyle(n); return [s.color, s.backgroundColor]; });
    expect(colours[0], "the glyph is not drawn in the disc's own colour").not.toEqual(colours[1]);
    const barTop = (await page.locator("#ov-bottom").boundingBox())!.y;
    await expect.poll(async () => { const b = await cues.boundingBox(); return b ? b.y + b.height : Infinity; }).toBeLessThanOrEqual(barTop);
    await expect(page.locator("#overlay")).not.toHaveClass(/\bon\b/, { timeout: 6_000 });
    await expect.poll(async () => { const b = await cues.boundingBox(); return b ? b.y + b.height : 0; }).toBeCloseTo(floor.y + floor.height, 0);
  });

  test("the controls hide 3.6 s after the last key while playing, and stay while paused; Back hides, then stops", async ({ page, request }) => {
    test.setTimeout(150_000);
    await playing(page, request, "e2e-player-phone-5025-e");
    const overlay = page.locator("#overlay");
    await page.keyboard.press("ArrowDown");
    await expect(overlay).toHaveClass(/\bon\b/);
    await page.waitForTimeout(2_800);
    await expect(overlay, "still up inside 3.6 s").toHaveClass(/\bon\b/);
    await expect(overlay).not.toHaveClass(/\bon\b/, { timeout: 3_000 });
    // Paused: the controls come up and stay well past 3.6 s.
    await page.keyboard.press("Enter");
    await expect(overlay).toHaveClass(/\bon\b/);
    await page.waitForTimeout(5_000);
    await expect(overlay, "held while paused").toHaveClass(/\bon\b/);
    // Back hides them; Back again stops.
    await page.keyboard.press("Escape");
    await expect(overlay).not.toHaveClass(/\bon\b/);
    await expect(page.locator("#idle")).not.toHaveClass(/\bon\b/);
    await page.keyboard.press("Escape");
    await expect(page.locator("#idle")).toHaveClass(/\bon\b/, { timeout: 10_000 });
  });
});

// DEB-5396 (DEB-5394, FR-36) — the next episode's encode starts before the card: at the credits marker, or 60 s before the end with
// none, and a viewer who goes back, stops or cancels leaves nothing running. The backend is stood in for by page.route (as the title
// page's spec does), so this proves what the app SENDS and when, not what the encoder does with it. The fake AVPlay's stream is 120 s.
test.describe("DEB-5394 FR-36 — the next episode's encode starts before the card", () => {
  const API = "http://app:9505";
  const SEGMENTS = { credits_start_ms: 80_000 };

  type Seen = { prewarms: any[]; cancels: any[]; starts: any[]; stops: any[]; push: (message: object) => void };

  /** A two-episode series; the first plays from its page with the second as `nextId`. [segments]: the first episode's markers. */
  async function playingFirst(page: Page, segments: object | null, autoplayNext = true): Promise<Seen> {
    const sockets: any[] = [];
    const seen: Seen = { prewarms: [], cancels: [], starts: [], stops: [], push: (message) => sockets.forEach((ws) => ws.send(JSON.stringify(message))) };
    const card = { id: "sr1", kind: "SERIES", title: "Paper Series", year: 2024, genre: null, rating: null, poster_url: "/api/tv/image/sr1-poster", backdrop_url: "/api/tv/image/sr1-backdrop" };
    const episode = (id: string, n: number, title: string, extra: object = {}) => ({ id, episode_number: n, title, runtime: 40, overview: null, still_url: `/api/tv/image/${id}-still`, ...extra });
    const series = { card, synopsis: "x", cast: [], related: [], logo_url: null,
      seasons: [{ index: 1, name: "Season 1", episodes: [episode("e1", 1, "Opening Reel", segments ? { segments } : {}), episode("e2", 2, "Second Reel")] }] };
    const feed = { heroes: [], channels: [], rows: [{ id: "new", title: "New Arrivals", kind: "NEWLY_ADDED", items: [card] }], tile_shape: "POSTER", hero_height_pct: 48, focus_detail: "none" };
    await page.addInitScript(fakeAvPlayInitScript);
    await page.addInitScript(() => {
      localStorage.setItem("ravilo.screen.serverUrl", "http://app:9505");
      localStorage.setItem("ravilo.screen.profiles", JSON.stringify([{ userId: "viewer", displayName: "Test Viewer", token: "test-token" }]));
      (window as any).__avplay.setDuration(120_000);
    });
    await page.routeWebSocket(/\/api\/tv\/events/, (ws) => { sockets.push(ws); });   // accepted, silent until a test pushes the phone's command
    const json = (route: any, status: number, body: object = {}) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    await page.route(`${API}/**`, async (route) => {
      const req = route.request();
      const path = new URL(req.url()).pathname;
      const body = req.postData() ? JSON.parse(req.postData()!) : null;
      if (req.method() === "OPTIONS") return route.fulfill({ status: 204 });
      if (path === "/api/tv/home") return json(route, 200, feed);
      if (path === "/api/tv/series/sr1") return json(route, 200, series);
      if (path === "/api/tv/playstate") return json(route, 200, {});
      if (path === "/api/tv/config") return json(route, 200, { autoplay_next: autoplayNext });
      if (path === "/api/tv/playback/prewarm") { seen.prewarms.push(body); return json(route, 200, { status: "warm" }); }
      if (path === "/api/tv/playback/prewarm/cancel") { seen.cancels.push(body); return route.fulfill({ status: 204 }); }
      if (path === "/api/tv/playback/start") {
        seen.starts.push(body);
        return json(route, 200, { jellyfin_base_url: API, access_token: "", item_id: body.item_id, container: "ts", direct_play: false,
          hls_url: `${API}/hls/${body.item_id}/master.m3u8`, start_position_ms: 0, expires_at: 9_999_999_999 });
      }
      if (path === "/api/tv/playback/stop") { seen.stops.push(body); return route.fulfill({ status: 204 }); }
      if (path.startsWith("/api/tv/")) return route.fulfill({ status: 204 });
      return json(route, 404);
    });
    await page.goto(SCREEN_URL);
    await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
    await page.keyboard.press("Enter");
    await expect(page.locator("#detail-play")).toHaveClass(/focused/);
    await page.keyboard.press("Enter");
    await waitForAvplayCall(page, "open");
    await expect.poll(() => seen.starts.map((s) => s.item_id)).toEqual(["e1"]);
    return seen;
  }
  const jump = (page: Page, ms: number) => page.evaluate((m) => (window as any).__avplay.jump(m), ms);
  const settle = (page: Page) => page.waitForTimeout(800);   // a few 250 ms ticks of the segment loop

  test("with a credits marker: nothing before it, one prewarm for nextId at it, and none sooner at 60 s before the end", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await playingFirst(page, SEGMENTS);
    await jump(page, 70_000);                       // inside the last 60 s, but before the credits: the marker decides
    await settle(page);
    expect(seen.prewarms).toEqual([]);
    await jump(page, 81_000);
    await expect.poll(() => seen.prewarms.length).toBe(1);
    expect(seen.prewarms[0].item_id).toBe("e2");
    expect(seen.prewarms[0].capabilities).toEqual(seen.starts[0].capabilities);
    await settle(page);
    expect(seen.prewarms, "one post, not one per tick").toHaveLength(1);
  });

  test("with no marker: nothing at 59 s, one prewarm at 60 s before the end (the card's last second is later)", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await playingFirst(page, null);
    await jump(page, 50_000);
    await settle(page);
    expect(seen.prewarms).toEqual([]);
    await jump(page, 60_500);
    await expect.poll(() => seen.prewarms.map((p) => p.item_id)).toEqual(["e2"]);
    await expect(page.locator("#nextup"), "the card is not up yet").not.toHaveClass(/\bon\b/);
  });

  test("a seek back before the trigger posts the cancel, and going past it again warms again", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await playingFirst(page, null);
    await jump(page, 62_000);
    await expect.poll(() => seen.prewarms).toHaveLength(1);
    // −10 s on the controls: ArrowRight reveals them on +30 s, two ArrowLefts reach −10 s.
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("ArrowLeft");
    await page.keyboard.press("ArrowLeft");
    await expect(page.locator("#ov-b10")).toHaveClass(/\bfoc\b/);
    await page.keyboard.press("Enter");
    await expect.poll(() => seen.cancels).toEqual([{ item_id: "e2" }]);
    await jump(page, 65_000);
    await expect.poll(() => seen.prewarms).toHaveLength(2);
  });

  const seeks = (page: Page) => page.evaluate(() => (window as any).__avplayCalls.filter((c: any[]) => c[0] === "seekTo").map((c: any[]) => c[1]));

  test("a seek back before the marker while the card shows posts no cancel, and the countdown's start adopts the warm job", async ({ page }) => {
    test.setTimeout(90_000);
    const seen = await playingFirst(page, SEGMENTS);
    await jump(page, 81_000);
    await expect(page.locator("#nextup")).toHaveClass(/\bon\b/);
    await expect.poll(() => seen.prewarms).toHaveLength(1);
    // The card owns OK and Back on the remote, so the seek comes as the phone's absolute seek, to 70 s: under the 80 s marker.
    seen.push({ type: "player_command", command: "seek", args: { position_ms: 70_000 } });
    await expect.poll(() => seeks(page)).toEqual([70_000]);
    await settle(page);
    expect(seen.cancels, "the card is up: the seek drops nothing").toEqual([]);
    await expect.poll(() => seen.starts.map((s) => s.item_id), { timeout: 20_000 }).toEqual(["e1", "e2"]);
    await settle(page);
    expect(seen.cancels, "the countdown's start is the adoption").toEqual([]);
    expect(seen.prewarms, "and nothing is warmed a second time").toHaveLength(1);
  });

  test("the same seek back with no card showing posts the cancel", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await playingFirst(page, null);    // no marker: the warm is at 60 s, the card in the last second
    await jump(page, 62_000);
    await expect.poll(() => seen.prewarms).toHaveLength(1);
    await expect(page.locator("#nextup")).not.toHaveClass(/\bon\b/);
    seen.push({ type: "player_command", command: "seek", args: { position_ms: 40_000 } });
    await expect.poll(() => seen.cancels).toEqual([{ item_id: "e2" }]);
  });

  test("a forward seek that re-prepares posts no cancel and no second prewarm", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await playingFirst(page, null);
    await jump(page, 62_000);
    await expect.poll(() => seen.prewarms).toHaveLength(1);
    await page.evaluate(() => (window as any).__avplay.zeroAfterSeek(3_000));   // AVPlay reads 0 while it re-prepares
    await page.keyboard.press("ArrowRight");
    await expect(page.locator("#ov-f30")).toHaveClass(/\bfoc\b/);
    await page.keyboard.press("Enter");             // +30 s
    await expect.poll(() => page.evaluate(() => (window as any).__avplayCalls.filter((c: any[]) => c[0] === "seekTo").length)).toBe(1);
    await page.waitForTimeout(4_500);               // the zero window and a few ticks after it
    expect(seen.cancels, "a forward seek never cancels").toEqual([]);
    expect(seen.prewarms, "so there is no second prewarm").toHaveLength(1);
  });

  test("the countdown starting nextId posts no cancel, with autoplay on", async ({ page }) => {
    test.setTimeout(90_000);
    const seen = await playingFirst(page, SEGMENTS);
    await jump(page, 81_000);
    await expect.poll(() => seen.prewarms).toHaveLength(1);
    await expect(page.locator("#nextup")).toHaveClass(/\bon\b/);
    await expect.poll(() => seen.starts.map((s) => s.item_id), { timeout: 20_000 }).toEqual(["e1", "e2"]);
    await settle(page);
    expect(seen.cancels, "the start is the adoption; nothing is cancelled").toEqual([]);
    expect(seen.prewarms).toHaveLength(1);
  });

  test("OK on the card and the controls' Next start nextId with no cancel", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await playingFirst(page, SEGMENTS);
    await jump(page, 81_000);
    await expect(page.locator("#nextup")).toHaveClass(/\bon\b/);
    await page.keyboard.press("Enter");
    await expect.poll(() => seen.starts.map((s) => s.item_id)).toEqual(["e1", "e2"]);
    await settle(page);
    expect(seen.cancels).toEqual([]);
  });

  test("Back on the card posts the cancel, and the title then plays on with nothing warming", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await playingFirst(page, SEGMENTS);
    await jump(page, 81_000);
    await expect(page.locator("#nextup")).toHaveClass(/\bon\b/);
    await expect.poll(() => seen.prewarms).toHaveLength(1);
    await page.keyboard.press("Escape");
    await expect.poll(() => seen.cancels).toEqual([{ item_id: "e2" }]);
    await expect(page.locator("#nextup")).not.toHaveClass(/\bon\b/);
    await settle(page);
    expect(seen.prewarms, "a cancelled card does not warm again").toHaveLength(1);
  });

  test("Back out of the player after the prewarm cancels it; with autoplay off the prewarm still starts", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await playingFirst(page, null, false);
    await jump(page, 61_000);
    await expect.poll(() => seen.prewarms.map((p) => p.item_id)).toEqual(["e2"]);
    await page.keyboard.press("Escape");            // hides nothing (controls are down): stops the title
    await expect.poll(() => seen.cancels).toEqual([{ item_id: "e2" }]);
  });
});

// DEB-5081 (DEB-5078, FR-33) — a start that fails on our encoder before its first picture is asked once more, out of sight. The backend is
// stood in for by page.route, so this proves what the app SENDS and shows: how many starts, the stops and reports between them, and that
// the error screen never draws on a start that was asked again and played.
test.describe("DEB-5078 FR-33 — a start that fails on our encoder is asked once more", () => {
  const API = "http://app:9505";

  type Seen = { starts: any[]; stops: any[]; qoe: any[] };

  /** [encoders]: the `encoder` each successive start's ticket carries (undefined = the field is absent, as an older server sends it). */
  async function boot(page: Page, encoders: Array<string | undefined>): Promise<Seen> {
    const seen: Seen = { starts: [], stops: [], qoe: [] };
    const card = { id: "m1", kind: "MOVIE", title: "Paper Moon", year: 2024, genre: null, rating: null, poster_url: "/api/tv/image/m1-poster", backdrop_url: "/api/tv/image/m1-backdrop" };
    const detail = { card, synopsis: "x", runtime: 90, cast: [], related: [], logo_url: null };
    const feed = { heroes: [], channels: [], rows: [{ id: "new", title: "New Arrivals", kind: "NEWLY_ADDED", items: [card] }], tile_shape: "POSTER", hero_height_pct: 48, focus_detail: "none" };
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
      if (path === "/api/tv/movie/m1") return json(route, 200, detail);
      if (path === "/api/tv/playstate") return json(route, 200, {});
      if (path === "/api/tv/playback/qoe") { seen.qoe.push(JSON.parse(req.postData()!)); return route.fulfill({ status: 204 }); }
      if (path === "/api/tv/playback/start") {
        const n = seen.starts.length;
        seen.starts.push(JSON.parse(req.postData() ?? "{}"));
        const encoder = encoders[Math.min(n, encoders.length - 1)];
        return json(route, 200, { jellyfin_base_url: API, access_token: "", item_id: "m1", container: "ts", direct_play: false,
          hls_url: `${API}/hls/m1/${n}/master.m3u8`, start_position_ms: 0, expires_at: 9_999_999_999, ...(encoder ? { encoder } : {}) });
      }
      if (path === "/api/tv/playback/stop") { seen.stops.push(JSON.parse(req.postData() ?? "{}")); return route.fulfill({ status: 204 }); }
      if (path.startsWith("/api/tv/")) return route.fulfill({ status: 204 });
      return json(route, 404);
    });
    await page.goto(SCREEN_URL);
    await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
    return seen;
  }
  const failNext = (page: Page, times: number) => page.evaluate((n) => { (window as any).__avplay.failPrepareTimes = n; }, times);
  const pressPlay = async (page: Page) => {
    await page.keyboard.press("Enter");
    await expect(page.locator("#detail-play")).toHaveClass(/focused/);
    await page.keyboard.press("Enter");
  };
  const opens = (page: Page) => page.evaluate(() => (window as any).__avplayCalls.filter((c: any[]) => c[0] === "open").map((c: any[]) => c[1]));
  /** Every frame of the page's own state that says *That didn't start*: the failed view must never be on, at any moment of the run. */
  const watchFailed = (page: Page) => page.evaluate(() => {
    (window as any).__failedSeen = false;
    new MutationObserver(() => { if (document.getElementById("failed")?.classList.contains("on")) (window as any).__failedSeen = true; })
      .observe(document.getElementById("failed")!, { attributes: true, attributeFilter: ["class"] });
  });
  const failedSeen = (page: Page) => page.evaluate(() => (window as any).__failedSeen as boolean);

  test("a failure before the first picture on our encoder makes exactly one more start, with no error screen, and the second ticket plays", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page, ["ours", "jellyfin"]);
    await watchFailed(page);
    await failNext(page, 1);
    await pressPlay(page);
    await expect.poll(() => seen.starts.length, { timeout: 15_000 }).toBe(2);
    await expect.poll(() => opens(page).then((o: string[]) => o.length), { timeout: 15_000 }).toBe(2);
    await waitForAvplayCall(page, "play");
    await expect(page.locator("#loading"), "the loading view is what the viewer sees until it plays").not.toHaveClass(/\bon\b/, { timeout: 15_000 });
    expect(await opens(page), "the second ticket is the one opened").toEqual([expect.stringContaining("/hls/m1/0/"), expect.stringContaining("/hls/m1/1/")]);
    expect(await failedSeen(page), "the error screen never drew").toBe(false);
    await page.waitForTimeout(1_000);
    expect(seen.starts, "and there is no third start").toHaveLength(2);
    expect(seen.starts[1], "the same item, the same capabilities").toMatchObject({ item_id: seen.starts[0].item_id, capabilities: seen.starts[0].capabilities });
    // The failed attempt is closed as any failed start: its own report with start_failure_status, and the session stopped at 0.
    expect(seen.stops[0], "the failed attempt's session is stopped at 0").toMatchObject({ item_id: "m1", position_ms: 0 });
    expect(seen.qoe[0], "each attempt reports as its own start").toMatchObject({ item_id: "m1", per_item: true, start_failure_status: -1 });
  });

  test("a second failure shows the error, and asks no third time", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page, ["ours", "ours"]);
    await failNext(page, 2);
    await pressPlay(page);
    await expect(page.locator("#failed")).toHaveClass(/\bon\b/, { timeout: 15_000 });
    await page.waitForTimeout(1_000);
    expect(seen.starts).toHaveLength(2);
    await expect.poll(() => seen.qoe.filter((q) => q.start_failure_status === -1).length).toBe(2);
  });

  test("a ticket with no encoder goes straight to the error", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page, [undefined, "ours"]);
    await failNext(page, 1);
    await pressPlay(page);
    await expect(page.locator("#failed")).toHaveClass(/\bon\b/, { timeout: 15_000 });
    await page.waitForTimeout(1_000);
    expect(seen.starts, "Jellyfin's or an older server's stream is not asked again").toHaveLength(1);
  });

  test("an error after the first frame makes no second start", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page, ["ours", "ours"]);
    await pressPlay(page);
    const opened = await waitForAvplayCall(page, "open");
    await waitForAvplayCall(page, "play", opened.index);
    await expect.poll(() => page.evaluate(() => (window as any).webapis.avplay.getCurrentTime()), { timeout: 10_000 }).toBeGreaterThan(1_000);   // the first frame is a few ticks in
    await page.waitForTimeout(1_000);
    await page.evaluate(() => (window as any).__avplay.error("PLAYER_ERROR_CONNECTION_FAILED"));
    await expect(page.locator("#failed")).toHaveClass(/\bon\b/, { timeout: 15_000 });
    await page.waitForTimeout(1_000);
    expect(seen.starts).toHaveLength(1);
  });

  test("Back during the second ask leaves nothing playing and makes no further start", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page, ["ours", "ours"]);
    // Hold the second start so Back lands while it is in flight.
    let release!: () => void;
    const held = new Promise<void>((r) => { release = r; });
    let starts = 0, inFlight = 0;   // counted here, because the boot's own handler only records a start once it is let through
    await page.route(`${API}/api/tv/playback/start`, async (route) => { if (starts++ >= 1) { inFlight++; await held; } await route.fallback(); });
    await failNext(page, 1);
    await pressPlay(page);
    // Back only once the second ask is on the wire: before that the title is still the first attempt's (loaded), and Back means something else.
    await expect.poll(() => inFlight, { timeout: 15_000 }).toBe(1);
    await expect(page.locator("#loading")).toHaveClass(/\bon\b/);
    await page.keyboard.press("Escape");
    await expect(page.locator("#idle")).toHaveClass(/\bon\b/, { timeout: 10_000 });
    release();
    await expect.poll(() => seen.starts.length, { timeout: 10_000 }).toBe(2);   // the held start is answered, and the app drops it
    await page.waitForTimeout(1_500);
    expect(await opens(page), "the second ticket is never opened").toHaveLength(1);
    await expect(page.locator("#failed")).not.toHaveClass(/\bon\b/);
  });
});

async function bootAndSignIn(page: Page, request: APIRequestContext, phoneDevice = "e2e-player-phone-r264"): Promise<{ phoneToken: string; screenId: string }> {
  await ownSignInBucket(page);
  await page.goto(SCREEN_URL, { waitUntil: "domcontentloaded", timeout: 10_000 });
  await page.locator("#setupHost").fill(process.env.APP_URL_FROM_SCREEN ?? "http://app:9505");
  await page.locator("#setupConnect").click();
  await expect(page.locator("#signin")).toHaveClass(/\bon\b/, { timeout: 15_000 });
  const codeLocator = page.locator("#signin-code");
  await expect(codeLocator).not.toHaveText("", { timeout: 10_000 });
  const code = (await codeLocator.textContent())?.replace(/\s/g, "");

  const login = await request.post("/api/tv/login", {
    data: { username: JF_USER, password: JF_PASS, device_id: phoneDevice, device_name: "E2E Player Phone" },
    headers: R252,
  });
  expect(login.status(), await login.text()).toBe(200);
  const phoneToken = (await login.json()).device_token as string;
  const auth = { authorization: `Bearer ${phoneToken}` };
  const screenIds = async (): Promise<string[]> =>
    ((await (await request.get("/api/remote/devices", { headers: auth })).json()) as any[]).filter((d) => d.kind === "tv" && d.platform === "tizen").map((d) => d.device_id);
  const before = new Set(await screenIds());

  const pair = await request.post("/api/tv/signin/approve", { headers: auth, data: { code } });
  expect(pair.status(), await pair.text()).toBe(204);

  let screenId = "";
  await expect
    .poll(async () => {
      const devices = (await (await request.get("/api/remote/devices", { headers: auth })).json()) as any[];
      const mine = devices.find((d) => d.kind === "tv" && d.platform === "tizen" && !before.has(d.device_id) && d.online === true);
      screenId = mine?.device_id ?? "";
      return screenId !== "";
    }, { timeout: 15_000 })
    .toBe(true);
  return { phoneToken, screenId };
}
