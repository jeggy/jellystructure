import { test, expect, type Page } from "@playwright/test";

const SCREEN_URL = process.env.RAVILO_SCREEN_CAST_URL ?? "http://localhost:8092";
const card = (id: string) => ({ id, kind: "MOVIE", title: `Story ${id}`, year: 2024,
  genre: null, rating: null, poster_url: `/api/tv/image/${id}`, backdrop_url: null });

async function boot(page: Page, homeStatus = 200) {
  await page.route("**/api/tv/home", route => homeStatus === 200
    ? route.fulfill({ status: 200, contentType: "application/json",
      body: JSON.stringify({ heroes: [], channels: [], rows: [], tile_shape: "POSTER", hero_height_pct: 56 }) })
    : route.fulfill({ status: homeStatus, contentType: "application/json", body: "{}" }));
  await page.addInitScript(() => {
    localStorage.setItem("ravilo.screen.serverUrl", "http://app:9505");
    localStorage.setItem("ravilo.screen.profiles", JSON.stringify([
      { userId: "viewer", displayName: "Test Viewer", token: "test-token" },
    ]));
  });
  await page.goto(SCREEN_URL);
  if (homeStatus === 200) await expect(page.locator("#nav-home.focused")).toBeVisible();
  else await expect(page.locator("#shell-body .load-failure")).toBeVisible();
}

// DEB-5693 (DEB-5685 FR-62): every tile shows a picture or its letters, on every page.
test.describe("every tile shows a picture or its letters (DEB-5685 FR-62)", () => {
  const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==", "base64");
  const browse = (page: Page, items: object[]) => page.route("**/api/tv/browse?**", route => route.fulfill({
    status: 200, contentType: "application/json", body: JSON.stringify({ query: "", items, total: items.length }) }));
  const openMovies = async (page: Page) => {
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("Enter");
    await expect(page.locator(".browse-grid .home-tile").first()).toBeVisible();
  };

  test("a grid draws its posters when Home answered 500", async ({ page }) => {
    await page.route("**/api/tv/image/**", route => route.fulfill({ status: 200, contentType: "image/png", body: PNG }));
    await browse(page, [card("amber"), card("birch")]);
    await boot(page, 500);
    await page.keyboard.press("ArrowUp");   // off the failure's Retry, onto the nav (the avatar remembered)
    for (let i = 0; i < 6; i++) await page.keyboard.press("ArrowLeft");
    await expect(page.locator("#nav-home.focused")).toBeVisible();
    await openMovies(page);
    await expect.poll(() => page.locator(".browse-grid .tile-art img").count()).toBeGreaterThan(0);
  });

  test("a card with no poster_url draws its first two letters", async ({ page }) => {
    await page.route("**/api/tv/image/**", route => route.fulfill({ status: 200, contentType: "image/png", body: PNG }));
    await browse(page, [{ ...card("plain"), title: "Quiet River", poster_url: null }, card("amber")]);
    await boot(page);
    await openMovies(page);
    await expect(page.locator("#browse-tile-0 .tile-art .tile-letters")).toHaveText("QU");
    await expect(page.locator("#browse-tile-0 .tile-art img")).toHaveCount(0);
    await expect(page.locator("#browse-tile-1 .tile-letters")).toHaveCount(0);
  });

  test("an image answered 404 draws the fallback, and is asked for again once its tile comes back", async ({ page }) => {
    const asked: string[] = [];
    await page.route("**/api/tv/image/**", route => {
      const url = route.request().url();
      if (!url.endsWith("/movie-0")) return route.fulfill({ status: 200, contentType: "image/png", body: PNG });
      asked.push(url);
      return route.fulfill({ status: 404, headers: { "cache-control": "no-store" }, body: "" });
    });
    await browse(page, Array.from({ length: 130 }, (_, i) => card(`movie-${i}`)));
    await boot(page);
    await openMovies(page);
    await expect(page.locator("#browse-tile-0 .tile-art .tile-letters")).toHaveText("ST");
    expect(asked.length).toBe(1);
    // Twenty rows down, tile 0 is more than a screen away and gives its slot up.
    for (let i = 0; i < 20; i++) await page.keyboard.press("ArrowDown");
    await expect(page.locator("#browse-tile-120.focused")).toBeVisible();
    await expect(page.locator("#browse-tile-0 .tile-art > *")).toHaveCount(0);
    for (let i = 0; i < 20; i++) await page.keyboard.press("ArrowUp");
    await expect(page.locator("#browse-tile-0.focused")).toBeVisible();
    await expect.poll(() => asked.length).toBe(2);
    await expect(page.locator("#browse-tile-0 .tile-art .tile-letters")).toHaveText("ST");
  });
});

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
  await expect(page.locator("#shell-body .load-failure-title")).toHaveText("Couldn't reach the server");   // the page opened; its facts are not mocked (FR-20: loading is a wordless skeleton)
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

test("a grid's first row keeps the page at its top and a later row rests 248 px from the top (DEB-5487, FR-39)", async ({ page }) => {
  await page.route("**/api/tv/browse?**", route => route.fulfill({ status: 200, contentType: "application/json",
    body: JSON.stringify({ query: "", items: Array.from({ length: 30 }, (_, i) => card(`movie-${i}`)), total: 30 }) }));
  await boot(page);
  await page.keyboard.press("ArrowRight");
  await page.keyboard.press("Enter");
  await expect(page.locator("#browse-tile-0.focused")).toBeVisible();
  const layoutTop = (selector: string) => page.locator(selector).evaluate(element => {
    const rect = element.getBoundingClientRect();
    return rect.top + (rect.height - (element as HTMLElement).offsetHeight) / 2;
  });
  const natural = await layoutTop("#browse-tile-0");
  expect(await page.locator(".browse-page").evaluate(element => new DOMMatrix(getComputedStyle(element).transform).m42)).toBe(0);
  for (const row of [1, 2, 4]) {
    for (let step = 0; step < (row === 4 ? 2 : 1); step++) await page.keyboard.press("ArrowDown");
    await expect(page.locator(`#browse-tile-${row * 6}.focused`)).toBeVisible();
    await expect.poll(async () => Math.abs(await layoutTop(`#browse-tile-${row * 6}`) - 248), { timeout: 4000 }).toBeLessThanOrEqual(2);
    expect(await page.locator("#shell-body").evaluate(body => body.scrollTop)).toBe(0);
  }
  // Back to the first row: the page returns to its top.
  for (let i = 0; i < 4; i++) await page.keyboard.press("ArrowUp");
  await expect(page.locator("#browse-tile-0.focused")).toBeVisible();
  await expect.poll(() => layoutTop("#browse-tile-0"), { timeout: 4000 }).toBeCloseTo(natural, 0);
});
