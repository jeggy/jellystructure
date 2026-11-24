import { test, expect, Page } from "@playwright/test";

const SCREEN_URL = process.env.RAVILO_SCREEN_CAST_URL ?? "http://localhost:8092";

const card = (id: string, title: string) => ({
  id, kind: "MOVIE", title, year: 2024, genre: null, rating: null,
  poster_url: `/api/tv/image/${id}-poster`, backdrop_url: `/api/tv/image/${id}-backdrop`,
});

const homeFeed = {
  heroes: [{ item: card("hero-a", "Harbor Lights"), tagline_kicker: "Featured",
    backdrop_url: "/api/tv/image/hero-a-backdrop", logo_url: null, badge: null, synopsis: "A voyage begins." }],
  channels: [{ id: "forest", name: "Forest Stories", logo_url: "/api/tv/image/forest-logo",
    style: "TEXT", brand_color: null }],
  rows: [
    { id: "new", title: "New Arrivals", kind: "NEWLY_ADDED", items: [card("new-a", "Paper Moon")] },
    { id: "future", title: "Future Shelf", kind: "FUTURE_KIND", items: [card("future-a", "Copper River")] },
    { id: "continue", title: "Keep Watching", kind: "CONTINUE", items: [card("continue-a", "Blue Orchard")] },
  ],
  tile_shape: "SQUARE", hero_height_pct: 48, focus_detail: "none",
  live_tv_home: { on_now_row_position: 0 },
};

async function boot(page: Page, feed: object = homeFeed) {
  await page.route("**/api/tv/home", route => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(feed) }));
  await page.addInitScript(() => {
    localStorage.setItem("ravilo.screen.serverUrl", "http://app:9505");
    localStorage.setItem("ravilo.screen.profiles", JSON.stringify([{ userId: "viewer", displayName: "Test Viewer", token: "test-token" }]));
  });
  await page.goto(SCREEN_URL);
  await expect(page.locator("#shell-body .home-feed")).toBeVisible();
}

test("Tizen Home draws server order, unknown kind as CUSTOM, and opens a channel feed (DEB-4658 FR-5)", async ({ page }) => {
  await page.route("**/api/tv/channel/forest", route => route.fulfill({
    status: 200, contentType: "application/json",
    body: JSON.stringify({ heroes: [], channels: [], rows: [
      { id: "forest-row", title: "Forest Favorites", kind: "CUSTOM", items: [card("forest-a", "Moss House")] },
    ], tile_shape: "POSTER", hero_height_pct: 56, focus_detail: "none" }),
  }));
  await boot(page);
  await expect(page.locator(".home-hero")).toHaveCount(1);
  await expect(page.locator(".home-hero .hero-title")).toHaveText("Harbor Lights");
  const heroHeight = await page.locator(".home-hero").evaluate(element => element.getBoundingClientRect().height);
  expect(heroHeight).toBeCloseTo(page.viewportSize()!.height * 0.48, 0);
  await expect(page.locator("#shell-body .home-heading")).toHaveText([
    "Collections", "New Arrivals", "Future Shelf", "Keep Watching",
  ]);
  await expect(page.locator("#home-row-1-tile-0")).toContainText("Copper River");
  await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/tile-square/);
  await expect(page.locator("#home-row-2-tile-0")).toHaveClass(/tile-landscape/);
  await expect(page.locator("#shell-body [data-row=\"on_now\"]")).toHaveCount(0);
  await expect(page.locator("#shell-body .focus-detail")).toHaveCount(0);

  await page.keyboard.press("ArrowDown");
  await expect(page.locator("#home-channel-0")).toHaveClass(/focused/);
  await page.keyboard.press("Enter");
  await expect(page.locator("#shell-body .home-heading")).toHaveText(["Forest Favorites"]);
  await expect(page.locator("#home-row-0-tile-0")).toContainText("Moss House");
  await page.keyboard.press("Escape");
  await expect(page.locator("#home-channel-0")).toHaveClass(/focused/);
  await page.keyboard.press("ArrowDown");
  await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
  await page.keyboard.press("Enter");
  // The title's page opened (its facts are not mocked, so it ends in DEB-4660's failure line; the loading state is a skeleton with no words, FR-20).
  await expect(page.locator("#shell-body .shell-placeholder")).toHaveText("Couldn't reach the server");
  await page.keyboard.press("Escape");
  await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
});

test("Tizen Home delays distant artwork and keeps at most 60 tile images (DEB-4658 FR-14)", async ({ page }) => {
  const rows = Array.from({ length: 10 }, (_, row) => ({
    id: `row-${row}`, title: `Shelf ${row}`, kind: "CUSTOM",
    items: Array.from({ length: 12 }, (_, tile) => card(`r${row}-t${tile}`, `Story ${row}-${tile}`)),
  }));
  await boot(page, { heroes: homeFeed.heroes, channels: [], rows, tile_shape: "POSTER", hero_height_pct: 48 });
  const sourced = page.locator("#shell-body .tile-art img[src]");
  const before = await sourced.count();
  expect(before).toBeGreaterThan(0);
  expect(before).toBeLessThanOrEqual(60);
  await expect(page.locator("#home-row-9-tile-0 img[src]")).toHaveCount(0);
  for (let i = 0; i < 10; i++) await page.keyboard.press("ArrowDown");
  await expect(page.locator("#shell-body .home-tile.focused")).toHaveAttribute("data-row", "row-9");
  expect(await page.locator("#shell-body [data-row=\"row-9\"] img[src]").count()).toBeGreaterThan(0);
  expect(await sourced.count()).toBeLessThanOrEqual(60);
});

test("Tizen Home gives deep focused artwork priority in dense rows (DEB-4729, DEB-4658 FR-14)", async ({ page }) => {
  const rows = Array.from({ length: 20 }, (_, row) => ({
    id: `row-${row}`, title: `Shelf ${row}`, kind: "CUSTOM",
    items: Array.from({ length: 30 }, (_, tile) => card(`r${row}-t${tile}`, `Story ${row}-${tile}`)),
  }));
  await boot(page, { heroes: homeFeed.heroes, channels: [], rows, tile_shape: "POSTER", hero_height_pct: 48 });
  for (let i = 0; i < rows.length; i++) await page.keyboard.press("ArrowDown");
  const focused = page.locator("#shell-body .home-tile.focused");
  await expect(focused).toHaveAttribute("data-row", "row-19");
  await expect(focused.locator(".tile-art img[src]")).toHaveCount(1);
  expect(await page.locator("#shell-body .tile-art img[src]").count()).toBeLessThanOrEqual(60);
});

test("Tizen Up/Down visits a one-tile Continue Watching row before the long row below it, and Up returns (DEB-4888, DEB-4654 FR-4)", async ({ page }) => {
  // Home lands on the hero, which is centred; the one-tile Continue row sits far to its left. The old nearest-distance
  // pick weighed the sideways gap double and went straight past the short row to a tile of the long one.
  const feed = {
    heroes: homeFeed.heroes, channels: [],
    rows: [
      { id: "continue", title: "Keep Watching", kind: "CONTINUE", items: [card("continue-a", "Blue Orchard")] },
      { id: "long", title: "Long Shelf", kind: "CUSTOM",
        items: Array.from({ length: 14 }, (_, tile) => card(`long-${tile}`, `Story ${tile}`)) },
      { id: "after", title: "After Shelf", kind: "CUSTOM", items: [card("after-a", "Tail Light")] },
    ],
    tile_shape: "POSTER", hero_height_pct: 48, focus_detail: "none",
  };
  await boot(page, feed);
  await expect(page.locator("#home-hero-0")).toHaveClass(/focused/);

  await page.keyboard.press("ArrowDown");
  await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
  await page.keyboard.press("ArrowDown");
  await expect(page.locator("#shell-body .home-tile.focused")).toHaveAttribute("data-row", "row-1");
  await page.keyboard.press("ArrowDown");
  await expect(page.locator("#home-row-2-tile-0")).toHaveClass(/focused/);
  await page.keyboard.press("ArrowUp");
  await expect(page.locator("#shell-body .home-tile.focused")).toHaveAttribute("data-row", "row-1");
  await page.keyboard.press("ArrowUp");
  await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
  await page.keyboard.press("ArrowUp");
  await expect(page.locator("#home-hero-0")).toHaveClass(/focused/);
});

test("Tizen Down from the hero lands on the leftmost tile of a first row wider than the screen (DEB-5487, FR-40)", async ({ page }) => {
  const feed = {
    heroes: homeFeed.heroes, channels: [],
    rows: [
      { id: "wide", title: "Wide Shelf", kind: "CUSTOM",
        items: Array.from({ length: 30 }, (_, tile) => card(`wide-${tile}`, `Story ${tile}`)) },
      { id: "after", title: "After Shelf", kind: "CUSTOM", items: [card("after-a", "Tail Light")] },
    ],
    tile_shape: "POSTER", hero_height_pct: 48, focus_detail: "none",
  };
  await boot(page, feed);
  await expect(page.locator("#home-hero-0")).toHaveClass(/focused/);
  await page.keyboard.press("ArrowDown");
  await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
});

test("Tizen every on-screen tile keeps its poster while focus moves along a row (DEB-5487, FR-42)", async ({ page }) => {
  const rows = Array.from({ length: 4 }, (_, row) => ({
    id: `row-${row}`, title: `Shelf ${row}`, kind: "CUSTOM",
    items: Array.from({ length: 30 }, (_, tile) => card(`r${row}-t${tile}`, `Story ${row}-${tile}`)),
  }));
  await boot(page, { heroes: homeFeed.heroes, channels: [], rows, tile_shape: "POSTER", hero_height_pct: 48 });
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("ArrowDown");
  await expect(page.locator("#shell-body .home-tile.focused")).toHaveAttribute("data-row", "row-1");
  for (let i = 0; i < 10; i++) await page.keyboard.press("ArrowRight");
  await expect(page.locator("#home-row-1-tile-10")).toHaveClass(/focused/);
  const bare = await page.evaluate(() => {
    const w = window.innerWidth, h = window.innerHeight;
    return Array.from(document.querySelectorAll("#shell-body .tile-art")).filter(art => {
      const r = art.getBoundingClientRect();
      return r.width > 0 && r.height > 0 && r.right > 0 && r.left < w && r.bottom > 0 && r.top < h && !art.querySelector("img[src]");
    }).length;
  });
  expect(bare).toBe(0);
  expect(await page.locator("#shell-body .tile-art img[src]").count()).toBeLessThanOrEqual(60);
});
