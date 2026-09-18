import { test, expect } from "@playwright/test";

// Signing in to Ravilo must always work. Added 2026-09-17 after two production findings in one hour:
//
//  1. A client that sends no X-Ravilo-Version got **503 "Could not reach Jellyfin"**. Phase 224 omits
//     Version from the Jellyfin header when the device reports none; Jellyfin 10.11.11 answers 400 to a
//     sign-in without one. Every pre-R252 build was locked out. (The mock Jellyfin used to accept
//     anything — it now refuses what the real server refuses.)
//  2. The web app signed in against **its own origin** — a static file server with no /api — and got
//     a 404, because ravilo-web ran without DEFAULT_SERVER_URL.
//
// The Ravilo UI is a Compose canvas with no DOM to click (see ravilo-web.spec.ts for why CI never
// touches its rendering), so this drives the same HTTP contract the app drives.

const USER = process.env.JELLYFIN_USER ?? "admin";
const PASS = process.env.JELLYFIN_PASS ?? "password";
const WEB = process.env.RAVILO_WEB_URL ?? "http://localhost:8082";

const body = (over: Record<string, unknown> = {}) => ({
  username: USER,
  password: PASS,
  device_id: "e2e-device-0001",
  device_name: "E2E Browser",
  ...over,
});
const R252 = { "x-ravilo-platform": "web", "x-ravilo-version": "9.99" };

test.describe("Ravilo sign-in", () => {
  test("valid credentials sign in, and the token it returns is accepted", async ({ request }) => {
    const login = await request.post("/api/tv/login", { data: body(), headers: R252 });
    expect(login.status(), await login.text()).toBe(200);
    const json = await login.json();
    expect(json.device_token).toBeTruthy();
    expect(json.session.display_name).toBe(USER);

    const denied = await request.get("/api/tv/home");
    expect(denied.status()).toBe(401);
    const home = await request.get("/api/tv/home", {
      headers: { ...R252, Authorization: `Bearer ${json.device_token}` },
    });
    expect(home.status(), await home.text()).toBe(200);
  });

  test("a client that reports no version can still sign in", async ({ request }) => {
    const login = await request.post("/api/tv/login", { data: body({ device_id: "e2e-device-old" }) });
    expect(login.status(), await login.text()).toBe(200);
    expect((await login.json()).device_token).toBeTruthy();
  });

  test("a wrong password is a 401 that says so — never 'could not reach'", async ({ request }) => {
    const login = await request.post("/api/tv/login", { data: body({ password: "nope" }), headers: R252 });
    expect(login.status()).toBe(401);
    expect((await login.json()).error).toBe("Invalid username or password");
  });

  test("signing in twice from one device keeps working", async ({ request }) => {
    for (let i = 0; i < 2; i++) {
      const login = await request.post("/api/tv/login", { data: body({ device_id: "e2e-device-twice" }), headers: R252 });
      expect(login.status(), await login.text()).toBe(200);
    }
  });
});

test.describe("Ravilo web reaches its backend", () => {
  test("the page is told where the backend is, and that address signs in", async ({ request }) => {
    // Phase 235 (FR-235-8) moved this out of an inline <script> injected into index.html's own HTML
    // (the corrected CSP has no 'unsafe-inline') and into a separate /runtime-config.js the page loads
    // — checking index.html's raw text for the assignment stopped working the moment that shipped.
    const js = await (await request.get(`${WEB}/runtime-config.js`)).text();
    const m = js.match(/window\.__RAVILO_DEFAULT_SERVER__\s*=\s*"([^"]+)"/);
    expect(m, "ravilo-web's runtime-config.js served no default server: the app would sign in against its own origin").toBeTruthy();
    const login = await request.post(`${m![1]}/api/tv/login`, { data: body({ device_id: "e2e-device-web" }), headers: R252 });
    expect(login.status(), await login.text()).toBe(200);
  });

  test("the static origin has no API, so it must never be the fallback", async ({ request }) => {
    const login = await request.post(`${WEB}/api/tv/login`, { data: body() });
    expect(login.status()).not.toBe(200);
  });

  // A preflight never carries the token. The first version of this test only preflighted /login — an
  // open path — and so missed that every AUTHENTICATED route answered its preflight with 401: the web
  // app could sign in and then load nothing (production, 2026-09-18).
  for (const [path, method, headers] of [
    ["/api/tv/login", "POST", "content-type,x-ravilo-platform,x-ravilo-version"],
    ["/api/tv/home", "GET", "authorization,x-ravilo-platform,x-ravilo-version"],
    ["/api/tv/rev", "GET", "authorization,x-ravilo-platform,x-ravilo-version"],
  ] as const) {
    test(`the backend answers the web app's preflight for ${method} ${path}`, async ({ request }) => {
      const origin = new URL(WEB).origin;
      const pre = await request.fetch(path, {
        method: "OPTIONS",
        headers: { Origin: origin, "Access-Control-Request-Method": method, "Access-Control-Request-Headers": headers },
      });
      expect(pre.status()).toBe(200);
      expect(pre.headers()["access-control-allow-origin"]).toBe(origin);
      expect(pre.headers()["access-control-allow-headers"]?.toLowerCase()).toContain("x-ravilo-version");
    });
  }

  test("a preflight from an origin that is not allowed gets nothing", async ({ request }) => {
    const pre = await request.fetch("/api/tv/home", {
      method: "OPTIONS",
      headers: { Origin: "https://evil.example.com", "Access-Control-Request-Method": "GET", "Access-Control-Request-Headers": "authorization" },
    });
    expect(pre.headers()["access-control-allow-origin"]).toBeUndefined();
    expect(pre.status()).not.toBe(200);
  });

  test("an OPTIONS that is not a preflight is still refused without a token", async ({ request }) => {
    const res = await request.fetch("/api/tv/home", { method: "OPTIONS" });
    expect(res.status()).toBe(401);
  });
});

// The Chromecast receiver must be allowed to load Google's Cast framework. The site-wide policy says
// script-src 'self'; under it the receiver never started and a real TV showed nothing (2026-09-18).
test("the cast receiver page may load the Cast framework it depends on", async ({ request }) => {
  const res = await request.get("/cast/");
  expect(res.status()).toBe(200);
  const html = await res.text();
  const external = [...html.matchAll(/<script[^>]+src="(https:\/\/[^"/]+)/g)].map((m) => m[1]);
  expect(external.length).toBeGreaterThan(0);
  const scriptSrc = (res.headers()["content-security-policy"] ?? "").split(";").find((d) => d.trim().startsWith("script-src")) ?? "";
  for (const origin of external) expect(scriptSrc, `CSP blocks ${origin}`).toContain(new URL(origin).host);
  // …and the HLS player the framework fetches by itself at runtime, which no <script> tag names.
  expect(scriptSrc).toContain("ajax.googleapis.com");
  expect(res.headers()["x-frame-options"]).toBeUndefined();

  const admin = await request.get("/");
  expect(admin.headers()["x-frame-options"]).toBe("DENY");
  expect(admin.headers()["content-security-policy"]).not.toContain("gstatic");
});

