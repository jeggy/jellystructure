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
