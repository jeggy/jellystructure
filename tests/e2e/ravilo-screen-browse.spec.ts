import { test, expect, type Page } from "@playwright/test";

const SCREEN_URL = process.env.RAVILO_SCREEN_CAST_URL ?? "http://localhost:8092";
const card = (id: string) => ({ id, kind: "MOVIE", title: `Story ${id}`, year: 2024,
  genre: null, rating: null, poster_url: `/api/tv/image/${id}`, backdrop_url: null });

async function boot(page: Page) {
  await page.route("**/api/tv/home", route => route.fulfill({ status: 200, contentType: "application/json",
    body: JSON.stringify({ heroes: [], channels: [], rows: [], tile_shape: "POSTER", hero_height_pct: 56 }) }));
  await page.addInitScript(() => {
    localStorage.setItem("ravilo.screen.serverUrl", "http://app:9505");
    localStorage.setItem("ravilo.screen.profiles", JSON.stringify([
      { userId: "viewer", displayName: "Test Viewer", token: "test-token" },
    ]));
  });
  await page.goto(SCREEN_URL);
  await expect(page.locator("#nav-home.focused")).toBeVisible();
}

test("Movies and Series use their browse kinds and preserve server order (DEB-4659 FR-6)", async ({ page }) => {
  const calls: string[] = [];
  await page.route("**/api/tv/browse?**", route => {
    const url = new URL(route.request().url());
    calls.push(url.searchParams.get("kind")!);
    return route.fulfill({ status: 200, contentType: "application/json",
      body: JSON.stringify({ query: "", items: [card("amber"), card("birch")], total: 2 }) });
  });
  await boot(page);
  await page.keyboard.press("ArrowRight");
  await page.keyboard.press("Enter");
  await expect(page.locator(".browse-grid .tile-title")).toHaveText(["Story amber", "Story birch"]);
  await expect(page.locator(".browse-count")).toHaveText("2 titles");
  await page.keyboard.press("Escape");
  await page.keyboard.press("ArrowRight");
  await page.keyboard.press("Enter");
  await expect(page.locator(".browse-grid .tile-title")).toHaveText(["Story amber", "Story birch"]);
  expect(calls).toEqual(["movie", "series"]);
  await expect(page.locator(".browse-page [class*=filter], .browse-page [class*=facet]")).toHaveCount(0);
});

test("grid requests the next page near its end and Back restores tile focus (DEB-4659 FR-6)", async ({ page }) => {
  const pages: number[] = [];
  await page.route("**/api/tv/browse?**", route => {
    const url = new URL(route.request().url());
    const pageNumber = Number(url.searchParams.get("page"));
    pages.push(pageNumber);
    return route.fulfill({ status: 200, contentType: "application/json",
      body: JSON.stringify({ query: "", items: pageNumber === 1
        ? Array.from({ length: 60 }, (_, i) => card(`movie-${i}`)) : [card("movie-60")], total: 61 }) });
  });
  await boot(page);
  await page.keyboard.press("ArrowRight");
  await page.keyboard.press("Enter");
  await expect(page.locator(".browse-grid .home-tile")).toHaveCount(60);
  for (let row = 0; row < 8; row++) await page.keyboard.press("ArrowDown");
  await expect(page.locator("#browse-tile-48.focused")).toBeVisible();
  await expect(page.locator(".browse-grid .home-tile")).toHaveCount(61);
  expect(pages).toEqual([1, 2]);
  await page.keyboard.press("Enter");
  await expect(page.locator("#shell-body .shell-placeholder")).toHaveText("Couldn't reach the server");   // the page opened; its facts are not mocked (FR-20: loading is a wordless skeleton)
  await page.keyboard.press("Escape");
  await expect(page.locator("#browse-tile-48.focused")).toBeVisible();
});

test("My List opens from the avatar and shows the Android TV empty line (DEB-4659 FR-6)", async ({ page }) => {
  const calls: string[] = [];
  await page.route("**/api/tv/browse?**", route => {
    calls.push(new URL(route.request().url()).searchParams.get("kind")!);
    return route.fulfill({ status: 200, contentType: "application/json",
      body: JSON.stringify({ query: "", items: [], total: 0 }) });
  });
  await boot(page);
  for (let i = 0; i < 4; i++) await page.keyboard.press("ArrowRight");
  await page.keyboard.press("Enter");
  await expect(page.locator("#profile-menu.on")).toBeVisible();
  await page.keyboard.press("ArrowUp");
  await page.keyboard.press("Enter");
  await expect(page.locator(".browse-empty")).toHaveText("Add a title with + My List on its page.");
  expect(calls).toEqual(["mylist"]);
  await page.keyboard.press("Escape");
  await expect(page.locator("#nav-profile.focused")).toBeVisible();
});
