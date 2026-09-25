import { test, expect, type APIRequestContext, type Page } from "@playwright/test";
import { fakeAvPlayInitScript, avplayCalls, waitForAvplayCall, scannedItemId } from "./helpers/screen-cast";

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

    const { phoneToken, screenId } = await bootAndPair(page, request);
    const auth = { authorization: `Bearer ${phoneToken}` };
    const nowPlaying = async () => (await (await request.get(`/api/remote/devices/${screenId}`, { headers: auth })).json())?.now_playing ?? {};

    const itemId = await scannedItemId(request, phoneToken, "Big Buck Bunny", { username: JF_USER, password: JF_PASS });
    const play = await request.post("/api/remote/play", { headers: auth, data: { device_id: screenId, jellyfin_item_id: itemId, start_position_ms: 0 } });
    expect(play.status(), await play.text()).toBe(202);
    const opened = await waitForAvplayCall(page, "open");
    await waitForAvplayCall(page, "play", opened.index);
    await expect.poll(async () => (await nowPlaying()).loaded, { timeout: 15_000 }).toBe(true);

    // ── Transport: OK pauses (and the chrome shows), ← seeks back, → seeks forward. ──────────────────
    const before = (await avplayCalls(page)).length;
    await page.keyboard.press("Enter");
    await waitForAvplayCall(page, "pause", before);
    await expect(page.locator("#overlay")).toHaveClass(/\bon\b/);
    await expect(page.locator("#ov-hint")).not.toHaveText("");
    const seekFrom = (await avplayCalls(page)).length;
    await page.keyboard.press("ArrowRight");
    await waitForAvplayCall(page, "seekTo", seekFrom);

    // ── The picker: ↓ opens it on Subtitles, with Off, the languages and the size row. ─────────────
    await page.keyboard.press("ArrowDown");
    const picker = page.locator("#picker");
    await expect(picker).toHaveClass(/\bon\b/);
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
    await page.keyboard.press("ArrowDown");
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
    await page.keyboard.press("ArrowDown");
    await expect(picker).toHaveClass(/\bon\b/);
    await page.keyboard.press("Escape");
    await expect(picker).not.toHaveClass(/\bon\b/);
    await page.keyboard.press("ArrowUp");              // chrome up
    await expect(page.locator("#overlay")).toHaveClass(/\bon\b/);
    await page.keyboard.press("Escape");               // hides it
    await expect(page.locator("#overlay")).not.toHaveClass(/\bon\b/);
    await page.keyboard.press("Escape");               // stops
    await expect(page.locator("#idle")).toHaveClass(/\bon\b/, { timeout: 10_000 });

    expect(pageErrors, pageErrors.join("\n")).toEqual([]);
  });

  test("a title that cannot start says why, in one sentence, and goes back to idle", async ({ page, request }) => {
    test.setTimeout(120_000);
    await page.addInitScript(fakeAvPlayInitScript);
    const { phoneToken, screenId } = await bootAndPair(page, request, "e2e-player-phone-r264-b");
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

async function bootAndPair(page: Page, request: APIRequestContext, phoneDevice = "e2e-player-phone-r264"): Promise<{ phoneToken: string; screenId: string }> {
  await page.goto(SCREEN_URL, { waitUntil: "domcontentloaded", timeout: 10_000 });
  await page.locator("#setupHost").fill(process.env.APP_URL_FROM_SCREEN ?? "http://app:9505");
  await page.locator("#setupConnect").click();
  await expect(page.locator("#idle")).toHaveClass(/\bon\b/, { timeout: 15_000 });
  const codeLocator = page.locator("#idle-code");
  await expect(codeLocator).not.toHaveText("", { timeout: 10_000 });
  const code = await codeLocator.textContent();

  const login = await request.post("/api/tv/login", {
    data: { username: JF_USER, password: JF_PASS, device_id: phoneDevice, device_name: "E2E Player Phone" },
    headers: R252,
  });
  expect(login.status(), await login.text()).toBe(200);
  const phoneToken = (await login.json()).device_token as string;
  const auth = { authorization: `Bearer ${phoneToken}` };
  const screenIds = async (): Promise<string[]> =>
    ((await (await request.get("/api/remote/devices", { headers: auth })).json()) as any[]).filter((d) => d.kind === "screen").map((d) => d.device_id);
  const before = new Set(await screenIds());

  const pair = await request.post("/api/remote/pair", { headers: auth, data: { code } });
  expect(pair.status(), await pair.text()).toBe(200);

  let screenId = "";
  await expect
    .poll(async () => {
      const devices = (await (await request.get("/api/remote/devices", { headers: auth })).json()) as any[];
      const mine = devices.find((d) => d.kind === "screen" && !before.has(d.device_id) && d.online === true);
      screenId = mine?.device_id ?? "";
      return screenId !== "";
    }, { timeout: 15_000 })
    .toBe(true);
  return { phoneToken, screenId };
}
