import { test, expect } from "@playwright/test";

const SCREEN_URL = process.env.RAVILO_SCREEN_CAST_URL ?? "http://localhost:8092";

// The shell's entry point precedes the sign-in piece. A remembered receiver token is enough to
// exercise its router without deriving or faking a server feed; piece 2 replaces this boot bridge.
test("Tizen shell moves spatially across the bar and Back restores each page's focus (DEB-4656 FR-4)", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.addInitScript(() => {
    localStorage.setItem("ravilo.screen.serverUrl", "http://app:9505");
    localStorage.setItem("ravilo.screen.tokens", JSON.stringify({ viewer: "test-token" }));
    (window as any).__exitCalls = 0;
    window.close = () => { (window as any).__exitCalls++; };
  });
  await page.goto(SCREEN_URL);
  await expect(page.locator("#shell")).toHaveClass(/\bon\b/);
  const focused = page.locator("#shell .shell-target.focused");
  await expect(focused).toHaveCount(1);
  await expect(focused).toHaveAttribute("id", "shell-page-target");

  await page.keyboard.press("ArrowUp");
  await expect(focused).toHaveAttribute("id", "nav-home");
  await page.keyboard.press("ArrowRight");
  await expect(focused).toHaveAttribute("id", "nav-movies");
  await page.keyboard.press("Enter");
  await expect(page.locator("#shell-body")).toHaveText("Movies");
  await expect(focused).toHaveAttribute("id", "shell-page-target");
  await page.keyboard.press("ArrowUp");
  await expect(focused).toHaveAttribute("id", "nav-movies");
  await page.keyboard.press("ArrowRight");
  await expect(focused).toHaveAttribute("id", "nav-series");
  await page.keyboard.press("Enter");
  await expect(page.locator("#shell-body")).toHaveText("Series");
  await page.keyboard.press("Escape");
  await expect(page.locator("#shell-body")).toHaveText("Movies");
  await expect(focused).toHaveAttribute("id", "nav-series");
  await page.keyboard.press("Escape");
  await expect(page.locator("#shell-body")).toHaveText("Home");
  await expect(focused).toHaveAttribute("id", "nav-movies");
  await page.keyboard.press("Escape");
  expect(await page.evaluate(() => (window as any).__exitCalls)).toBe(1);
  await expect(focused).toHaveCount(1);
  expect(errors).toEqual([]);
});

test("Back on Home moves to the bar before exiting; all five bar targets are reachable", async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem("ravilo.screen.serverUrl", "http://app:9505");
    localStorage.setItem("ravilo.screen.tokens", JSON.stringify({ viewer: "test-token" }));
    (window as any).__exitCalls = 0;
    window.close = () => { (window as any).__exitCalls++; };
  });
  await page.goto(SCREEN_URL);
  const focused = page.locator("#shell .shell-target.focused");
  await expect(focused).toHaveAttribute("id", "shell-page-target");
  await page.evaluate(() => window.dispatchEvent(new KeyboardEvent("keydown", { key: "XF86Back" })));
  await expect(focused).toHaveAttribute("id", "nav-home");
  for (const id of ["nav-movies", "nav-series", "nav-search", "nav-profile"]) {
    await page.keyboard.press("ArrowRight");
    await expect(focused).toHaveAttribute("id", id);
  }
  await page.evaluate(() => window.dispatchEvent(new KeyboardEvent("keydown", { key: "XF86Back" })));
  expect(await page.evaluate(() => (window as any).__exitCalls)).toBe(1);
});

test("a row re-enters at its last tile and scrolls it into view", async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem("ravilo.screen.serverUrl", "http://app:9505");
    localStorage.setItem("ravilo.screen.tokens", JSON.stringify({ viewer: "test-token" }));
  });
  await page.goto(SCREEN_URL);
  const focused = page.locator("#shell .shell-target.focused");
  await expect(focused).toHaveAttribute("id", "shell-page-target");
  await page.keyboard.press("ArrowUp");
  await page.evaluate(() => {
    const body = document.getElementById("shell-body")!;
    body.innerHTML = '<div id="test-row" style="display:flex;width:140px;overflow-x:auto"><div id="tile-a" class="shell-target" data-row="tiles" tabindex="-1" style="width:110px;height:90px;flex:none">A</div><div id="tile-b" class="shell-target" data-row="tiles" tabindex="-1" style="width:110px;height:90px;flex:none">B</div><div id="tile-c" class="shell-target" data-row="tiles" tabindex="-1" style="width:110px;height:90px;flex:none">C</div></div>';
  });
  await page.keyboard.press("ArrowDown");
  await expect(focused).toHaveAttribute("data-row", "tiles");
  await page.keyboard.press("ArrowRight");
  await page.keyboard.press("ArrowRight");
  await expect(focused).toHaveAttribute("id", "tile-c");
  await expect.poll(() => page.locator("#test-row").evaluate(row => row.scrollLeft)).toBeGreaterThan(0);
  await page.keyboard.press("ArrowUp");
  await expect(focused).toHaveAttribute("data-row", "bar");
  await page.keyboard.press("ArrowDown");
  await expect(focused).toHaveAttribute("id", "tile-c");
  await expect(focused).toHaveCount(1);
});
