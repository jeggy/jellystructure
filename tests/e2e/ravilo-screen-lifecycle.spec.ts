import { test, expect, type APIRequestContext, type Page } from "@playwright/test";
import { fakeAvPlayInitScript, ownSignInBucket, avplayCalls, waitForAvplayCall, scannedItemId } from "./helpers/screen-cast";

// DEB-4925 (FR-22) — leaving the Samsung app and coming back. Tizen fires `visibilitychange` when the viewer goes to
// another app; the screen ends a stream as Back does, and on a return it opens its events socket afresh and, after
// 5 minutes away, loads a fresh Home. Driven through the real ravilo-screen.js with the fake AVPlay; whether Tizen 5.0
// fires the event and what AVPlay does across a 20-minute absence is QA's, on the Samsung.

const SCREEN_URL = process.env.RAVILO_SCREEN_CAST_URL ?? "http://localhost:8092";
const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";
const R252 = { "x-ravilo-platform": "phone", "x-ravilo-version": "9.99" };

async function setHidden(page: Page, hidden: boolean) {
  await page.evaluate((h) => {
    Object.defineProperty(document, "hidden", { configurable: true, get: () => h });
    Object.defineProperty(document, "visibilityState", { configurable: true, get: () => (h ? "hidden" : "visible") });
    document.dispatchEvent(new Event("visibilitychange"));
  }, hidden);
}

test.describe("DEB-4925 FR-22 — hiding and returning to the Samsung app", () => {
  test("hidden ends the stream like Back; a return after 5 minutes is a live Home on a fresh socket", async ({ page, request }) => {
    test.setTimeout(150_000);
    await page.addInitScript(fakeAvPlayInitScript);
    await page.addInitScript(() => {
      (window as any).__sockets = 0;
      const Native = window.WebSocket;
      (window as any).WebSocket = function (...a: any[]) { (window as any).__sockets++; return new (Native as any)(...a); } as any;
      Object.assign((window as any).WebSocket, Native);
      (window as any).WebSocket.prototype = Native.prototype;
    });
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
    await expect(page.locator("html")).toHaveClass(/\bavplay-on\b/);

    // ── hidden: the stream ends once, as Back does ──
    const before = (await avplayCalls(page)).length;
    await setHidden(page, true);
    await waitForAvplayCall(page, "close", before);
    await expect(page.locator("#idle")).toHaveClass(/\bon\b/);
    await expect(page.locator("html")).not.toHaveClass(/\bavplay-on\b/);
    await expect.poll(async () => (await nowPlaying()).loaded ?? false, { timeout: 10_000 }).toBe(false);
    await setHidden(page, true);   // a second hide does nothing
    expect((await avplayCalls(page)).filter((c, i) => i >= before && c[0] === "close")).toHaveLength(1);

    // ── back after 5 minutes: a new socket and a freshly loaded Home ──
    const socketsBefore = await page.evaluate(() => (window as any).__sockets as number);
    await page.evaluate(() => { const real = Date.now; Date.now = () => real() + 6 * 60_000; });
    await setHidden(page, false);
    await expect.poll(async () => page.evaluate(() => (window as any).__sockets as number), { timeout: 10_000 }).toBeGreaterThan(socketsBefore);
    await expect(page.locator("#shell")).toHaveClass(/\bon\b/);
    await expect(page.locator("#shell-body [data-row], #shell-body .shell-row").first()).toBeVisible({ timeout: 15_000 });
    await page.keyboard.press("ArrowDown");   // and a key still moves

    expect(pageErrors, pageErrors.join("\n")).toEqual([]);
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
