import { test, expect } from "@playwright/test";
import http from "node:http";

// Phase 247 — a sideloaded Tizen widget (ravilo-screen, R264/R269/236) has no fixed https://<host>
// origin an admin can type into CORS_ALLOWED_ORIGINS. Depending on the runtime its cross-origin fetches
// carry either the literal string "null" (the standard browser behaviour for an opaque-origin document —
// file://, or a packaged-app scheme with no registered origin) or some Tizen-internal scheme
// Server.kt:269's allowHost(origin, schemes = listOf("http","https")) has no path to matching either
// way. Measured live 2026-09-19 against the real linuxX64 binary (a scratch instance, no Docker): a bare
// GET with no Origin header succeeds; the same call with Origin: null gets no
// Access-Control-Allow-Origin header and a flat 403, for both the unauthenticated setup-screen probe
// (/api/health) and an authenticated TV route's preflight (/api/tv/screen/code). This locks that
// measurement in as a permanent regression test, side by side with the pre-existing
// https://evil.example.com case (ravilo-login.spec.ts) so both refusal shapes are guarded in one place —
// see phase-247's spec for the security reasoning behind why an eventual fix here would not reopen the
// 2026-08-02 finding-M1 credentialed-anyHost() hole.

test.describe("CORS — a sideloaded screen's origin", () => {
  test("a same-origin/no-Origin request to /api/health still works (control)", async ({ request }) => {
    const res = await request.get("/api/health");
    expect(res.status()).toBe(200);
    expect((await res.json()).status).toBe("ok");
  });

  test("Origin: null gets no Access-Control-Allow-Origin on a plain GET to /api/health", async ({ request }) => {
    const res = await request.get("/api/health", { headers: { Origin: "null" } });
    expect(res.headers()["access-control-allow-origin"]).toBeUndefined();
    // Measured live: Ktor's CORS plugin doesn't merely withhold the header, it refuses the request
    // outright. Asserted as its own fact — if a future Ktor upgrade instead lets an unheadered response
    // through unrefused (still with no ACAO header, so still unreadable by a real browser's fetch()),
    // that's a meaningful behaviour change worth this test failing loudly to surface, not silently
    // absorbing.
    expect(res.status()).toBe(403);
  });

  test("Origin: null preflight is refused for the setup screen's own health probe", async ({ request }) => {
    const pre = await request.fetch("/api/health", {
      method: "OPTIONS",
      headers: { Origin: "null", "Access-Control-Request-Method": "GET" },
    });
    expect(pre.headers()["access-control-allow-origin"]).toBeUndefined();
    expect(pre.status()).not.toBe(200);
  });

  test("Origin: null preflight is refused for a password route (FR-247-5)", async ({ request }) => {
    const pre = await request.fetch("/api/tv/login", {
      method: "OPTIONS",
      headers: {
        Origin: "null",
        "Access-Control-Request-Method": "POST",
        "Access-Control-Request-Headers": "content-type",
      },
    });
    expect(pre.headers()["access-control-allow-origin"]).toBeUndefined();
    expect(pre.status()).not.toBe(200);
  });

  // Side by side with the null-origin case on purpose (phase-247 FR-247-1): an arbitrary unlisted
  // origin must be refused exactly the same way, so a future edit can't narrow the null-origin refusal
  // without this file also catching it widening the general one.
  test("an arbitrary unlisted origin is refused the same way", async ({ request }) => {
    const pre = await request.fetch("/api/tv/screen/code", {
      method: "OPTIONS",
      headers: {
        Origin: "https://evil.example.com",
        "Access-Control-Request-Method": "POST",
        "Access-Control-Request-Headers": "content-type",
      },
    });
    expect(pre.headers()["access-control-allow-origin"]).toBeUndefined();
    expect(pre.status()).not.toBe(200);
  });
});

// Phase 247 (FR-247-3) — measured on the Tizen 10.0 TV emulator 2026-09-25: a packaged widget's fetch
// sends no Origin at all, but its WebSocket handshake sends `Origin: file://`, which the shared policy
// refused with a 403 — so a paired TV never received a play_item. `/api/tv/events` (token-only) now admits
// exactly that origin; the cookie-authenticated admin `/ws` must not, and `null` (forgeable by any page)
// must reach neither. Asserted as real upgrade handshakes against the real binary: Playwright's request
// API cannot send one, and the backend's own unit test cannot run a WebSocket natively.
function handshake(path: string, origin?: string): Promise<number> {
  const base = new URL(process.env.APP_URL ?? "http://localhost:9505");
  return new Promise((resolve, reject) => {
    const req = http.request({
      host: base.hostname,
      port: base.port,
      path,
      headers: {
        Connection: "Upgrade",
        Upgrade: "websocket",
        "Sec-WebSocket-Version": "13",
        "Sec-WebSocket-Key": "dGhlIHNhbXBsZSBub25jZQ==",
        ...(origin ? { Origin: origin } : {}),
      },
    });
    req.on("upgrade", (res, socket) => { socket.destroy(); resolve(res.statusCode ?? 0); });
    req.on("response", (res) => { res.resume(); resolve(res.statusCode ?? 0); });
    req.on("error", reject);
    req.end();
  });
}

test.describe("CORS — a sideloaded screen's events socket (FR-247-3)", () => {
  // The token is deliberately bogus: the handler accepts the upgrade and then closes with a policy
  // violation, so a 101 here proves the ORIGIN was admitted, and nothing more.
  const events = "/api/tv/events?token=not-a-device-token";

  test("with no Origin the events socket upgrades (control)", async () => {
    expect(await handshake(events)).toBe(101);
  });

  test("a Tizen widget's file:// origin upgrades the events socket", async () => {
    expect(await handshake(events, "file://")).toBe(101);
  });

  test("file:// is refused on the cookie-authenticated admin socket", async () => {
    expect(await handshake("/ws", "file://")).toBe(403);
  });

  test("https://evil.example.com is refused on both sockets", async () => {
    expect(await handshake(events, "https://evil.example.com")).toBe(403);
    expect(await handshake("/ws", "https://evil.example.com")).toBe(403);
  });

  // FR-247-5 admits `null` on the events route (token-only); the cookie-authenticated admin socket still refuses it.
  test("null is refused on the admin socket", async () => {
    expect(await handshake("/ws", "null")).toBe(403);
  });
});

// Phase 247 (FR-247-4, FR-247-5) — measured on the Tizen 5.0 TV emulator 2026-11-16 (DEB-4704, DEB-4721): Chromium 63
// puts the page's origin on every non-GET fetch, and writes a `file:` document's origin as `null` there (as `file://`
// on a WebSocket handshake). `file://` is admitted on every token-only route (`/api/tv` except `/api/tv/admin`, and
// `/api/remote`); `null` only on `/api/tv` minus `/api/tv/admin` and the two password routes. The routes are asked with
// no credential on purpose: "admitted" means the route's own answer (a 400 or 401 with a JSON body) instead of the CORS
// plugin's empty 403, and nothing more.
test.describe("CORS — a Tizen 5.0 widget's POST (FR-247-4, FR-247-5)", () => {
  const post = (request: import("@playwright/test").APIRequestContext, path: string, origin?: string) =>
    request.post(path, { data: {}, headers: origin ? { Origin: origin } : {} });

  const nullRoutes = ["/api/tv/signin/code", "/api/tv/playback/start"];
  const passwordRoutes = ["/api/tv/login", "/api/tv/account/password"];
  const fileRoutes = [...nullRoutes, ...passwordRoutes, "/api/remote/play"];
  const cookieRoutes = ["/api/tv/admin/config", "/api/auth/login", "/api/scan/cancel"];

  for (const path of fileRoutes) {
    test(`file:// reaches ${path} (the route answers, not the CORS plugin)`, async ({ request }) => {
      const control = await post(request, path);
      const widget = await post(request, path, "file://");
      expect(widget.status()).toBe(control.status());
      expect(widget.status()).not.toBe(403);
      expect(widget.headers()["access-control-allow-origin"]).toBe("file://");
    });
  }

  for (const path of nullRoutes) {
    test(`null reaches ${path}`, async ({ request }) => {
      const control = await post(request, path);
      const widget = await post(request, path, "null");
      expect(widget.status()).toBe(control.status());
      expect(widget.status()).not.toBe(403);
      expect(widget.headers()["access-control-allow-origin"]).toBe("null");
    });
  }

  for (const path of [...passwordRoutes, "/api/remote/play", ...cookieRoutes]) {
    test(`null is refused on ${path}`, async ({ request }) => {
      const res = await post(request, path, "null");
      expect(res.status()).toBe(403);
      expect(res.headers()["access-control-allow-origin"]).toBeUndefined();
    });
  }

  for (const path of cookieRoutes) {
    test(`file:// is refused on ${path}`, async ({ request }) => {
      const res = await post(request, path, "file://");
      expect(res.status()).toBe(403);
      expect(res.headers()["access-control-allow-origin"]).toBeUndefined();
    });
  }

  for (const path of [...fileRoutes, ...cookieRoutes]) {
    test(`https://evil.example.com is refused on ${path}`, async ({ request }) => {
      const res = await post(request, path, "https://evil.example.com");
      expect(res.status()).toBe(403);
      expect(res.headers()["access-control-allow-origin"]).toBeUndefined();
    });
  }

  test("null is admitted on the events handshake's policy (the browser sends file://; both are accepted)", async () => {
    expect(await handshake("/api/tv/events?token=not-a-device-token", "null")).toBe(101);
  });
});
