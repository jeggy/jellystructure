import { test, expect } from "@playwright/test";

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

  test("Origin: null preflight is refused for an authenticated TV route (screen pairing)", async ({ request }) => {
    const pre = await request.fetch("/api/tv/screen/code", {
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
