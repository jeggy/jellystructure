import { test, expect } from "@playwright/test";

// Phase 236 — the backend drives a TV for a phone. The Chromecast receiver model (218/R245) with
// Google removed from the middle: a receiver-only TV app (R264, not yet built) enrols by a code shown
// on its own screen, and the phone (R265, not yet built) lists it and drives it entirely through the
// server. Since neither client exists yet, this test plays BOTH parts by hand against the real routes —
// exactly the "fake screen device" the phase's own Acceptance section asks for — using real WebSocket
// connections in a live browser page (Node's own runtime in this project's Playwright image has no
// global WebSocket — confirmed empirically against mcr.microsoft.com/playwright:v1.45.0-noble, Node
// 20.15.0 — so this drives the whole flow from inside a same-origin `page.evaluate`, which does).

const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";

test.describe("Phase 236 — screens API", () => {
  test("a screen pairs by code, plays for the pairing user, and reports status back to a subscribed phone", async ({ page, request, baseURL }) => {
    test.setTimeout(60_000);

    // Same-origin page so every fetch()/WebSocket call below is same-origin — no CORS involved.
    await page.goto("/login", { waitUntil: "domcontentloaded" });

    // The admin cookie session (for minting an API key) is a separate credential path from the TV
    // device-token login below, but proxies the same Jellyfin account — see auth.spec.ts.
    const adminLogin = await request.post("/api/auth/login", { data: { username: JF_USER, password: JF_PASS } });
    expect(adminLogin.status(), await adminLogin.text()).toBe(200);

    const result = await page.evaluate(
      async ({ user, pass }) => {
        const R252 = { "x-ravilo-platform": "phone", "x-ravilo-version": "9.99" };
        const out: Record<string, unknown> = {};

        async function json(res: Response) {
          return res.status === 200 || res.status === 202 ? res.json() : null;
        }

        // ── 1. The phone signs in (device token). ──────────────────────────────────────────────
        const phoneLogin = await fetch("/api/tv/login", {
          method: "POST",
          headers: { "content-type": "application/json", ...R252 },
          body: JSON.stringify({ username: user, password: pass, device_id: "e2e-phone-236", device_name: "E2E Phone" }),
        });
        out.phoneLoginStatus = phoneLogin.status;
        const phoneAuth = await json(phoneLogin);
        const phoneToken = phoneAuth?.device_token as string | undefined;
        const phoneUserId = phoneAuth?.session?.user_id as string | undefined;

        // ── 2. The unpaired screen mints a code (open path). ────────────────────────────────────
        const codeRes = await fetch("/api/tv/screen/code", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ device_id: "e2e-screen-236", device_name: "E2E Screen", platform: "tizen-screen" }),
        });
        out.codeStatus = codeRes.status;
        const codeBody = await json(codeRes);

        // Poll before claiming: must wait, never a stale/wrong code (FR-236-2).
        const earlyPoll = await fetch("/api/tv/screen/claim", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ code: codeBody.code, claim_secret: codeBody.claim_secret }),
        });
        out.earlyPollStatus = earlyPoll.status; // expect 202

        // ── 3. The phone claims the code (device token only — FR-236-2). ───────────────────────
        const pairRes = await fetch("/api/remote/pair", {
          method: "POST",
          headers: { "content-type": "application/json", authorization: `Bearer ${phoneToken}` },
          body: JSON.stringify({ code: codeBody.code }),
        });
        out.pairStatus = pairRes.status;

        // An API key can never pair (FR-236-2) — checked once, inline, no separate key needed yet.
        // (Skipped here: exercised again below with the real API key once one exists.)

        // ── 4. The screen collects its own token (single-use). ──────────────────────────────────
        const claimRes = await fetch("/api/tv/screen/claim", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ code: codeBody.code, claim_secret: codeBody.claim_secret }),
        });
        out.claimStatus = claimRes.status;
        const claimed = await json(claimRes);
        const screenToken = claimed?.device_token as string | undefined;

        const replay = await fetch("/api/tv/screen/claim", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ code: codeBody.code, claim_secret: codeBody.claim_secret }),
        });
        out.replayClaimStatus = replay.status; // single-use ⇒ unknown now, never 200 again

        // ── 5. The screen opens its events socket (device token in the query string). ───────────
        const wsBase = location.origin.replace(/^http/, "ws");
        const screenSocket = new WebSocket(`${wsBase}/api/tv/events?token=${screenToken}`);
        const screenMessages: any[] = [];
        screenSocket.onmessage = (e) => { try { screenMessages.push(JSON.parse(e.data)); } catch {} };
        await new Promise((resolve, reject) => {
          screenSocket.onopen = resolve;
          screenSocket.onerror = reject;
          setTimeout(() => reject(new Error("screen socket open timeout")), 8_000);
        });

        // ── 6. The phone opens its own events socket and subscribes to the screen's status. ─────
        const phoneSocket = new WebSocket(`${wsBase}/api/tv/events?token=${phoneToken}`);
        const phoneMessages: any[] = [];
        phoneSocket.onmessage = (e) => { try { phoneMessages.push(JSON.parse(e.data)); } catch {} };
        await new Promise((resolve, reject) => {
          phoneSocket.onopen = resolve;
          phoneSocket.onerror = reject;
          setTimeout(() => reject(new Error("phone socket open timeout")), 8_000);
        });
        phoneSocket.send(JSON.stringify({ type: "subscribe_device", device_id: "e2e-screen-236" }));

        // ── 7. The phone lists its devices: the screen is there, nearby (same test-runner address). ──
        const devicesRes = await fetch("/api/remote/devices", { headers: { authorization: `Bearer ${phoneToken}` } });
        out.devicesStatus = devicesRes.status;
        const devices = await json(devicesRes);
        const screenDevice = (devices ?? []).find((d: any) => d.device_id === "e2e-screen-236");
        out.screenListed = !!screenDevice;
        out.screenNearby = screenDevice?.nearby;
        out.screenKind = screenDevice?.kind;

        // ── 8. The phone plays an item; the screen's socket receives play_item with session_user_id. ──
        const playRes = await fetch("/api/remote/play", {
          method: "POST",
          headers: { "content-type": "application/json", authorization: `Bearer ${phoneToken}` },
          body: JSON.stringify({ device_id: "e2e-screen-236", jellyfin_item_id: "e2e-fake-item-1", start_position_ms: 0 }),
        });
        out.playStatus = playRes.status;

        // Bug fix — [fromIndex] lets a caller ignore messages already in [bag] before some action it's
        // about to trigger, so it waits for a NEW message of [type] rather than re-matching a stale one
        // already sitting in the buffer (the phone's own subscribe_device push and the seek's push are
        // both "device_status" — without this, waitForType always returned the first/stale one).
        async function waitForType(bag: any[], type: string, timeoutMs = 5_000, fromIndex = 0): Promise<any> {
          const start = Date.now();
          while (Date.now() - start < timeoutMs) {
            const found = bag.slice(fromIndex).find((m) => m.type === type);
            if (found) return found;
            await new Promise((r) => setTimeout(r, 100));
          }
          return null;
        }

        const playItemMsg = await waitForType(screenMessages, "play_item");
        out.playItemSessionUserId = playItemMsg?.session_user_id;

        // The screen "starts playing" and reports it.
        await fetch("/api/tv/playback/status", {
          method: "POST",
          headers: { "content-type": "application/json", authorization: `Bearer ${screenToken}` },
          body: JSON.stringify({ item_id: "e2e-fake-item-1", loaded: true, playing: true, position_ms: 0, session_user_id: phoneUserId }),
        });

        // ── 9. A garbage API key is simply unauthenticated (401) — the real "different real user's
        //    key gets 404" case is exercised below, outside page.evaluate, once such a key exists. ──
        const foreignPlay = await fetch("/api/remote/play", {
          method: "POST",
          headers: { "content-type": "application/json", "x-js-api-key": "not-a-real-key-236" },
          body: JSON.stringify({ device_id: "e2e-screen-236", jellyfin_item_id: "e2e-fake-item-1" }),
        });
        out.wrongApiKeyStatus = foreignPlay.status; // 401 — the key itself doesn't validate at all

        // ── 10. The phone commands seek; the screen's socket receives player_command. ────────────
        const cmdRes = await fetch("/api/remote/command", {
          method: "POST",
          headers: { "content-type": "application/json", authorization: `Bearer ${phoneToken}` },
          body: JSON.stringify({ device_id: "e2e-screen-236", command: "seek", position_ms: 30000 }),
        });
        out.commandStatus = cmdRes.status;
        const seekMsg = await waitForType(screenMessages, "player_command");
        out.seekCommand = seekMsg?.command;
        out.seekArgsPositionMs = seekMsg?.args?.position_ms;

        // Phase 111 backward compatibility — the original bare {device_id, command:"pause"} body.
        const pauseRes = await fetch("/api/remote/command", {
          method: "POST",
          headers: { "content-type": "application/json", authorization: `Bearer ${phoneToken}` },
          body: JSON.stringify({ device_id: "e2e-screen-236", command: "pause" }),
        });
        out.legacyPauseStatus = pauseRes.status;

        // The screen "acts on" the seek and reports the new position — the phone's subscription
        // should see it as a device_status push. Recorded BEFORE posting, so waitForType below skips
        // the earlier device_status push from step 8's position_ms:0 report rather than re-matching it.
        const beforeSeekStatusCount = phoneMessages.length;
        await fetch("/api/tv/playback/status", {
          method: "POST",
          headers: { "content-type": "application/json", authorization: `Bearer ${screenToken}` },
          body: JSON.stringify({ item_id: "e2e-fake-item-1", loaded: true, playing: true, position_ms: 30000, session_user_id: phoneUserId }),
        });

        const statusMsg = await waitForType(phoneMessages, "device_status", 5_000, beforeSeekStatusCount);
        out.subscribedDeviceId = statusMsg?.device_id;
        out.subscribedPositionMs = statusMsg?.status?.position_ms;

        screenSocket.close();
        phoneSocket.close();
        return out;
      },
      { user: JF_USER, pass: JF_PASS },
    );

    expect(result.phoneLoginStatus).toBe(200);
    expect(result.codeStatus).toBe(200);
    expect(result.earlyPollStatus).toBe(202);
    expect(result.pairStatus).toBe(200);
    expect(result.claimStatus).toBe(200);
    expect(result.replayClaimStatus).toBe(401); // single-use: gone after the first successful collect
    expect(result.devicesStatus).toBe(200);
    expect(result.screenListed).toBe(true);
    expect(result.screenNearby).toBe(true);
    expect(result.screenKind).toBe("screen");
    expect(result.playStatus).toBe(202);
    expect(result.playItemSessionUserId).toBeTruthy();
    expect(result.wrongApiKeyStatus).toBe(401);
    expect(result.commandStatus).toBe(202);
    expect(result.seekCommand).toBe("seek");
    expect(result.seekArgsPositionMs).toBe(30000);
    expect(result.legacyPauseStatus).toBe(202);
    expect(result.subscribedDeviceId).toBe("e2e-screen-236");
    expect(result.subscribedPositionMs).toBe(30000);

    // ── An API key bound to the SAME user succeeds identically; a key bound to a DIFFERENT user
    //    gets 404 (FR-236-3's "one API, two credentials" contract). ──────────────────────────────
    const sameUserKeyRes = await request.post("/api/settings/api-keys", {
      data: { name: "e2e-236-same-user", jellyfin_user_id: await getUserId(request), jellyfin_username: JF_USER },
    });
    expect(sameUserKeyRes.status(), await sameUserKeyRes.text()).toBe(200);
    const sameUserKey = (await sameUserKeyRes.json()).token as string;

    const listWithKey = await request.get("/api/remote/devices", { headers: { "x-js-api-key": sameUserKey } });
    expect(listWithKey.status()).toBe(200);
    const listedWithKey = await listWithKey.json();
    expect(listedWithKey.some((d: any) => d.device_id === "e2e-screen-236")).toBe(true);

    const otherUserKeyRes = await request.post("/api/settings/api-keys", {
      data: { name: "e2e-236-other-user", jellyfin_user_id: "e2e-nonexistent-user-236", jellyfin_username: "nobody" },
    });
    expect(otherUserKeyRes.status()).toBe(200);
    const otherUserKey = (await otherUserKeyRes.json()).token as string;
    const foreignList = await request.post("/api/remote/play", {
      headers: { "x-js-api-key": otherUserKey },
      data: { device_id: "e2e-screen-236", jellyfin_item_id: "e2e-fake-item-1" },
    });
    expect(foreignList.status()).toBe(404);

    async function getUserId(req: typeof request): Promise<string> {
      // The TV login response already carries it; re-derive here since api-key creation happens
      // outside the page.evaluate above.
      const login = await req.post("/api/tv/login", {
        data: { username: JF_USER, password: JF_PASS, device_id: "e2e-userid-lookup-236" },
      });
      return (await login.json()).session.user_id;
    }
  });
});
