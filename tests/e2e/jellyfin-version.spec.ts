import { test, expect } from "@playwright/test";

// Phase 243 — jellystructure targets Jellyfin 12.x and says so.
//
// Before this phase nothing in the product read Jellyfin's version. The 10.11.11 -> 12.1.0 upgrade
// changed three route behaviours at once and nothing announced any of it, because nothing was
// watching. These assertions are the "something is watching" part: they fail if the version stops
// reaching /api/health (a broken public probe, a renamed field, a reverted read).
//
// /api/health is deliberately unauthenticated (AuthPlugin) — the container's own HEALTHCHECK hits it.
// /api/health/full is NOT, which is the whole point of phase 238's split: audit-shaped detail belongs
// behind a session. So anything here that touches /full signs in first.

const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";

test.describe("Jellyfin version reporting (243)", () => {
  test("/api/health reports the connected Jellyfin's version", async ({ request }) => {
    const login = await request.post("/api/auth/login", { data: { username: JF_USER, password: JF_PASS } });
    expect(login.status(), await login.text()).toBe(200);

    // The version is latched from the public probe by whoever last called testConnection, so make
    // sure that has happened at least once in this process before reading it.
    const full = await request.get("/api/health/full");
    expect(full.ok(), `/api/health/full -> ${full.status()}`).toBeTruthy();

    const res = await request.get("/api/health");
    expect(res.ok()).toBeTruthy();
    const body = await res.json();

    // FR-243-2. The mock reports 12.1.0 (tests/mock-jellyfin/server.js, JELLYFIN_VERSION), which is
    // the real household server's version as of 2026-09-19.
    expect(body).toHaveProperty("jellyfin_version");
    expect(body.jellyfin_version).toBe("12.1.0");
  });

  test("/api/health/full carries a Jellyfin version check that passes above the floor", async ({
    request,
  }) => {
    const login = await request.post("/api/auth/login", { data: { username: JF_USER, password: JF_PASS } });
    expect(login.status(), await login.text()).toBe(200);

    const res = await request.get("/api/health/full");
    expect(res.ok(), `/api/health/full -> ${res.status()}`).toBeTruthy();
    const body = await res.json();

    const check = (body.checks ?? []).find((c: { name: string }) => c.name === "Jellyfin version");
    expect(check, "FR-243-3's check must exist, whatever the version").toBeTruthy();
    expect(check.ok).toBe(true);
    // It names the version it saw, so an operator reading the check learns the fact rather than
    // just a green tick.
    expect(check.detail).toContain("12.1.0");
  });

  test("the unauthenticated public probe is what carries the version", async ({ request }) => {
    // Pinning the mechanism, not just the outcome. FR-243-2 reads from /System/Info/Public
    // specifically because it still answers when the credential form has changed underneath us —
    // which is exactly when the version is the one fact that would explain everything. If someone
    // moves the read to the authenticated /System/Info, this stays green while the real failure
    // mode returns, so assert the endpoint answers anonymously and carries Version.
    const jellyfin = process.env.JELLYFIN_URL ?? "http://localhost:8096";
    const res = await request.get(`${jellyfin}/System/Info/Public`);
    expect(res.ok()).toBeTruthy();
    expect((await res.json()).Version).toBe("12.1.0");
  });
});
