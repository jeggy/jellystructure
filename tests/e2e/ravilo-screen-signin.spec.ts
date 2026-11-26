import { test, expect, Browser } from "@playwright/test";
import { ownSignInBucket } from "./helpers/screen-cast";

const SCREEN_URL = process.env.RAVILO_SCREEN_CAST_URL ?? "http://localhost:8092";
const APP_URL = process.env.APP_URL ?? "http://localhost:9505";

async function approveFromSecondContext(browser: Browser, code: string, username = "admin", password = "password") {
  const context = await browser.newContext({ baseURL: APP_URL });
  try {
    const login = await context.request.post("/api/tv/login", {
      data: { username, password, device_id: `qa-approver-${Date.now()}`, device_name: "QA approver" },
      headers: { "x-ravilo-platform": "phone", "x-ravilo-version": "9.99" },
    });
    expect(login.status(), await login.text()).toBe(200);
    const token = (await login.json()).device_token as string;
    const approve = await context.request.post("/api/tv/signin/approve", {
      headers: { authorization: `Bearer ${token}` }, data: { code },
    });
    expect(approve.status(), await approve.text()).toBe(204);
  } finally {
    await context.close();
  }
}

async function boot(page: import("@playwright/test").Page) {
  await ownSignInBucket(page);
  await page.addInitScript(() => localStorage.setItem("ravilo.screen.serverUrl", "http://app:9505"));
  await page.goto(SCREEN_URL);
  await expect(page.locator("#signin")).toHaveClass(/\bon\b/);
}

async function signInByCode(page: import("@playwright/test").Page, browser: Browser, username = "admin", password = "password") {
  await expect(page.locator("#signin-code")).not.toBeEmpty();
  const code = (await page.locator("#signin-code").textContent())!.replace(/\s/g, "");
  await approveFromSecondContext(browser, code, username, password);
  await expect(page.locator("#shell")).toHaveClass(/\bon\b/, { timeout: 15_000 });
}

async function openProfileMenu(page: import("@playwright/test").Page) {
  await page.keyboard.press("ArrowUp");
  for (let i = 0; i < 4; i++) await page.keyboard.press("ArrowRight");
  await expect(page.locator("#nav-profile")).toHaveClass(/focused/);
  await page.keyboard.press("Enter");
  await expect(page.locator("#profile-menu")).toHaveClass(/\bon\b/);
}

test("Tizen code sign-in approves in another browser context and opens Home (DEB-4657 FR-2)", async ({ page, browser }) => {
  await boot(page);
  await expect(page.locator("#signin-password-instead")).toBeFocused();
  await expect(page.locator("#signin-tv-name")).toHaveText("Samsung TV");
  await signInByCode(page, browser);
  await expect(page.locator("#shell-body .home-feed")).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem("ravilo.screen.profiles"))).toContain("admin");
});

test("Tizen sign-in stays on the code when the OK that opened it arrives late (DEB-4831; restores DEB-4654 FR-2)", async ({ page }) => {
  await boot(page);
  await page.keyboard.press("Enter");
  await expect(page.locator("#signin-code")).not.toBeEmpty();
  await expect(page.locator("#signin-form")).toBeHidden();
  await expect(page.locator("#signin-code-view")).toBeVisible();
});

test("Tizen password sign-in keeps username after a wrong password (DEB-4657 FR-2)", async ({ page }) => {
  await boot(page);
  await page.waitForTimeout(1_200); // an OK in the first second is the press that opened this screen (DEB-4831)
  await page.keyboard.press("Enter");
  await expect(page.locator("#signin-username")).toBeFocused();
  await page.locator("#signin-username").fill("admin");
  await page.locator("#signin-password").fill("wrong-password");
  await page.keyboard.press("Enter");
  await expect(page.locator("#signin-password-message")).toHaveText(/Wrong username or password/);
  await expect(page.locator("#signin-username")).toHaveValue("admin");
  await page.locator("#signin-password").fill("password");
  await page.keyboard.press("Enter");
  await expect(page.locator("#shell")).toHaveClass(/\bon\b/);
});

test("Tizen stores two viewers, opens the picker, and signs out only one (DEB-4657 FR-3)", async ({ page, browser, request }) => {
  await boot(page);
  await signInByCode(page, browser);
  await openProfileMenu(page);
  await page.keyboard.press("Enter"); // Switch profile is focused first.
  await expect(page.locator("#profile-picker")).toHaveClass(/\bon\b/);
  await page.keyboard.press("ArrowRight"); // Add user.
  await page.keyboard.press("Enter");
  await signInByCode(page, browser, "qa-viewer", "qa-password");
  await page.reload();
  const sessions = JSON.parse((await page.evaluate(() => localStorage.getItem("ravilo.screen.profiles")))!) as
    { userId: string; displayName: string; token: string }[];
  const adminToken = sessions.find(s => s.displayName === "admin")!.token;
  const qaToken = sessions.find(s => s.displayName === "qa-viewer")!.token;
  await expect(page.locator("#profile-picker")).toHaveClass(/\bon\b/);
  await expect(page.locator("#profile-picker-body .profile-choice")).toHaveCount(3);
  await page.keyboard.press("Enter"); // First viewer.
  await expect(page.locator("#shell")).toHaveClass(/\bon\b/);
  await openProfileMenu(page);
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Enter"); // Sign out.
  await expect(page.locator("#profile-picker")).toHaveClass(/\bon\b/);
  await expect(page.locator("#profile-picker-body .profile-choice")).toHaveCount(2);
  const names = await page.locator("#profile-picker-body").textContent();
  expect(names).toContain("qa-viewer");
  expect(names).not.toContain("admin");
  await expect.poll(async () => (await request.get("/api/tv/config", { headers: { authorization: `Bearer ${adminToken}` } })).status()).toBe(401);
  expect((await request.get("/api/tv/config", { headers: { authorization: `Bearer ${qaToken}` } })).status()).toBe(200);
});

test("Tizen opens password directly when code sign-in is unavailable (DEB-4657 FR-2)", async ({ page }) => {
  await page.route("**/api/tv/signin/code", route => route.fulfill({ status: 200, contentType: "application/json", body: '{"available":false}' }));
  await boot(page);
  await expect(page.locator("#signin-form")).toBeVisible();
  await expect(page.locator("#signin-username")).toBeFocused();
  await expect(page.locator("#signin-code")).toBeHidden();
});

test("Tizen remembers an empty password 403 and signs in by code (DEB-4813 FR-2)", async ({ page }) => {
  await page.route("**/api/tv/login", route => route.fulfill({
    status: 403, body: "", headers: { "access-control-allow-origin": "*" },
  }));
  await boot(page);
  await page.waitForTimeout(1_200); // an OK in the first second is the press that opened this screen (DEB-4831)
  await page.locator("#signin-password-instead").click();
  await page.locator("#signin-username").fill("qa-viewer");
  await page.locator("#signin-password").fill("example-password");
  await page.locator("#signin-submit").click();
  await expect(page.locator("#signin-form")).toBeHidden();
  await expect(page.locator("#signin-code-only-message")).toHaveText(
    "This TV can't sign in with a password. Use the code with Ravilo on your phone.");
  await expect(page.locator("#signin-code-only-button")).toHaveText("Use the code instead");
  await expect(page.locator("#signin-code-only-button")).toBeFocused();
  await expect(page.locator("#signin-password-message")).toBeEmpty();
  await page.locator("#signin-code-only-button").click();
  await expect(page.locator("#signin-code")).toBeFocused();
  await expect(page.locator("#signin-password-instead")).toBeHidden();
  await page.reload();
  await expect(page.locator("#signin-code")).toBeFocused();
  await expect(page.locator("#signin-password-instead")).toBeHidden();
});

test("Tizen retries a code when it is unavailable on a code-only install (DEB-4813 FR-2)", async ({ page }) => {
  let requests = 0;
  await page.addInitScript(() => localStorage.setItem("ravilo.screen.codeOnlyServer", "http://app:9505"));
  await page.route("**/api/tv/signin/code", route => {
    requests++;
    return route.fulfill({ status: 200, contentType: "application/json", body: '{"available":false}' });
  });
  await boot(page);
  await expect(page.locator("#signin-form")).toBeHidden();
  await expect(page.locator("#signin-message")).toHaveText(
    "Sign-in with a code is turned off on this server, and this TV can only sign in with a code. Ask whoever runs the server to turn it on.");
  await expect(page.locator("#signin-retry")).toHaveText("Try again");
  await expect(page.locator("#signin-retry")).toBeFocused();
  await page.locator("#signin-retry").click();
  await expect.poll(() => requests).toBe(2);
  await expect(page.locator("#signin-form")).toBeHidden();
});

test("Tizen keeps a JSON password 403 distinct from an empty refusal (DEB-4813 FR-2)", async ({ page }) => {
  await page.route("**/api/tv/login", route => route.fulfill({
    status: 403, contentType: "application/json", body: '{"error":"account_blocked"}',
    headers: { "access-control-allow-origin": "*" },
  }));
  await boot(page);
  await page.waitForTimeout(1_200); // an OK in the first second is the press that opened this screen (DEB-4831)
  await page.locator("#signin-password-instead").click();
  await page.locator("#signin-username").fill("qa-viewer");
  await page.locator("#signin-password").fill("example-password");
  await page.locator("#signin-submit").click();
  await expect(page.locator("#signin-form")).toBeVisible();
  await expect(page.locator("#signin-code-only")).toBeHidden();
  expect(await page.evaluate(() => localStorage.getItem("ravilo.screen.codeOnlyServer"))).toBeNull();
});

test("Tizen Add user preserves code-only sign-in (DEB-4813 FR-3)", async ({ page, browser }) => {
  await page.addInitScript(() => localStorage.setItem("ravilo.screen.codeOnlyServer", "http://app:9505"));
  await boot(page);
  await signInByCode(page, browser);
  await openProfileMenu(page);
  await page.keyboard.press("Enter");
  await page.keyboard.press("ArrowRight");
  await page.keyboard.press("Enter");
  await expect(page.locator("#signin")).toHaveClass(/\bon\b/);
  await expect(page.locator("#signin-code")).toBeFocused();
  await expect(page.locator("#signin-password-instead")).toBeHidden();
});

test("Tizen forgets code-only sign-in when the server address changes (DEB-4813 FR-2)", async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem("ravilo.screen.codeOnlyServer", "https://old.example.test"));
  await boot(page);
  await expect(page.locator("#signin-password-instead")).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem("ravilo.screen.codeOnlyServer"))).toBeNull();
});

test("Tizen drops old receiver tokens before sign-in (DEB-4657 FR-1)", async ({ page }) => {
  let revoked = false;
  await page.route("**/api/tv/pair/unpair", route => {
    if (route.request().method() !== "POST") return route.continue();
    revoked = route.request().headers().authorization === "Bearer retired-screen-token";
    return route.fulfill({ status: 200, contentType: "application/json", body: '{"status":"unpaired"}' });
  });
  await page.addInitScript(() => localStorage.setItem("ravilo.screen.tokens", JSON.stringify({ viewer: "retired-screen-token" })));
  await boot(page);
  await expect(page.locator("#signin")).toHaveClass(/\bon\b/);
  expect(await page.evaluate(() => localStorage.getItem("ravilo.screen.tokens"))).toBeNull();
  await expect.poll(() => revoked).toBe(true);
});

test("Tizen draws the sign-in link as a QR matrix (DEB-4657 FR-2)", async ({ page }) => {
  await page.route("**/api/tv/signin/code", route => route.fulfill({
    status: 200, contentType: "application/json",
    body: JSON.stringify({ available: true, code: "ABCDEF", secret: "test-secret", expiresAt: Date.now() + 600_000,
      linkUrl: "https://example.test/tv?c=ABCDEF" }),
  }));
  await boot(page);
  await expect(page.locator("#signin-code")).toHaveText("ABC DEF");
  await expect(page.locator("#signin-qr canvas")).toBeVisible();
  const painted = await page.locator("#signin-qr canvas").evaluate(canvas => {
    const surface = canvas as HTMLCanvasElement;
    const context = surface.getContext("2d")!;
    const quietWhite = Array.from(context.getImageData(0, 0, 8, 8).data).every(v => v === 255);
    const darkModules = Array.from(context.getImageData(0, 0, surface.width, surface.height).data)
      .some((v, i) => i % 4 === 0 && v === 0);
    return { quietWhite, darkModules };
  });
  expect(painted.quietWhite).toBe(true); // Four-module quiet zone.
  expect(painted.darkModules).toBe(true); // Black modules.
});

// DEB-4947 (FR-11) — Wrong server? is the way back to setup; holding Back is not.
const PROFILES = "ravilo.screen.profiles";
const SERVER = "http://app:9505";
const OTHER = "http://other-server.invalid";

/** Seeds once, so the reload that Wrong server? triggers sees what the app itself stored by then. */
async function bootSeeded(page: import("@playwright/test").Page, profiles: unknown[] = []) {
  await ownSignInBucket(page);
  await page.addInitScript(([server, key, list]) => {
    if (sessionStorage.getItem("__seeded")) return;
    sessionStorage.setItem("__seeded", "1");
    localStorage.setItem("ravilo.screen.serverUrl", server as string);
    if ((list as unknown[]).length) localStorage.setItem(key as string, JSON.stringify(list));
  }, [SERVER, PROFILES, profiles] as const);
  await page.goto(SCREEN_URL);
}

async function mockOtherServer(page: import("@playwright/test").Page) {
  await page.route(`${OTHER}/**`, route => route.request().url() === `${OTHER}/api/health`
    ? route.fulfill({ status: 200, contentType: "application/json", headers: { "access-control-allow-origin": "*" }, body: '{"status":"ok","version":"9.9"}' })
    : route.abort());
}

test("Tizen sign-in reaches Wrong server? with Down, opens setup prefilled, and a held Back does nothing (DEB-4947 FR-11)", async ({ page }) => {
  await bootSeeded(page);
  await expect(page.locator("#signin")).toHaveClass(/\bon\b/);
  await expect(page.locator("#signin-password-instead")).toBeFocused();
  await expect(page.locator("#signin-change-server")).toHaveText("Wrong server?");
  await expect(page.locator("#signin-server")).toHaveText("app:9505");
  await page.keyboard.down("Escape");
  for (let i = 0; i < 6; i++) { await page.waitForTimeout(600); await page.keyboard.down("Escape"); }
  await page.keyboard.up("Escape");
  await expect(page.locator("#setup")).not.toHaveClass(/\bon\b/);
  await expect(page.locator("#signin")).toHaveClass(/\bon\b/);
  await page.keyboard.press("ArrowDown");
  await expect(page.locator("#signin-change-server")).toBeFocused();
  await page.keyboard.press("ArrowDown");
  await expect(page.locator("#signin-change-server")).toBeFocused(); // Down from it goes nowhere.
  await page.keyboard.press("Enter");
  await expect(page.locator("#setup")).toHaveClass(/\bon\b/);
  await expect(page.locator("#setupHost")).toHaveValue(SERVER);
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Enter");
  await expect(page.locator("#signin")).toHaveClass(/\bon\b/);
});

test("Tizen sign-in's Wrong server? is also on the password form (DEB-4947 FR-11)", async ({ page }) => {
  await bootSeeded(page);
  await page.waitForTimeout(1_200); // an OK in the first second is the press that opened this screen (DEB-4831)
  await page.keyboard.press("Enter");
  await expect(page.locator("#signin-username")).toBeFocused();
  for (let i = 0; i < 4; i++) await page.keyboard.press("ArrowDown"); // username, password, Sign in, Cancel, then it.
  await expect(page.locator("#signin-change-server")).toBeFocused();
});

test("Tizen Add user shows Wrong server?; the same address keeps the profile, another leaves none (DEB-4947 FR-11)", async ({ page }) => {
  await mockOtherServer(page);
  await bootSeeded(page, [{ userId: "viewer", displayName: "Test Viewer", token: "token-viewer" }]);
  await expect(page.locator("#shell")).toHaveClass(/\bon\b/);
  await openProfileMenu(page);
  await page.keyboard.press("Enter"); // Switch profile.
  await expect(page.locator("#profile-picker")).toHaveClass(/\bon\b/);
  await page.keyboard.press("ArrowRight"); // Add user.
  await page.keyboard.press("Enter");
  await expect(page.locator("#signin")).toHaveClass(/\bon\b/);
  await expect(page.locator("#signin-change-server")).toBeVisible();
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("ArrowDown");
  await expect(page.locator("#signin-change-server")).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.locator("#setupHost")).toHaveValue(SERVER);
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Enter");
  await expect(page.locator("#shell")).toHaveClass(/\bon\b/); // One profile: the app opens on it again.
  const kept = JSON.parse((await page.evaluate(key => localStorage.getItem(key), PROFILES))!) as unknown[];
  expect(kept).toHaveLength(1);
  await openProfileMenu(page); // Add user again, then to setup with a different address.
  await page.keyboard.press("Enter");
  await page.keyboard.press("ArrowRight");
  await page.keyboard.press("Enter");
  await expect(page.locator("#signin")).toHaveClass(/\bon\b/);
  await page.locator("#signin-change-server").click();
  await expect(page.locator("#setup")).toHaveClass(/\bon\b/);
  await page.locator("#setupHost").fill(OTHER);
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Enter");
  await expect(page.locator("#signin")).toHaveClass(/\bon\b/);
  expect(await page.evaluate(() => localStorage.getItem("ravilo.screen.serverUrl"))).toBe(OTHER);
  expect(await page.evaluate(key => localStorage.getItem(key), PROFILES)).toBeNull();
});

test("Tizen sign-in's could-not-reach state reaches Wrong server? from Retry (DEB-4947 FR-11)", async ({ page }) => {
  await page.route("**/api/tv/signin/code", route => route.abort());
  await bootSeeded(page);
  await expect(page.locator("#signin-retry")).toBeFocused();
  await page.keyboard.press("ArrowDown");
  await expect(page.locator("#signin-change-server")).toBeFocused();
});

// DEB-5696 (DEB-5685 FR-60): the server ending this sign-in. `{"type":"signed_out"}` on the events socket removes the profile and stops the
// socket for that token; the picker opens when other profiles remain, the sign-in screen when it was the last.
test.describe("the server ends a sign-in (DEB-5685, FR-60)", () => {
  const viewer = { userId: "viewer", displayName: "Test Viewer", token: "test-token" };
  const other = { userId: "other", displayName: "Other Viewer", token: "other-token" };
  const feed = { heroes: [], channels: [], rows: [], tile_shape: "POSTER", hero_height_pct: 48, focus_detail: "none" };

  async function bootSignedIn(page: import("@playwright/test").Page, profiles: object[]) {
    const sockets: any[] = [];
    const seen = { sockets, opens: 0, unpaired: [] as string[], push: (message: object) => sockets.forEach((ws) => ws.send(JSON.stringify(message))) };
    await page.addInitScript(([key, list]) => {
      localStorage.setItem("ravilo.screen.serverUrl", "http://app:9505");
      localStorage.setItem(key as string, JSON.stringify(list));
    }, [PROFILES, profiles] as const);
    await page.routeWebSocket(/\/api\/tv\/events/, (ws) => { seen.opens++; sockets.push(ws); });
    await page.route(`${SERVER}/**`, async (route) => {
      const req = route.request();
      const path = new URL(req.url()).pathname;
      if (req.method() === "OPTIONS") return route.fulfill({ status: 204 });
      if (path === "/api/tv/home") return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(feed) });
      if (path === "/api/tv/pair/unpair") { seen.unpaired.push(req.headers()["authorization"] ?? ""); return route.fulfill({ status: 204 }); }
      return route.fulfill({ status: 204 });
    });
    await page.goto(SCREEN_URL);
    if (profiles.length > 1) {
      await expect(page.locator("#profile-picker")).toHaveClass(/\bon\b/);
      await page.keyboard.press("Enter");                                          // Test Viewer
    }
    await expect(page.locator("#shell")).toHaveClass(/\bon\b/);
    await expect.poll(() => seen.sockets.length).toBe(1);
    return seen;
  }

  test("with another profile left: the profile is removed and the picker opens", async ({ page }) => {
    const seen = await bootSignedIn(page, [viewer, other]);
    seen.push({ type: "signed_out" });
    await expect(page.locator("#profile-picker")).toHaveClass(/\bon\b/);
    await expect(page.locator("#shell")).not.toHaveClass(/\bon\b/);
    await expect(page.locator("#profile-picker button")).toHaveText(["Other Viewer", "Add user"]);
    const kept = await page.evaluate((key) => JSON.parse(localStorage.getItem(key)!), PROFILES);
    expect(kept.map((p: any) => p.userId)).toEqual(["other"]);
    expect(seen.unpaired.length).toBeLessThanOrEqual(1);
  });

  test("the last profile: the sign-in screen opens, and the socket is not opened again for that token", async ({ page }) => {
    const seen = await bootSignedIn(page, [viewer]);
    seen.push({ type: "signed_out" });
    await expect(page.locator("#signin")).toHaveClass(/\bon\b/);
    expect(await page.evaluate((key) => localStorage.getItem(key), PROFILES)).toBe("[]");
    await page.waitForTimeout(3_000);                                              // past the first reconnect delay
    expect(seen.opens).toBe(1);
  });
});
