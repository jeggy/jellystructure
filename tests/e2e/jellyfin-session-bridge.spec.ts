import { test, expect } from "@playwright/test";

// Phases 238 + 241 — the Jellyfin session bridge connects, and the suite can tell.
//
// This spec is the one the 241 dev review said had to exist before that phase could prove anything.
// The chain it closes:
//
//   * Phase 110 opens one outbound WebSocket to Jellyfin's /socket per connected TV. Everything
//     dashboard-side rides it: pause/seek from Jellyfin, remote control, Home Assistant, TvEventBus
//     commands.
//   * On Jellyfin 12.1 the old `/socket?api_key=<token>` handshake is refused with 403 (measured live
//     against the household server, 2026-09-20, with a real token: api_key 403, apikey 101,
//     Authorization header 101, X-Emby-Token 403). So the bridge could not open at all.
//   * The failure was SILENT — one warn line per device, ever — and the mock Jellyfin returned 101 to
//     any handshake at all, so the e2e suite could not have caught it either.
//
// Phase 241 made the mock refuse what the real server refuses. Phase 238 made the product send the
// header form and made bridge state observable. Neither alone is enough: a tightened mock plus a
// silent bridge is a broken bridge and a green suite — this phase's own failure mode one level up.
// So this spec asserts on the bridge's OWN state, which is what makes reverting either half fail.

const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";
const DEVICE_ID = "e2e-bridge-238";

test.describe("Jellyfin session bridge (238/241)", () => {
  test("a connected TV's bridge reaches Jellyfin and says so on /api/health/full", async ({
    page,
    request,
  }) => {
    test.setTimeout(60_000);

    // /api/health/full is authenticated (unlike /api/health, which the container HEALTHCHECK hits).
    const adminLogin = await request.post("/api/auth/login", {
      data: { username: JF_USER, password: JF_PASS },
    });
    expect(adminLogin.status(), await adminLogin.text()).toBe(200);

    // Same-origin page: Node's runtime in this Playwright image has no global WebSocket, so the
    // socket is opened from inside the browser (the pattern screens.spec.ts established).
    await page.goto("/login", { waitUntil: "domcontentloaded" });

    const opened = await page.evaluate(
      async ({ user, pass, deviceId }) => {
        const login = await fetch("/api/tv/login", {
          method: "POST",
          headers: {
            "content-type": "application/json",
            "x-ravilo-platform": "tv",
            "x-ravilo-version": "9.99",
          },
          body: JSON.stringify({
            username: user,
            password: pass,
            device_id: deviceId,
            device_name: "E2E Bridge TV",
          }),
        });
        if (login.status !== 200) return { ok: false, status: login.status };
        const token = (await login.json())?.device_token as string;

        // Opening this socket is what makes the backend start a bridge for the device
        // (Server.kt's /api/tv/events handler calls sessionBridge.connect).
        const ws = new WebSocket(`${location.origin.replace(/^http/, "ws")}/api/tv/events?token=${token}`);
        await new Promise((resolve, reject) => {
          ws.onopen = resolve;
          ws.onerror = reject;
          setTimeout(() => reject(new Error("events socket open timeout")), 8_000);
        });
        // Hold it open for the rest of the test: a closed socket disconnects the bridge.
        (window as unknown as { __bridgeWs?: WebSocket }).__bridgeWs = ws;
        return { ok: true, status: 200 };
      },
      { user: JF_USER, pass: JF_PASS, deviceId: DEVICE_ID },
    );
    expect(opened.ok, `TV login/socket failed: ${JSON.stringify(opened)}`).toBeTruthy();

    // The bridge connects asynchronously (and retries with backoff), so poll rather than sleep.
    let bridge: Record<string, unknown> | undefined;
    for (let i = 0; i < 30; i++) {
      const res = await request.get("/api/health/full");
      expect(res.ok()).toBeTruthy();
      const body = await res.json();
      // FR-238-3 — the per-device block lives on the AUTHENTICATED endpoint. Device ids and failure
      // reasons must never reach /api/health, which is world-readable.
      const bridges = (body.session_bridges ?? []) as Array<Record<string, unknown>>;
      bridge = bridges.find((b) => b.device_id === DEVICE_ID);
      if (bridge?.connected === true) break;
      await new Promise((r) => setTimeout(r, 1_000));
    }

    expect(bridge, "FR-238-3: the connected device must appear in session_bridges").toBeTruthy();
    expect(
      bridge!.connected,
      `bridge never connected — last_error=${bridge!.last_error}, ` +
        `consecutive_failures=${bridge!.consecutive_failures}. ` +
        `On the tightened mock this is what reverting FR-238-1 looks like.`,
    ).toBe(true);
    // Connected means no outstanding failure and no reason: `last_error` null is reserved for exactly
    // this state, and `never_attempted` for the config-gap case that throws nothing.
    expect(bridge!.last_error).toBeFalsy();
    expect(bridge!.consecutive_failures).toBe(0);
    expect(bridge!.never_attempted).toBe(false);
  });

  test("/api/health carries bridge counts and never a device id", async ({ request }) => {
    // FR-238-3 + dev review item 4: this endpoint is unauthenticated, so it gets numbers only. A
    // per-device list here would be world-readable — which, on this household's internet-facing
    // server, means the internet.
    const res = await request.get("/api/health");
    expect(res.ok()).toBeTruthy();
    const body = await res.json();

    expect(body.session_bridges).toBeTruthy();
    expect(typeof body.session_bridges.connected).toBe("number");
    expect(typeof body.session_bridges.failing).toBe("number");

    const raw = JSON.stringify(body);
    expect(raw, "no device id may appear on the unauthenticated endpoint").not.toContain(DEVICE_ID);
  });
});
