import { test, expect } from "@playwright/test";

// Phase 248 — the phone -> backend -> Samsung TV path (236/R264/R265) is the owner's most important
// use case and the owner rarely has a real Samsung TV to test on. This drives the ACTUAL
// ravilo-screen.js bundle through a full cast, with a fake `webapis.avplay` standing in for the one
// thing CI genuinely cannot have (Samsung's own video pipeline) — everything else (pairing, the events
// socket, TvApiClient, the receiver's own state machine, ravilo-receiver-core) is the real, shipped
// code. See ravilo-screen-cors.spec.ts / ravilo-screen.spec.ts (phase 247) for the CORS-refusal
// coverage this spec deliberately does not touch: the `ravilo-screen-cast` origin alias below is added
// to the allow-list for this spec only, so 247's proof that `ravilo-screen`'s own (unlisted) origin is
// refused stays intact.
//
// Three real bugs found by driving the real code while building this spec, not by the hand-rolled
// protocol test in screens.spec.ts (which plays both roles by hand and exercises none of this):
//   1. Screen.kt's HttpClient(Js) never called install(WebSockets), so connectEvents() threw
//      immediately and was silently swallowed by eventLoop()'s runCatching — the receiver's events
//      socket never connected at all. Fixed: matches every other WS-using client in this codebase.
//   2. RemoteRoutes.kt sends the phase-111 stop/pause/unpause quartet capitalized ("Pause"), but
//      Screen.kt's onPlaystateCommand matched lowercase only — every other consumer (PlayerScreen.kt)
//      already normalizes with .lowercase(); this file didn't. Fixed the same way.
//   3. The real sender (ravilo-ui's ScreenSender.seekTo(), R265's scrub bar) sends only absolute
//      "seek" + position_ms; onPlayerCommand had a "seek_relative" case but no "seek" case, and no
//      sender anywhere sends "seek_relative" (RemoteRoutes.kt's own dispatcher doesn't even recognize
//      that name). Fixed by adding a "seek" case reading position_ms, alongside the existing one.
// Together these meant a real phone paired to a real Tizen ravilo-screen could list it, but every
// play/pause/stop/seek command sent to it went nowhere. All three fixed as part of this phase, per
// the owner's explicit go-ahead once each was found and confirmed against a real local backend.

const SCREEN_URL = process.env.RAVILO_SCREEN_CAST_URL ?? "http://localhost:8092";
const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";

// Records every call the real AvPlayBackend (MediaBackend.kt) makes, in order, onto window.__avplayCalls
// — read back via page.evaluate rather than asserting on transient DOM state (the loading screen closes
// again within milliseconds of the fake's prepareAsync succeeding; polling for the call log is the
// deterministic signal FR-248-3 asks for).
function fakeAvPlayInitScript() {
  (window as any).__avplayCalls = [];
  let state = "IDLE";
  let posMs = 0;
  let ticking: any = null;
  let listener: any = {};
  (window as any).webapis = {
    avplay: {
      open(url: string) {
        (window as any).__avplayCalls.push(["open", url]);
        state = "IDLE";
      },
      setDisplayRect(...args: number[]) {
        (window as any).__avplayCalls.push(["setDisplayRect", ...args]);
      },
      setDisplayMethod(method: string) {
        (window as any).__avplayCalls.push(["setDisplayMethod", method]);
      },
      setListener(l: any) {
        listener = l;
      },
      prepareAsync(onSuccess: () => void, _onError: (e: unknown) => void) {
        (window as any).__avplayCalls.push(["prepareAsync"]);
        state = "READY";
        setTimeout(() => { try { onSuccess(); } catch { /* Kotlin's own callback, not ours to swallow */ } }, 10);
      },
      play() {
        (window as any).__avplayCalls.push(["play"]);
        state = "PLAYING";
        if (!ticking) ticking = setInterval(() => { posMs += 250; }, 250);
      },
      pause() {
        (window as any).__avplayCalls.push(["pause"]);
        state = "PAUSED";
        if (ticking) { clearInterval(ticking); ticking = null; }
      },
      stop() {
        (window as any).__avplayCalls.push(["stop"]);
        state = "IDLE";
      },
      seekTo(ms: number, onSuccess?: () => void) {
        (window as any).__avplayCalls.push(["seekTo", ms]);
        posMs = ms;
        onSuccess?.();
      },
      close() {
        (window as any).__avplayCalls.push(["close"]);
        if (ticking) { clearInterval(ticking); ticking = null; }
        state = "NONE";
        posMs = 0;
      },
      getState() { return state; },
      getCurrentTime() { return posMs; },
      getDuration() { return 60_000; },
      getTotalTrackInfo() { return []; },
      setSelectTrack(type: string, index: number) {
        (window as any).__avplayCalls.push(["setSelectTrack", type, index]);
      },
      setStreamingProperty() {},
    },
  };
}

async function avplayCalls(page: import("@playwright/test").Page): Promise<Array<[string, ...unknown[]]>> {
  return page.evaluate(() => (window as any).__avplayCalls ?? []);
}

/** Waits for a call named [name] to appear at index >= [fromIndex] in the fake's recorded call log —
 *  the "no fixed sleeps" rule (FR-248-3) applied to a DOM-external signal, same shape as
 *  screens.spec.ts's waitForType/fromIndex pattern. */
async function waitForAvplayCall(page: import("@playwright/test").Page, name: string, fromIndex = 0, timeoutMs = 10_000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const calls = await avplayCalls(page);
    const idx = calls.findIndex((c, i) => i >= fromIndex && c[0] === name);
    if (idx >= 0) return { index: idx, call: calls[idx] };
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error(`avplay never called "${name}" (from index ${fromIndex}) within ${timeoutMs}ms — got: ${JSON.stringify(await avplayCalls(page))}`);
}

test.describe("Phase 248 — casting to a screen, driven through the real bundle", () => {
  test("pair, play, pause, seek and stop all reach the real ravilo-screen code", async ({ page, request }) => {
    test.setTimeout(120_000); // generous — a shared, busy backend's own scan decides most of this

    await page.addInitScript(fakeAvPlayInitScript);

    const pageErrors: string[] = [];
    page.on("pageerror", (err) => pageErrors.push(err.message));

    // ── 1. The screen boots, completes R269's setup screen against the real backend. ────────────
    await page.goto(SCREEN_URL, { waitUntil: "domcontentloaded", timeout: 10_000 });
    await page.locator("#setupHost").fill(process.env.APP_URL_FROM_SCREEN ?? "http://app:9505");
    await page.locator("#setupConnect").click();
    // Screen.kt's idle() runs immediately once setup succeeds — explicit "on", not the static-markup
    // default 247's spec had to work around (see that phase's dev notes on this exact gotcha).
    await expect(page.locator("#idle")).toHaveClass(/\bon\b/, { timeout: 15_000 });
    await expect(page.locator("#setup")).not.toHaveClass(/\bon\b/);

    // ── 2. A real pairing code appears; a fake phone (raw HTTP, same shape as screens.spec.ts) claims it. ──
    // pairingLoop() mints the code over a real network round trip after idle() has already rendered
    // (with an empty code) — auto-retrying expect, not a single read, or this races the mint.
    const codeLocator = page.locator("#idle-code");
    await expect(codeLocator).not.toHaveText("", { timeout: 10_000 });
    const code = await codeLocator.textContent();

    const R252 = { "x-ravilo-platform": "phone", "x-ravilo-version": "9.99" };
    const phoneLogin = await request.post("/api/tv/login", {
      data: { username: JF_USER, password: JF_PASS, device_id: "e2e-cast-phone-248", device_name: "E2E Cast Phone" },
      headers: R252,
    });
    expect(phoneLogin.status(), await phoneLogin.text()).toBe(200);
    const phoneToken = (await phoneLogin.json()).device_token as string;

    const pairRes = await request.post("/api/remote/pair", {
      headers: { authorization: `Bearer ${phoneToken}` },
      data: { code },
    });
    expect(pairRes.status(), await pairRes.text()).toBe(200);

    // The receiver's own pairingLoop() picks up its token, but eventLoop() (a SEPARATE coroutine) is
    // what actually opens the /api/tv/events socket tvEventBus.isConnected() checks — /api/remote/play
    // 409s "device_offline" until that socket is up, which is a real, independent race from pairing.
    await expect
      .poll(async () => {
        const res = await request.get("/api/remote/devices", { headers: { authorization: `Bearer ${phoneToken}` } });
        const devices = await res.json();
        return devices.some((d: any) => d.kind === "screen" && d.online === true);
      }, { timeout: 15_000 })
      .toBe(true);

    const devices = await (await request.get("/api/remote/devices", { headers: { authorization: `Bearer ${phoneToken}` } })).json();
    const screenDeviceId = devices.find((d: any) => d.kind === "screen").device_id as string;

    // ── 3. A real, scanned library item — never a fake id (requireVisible() checks jellystructure's
    //    own MediaStore, not the mock Jellyfin, so an unscanned id would 403 at the receiver). This
    //    spec must not depend on scan-fixture.spec.ts having already run (file order is not a
    //    contract — confirmed missing exactly this way on the first real CI run), so it triggers its
    //    own scan via the admin cookie session, no browser UI needed (Playwright's `request` context
    //    carries the cookie automatically to the follow-up calls). No `full=true` — this only needs
    //    Sintel matched at all, not freshly re-pulled, and skipping it avoids redundant TMDB churn if
    //    another spec file already scanned this same shared backend. A concurrent 409 from another
    //    file's own scan is fine — either way, waiting for `running` to clear is what matters. A
    //    generous timeout: a busy shared runner, not just this one scan, decides how long this takes. ──
    await request.post("/api/auth/login", { data: { username: JF_USER, password: JF_PASS } });
    await request.post("/api/scan");
    await expect
      .poll(async () => (await (await request.get("/api/scan/status")).json())?.running, { timeout: 90_000 })
      .toBe(false);

    const search = await (await request.get("/api/tv/search?q=Sintel", { headers: { authorization: `Bearer ${phoneToken}`, ...R252 } })).json();
    const sintelId = search.items?.[0]?.id as string;
    expect(sintelId, "Sintel was not found via /api/tv/search — was the fixture scan run first?").toBeTruthy();

    // ── 4. The phone plays it; the RECEIVER'S OWN CODE negotiates and opens the fake player. ───────
    const playRes = await request.post("/api/remote/play", {
      headers: { authorization: `Bearer ${phoneToken}` },
      data: { device_id: screenDeviceId, jellyfin_item_id: sintelId, start_position_ms: 0 },
    });
    expect(playRes.status(), await playRes.text()).toBe(202);

    const opened = await waitForAvplayCall(page, "open");
    expect(String(opened.call[1])).toContain(sintelId);
    const prepared = await waitForAvplayCall(page, "prepareAsync", opened.index);
    await waitForAvplayCall(page, "play", prepared.index);

    await expect
      .poll(async () => {
        const status = await (await request.get(`/api/remote/devices/${screenDeviceId}`, { headers: { authorization: `Bearer ${phoneToken}` } })).json();
        return status?.now_playing?.playing;
      }, { timeout: 10_000 })
      .toBe(true);

    // ── 5. Pause reaches the real player. ───────────────────────────────────────────────────────
    let callsSoFar = (await avplayCalls(page)).length;
    const pauseRes = await request.post("/api/remote/command", {
      headers: { authorization: `Bearer ${phoneToken}` },
      data: { device_id: screenDeviceId, command: "pause" },
    });
    expect(pauseRes.status()).toBe(202);
    await waitForAvplayCall(page, "pause", callsSoFar);
    // toBeFalsy(), not toBe(false) — a real, confirmed gap found building this test: the server's
    // global ContentNegotiation (Server.kt:193) serializes with encodeDefaults=false, so `playing`
    // (default false) is DROPPED from the JSON entirely once paused, not sent as `false`. This is
    // exactly the "second symptom" phase 245's own notes predicted and deliberately deferred
    // ("a bigger-radius change... left for its own phase") — confirmed here, not fixed here.
    await expect
      .poll(async () => (await (await request.get(`/api/remote/devices/${screenDeviceId}`, { headers: { authorization: `Bearer ${phoneToken}` } })).json())?.now_playing?.playing)
      .toBeFalsy();

    // Resume before testing seek, so its target position is measured from a known base.
    await request.post("/api/remote/command", { headers: { authorization: `Bearer ${phoneToken}` }, data: { device_id: screenDeviceId, command: "unpause" } });
    await waitForAvplayCall(page, "play", callsSoFar);

    // ── 6. Absolute "seek" — what the REAL sender actually sends. A second real bug found building
    //    this spec: ravilo-ui's ScreenSender.seekTo() (R265's scrub bar) sends only this shape
    //    (RemoteRoutes.kt maps command "seek" + position_ms to a player_command event) — no client in
    //    this codebase sends "seek_relative" at all, so that was the wrong case to guard here. Fixed
    //    alongside the "Pause" casing bug: onPlayerCommand grew a "seek" branch reading position_ms,
    //    matching "seek_relative"'s existing shape. Before this fix, dragging the phone's scrub bar
    //    while casting to a Tizen screen did nothing at all. ──────────────────────────────────────
    callsSoFar = (await avplayCalls(page)).length;
    const seekRes = await request.post("/api/remote/command", {
      headers: { authorization: `Bearer ${phoneToken}` },
      data: { device_id: screenDeviceId, command: "seek", position_ms: 45_000 },
    });
    expect(seekRes.status(), await seekRes.text()).toBe(202);
    const seekCall = await waitForAvplayCall(page, "seekTo", callsSoFar);
    expect(seekCall.call[1]).toBe(45_000);

    // Note (not tested here): Screen.kt's "seek_relative" case is unreachable through this same route
    // today — RemoteRoutes.kt's /api/remote/command dispatcher recognizes "seek", "skip", "next", …
    // but never "seek_relative", and no sender in this codebase sends any of those in a way that
    // reaches it either. Genuinely dead code on both ends, not a regression risk worth a test.

    // ── 7. Stop returns the receiver to idle. ───────────────────────────────────────────────────
    callsSoFar = (await avplayCalls(page)).length;
    const stopRes = await request.post("/api/remote/command", {
      headers: { authorization: `Bearer ${phoneToken}` },
      data: { device_id: screenDeviceId, command: "stop" },
    });
    expect(stopRes.status()).toBe(202);
    await waitForAvplayCall(page, "close", callsSoFar);
    await expect(page.locator("#idle")).toHaveClass(/\bon\b/, { timeout: 10_000 });

    expect(pageErrors).toEqual([]);
  });
});
