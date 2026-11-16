import { test, expect, Page } from "@playwright/test";

// DEB-4663 (FR-11, FR-12) — the Tizen app's Settings, driven with keyboard events against the test
// stack's `ravilo-screen` image. Strings below are the shipped i18n/*.json copy.
const SCREEN_URL = process.env.RAVILO_SCREEN_CAST_URL ?? "http://localhost:8092";
const SERVER = "http://app:9505";
const PROFILES = "ravilo.screen.profiles";

const viewer = (userId: string, displayName: string) => ({ userId, displayName, token: `token-${userId}` });

async function boot(page: Page, profiles = [viewer("viewer", "Test Viewer")]) {
  await page.addInitScript(([server, key, list]) => {
    // Seeds once: a reload (Change server) must see what the app itself stored by then.
    if (sessionStorage.getItem("__seeded")) return;
    sessionStorage.setItem("__seeded", "1");
    localStorage.setItem("ravilo.screen.serverUrl", server as string);
    localStorage.setItem(key as string, JSON.stringify(list));
  }, [SERVER, PROFILES, profiles] as const);
  await page.goto(SCREEN_URL);
  if (profiles.length > 1) {
    await expect(page.locator("#profile-picker")).toHaveClass(/\bon\b/);
    await page.keyboard.press("Enter");
  }
  await expect(page.locator("#shell")).toHaveClass(/\bon\b/);
}

async function openSettings(page: Page) {
  await page.keyboard.press("ArrowUp");
  for (let i = 0; i < 4; i++) await page.keyboard.press("ArrowRight");
  await expect(page.locator("#nav-profile")).toHaveClass(/focused/);
  await page.keyboard.press("Enter");
  await expect(page.locator("#profile-menu")).toHaveClass(/\bon\b/);
  await page.keyboard.press("ArrowDown"); // Switch profile is focused first; Settings is the next row.
  await page.keyboard.press("Enter");
  await expect(page.locator("#profile-menu")).not.toHaveClass(/\bon\b/);
  await expect(page.locator("#set-title")).toBeVisible();
}

const bar = (page: Page) => page.locator(".shell-nav").evaluate(nav =>
  Array.from(nav.querySelectorAll(".shell-target")).map(e => e.textContent));

test("Settings opens from the profile menu and Back returns to Home (DEB-4663 FR-11)", async ({ page }) => {
  await boot(page);
  await openSettings(page);
  await expect(page.locator("#set-title")).toHaveText("Settings");
  await expect(page.locator("#shell .shell-target.focused")).toHaveAttribute("id", "set-lang-tv");
  await page.keyboard.press("Escape");
  await expect(page.locator("#shell-body")).toHaveText("Home");
});

test("a language switch changes the bar's labels and is remembered (DEB-4663 FR-11)", async ({ page }) => {
  await boot(page);
  expect(await bar(page)).toEqual(["Home", "Movies", "Series", "Search"]);
  await openSettings(page);
  const choices = await page.locator(".set-choice[data-row=lang]").allTextContents();
  expect(choices).toEqual(["Follow the TV", "English", "Dansk", "Føroyskt"]);
  await page.keyboard.press("ArrowRight"); // English
  await page.keyboard.press("ArrowRight"); // Dansk
  await expect(page.locator("#shell .shell-target.focused")).toHaveAttribute("id", "set-lang-da");
  await page.keyboard.press("Enter");
  expect(await bar(page)).toEqual(["Hjem", "Film", "Serier", "Søg"]);
  await expect(page.locator("#set-title")).toHaveText("Indstillinger");
  await expect(page.locator("#set-lang-da")).toHaveClass(/\bcur\b/);
  await expect(page.locator("#shell .shell-target.focused")).toHaveAttribute("id", "set-lang-da");
  await page.keyboard.press("ArrowRight"); // Føroyskt
  await page.keyboard.press("Enter");
  expect(await bar(page)).toEqual(["Heim", "Filmar", "Seriur", "Leita"]);
  await page.reload();
  await expect(page.locator("#shell")).toHaveClass(/\bon\b/);
  expect(await bar(page)).toEqual(["Heim", "Filmar", "Seriur", "Leita"]);
});

test.describe("a Danish TV", () => {
  test.use({ locale: "da-DK" });

  test("follows the TV until a language is picked (DEB-4663 FR-11)", async ({ page }) => {
    await boot(page);
    expect(await bar(page)).toEqual(["Hjem", "Film", "Serier", "Søg"]);
    await openSettings(page);
    await expect(page.locator("#set-lang-tv")).toHaveClass(/\bcur\b/);
    await expect(page.locator("#set-lang-tv")).toHaveText("Følg tv'et");
    await page.keyboard.press("ArrowRight"); // English
    await page.keyboard.press("Enter");
    expect(await bar(page)).toEqual(["Home", "Movies", "Series", "Search"]);
    await page.keyboard.press("ArrowLeft"); // back to Follow the TV
    await page.keyboard.press("Enter");
    expect(await bar(page)).toEqual(["Hjem", "Film", "Serier", "Søg"]);
  });
});

test("About names the app's version and the server's own (DEB-4663 FR-11)", async ({ page, request }) => {
  await boot(page);
  await openSettings(page);
  const health = await (await request.get("/api/health")).json();
  expect(health.version).toBeTruthy();
  await expect(page.locator("#set-server-version")).toHaveText(health.version);
  await expect(page.locator("#set-app-version")).toHaveText(/\S/);
  await expect(page.locator("#set-app-version")).not.toHaveText("…");
  await expect(page.locator("#set-server-address")).toHaveText("app:9505");
});

test("Change server opens setup prefilled; the same address keeps every profile (DEB-4663 FR-11)", async ({ page }) => {
  await boot(page, [viewer("one", "One"), viewer("two", "Two")]);
  await openSettings(page);
  await expect(page.locator("#set-server-note")).toHaveText("A new address signs every profile out.");
  await page.keyboard.press("ArrowDown");
  await expect(page.locator("#shell .shell-target.focused")).toHaveAttribute("id", "set-change-server");
  await page.keyboard.press("Enter");
  await expect(page.locator("#setup")).toHaveClass(/\bon\b/);
  await expect(page.locator("#setupHost")).toHaveValue(SERVER);
  await page.locator("#setupHost").press("Enter");
  await expect(page.locator("#profile-picker")).toHaveClass(/\bon\b/);
  const kept = JSON.parse((await page.evaluate(key => localStorage.getItem(key), PROFILES))!) as unknown[];
  expect(kept).toHaveLength(2);
  expect(await page.evaluate(() => localStorage.getItem("ravilo.screen.serverUrl"))).toBe(SERVER);
});

test("a new address signs every profile out (DEB-4663 FR-11)", async ({ page }) => {
  const other = "http://other-server.invalid";
  await page.route(`${other}/**`, route => route.request().url() === `${other}/api/health`
    ? route.fulfill({ status: 200, contentType: "application/json", headers: { "access-control-allow-origin": "*" }, body: '{"status":"ok","version":"9.9"}' })
    : route.abort());
  await boot(page, [viewer("one", "One"), viewer("two", "Two")]);
  await openSettings(page);
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Enter");
  await expect(page.locator("#setup")).toHaveClass(/\bon\b/);
  await page.locator("#setupHost").fill(other);
  await page.locator("#setupHost").press("Enter");
  await expect(page.locator("#signin")).toHaveClass(/\bon\b/);
  expect(await page.evaluate(() => localStorage.getItem("ravilo.screen.serverUrl"))).toBe(other);
  expect(await page.evaluate(key => localStorage.getItem(key), PROFILES)).toBeNull();
  expect(await page.evaluate(() => localStorage.getItem("ravilo.screen.activeProfile"))).toBeNull();
});
