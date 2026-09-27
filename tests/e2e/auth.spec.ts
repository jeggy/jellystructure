import { test, expect } from "@playwright/test";

// Reads Jellyfin credentials from env so real creds are never committed.
const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";

test.describe("Authentication", () => {
  // Phase 264 (FR-264-5) — before any login: the icons load without a session (dev review item 1).
  test("the admin has a favicon, and /favicon.ico answers at the root", async ({ page, request }) => {
    await page.goto("/login");
    const href = await page.locator('link[rel="icon"][type="image/svg+xml"]').getAttribute("href");
    expect(href).toBeTruthy();
    const svg = await request.get(new URL(href!, page.url()).toString());
    expect(svg.status()).toBe(200);
    expect(svg.headers()["content-type"]).toContain("image/svg+xml");
    const ico = await request.get("/favicon.ico");
    expect(ico.status()).toBe(200);
    expect(ico.headers()["content-type"]).toMatch(/image\/(x-icon|vnd\.microsoft\.icon)/);
    const touch = await request.get("/apple-touch-icon.png");
    expect(touch.status()).toBe(200);
  });

  test("login page renders and accepts valid credentials", async ({ page }) => {
    await page.goto("/login");
    await expect(page.locator("h1, h2").first()).toBeVisible();

    await page.fill('[id="username"], input[name="username"], input[type="text"]', JF_USER);
    await page.fill('[id="password"], input[name="password"], input[type="password"]', JF_PASS);
    await page.click('button[type="submit"], button:has-text("Sign in"), button:has-text("Login")');

    // After login the app sets hash to "#/" — wait for dashboard content to confirm success.
    // .first() because .statgrid and the <h1> both render together once loaded -- without it this
    // is a Playwright strict-mode violation (2 elements match a bare OR-selector), confirmed live
    // 2026-08-08 the first time this suite ever actually ran against a working CI stack.
    await expect(page.locator('.statgrid, h1:has-text("Dashboard")').first()).toBeVisible({ timeout: 10_000 });
  });

  test("bad credentials show an error", async ({ page }) => {
    await page.goto("/login");
    await page.fill('input[type="text"]', "notauser");
    await page.fill('input[type="password"]', "wrongpassword");
    await page.click('button[type="submit"], button:has-text("Sign in"), button:has-text("Login")');

    // Should stay on login with error visible
    await expect(page.locator('[class*="badge bad"], [class*="error"]').first()).toBeVisible({
      timeout: 8_000,
    });
  });
});
