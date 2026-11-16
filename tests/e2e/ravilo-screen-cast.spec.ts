import { test, expect } from "@playwright/test";
import { fakeAvPlayInitScript, avplayCalls, waitForAvplayCall, scannedItemId } from "./helpers/screen-cast";

// Phase 248 — the phone -> backend -> Samsung TV path (236/R264/R265) is the owner's most important
// use case and the owner rarely has a real Samsung TV to test on. This drives the ACTUAL
// ravilo-screen.js bundle through a full cast, with a fake `webapis.avplay` standing in for the one
// thing CI genuinely cannot have (Samsung's own video pipeline) — everything else (sign-in, the events
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

test.describe("Phase 248 — casting to a screen, driven through the real bundle", () => {
  test("sign in, play, pause, seek and stop all reach the real ravilo-screen code", async ({ page, request }) => {
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
    await expect(page.locator("#signin")).toHaveClass(/\bon\b/, { timeout: 15_000 });
    await expect(page.locator("#setup")).not.toHaveClass(/\bon\b/);

    // ── 2. The TV mints a sign-in code; a signed-in phone approves it. ──
    // The mint is asynchronous, so wait for the code before approving it.
    const codeLocator = page.locator("#signin-code");
    await expect(codeLocator).not.toHaveText("", { timeout: 10_000 });
    const code = (await codeLocator.textContent())?.replace(/\s/g, "");

    const R252 = { "x-ravilo-platform": "phone", "x-ravilo-version": "9.99" };
    const phoneLogin = await request.post("/api/tv/login", {
      data: { username: JF_USER, password: JF_PASS, device_id: "e2e-cast-phone-248", device_name: "E2E Cast Phone" },
      headers: R252,
    });
    expect(phoneLogin.status(), await phoneLogin.text()).toBe(200);
    const phoneToken = (await phoneLogin.json()).device_token as string;

    const pairRes = await request.post("/api/tv/signin/approve", {
      headers: { authorization: `Bearer ${phoneToken}` },
      data: { code },
    });
    expect(pairRes.status(), await pairRes.text()).toBe(204);

    // Polling collects the TV's own token, then eventLoop() opens /api/tv/events. Remote play
    // returns device_offline until that socket is up, independently of successful sign-in.
    await expect
      .poll(async () => {
        const res = await request.get("/api/remote/devices", { headers: { authorization: `Bearer ${phoneToken}` } });
        const devices = await res.json();
        return devices.some((d: any) => d.kind === "tv" && d.platform === "tizen" && d.online === true);
      }, { timeout: 15_000 })
      .toBe(true);

    const devices = await (await request.get("/api/remote/devices", { headers: { authorization: `Bearer ${phoneToken}` } })).json();
    const screenDeviceId = devices.find((d: any) => d.kind === "tv" && d.platform === "tizen" && d.online).device_id as string;

    // ── 3. A real, scanned library item — never a fake id (requireVisible() checks jellystructure's
    //    own MediaStore, not the mock Jellyfin, so an unscanned id would 403 at the receiver). This
    //    spec must not depend on scan-fixture.spec.ts having already run (file order is not a
    //    contract — confirmed missing exactly this way on the first real CI run), so if Sintel is not
    //    searchable yet it starts a scan itself (a 409 from another file's scan is just as good). It
    //    then polls SEARCH, not /api/scan/status: since 2026-09-24 a second full pipeline run queued
    //    behind another spec's outlasted 90 s on a busy stack. See scannedItemId. ──
    const sintelId = await scannedItemId(request, phoneToken, "Sintel", { username: JF_USER, password: JF_PASS });

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
    // DEB-4656 FR-4 / DEB-4658 FR-5 — stopping reveals the server's Home feed and its focus.
    await expect(page.locator("#shell")).toHaveClass(/\bon\b/);
    await expect(page.locator("#shell-body .home-feed")).toBeVisible();
    await expect(page.locator("#shell-body .shell-target.focused")).toHaveCount(1);

    expect(pageErrors).toEqual([]);
  });
});
