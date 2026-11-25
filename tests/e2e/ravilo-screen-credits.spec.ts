import { test, expect, type Page } from "@playwright/test";
import { fakeAvPlayInitScript, avplayCalls, waitForAvplayCall } from "./helpers/screen-cast";

// DEB-5630 (DEB-5621, FR-47, FR-48) — on a Samsung the household's skip settings apply, and the credits get R182's card: Skip to scene,
// Next Episode or Skip credits, each beside Watch credits. The phone's push starts the item, the config is mocked per test, and AVPlay is
// the fake one (its stream is 120 s), so this proves what the app draws, sends and seeks to, not what a set's AVPlay does with it.

const SCREEN_URL = process.env.RAVILO_SCREEN_CAST_URL ?? "http://localhost:8092";
const API = "http://app:9505";

type Seen = { stops: any[]; remotePlays: any[]; push: (frame: object) => Promise<void> };
type Config = { skip_intro?: string; skip_credits?: string; skip_secs?: number; autoplay_next?: boolean };

const feed = {
  heroes: [], channels: [], rows: [], tile_shape: "POSTER", hero_height_pct: 48, focus_detail: "none",
};
const ticket = (itemId: string) => ({
  jellyfin_base_url: API, access_token: "", item_id: itemId, container: "ts", direct_play: false,
  hls_url: `${API}/hls/${itemId}/master.m3u8`, start_position_ms: 0, expires_at: 9_999_999_999,
});
const film = (segments: object) => ({ type: "play_item", jellyfin_id: "film-1", kind: "movie", title: "Paper Moon", segments });
const episode = (segments: object, next = true) => ({
  type: "play_item", jellyfin_id: "ep-1", kind: "episode", title: "Opening Reel", kicker: "S1 · E1", series_name: "Paper Series", segments,
  ...(next ? { next_id: "ep-2", next_title: "Second Reel", next_kicker: "S1 · E2" } : {}),
});

/** The shell is booted with a signed-in viewer and a fake events socket the test pushes the phone's play into. */
async function boot(page: Page, config: Config): Promise<Seen> {
  const seen: Seen = { stops: [], remotePlays: [], push: (frame) => page.evaluate((f) => (window as any).__pushEvent(f), frame) };
  await page.addInitScript(fakeAvPlayInitScript);
  await page.addInitScript(() => {
    const sockets: any[] = [];
    (window as any).__pushEvent = (frame: object) => {
      for (const s of sockets) if (s.readyState === 1) s.emit("message", new MessageEvent("message", { data: JSON.stringify(frame) }));
    };
    (window as any).__socketCount = () => sockets.filter((s) => s.readyState === 1).length;
    class FakeSocket extends EventTarget {
      static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
      url: string; readyState = 0; binaryType = "blob"; protocol = ""; extensions = ""; bufferedAmount = 0;
      onopen: any = null; onmessage: any = null; onclose: any = null; onerror: any = null;
      constructor(url: string) { super(); this.url = url; sockets.push(this); setTimeout(() => { this.readyState = 1; this.emit("open", new Event("open")); }, 0); }
      emit(type: string, ev: Event) { this.dispatchEvent(ev); (this as any)["on" + type]?.(ev); }
      send() {}
      close() { this.readyState = 3; this.emit("close", new Event("close")); }
    }
    (window as any).WebSocket = FakeSocket;
    localStorage.setItem("ravilo.screen.serverUrl", "http://app:9505");
    localStorage.setItem("ravilo.screen.profiles", JSON.stringify([{ userId: "viewer", displayName: "Test Viewer", token: "test-token" }]));
    (window as any).__avplay.setDuration(120_000);
  });
  const json = (route: any, body: object) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  await page.route(`${API}/**`, async (route) => {
    const req = route.request();
    const path = new URL(req.url()).pathname;
    const body = req.postData() ? JSON.parse(req.postData()!) : null;
    if (req.method() === "OPTIONS") return route.fulfill({ status: 204 });
    if (path === "/api/tv/home") return json(route, feed);
    if (path === "/api/tv/config") return json(route, config);
    if (path === "/api/tv/playstate") return json(route, {});
    if (path === "/api/tv/playback/start") return json(route, ticket(body.item_id));
    if (path === "/api/tv/playback/stop") { seen.stops.push(body); return route.fulfill({ status: 204 }); }
    if (path === "/api/remote/play") {
      seen.remotePlays.push(body);
      await route.fulfill({ status: 202 });
      await seen.push({ type: "play_item", jellyfin_id: body.jellyfin_item_id, kind: "episode", title: "Second Reel" });
      return;
    }
    if (path.startsWith("/api/tv/")) return route.fulfill({ status: 204 });
    return route.fulfill({ status: 404, contentType: "application/json", body: "{}" });
  });
  await page.goto(SCREEN_URL);
  await expect(page.locator("#shell-body .home-feed")).toBeVisible();
  await expect.poll(() => page.evaluate(() => (window as any).__socketCount())).toBeGreaterThan(0);
  return seen;
}

/** The phone's play, and the first picture: the segment loop only runs once the stream is loaded. */
async function play(page: Page, seen: Seen, frame: object) {
  await seen.push(frame);
  await waitForAvplayCall(page, "play");
  await expect(page.locator("#shell")).not.toHaveClass(/\bon\b/);
}
const jump = (page: Page, ms: number) => page.evaluate((m) => (window as any).__avplay.jump(m), ms);
const complete = (page: Page) => page.evaluate(() => (window as any).__avplay.complete());
const seeks = async (page: Page) => (await avplayCalls(page)).filter((c) => c[0] === "seekTo").map((c) => c[1]);
const settle = (page: Page) => page.waitForTimeout(1_000);   // a few 250 ms ticks of the segment loop
const card = (page: Page) => page.locator("#nextup");
const INTRO = { intro_start_ms: 5_000, intro_end_ms: 30_000 };

test.describe("DEB-5630 FR-47 — the household's Skip intro", () => {
  test("Off: no pill inside the intro", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page, { skip_intro: "OFF" });
    await play(page, seen, film(INTRO));
    await jump(page, 6_000);
    await settle(page);
    await expect(page.locator("#skip")).not.toHaveClass(/\bon\b/);
  });

  test("Prompt: today's pill, and OK on it seeks to the end of the intro", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page, { skip_intro: "PROMPT" });
    await play(page, seen, film(INTRO));
    await jump(page, 6_000);
    await expect(page.locator("#skip")).toHaveClass(/\bon\b/);
    await expect(page.locator("#skip")).toHaveText("Skip Intro");
    await settle(page);
    expect(await seeks(page), "Prompt never skips by itself").toEqual([]);
    await page.keyboard.press("Enter");
    await expect.poll(() => seeks(page)).toEqual([30_000]);
  });

  test("Auto: the pill counts skip_secs down and then seeks to the end of the intro", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page, { skip_intro: "AUTO", skip_secs: 4 });
    await play(page, seen, film(INTRO));
    await jump(page, 6_000);
    await expect(page.locator("#skip")).toHaveClass(/\bon\b/);
    const shownAt = Date.now();
    await expect(page.locator("#skip")).toContainText("4");
    await page.waitForTimeout(2_000);
    expect(await seeks(page), "not before the countdown runs out").toEqual([]);
    await expect.poll(() => seeks(page), { timeout: 6_000 }).toEqual([30_000]);
    expect(Date.now() - shownAt).toBeGreaterThanOrEqual(3_500);
    await expect(page.locator("#skip")).not.toHaveClass(/\bon\b/);
  });

  test("Auto: OK before the countdown ends skips at once", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page, { skip_intro: "AUTO", skip_secs: 8 });
    await play(page, seen, film(INTRO));
    await jump(page, 6_000);
    await expect(page.locator("#skip")).toHaveClass(/\bon\b/);
    await page.keyboard.press("Enter");
    await expect.poll(() => seeks(page), { timeout: 2_000 }).toEqual([30_000]);
  });

  test("Auto: Back on the pill cancels the skip for this item", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page, { skip_intro: "AUTO", skip_secs: 4 });
    await play(page, seen, film(INTRO));
    await jump(page, 6_000);
    await expect(page.locator("#skip")).toHaveClass(/\bon\b/);
    await page.keyboard.press("Escape");
    await expect(page.locator("#skip")).not.toHaveClass(/\bon\b/);
    await expect(page.locator("#idle"), "Back cancelled the skip, it did not leave").not.toHaveClass(/\bon\b/);
    await page.waitForTimeout(5_500);
    expect(await seeks(page)).toEqual([]);
    await expect(page.locator("#skip"), "and the pill does not come back inside this intro").not.toHaveClass(/\bon\b/);
  });
});

test.describe("DEB-5630 FR-48 — the card at the credits", () => {
  test("a film with a marker and no stinger: Skip credits beside Watch credits, and OK stops it at its length and leaves", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page, {});
    await play(page, seen, film({ credits_start_ms: 80_000 }));
    await jump(page, 79_000);
    await settle(page);
    await expect(card(page), "nothing before the marker").not.toHaveClass(/\bon\b/);
    await jump(page, 81_000);
    await expect(card(page)).toHaveClass(/\bon\b/);
    await expect(page.locator("#nu-k")).toHaveText("Credits");
    await expect(page.locator("#nu-t")).toHaveText("End of film");
    await expect(page.locator("#nu-p")).toHaveText("Skip credits");
    await expect(page.locator("#nu-w")).toHaveText("Watch credits");
    await expect(page.locator("#nu-c"), "no countdown").toHaveText("");
    await expect(page.locator("#nu-p")).toHaveClass(/\bfoc\b/);
    await page.keyboard.press("Enter");
    await expect.poll(() => seen.stops.length).toBe(1);
    expect(seen.stops[0]).toMatchObject({ item_id: "film-1", position_ms: 120_000 });
    await expect(page.locator("#shell")).toHaveClass(/\bon\b/);
    await expect(card(page)).not.toHaveClass(/\bon\b/);
  });

  test("a stinger: Skip to scene, no countdown, OK seeks to the scene and the card stays down", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page, {});
    await play(page, seen, film({ credits_start_ms: 80_000, stinger: { kind: "post_credits" } }));
    await jump(page, 81_000);
    await expect(card(page)).toHaveClass(/\bon\b/);
    await expect(page.locator("#nu-t")).toHaveText("There's a scene after this");
    await expect(page.locator("#nu-p")).toHaveText("Skip to scene");
    await expect(page.locator("#nu-c")).toHaveText("");
    await settle(page);
    expect(seen.stops, "nothing auto-skips past the scene").toEqual([]);
    await page.keyboard.press("Enter");
    await expect.poll(() => seeks(page)).toEqual([118_000]);   // no marker for the scene: 2 s before the end of the file, as Android TV does
    await expect(card(page)).not.toHaveClass(/\bon\b/);
    await jump(page, 118_500);
    await settle(page);
    await expect(card(page)).not.toHaveClass(/\bon\b/);
    expect(seen.stops).toEqual([]);
  });

  test("an episode with a next one: Next Episode beside Watch credits, today's countdown, Left/Right moves, OK plays the next", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page, { autoplay_next: true });
    await play(page, seen, episode({ credits_start_ms: 80_000 }));
    await jump(page, 81_000);
    await expect(card(page)).toHaveClass(/\bon\b/);
    await expect(page.locator("#nu-k")).toHaveText("UP NEXT");
    await expect(page.locator("#nu-t")).toContainText("Second Reel");
    await expect(page.locator("#nu-p")).toHaveText("Next");
    await expect(page.locator("#nu-w")).toHaveText("Watch credits");
    await expect(page.locator("#nu-c")).toHaveText(/Starts in \d+ s/);
    await expect(page.locator("#nu-p")).toHaveClass(/\bfoc\b/);
    await page.keyboard.press("ArrowRight");
    await expect(page.locator("#nu-w")).toHaveClass(/\bfoc\b/);
    await expect(page.locator("#nu-p")).not.toHaveClass(/\bfoc\b/);
    await page.keyboard.press("ArrowLeft");
    await expect(page.locator("#nu-p")).toHaveClass(/\bfoc\b/);
    await page.keyboard.press("Enter");
    await expect.poll(() => seen.remotePlays.map((p) => p.jellyfin_item_id)).toEqual(["ep-2"]);
  });

  test("Watch credits lowers the card for good on that item", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page, { autoplay_next: true });
    await play(page, seen, episode({ credits_start_ms: 80_000 }));
    await jump(page, 81_000);
    await expect(card(page)).toHaveClass(/\bon\b/);
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("Enter");
    await expect(card(page)).not.toHaveClass(/\bon\b/);
    await jump(page, 90_000);
    await settle(page);
    await expect(card(page), "latched for this item").not.toHaveClass(/\bon\b/);
    expect(seen.remotePlays).toEqual([]);
    await complete(page);
    await expect(page.locator("#shell"), "the title played to its end and the set is idle").toHaveClass(/\bon\b/);
    expect(seen.remotePlays).toEqual([]);
  });

  test("Back on the card does the same as Watch credits", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page, {});
    await play(page, seen, film({ credits_start_ms: 80_000 }));
    await jump(page, 81_000);
    await expect(card(page)).toHaveClass(/\bon\b/);
    await page.keyboard.press("Escape");
    await expect(card(page)).not.toHaveClass(/\bon\b/);
    await expect(page.locator("#idle")).not.toHaveClass(/\bon\b/);
    await settle(page);
    await expect(card(page)).not.toHaveClass(/\bon\b/);
  });

  test("no marker: no card for a film, however near its end", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page, {});
    await play(page, seen, film({}));
    await jump(page, 119_300);
    await settle(page);
    await expect(card(page)).not.toHaveClass(/\bon\b/);
  });

  test("Skip credits: Off raises no card at the credits, and autoplay starts the next episode at the real end", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page, { skip_credits: "OFF", autoplay_next: true });
    await play(page, seen, episode({ credits_start_ms: 80_000 }));
    await jump(page, 81_000);
    await settle(page);
    await expect(card(page)).not.toHaveClass(/\bon\b/);
    await jump(page, 119_500);
    await settle(page);
    await expect(card(page), "not in the last second either").not.toHaveClass(/\bon\b/);
    await complete(page);
    await expect(card(page)).toHaveClass(/\bon\b/);
    await expect(page.locator("#nu-c")).toHaveText(/Starts in \d+ s/);
    await expect.poll(() => seen.remotePlays.map((p) => p.jellyfin_item_id), { timeout: 20_000 }).toEqual(["ep-2"]);
  });

  test("Skip credits: Off with autoplay off: the item just ends", async ({ page }) => {
    test.setTimeout(60_000);
    const seen = await boot(page, { skip_credits: "OFF", autoplay_next: false });
    await play(page, seen, episode({ credits_start_ms: 80_000 }));
    await jump(page, 81_000);
    await settle(page);
    await expect(card(page)).not.toHaveClass(/\bon\b/);
    await complete(page);
    await expect(page.locator("#shell")).toHaveClass(/\bon\b/);
    await expect(card(page)).not.toHaveClass(/\bon\b/);
    expect(seen.remotePlays).toEqual([]);
  });
});
