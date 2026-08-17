import { test, expect, Page, Browser } from "@playwright/test";

// Regression coverage for the app shell (sidebar nav + topbar) — added 2026-08-13 after a live
// report of the sidebar rendering as a blank column (nav links/logo invisible, only the theme
// picker showing) that couldn't be reproduced from CSS/HTML review alone. Asserts the shell's
// real content is visible and sanely sized in both themes, on both a full-height desktop viewport
// and a shorter one (catches an overflow/scroll regression a tall viewport would hide).
const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";

async function login(page: Page) {
  await page.goto("/login");
  await page.fill('[id="username"], input[name="username"], input[type="text"]', JF_USER);
  await page.fill('[id="password"], input[name="password"], input[type="password"]', JF_PASS);
  await page.click('button[type="submit"], button:has-text("Sign in"), button:has-text("Login")');
  await expect(page.locator('.statgrid, h1:has-text("Dashboard")').first()).toBeVisible({ timeout: 10_000 });
}

// Fix (2026-08-17) — one shared login (beforeAll/afterAll) instead of one per test, same fix
// scan-fixture.spec.ts and bazarr-dashboard.spec.ts already apply and document: the app's
// login-rate-limiter (5 attempts/60s) is shared across the WHOLE Playwright run, not per file. This
// spec alone used to log in 3 times; combined with auth.spec.ts's 2 + bazarr-dashboard.spec.ts's 1 +
// scan-fixture.spec.ts's 1, a clean full-suite run makes 7 login POSTs well inside one rate-limit
// window and trips it — confirmed live via the error-context page snapshot showing "Too many login
// attempts — try again in a minute" on the login form. Theme/viewport switches between tests now set
// localStorage directly and reload() (the session cookie survives a reload) instead of re-logging in
// via addInitScript + a fresh page.
async function setLocalStorageAndReload(page: Page, entries: Record<string, string>) {
  await page.evaluate((e) => {
    for (const [k, v] of Object.entries(e)) window.localStorage.setItem(k, v);
  }, entries);
  await page.reload();
  await expect(page.locator('.statgrid, h1:has-text("Dashboard")').first()).toBeVisible({ timeout: 10_000 });
}

test.describe.serial("App shell layout", () => {
  let page: Page;

  test.beforeAll(async ({ browser }: { browser: Browser }) => {
    page = await browser.newPage();
    await login(page);
  });

  test.afterAll(async () => {
    await page.close();
  });

  for (const theme of ["dark", "light"] as const) {
    test(`sidebar nav + logo are visible in ${theme} mode`, async () => {
      await setLocalStorageAndReload(page, { "js-theme": theme });

      await expect(page.locator(".app-side")).toBeVisible();
      await expect(page.locator(".app-side .logo")).toBeVisible();
      await expect(page.locator('.app-side a.nav[href="#/dashboard"]')).toBeVisible();
      await expect(page.locator('.app-side a.nav[href="#/library"]')).toBeVisible();
      await expect(page.locator(".app-side .status")).toBeVisible();

      // The logo and first nav link must actually occupy real space near the top of the sidebar —
      // not just be "visible" per Playwright's own definition (non-zero box, not display:none), which
      // does NOT check pixel occlusion by another element. This is the check that actually would have
      // caught the 2026-08-13 incident: every toBeVisible()/boundingBox() assertion above still passed
      // even while the theme-picker's absolutely-positioned .seg silently covered the whole sidebar
      // edge-to-edge — elementFromPoint is the only way to confirm nothing is sitting on top.
      const logoBox = await page.locator(".app-side .logo").boundingBox();
      const navBox = await page.locator('.app-side a.nav[href="#/dashboard"]').boundingBox();
      expect(logoBox).not.toBeNull();
      expect(navBox).not.toBeNull();
      expect(logoBox!.height).toBeGreaterThan(10);
      expect(navBox!.height).toBeGreaterThan(10);

      const topmostAtLogo = await page.evaluate(({ x, y }) => {
        const el = document.elementFromPoint(x, y);
        return el ? { tag: el.tagName, cls: (el as HTMLElement).className } : null;
      }, { x: logoBox!.x + logoBox!.width / 2, y: logoBox!.y + logoBox!.height / 2 });
      expect(topmostAtLogo?.cls).toContain("logo");

      const topmostAtNav = await page.evaluate(({ x, y }) => {
        const el = document.elementFromPoint(x, y);
        return el ? el.closest("a.nav")?.getAttribute("href") ?? null : null;
      }, { x: navBox!.x + navBox!.width / 2, y: navBox!.y + navBox!.height / 2 });
      expect(topmostAtNav).toBe("#/dashboard");

      await expect(page).toHaveScreenshot(`shell-${theme}.png`, { maxDiffPixelRatio: 0.02 });
    });
  }

  test("sidebar nav stays visible on a short viewport (Towo nav enabled)", async () => {
    await page.setViewportSize({ width: 1400, height: 640 });
    await setLocalStorageAndReload(page, { "js-towo": "1" });

    await expect(page.locator('.app-side a.nav[href="#/dashboard"]')).toBeVisible();
    const navBox = await page.locator('.app-side a.nav[href="#/dashboard"]').boundingBox();
    expect(navBox?.height ?? 0).toBeGreaterThan(10);
  });
});
