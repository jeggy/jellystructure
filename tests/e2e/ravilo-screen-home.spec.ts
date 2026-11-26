import { test, expect, Page } from "@playwright/test";
import { fakeAvPlayInitScript } from "./helpers/screen-cast";

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

async function boot(page: Page, feed: object = homeFeed, fakeClock = false) {
  await page.route("**/api/tv/home", route => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(feed) }));
  await page.addInitScript(() => {
    localStorage.setItem("ravilo.screen.serverUrl", "http://app:9505");
    localStorage.setItem("ravilo.screen.profiles", JSON.stringify([{ userId: "viewer", displayName: "Test Viewer", token: "test-token" }]));
  });
  if (fakeClock) await page.clock.install();
  await page.goto(SCREEN_URL);
  await expect(page.locator("#shell-body .home-feed")).toBeVisible();
}

// DEB-5632 (DEB-5621 FR-5): a Continue Watching tile draws its progress bar only while show_continue_progress is true.
test.describe("Continue Watching progress follows show_continue_progress (DEB-5621 FR-5)", () => {
  const withProgress = { ...homeFeed, rows: [
    { id: "new", title: "New Arrivals", kind: "NEWLY_ADDED", items: [{ ...card("new-a", "Paper Moon"), progress_pct: 0.25 }] },
    { id: "continue", title: "Keep Watching", kind: "CONTINUE", items: [
      { ...card("continue-a", "Blue Orchard"), progress_pct: 0.4 }, { ...card("continue-b", "Red Meadow"), progress_pct: 0.7 }] },
  ] };
  const config = (page: Page, body: object, delayMs = 0) => page.route("**/api/tv/config", async route => {
    if (delayMs) await new Promise(resolve => setTimeout(resolve, delayMs));
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  });

  test("true: every Continue Watching tile has its bar, and only those", async ({ page }) => {
    await config(page, { show_continue_progress: true });
    await boot(page, withProgress);
    await expect(page.locator("#home-row-1-tile-0 .tile-progress, #home-row-1-tile-1 .tile-progress")).toHaveCount(2);
    await expect(page.locator("#shell-body .tile-progress")).toHaveCount(2);
    await expect(page.locator("#home-row-1-tile-1 .tile-progress-fill")).toHaveAttribute("style", /width: 70%/);
  });

  test("false: no tile has a bar", async ({ page }) => {
    await config(page, { show_continue_progress: false });
    await boot(page, withProgress);
    await expect(page.locator("#shell-body .home-heading")).toHaveText(["Collections", "New Arrivals", /^Keep Watching/]);   // FR-52: a two-item row carries its count
    await expect(page.locator("#home-row-1-tile-1")).toContainText("Red Meadow");
    await expect(page.locator("#shell-body .tile-progress")).toHaveCount(0);
  });

  test("false arriving after Home drew takes the bars off without a reload", async ({ page }) => {
    await config(page, { show_continue_progress: false }, 1_500);
    await boot(page, withProgress);
    await expect(page.locator("#shell-body .home-feed")).toBeVisible();
    await expect(page.locator("#shell-body .tile-progress")).toHaveCount(0, { timeout: 5_000 });
  });
});

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
  await expect(page.locator("#home-hero")).toHaveClass(/focused/);

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
  await expect(page.locator("#home-hero")).toHaveClass(/focused/);
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
  await expect(page.locator("#home-hero")).toHaveClass(/focused/);
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

// DEB-5487 FR-39/FR-41 — where the feed rests and how rails and tiles move. A tile's layout top is its rect with the grow undone.
const LINE_PX = 248;
const layoutTop = (page: Page, selector: string) => page.locator(selector).evaluate(element => {
  const rect = element.getBoundingClientRect();
  return rect.top + (rect.height - (element as HTMLElement).offsetHeight) / 2;
});
const layoutLeft = (page: Page, selector: string) => page.locator(selector).evaluate(element => {
  const rect = element.getBoundingClientRect();
  return rect.left + (rect.width - (element as HTMLElement).offsetWidth) / 2;
});
const trackShift = (page: Page, row: number) => page.locator(`#home-row-${row}-tile-0`).evaluate(
  tile => new DOMMatrix(getComputedStyle(tile.parentElement!).transform).m41);
async function onTheLine(page: Page, selector: string) {
  await expect.poll(async () => Math.abs(await layoutTop(page, selector) - LINE_PX), { timeout: 4000 }).toBeLessThanOrEqual(2);
}
const wideFeed = (rows: number, tiles: number, shape = "POSTER") => ({
  heroes: homeFeed.heroes, channels: [],
  rows: Array.from({ length: rows }, (_, row) => ({
    id: `row-${row}`, title: `Shelf ${row}`, kind: "CUSTOM",
    items: Array.from({ length: tiles }, (_, tile) => card(`r${row}-t${tile}`, `Story ${row}-${tile}`)),
  })),
  tile_shape: shape, hero_height_pct: 48, focus_detail: "none",
});

test("Tizen the focused row rests 248 px from the top, the last row too, and the hero brings the feed back (DEB-5487, FR-39)", async ({ page }) => {
  await boot(page, wideFeed(5, 8));
  const heroTop = await page.locator("#home-hero").evaluate(element => element.getBoundingClientRect().top);
  for (let row = 0; row < 5; row++) {
    await page.keyboard.press("ArrowDown");
    await expect(page.locator("#shell-body .home-tile.focused")).toHaveAttribute("data-row", `row-${row}`);
    await onTheLine(page, `#home-row-${row}-tile-0`);
    expect(await page.locator("#shell-body").evaluate(body => body.scrollTop)).toBe(0);
  }
  // The row above rests on the line again, and the way back to the hero is the feed at its top.
  await page.keyboard.press("ArrowUp");
  await onTheLine(page, "#home-row-3-tile-0");
  for (let row = 3; row > 0; row--) await page.keyboard.press("ArrowUp");
  await expect(page.locator("#home-row-0-tile-0, #shell-body .home-tile.focused[data-row=\"row-0\"]").first()).toHaveClass(/focused/);
  await page.keyboard.press("ArrowUp");
  await expect(page.locator("#home-hero")).toHaveClass(/focused/);
  await expect.poll(() => page.locator("#home-hero").evaluate(element => element.getBoundingClientRect().top), { timeout: 4000 }).toBeCloseTo(heroTop, 0);
});

test("Tizen the feed moves by a 220 ms transform, never by scrolling, and a key mid-move goes on from where it is (DEB-5487, FR-39)", async ({ page }) => {
  await boot(page, wideFeed(5, 8));
  const feed = page.locator(".home-feed");
  expect(await feed.evaluate(element => getComputedStyle(element).transitionDuration)).toBe("0.22s");
  expect(await feed.evaluate(element => getComputedStyle(element).transitionTimingFunction)).toBe("cubic-bezier(0.22, 0.61, 0.36, 1)");
  expect(await page.locator("#shell-body").evaluate(body => getComputedStyle(body).overflowY)).toBe("hidden");
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("ArrowDown");
  await expect(page.locator("#home-row-3-tile-0")).toHaveClass(/focused/);
  await onTheLine(page, "#home-row-3-tile-0");
  expect(await page.locator("#shell-body").evaluate(body => body.scrollTop)).toBe(0);
});

test("Tizen the focused tile grows to 1.10 and the tile it left goes back (DEB-5487, FR-41)", async ({ page }) => {
  await boot(page, wideFeed(2, 8));
  await page.keyboard.press("ArrowDown");
  await expect(page.locator("#home-row-0-tile-0")).toHaveCSS("transform", "matrix(1.1, 0, 0, 1.1, 0, 0)");
  await expect(page.locator("#home-row-0-tile-1")).toHaveCSS("transform", "none");
  await page.keyboard.press("ArrowRight");
  await expect(page.locator("#home-row-0-tile-1")).toHaveCSS("transform", "matrix(1.1, 0, 0, 1.1, 0, 0)");
  await expect(page.locator("#home-row-0-tile-0")).toHaveCSS("transform", "none");
  expect(await page.locator("#home-row-0-tile-1").evaluate(element => getComputedStyle(element).transitionDuration)).toBe("0.2s");
});

test("Tizen a rail moves only when the new tile would be cut: to the gutter at the start, just fully visible at the end (DEB-5487, FR-41)", async ({ page }) => {
  await boot(page, wideFeed(2, 20));
  await page.keyboard.press("ArrowDown");
  const width = page.viewportSize()!.width;
  // Tiles that are fully visible do not move the rail.
  for (let i = 1; i <= 4; i++) {
    await page.keyboard.press("ArrowRight");
    await expect(page.locator(`#home-row-0-tile-${i}`)).toHaveClass(/focused/);
    await page.waitForTimeout(300);
    expect(await trackShift(page, 0)).toBe(0);
  }
  // Right until a tile is cut at the end: the rail moves, and the grown tile with its ring ends fully on screen.
  let tile = 4;
  while (await trackShift(page, 0) === 0 && tile < 19) {
    tile++;
    await page.keyboard.press("ArrowRight");
    await expect(page.locator(`#home-row-0-tile-${tile}`)).toHaveClass(/focused/);
    await page.waitForTimeout(350);
  }
  expect(await trackShift(page, 0)).toBeLessThan(0);
  const box = await page.locator(`#home-row-0-tile-${tile}`).evaluate(element => {
    const rect = element.getBoundingClientRect();
    return { left: rect.left, right: rect.right };
  });
  expect(box.right + 4).toBeLessThanOrEqual(width);       // the ring is 4 px and has grown with the tile
  expect(box.right + 4).toBeGreaterThan(width - 20);      // and the rail moved no further than it had to
  // Left onto a tile that is fully visible: the rail stays where it is.
  const shifted = await trackShift(page, 0);
  tile--;
  await page.keyboard.press("ArrowLeft");
  await expect(page.locator(`#home-row-0-tile-${tile}`)).toHaveClass(/focused/);
  await page.waitForTimeout(350);
  expect(await trackShift(page, 0)).toBe(shifted);
  // Left until a tile is cut at the start: the rail moves and that tile comes to the 72 px gutter.
  let moved = false;
  for (let i = 0; i < 19 && !moved; i++) {
    tile--;
    await page.keyboard.press("ArrowLeft");
    await expect(page.locator(`#home-row-0-tile-${tile}`)).toHaveClass(/focused/);
    await page.waitForTimeout(350);
    moved = await trackShift(page, 0) !== shifted;
  }
  expect(moved).toBe(true);
  expect(await layoutLeft(page, `#home-row-0-tile-${tile}`)).toBeCloseTo(72, 0);
});

// DEB-5487 FR-38 — the hero is Android TV's carousel. The page runs on Playwright's fake clock, so "2 s" is what the test says.
const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==", "base64");
const heroFeed = (count: number, autoAdvance: number, rows = 1, extra: object = {}) => ({
  heroes: Array.from({ length: count }, (_, i) => ({
    item: card(`hero-${i}`, `Hero ${i}`), tagline_kicker: "Featured",
    backdrop_url: `/api/tv/image/hero-${i}-backdrop`, logo_url: null, badge: null, synopsis: `Synopsis ${i}`,
  })),
  channels: [],
  rows: Array.from({ length: rows }, (_, row) => ({
    id: `row-${row}`, title: `Shelf ${row}`, kind: "CUSTOM",
    items: Array.from({ length: 8 }, (_, tile) => card(`r${row}-t${tile}`, `Story ${row}-${tile}`)),
  })),
  tile_shape: "POSTER", hero_height_pct: 48, focus_detail: "none", auto_advance_seconds: autoAdvance, ...extra,
});
async function bootHero(page: Page, feed: object) {
  await page.route("**/api/tv/image/**", route => route.fulfill({ status: 200, contentType: "image/png", body: PNG }));
  await boot(page, feed, true);
  await expect(page.locator("#home-hero")).toHaveClass(/focused/);
}
const showing = (page: Page) => page.locator("#home-hero .hero-slide.on .hero-title");

test("Tizen the hero advances every auto_advance_seconds and wraps from the last slide to the first (DEB-5487, FR-38)", async ({ page }) => {
  await bootHero(page, heroFeed(3, 2));
  await expect(showing(page)).toHaveText("Hero 0");
  for (const next of ["Hero 1", "Hero 2", "Hero 0"]) {
    await page.clock.fastForward(2_100);
    await expect(showing(page)).toHaveText(next);
  }
});

test("Tizen Left and Right change the slide, wrap, keep focus on the hero and restart the interval (DEB-5487, FR-38)", async ({ page }) => {
  await bootHero(page, heroFeed(3, 4));
  await page.clock.fastForward(3_000);
  await expect(showing(page)).toHaveText("Hero 0");
  await page.keyboard.press("ArrowRight");
  await expect(showing(page)).toHaveText("Hero 1");
  // Three seconds after the key, where four seconds after boot would already have advanced it.
  await page.clock.fastForward(3_000);
  await expect(showing(page)).toHaveText("Hero 1");
  await page.clock.fastForward(1_100);
  await expect(showing(page)).toHaveText("Hero 2");
  await page.keyboard.press("ArrowRight");
  await expect(showing(page)).toHaveText("Hero 0");
  await page.keyboard.press("ArrowLeft");
  await expect(showing(page)).toHaveText("Hero 2");
  await expect(page.locator("#home-hero")).toHaveClass(/focused/);
});

test("Tizen auto_advance_seconds 0 never advances the hero (DEB-5487, FR-38)", async ({ page }) => {
  await bootHero(page, heroFeed(3, 0));
  await page.clock.fastForward(60_000);
  await expect(showing(page)).toHaveText("Hero 0");
  await expect(page.locator(".hero-dot")).toHaveCount(3);
});

test("Tizen the hero does not count while it is out of view, and a full interval starts when it is back (DEB-5487, FR-38)", async ({ page }) => {
  await bootHero(page, heroFeed(3, 2, 4));
  for (let i = 0; i < 3; i++) await page.keyboard.press("ArrowDown");
  await expect(page.locator("#shell-body .home-tile.focused")).toHaveAttribute("data-row", "row-2");
  await expect.poll(() => page.locator("#home-hero").evaluate(hero => hero.getBoundingClientRect().bottom), { timeout: 4000 }).toBeLessThan(92);
  const before = await showing(page).textContent();
  await page.clock.fastForward(10_000);
  await page.clock.fastForward(10_000);
  await expect(showing(page)).toHaveText(before!);
  // No drift while it is off screen.
  expect(await page.locator("#home-hero .hero-slide.on .hero-art").evaluate(art => getComputedStyle(art).transitionDuration)).toBe("0s");

  for (let i = 0; i < 3; i++) await page.keyboard.press("ArrowUp");
  await expect(page.locator("#home-hero")).toHaveClass(/focused/);
  await expect.poll(() => page.locator("#home-hero").evaluate(hero => hero.getBoundingClientRect().bottom), { timeout: 4000 }).toBeGreaterThan(300);
  await page.clock.fastForward(1_500);
  await expect(showing(page)).toHaveText(before!);
  await page.clock.fastForward(700);
  await expect(showing(page)).not.toHaveText(before!);
  expect(await page.locator("#home-hero .hero-slide.on .hero-art").evaluate(art => getComputedStyle(art).transitionDuration)).toBe("9s");
});

test("Tizen the hero does not count while the app is hidden (DEB-5487, FR-38)", async ({ page }) => {
  await bootHero(page, heroFeed(3, 2));
  await page.evaluate(() => {
    Object.defineProperty(document, "hidden", { configurable: true, get: () => true });
    document.dispatchEvent(new Event("visibilitychange"));
  });
  await page.clock.fastForward(10_000);
  await expect(showing(page)).toHaveText("Hero 0");
});

test("Tizen Back from a title opened on slide 2 shows slide 2 with a full interval (DEB-5487, FR-38)", async ({ page }) => {
  await bootHero(page, heroFeed(3, 3));
  await page.keyboard.press("ArrowRight");
  await expect(showing(page)).toHaveText("Hero 1");
  await expect(page.locator("#home-hero")).toHaveAttribute("data-media-id", "hero-1");
  await page.keyboard.press("Enter");
  await expect(page.locator("#shell-body .shell-placeholder")).toBeVisible();
  // Nothing counts under the title's page.
  await page.clock.fastForward(10_000);
  await page.keyboard.press("Escape");
  await expect(page.locator("#home-hero")).toHaveClass(/focused/);
  await expect(showing(page)).toHaveText("Hero 1");
  await page.clock.fastForward(2_000);
  await expect(showing(page)).toHaveText("Hero 1");
  await page.clock.fastForward(1_200);
  await expect(showing(page)).toHaveText("Hero 2");
});

test("Tizen the hero is one target with stacked slides, no ring, and dots 96 px from the right and 88 px from the bottom (DEB-5487, FR-38)", async ({ page }) => {
  await bootHero(page, heroFeed(3, 0));
  await expect(page.locator(".home-heroes")).toHaveCount(0);
  await expect(page.locator("#shell-body .home-hero")).toHaveCount(1);
  await expect(page.locator("#home-hero .hero-slide")).toHaveCount(3);
  expect(await page.locator("#home-hero").evaluate(hero => getComputedStyle(hero).boxShadow)).toBe("none");

  const dots = await page.locator("#home-hero").evaluate(hero => {
    const box = hero.getBoundingClientRect();
    const list = Array.from(hero.querySelectorAll(".hero-dot")).map(dot => {
      const rect = dot.getBoundingClientRect();
      const style = getComputedStyle(dot);
      return { left: rect.left, right: rect.right, bottom: rect.bottom, width: rect.width, height: rect.height,
        on: dot.classList.contains("on"), opacity: style.opacity, transition: style.transitionProperty };
    });
    return { list, right: box.right, bottom: box.bottom };
  });
  expect(dots.list.map(dot => dot.on)).toEqual([true, false, false]);
  expect(dots.list.map(dot => [dot.width, dot.height])).toEqual([[56, 16], [16, 16], [16, 16]]);
  expect(dots.right - dots.list[2].right).toBeCloseTo(96, 0);
  expect(dots.bottom - dots.list[2].bottom).toBeCloseTo(88, 0);
  expect(dots.list[1].left - dots.list[0].right).toBeCloseTo(16, 0);
  expect(dots.list[2].left - dots.list[1].right).toBeCloseTo(16, 0);
  expect(dots.list[1].opacity).toBe("0.35");
  expect(dots.list.every(dot => dot.transition === "opacity")).toBe(true);
  await page.keyboard.press("ArrowRight");
  await expect(page.locator(".hero-dot").nth(1)).toHaveClass(/on/);
  await expect(page.locator(".hero-dot").nth(0)).not.toHaveClass(/on/);

  // The crossfade is opacity only, 600 ms in and 400 ms out; the drift is a 9 s transform on the slide that shows.
  const fade = await page.locator("#home-hero").evaluate(hero => {
    const on = getComputedStyle(hero.querySelector(".hero-slide.on")!);
    const off = getComputedStyle(hero.querySelector(".hero-slide:not(.on)")!);
    const art = getComputedStyle(hero.querySelector(".hero-slide.on .hero-art")!);
    return [on.transitionProperty, on.transitionDuration, off.transitionProperty, off.transitionDuration, art.transitionProperty, art.transitionDuration];
  });
  expect(fade).toEqual(["opacity", "0.6s", "opacity", "0.4s", "transform", "9s"]);
});

test("Tizen one slide draws no dots (DEB-5487, FR-38)", async ({ page }) => {
  await bootHero(page, heroFeed(1, 2));
  await expect(page.locator(".hero-dot")).toHaveCount(0);
  await page.clock.fastForward(5_000);
  await expect(showing(page)).toHaveText("Hero 0");
  await page.keyboard.press("ArrowRight");
  await expect(showing(page)).toHaveText("Hero 0");
});

test("Tizen the hero loads the slide showing and the next two backdrops, and lets the rest go (DEB-5487, FR-38)", async ({ page }) => {
  await bootHero(page, heroFeed(5, 0));
  const loaded = () => page.locator("#home-hero .hero-art img[src]").evaluateAll(
    images => images.map(image => image.getAttribute("src")!.replace(/^.*hero-(\d)-backdrop$/, "$1")).sort());
  expect(await loaded()).toEqual(["0", "1", "2"]);
  await page.keyboard.press("ArrowRight");
  await page.keyboard.press("ArrowRight");
  await expect(showing(page)).toHaveText("Hero 2");
  await page.clock.fastForward(600);
  await expect.poll(loaded).toEqual(["2", "3", "4"]);
});

// DEB-5650 (DEB-5640 FR-52): Home's tiles carry Android TV's badges, and a row heading carries its count.
test.describe("Tizen tile badges and row counts (DEB-5640 FR-52)", () => {
  const series = (id: string, title: string, extra: object = {}) => ({ ...card(id, title), kind: "SERIES", ...extra });
  const feed = {
    heroes: [], channels: [],
    rows: [
      { id: "continue", title: "Keep Watching", kind: "CONTINUE", seedTotalCount: 37, items: [
        series("cw-code", "Blue Orchard", { season_number: 1, episode_number: 5, progress_pct: 0.4, next_up_label: "S01E06 · Next" }),
        series("cw-range", "Red Meadow", { season_number: 1, episode_number: 1, episode_number_end: 3 }),
        series("cw-new", "Dagbók", { season_number: 2026, episode_number: 302, episode_air_date: "2020-10-29" }),
        series("cw-date", "Dagbók Two", { season_number: 2026, episode_number: 302, episode_air_date: "2020-10-29", progress_pct: 0.4 }),
      ] },
      { id: "shelf", title: "Shelf", kind: "CUSTOM", items: [
        series("sh-done", "Paper Moon", { watched: true, quality_badge: "4K", next_up_label: "S02E01 · Up next" }),
        series("sh-soon", "Copper River", { upcoming_episode: "S03E01" }),
        series("sh-soon-watched", "Moss House", { upcoming_episode: "S04E02", watched: true }),
        series("sh-plain", "Plain Title"),
      ] },
      { id: "single", title: "Single", kind: "CUSTOM", items: [card("one", "Only One")] },
    ],
    tile_shape: "POSTER", hero_height_pct: 48, focus_detail: "none",
  };

  test("a Continue Watching tile names its episode on the art and has no second line", async ({ page }) => {
    await boot(page, feed);
    const code = page.locator("#home-row-0-tile-0");
    await expect(code.locator(".badge-episode")).toHaveText("S01E05");
    await expect(code.locator(".badge-start")).toHaveText("S01E05");
    await expect(code.locator(".tile-sub")).toHaveCount(0);
    await expect(page.locator("#home-row-0-tile-1 .badge-episode")).toHaveText("S01E01–E03");
  });

  test("a newest-first card shows its date, New before it is started", async ({ page }) => {
    await boot(page, feed);
    await expect(page.locator("#home-row-0-tile-2 .badge-episode")).toHaveText(/^New · .*29.*2020/);
    await expect(page.locator("#home-row-0-tile-3 .badge-episode")).toHaveText(/^(?!New).*29.*2020/);
  });

  test("a watched card shows ✓ top-right and quality shows bottom-right", async ({ page }) => {
    await boot(page, feed);
    const done = page.locator("#home-row-1-tile-0");
    await expect(done.locator(".badge-end.badge-watched")).toHaveText("✓");
    await expect(done.locator(".badge-quality")).toHaveText("4K");
    const [frame, quality, watched] = await Promise.all(
      [".tile-frame", ".badge-quality", ".badge-watched"].map(s => done.locator(s).evaluate(e => e.getBoundingClientRect().toJSON())));
    expect(quality.right).toBeLessThanOrEqual(frame.right);
    expect(frame.right - quality.right).toBeLessThan(16);
    expect(frame.bottom - quality.bottom).toBeLessThan(16);
    expect(watched.top - frame.top).toBeLessThan(16);
    expect(frame.right - watched.right).toBeLessThan(16);
  });

  test("a card outside Continue Watching puts next_up_label under its title", async ({ page }) => {
    await boot(page, feed);
    await expect(page.locator("#home-row-1-tile-0 .tile-title + .tile-sub")).toHaveText("S02E01 · Up next");
    await expect(page.locator("#home-row-1-tile-3 .tile-sub")).toHaveCount(0);
  });

  test("upcoming takes the top-left, and the top-right when a watched ✓ is not there to take it", async ({ page }) => {
    await boot(page, feed);
    await expect(page.locator("#home-row-1-tile-1 .badge-start.badge-upcoming")).toContainText("S03E01");
    await expect(page.locator("#home-row-1-tile-1 .badge-end")).toHaveCount(0);
    await expect(page.locator("#home-row-1-tile-2 .badge-start.badge-upcoming")).toContainText("S04E02");
    await expect(page.locator("#home-row-1-tile-2 .badge-end.badge-watched")).toHaveText("✓");
    await expect(page.locator("#home-row-1-tile-3 .tile-badge")).toHaveCount(0);
  });

  test("a row heading shows the seed's total, and a one-item row shows no count", async ({ page }) => {
    await boot(page, feed);
    const headings = page.locator("#shell-body .home-heading");
    await expect(headings.nth(0)).toContainText("Keep Watching");
    await expect(headings.nth(0).locator(".home-heading-count")).toHaveText("· 37");
    await expect(headings.nth(1).locator(".home-heading-count")).toHaveText("· 4");
    await expect(headings.nth(2)).toHaveText("Single");
    await expect(headings.nth(2).locator(".home-heading-count")).toHaveCount(0);
  });
});

// DEB-5690 (DEB-5685 FR-55) — a row that has more ends in See all, and the grid behind it is the row's whole list. The backend is
// stood in for by page.route, so this proves what the page asks for and what it does with the answer.
test.describe("Tizen a row's See all and its grid (DEB-5685 FR-55)", () => {
  const cards = (prefix: string, count: number) => Array.from({ length: count }, (_, i) => card(`${prefix}${i}`, `${prefix.toUpperCase()} ${i}`));
  const seed = { children: [{ kind: "cond", facet: "genre", values: ["drama"] }] };
  type Seen = { seeded: any[]; urls: string[] };

  /** Home (or the channel `forest`) with [rows]; the three reads a See all can make are answered with [whole]. */
  async function open(page: Page, rows: object[], answers: { seeded?: () => { status: number; body?: object } } = {}, channels = false): Promise<Seen> {
    const seen: Seen = { seeded: [], urls: [] };
    const json = (route: any, status: number, body: object = {}) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    const feed = (list: object[]) => ({ heroes: [], channels: [], rows: list, tile_shape: "POSTER", hero_height_pct: 48, focus_detail: "none" });
    await page.route("**/api/tv/browse/seeded", route => {
      seen.seeded.push(JSON.parse(route.request().postData()!));
      const answer = answers.seeded?.() ?? { status: 200, body: { items: cards("w", 12).map(c => ({ card: c })), total: 12 } };
      return json(route, answer.status, answer.body ?? {});
    });
    await page.route("**/api/tv/continue/all**", route => {
      seen.urls.push(route.request().url());
      return json(route, 200, cards("k", 10).map((c, i) => ({ ...c, progress_pct: 0.1 * (i + 1) })));
    });
    await page.route("**/api/tv/recommendations**", route => {
      seen.urls.push(route.request().url());
      return json(route, 200, { items: [card("r2", "Third Pick"), card("r0", "First Pick"), card("r1", "Second Pick")].map(c => ({ card: c })), total: 3 });
    });
    await page.route("**/api/tv/channel/forest", route => json(route, 200, feed(rows)));
    await page.route("**/api/tv/home", route => json(route, 200, { ...feed(rows), channels: channels ? [{ id: "forest", name: "Forest Stories", logo_url: null, style: "TEXT", brand_color: null }] : [] }));
    await page.addInitScript(() => {
      localStorage.setItem("ravilo.screen.serverUrl", "http://app:9505");
      localStorage.setItem("ravilo.screen.profiles", JSON.stringify([{ userId: "viewer", displayName: "Test Viewer", token: "test-token" }]));
    });
    await page.goto(SCREEN_URL);
    await expect(page.locator("#shell-body .home-feed")).toBeVisible();
    return seen;
  }

  const shelf = (id: string, count: number, extra: object = {}) => ({ id, title: `Shelf ${id}`, kind: "CUSTOM", items: cards(id, count), seedQuery: seed, ...extra });
  /** From the first tile of the first row, Right to the row's end. */
  const toEnd = async (page: Page, row: number, items: number) => {
    for (let i = 0; i < row; i++) await page.keyboard.press("ArrowDown");
    await expect(page.locator(`#home-row-${row}-tile-0`)).toHaveClass(/focused/);
    for (let i = 0; i < items; i++) await page.keyboard.press("ArrowRight");
    await expect(page.locator(`#home-row-${row}-see-all`)).toHaveClass(/focused/);
  };

  test("a seeded row of 9 ends in a See all tile of the row's size; one of 8 does not; it takes no image slot", async ({ page }) => {
    await open(page, [shelf("a", 9), shelf("b", 8)]);
    await expect(page.locator("#home-row-0-see-all")).toHaveText("→See all");
    await expect(page.locator("#home-row-0-see-all")).toHaveClass(/home-tile.*tile-poster|tile-poster.*home-tile/);
    await expect(page.locator("#shell-body .home-rail").nth(0).locator(".home-tile").last()).toHaveId("home-row-0-see-all");
    await expect(page.locator("#home-row-1-see-all")).toHaveCount(0);
    await expect(page.locator("#home-row-1-tile-7")).toHaveCount(1);
    await expect(page.locator("#home-row-0-see-all .tile-art")).toHaveCount(0);
    const box = (id: string) => page.locator(id).evaluate(el => ({ w: (el as HTMLElement).offsetWidth, h: (el.querySelector(".tile-art, .tile-see-all") as HTMLElement).offsetHeight }));
    expect(await box("#home-row-0-see-all")).toEqual(await box("#home-row-0-tile-0"));
  });

  test("it is a tile for focus: Right reaches it, it grows, and Left returns to the tile before it", async ({ page }) => {
    await open(page, [shelf("a", 9)]);
    await toEnd(page, 0, 9);
    await expect(page.locator("#shell-body .shell-target.focused")).toHaveCount(1);
    await expect.poll(() => page.locator("#home-row-0-see-all").evaluate(el => getComputedStyle(el).transform)).toMatch(/matrix\(1\.1/);
    await page.keyboard.press("ArrowLeft");
    await expect(page.locator("#home-row-0-tile-8")).toHaveClass(/focused/);
  });

  test("OK posts the seeded browse with the row's seed and draws the row's title, the count and the whole list; Back lands on the tile", async ({ page }) => {
    const seen = await open(page, [shelf("a", 9)]);
    await toEnd(page, 0, 9);
    await page.keyboard.press("Enter");
    await expect(page.locator(".browse-heading")).toHaveText("Shelf a");
    await expect(page.locator(".browse-count")).toHaveText("12 titles");
    await expect(page.locator(".browse-crumb")).toHaveCount(0);
    await expect(page.locator(".browse-grid .home-tile")).toHaveCount(12);
    await expect(page.locator(".browse-grid .tile-title").first()).toHaveText("W 0");          // no sort_by: the served order
    expect(seen.seeded).toHaveLength(1);
    expect(seen.seeded[0].query.children[0]).toMatchObject({ facet: "genre", values: ["drama"] });
    expect(seen.seeded[0].media_kind ?? null).toBeNull();
    await expect(page.locator("#browse-tile-0")).toHaveClass(/focused/);
    await expect(page.locator("#nav-home")).toHaveClass(/cur/);
    await page.keyboard.press("Escape");
    await expect(page.locator("#home-row-0-see-all")).toHaveClass(/focused/);
    await expect(page.locator("#shell-body .shell-target.focused")).toHaveCount(1);
  });

  test("the row's media kind is sent with its seed", async ({ page }) => {
    const seen = await open(page, [shelf("a", 9, { seedMediaKind: "SERIES" })]);
    await toEnd(page, 0, 9);
    await page.keyboard.press("Enter");
    await expect(page.locator(".browse-count")).toHaveText("12 titles");
    expect(seen.seeded[0].media_kind).toBe("SERIES");
  });

  test("a row with sort_by title opens A–Z", async ({ page }) => {
    const unsorted = { items: ["Charlie", "alpha", "Bravo"].map((t, i) => ({ card: card(`s${i}`, t) })), total: 3 };
    await open(page, [shelf("a", 9, { sort_by: "title", sort_descending: false })], { seeded: () => ({ status: 200, body: unsorted }) });
    await toEnd(page, 0, 9);
    await page.keyboard.press("Enter");
    await expect(page.locator(".browse-grid .tile-title")).toHaveText(["alpha", "Bravo", "Charlie"]);
    await expect(page.locator(".browse-count")).toHaveText("3 titles");
  });

  test("a Continue Watching row's tile reads /api/tv/continue/all, and its tiles draw FR-5's progress bar", async ({ page }) => {
    const seen = await open(page, [{ id: "continue", title: "Keep Watching", kind: "CONTINUE", items: cards("c", 9).map(c => ({ ...c, progress_pct: 0.3 })) }]);
    await toEnd(page, 0, 9);
    await page.keyboard.press("Enter");
    await expect(page.locator(".browse-heading")).toHaveText("Keep Watching");
    await expect(page.locator(".browse-count")).toHaveText("10 titles");
    await expect(page.locator(".browse-grid .tile-progress")).toHaveCount(10);
    expect(seen.seeded).toHaveLength(0);
    expect(seen.urls).toHaveLength(1);
    expect(new URL(seen.urls[0]).pathname).toBe("/api/tv/continue/all");
    expect(new URL(seen.urls[0]).search).toBe("");
  });

  test("a recommendations row reads /api/tv/recommendations and keeps the server's order", async ({ page }) => {
    const seen = await open(page, [{ id: "rec", title: "Recommended", kind: "CUSTOM", recommendations: true, seedTotalCount: 20, items: cards("r", 5) }]);
    await toEnd(page, 0, 5);
    await page.keyboard.press("Enter");
    await expect(page.locator(".browse-grid .tile-title")).toHaveText(["Third Pick", "First Pick", "Second Pick"]);
    await expect(page.locator(".browse-count")).toHaveText("3 titles");
    expect(seen.seeded).toHaveLength(0);
    expect(new URL(seen.urls[0]).pathname).toBe("/api/tv/recommendations");
    expect(new URL(seen.urls[0]).search).toBe("");
  });

  test("a recommendations row that already shows its whole list has no See all", async ({ page }) => {
    await open(page, [{ id: "rec", title: "Recommended", kind: "CUSTOM", recommendations: true, seedTotalCount: 5, items: cards("r", 5) }]);
    await expect(page.locator("#home-row-0-see-all")).toHaveCount(0);
  });

  test("on a channel page the Continue read carries the channel, and Back returns to the channel's row", async ({ page }) => {
    const seen = await open(page, [{ id: "continue", title: "Keep Watching", kind: "CONTINUE", items: cards("c", 9) }], {}, true);
    await expect(page.locator("#home-channel-0")).toHaveClass(/focused/);
    await page.keyboard.press("Enter");
    await expect(page.locator("#shell-body .home-heading")).toContainText(["Keep Watching"]);
    await toEnd(page, 0, 9);
    await page.keyboard.press("Enter");
    await expect(page.locator(".browse-count")).toHaveText("10 titles");
    expect(new URL(seen.urls[0]).search).toBe("?channel=forest");
    await page.keyboard.press("Escape");
    await expect(page.locator("#home-row-0-see-all")).toHaveClass(/focused/);
    await page.keyboard.press("Escape");
    await expect(page.locator("#home-channel-0")).toHaveClass(/focused/);
  });

  test("an empty answer says so; a failed read shows the failure and Retry asks again", async ({ page }) => {
    let reads = 0;
    const seen = await open(page, [shelf("a", 9)], { seeded: () => ++reads === 1 ? { status: 500 } : { status: 200, body: { items: [], total: 0 } } });
    await toEnd(page, 0, 9);
    await page.keyboard.press("Enter");
    await expect(page.locator(".load-failure")).toBeVisible();
    await expect(page.locator(".browse-heading")).toHaveText("Shelf a");
    await page.keyboard.press("Enter");
    await expect(page.locator(".browse-count")).toHaveText("0 titles");
    await expect(page.locator(".browse-empty")).toHaveText("No titles match these filters.");
    expect(seen.seeded).toHaveLength(2);
  });
});

// DEB-5695 (DEB-5685, FR-59) — Home stays current. The real bundle on Playwright's fake clock, the backend and its events socket stood in for
// by page.route and page.routeWebSocket: this proves what the app asks for and what it draws, not what the server pushes.
test.describe("Home stays current (DEB-5685 FR-59)", () => {
  const API = "http://app:9505";
  const feedOf = (...ids: string[]) => ({
    heroes: [], channels: [], tile_shape: "POSTER", hero_height_pct: 48, focus_detail: "none",
    rows: [{ id: "new", title: "New Arrivals", kind: "NEWLY_ADDED", items: ids.map((id) => card(id, `Title ${id}`)) }],
  });
  type Live = {
    home: object; homeStatuses: number[]; homeReads: number; configReads: number; starts: number; stops: number; opens: number[];
    push: (message: object) => void;
  };

  async function live(page: Page, opts: { closeSockets?: boolean } = {}): Promise<Live> {
    const sockets: any[] = [];
    const seen: Live = {
      home: feedOf("a", "b", "c"), homeStatuses: [], homeReads: 0, configReads: 0, starts: 0, stops: 0, opens: [],
      push: (message) => sockets.forEach((ws) => ws.send(JSON.stringify(message))),
    };
    await page.addInitScript(fakeAvPlayInitScript);
    await page.addInitScript(() => {
      localStorage.setItem("ravilo.screen.serverUrl", "http://app:9505");
      localStorage.setItem("ravilo.screen.profiles", JSON.stringify([{ userId: "viewer", displayName: "Test Viewer", token: "test-token" }]));
    });
    // When each events socket was opened, on the page's own (fake) clock.
    await page.routeWebSocket(/\/api\/tv\/events/, async (ws) => {
      sockets.push(ws);
      seen.opens.push(await page.evaluate(() => Date.now()));
      if (opts.closeSockets) setTimeout(() => ws.close(), 100);                                // opened, then cut
    });
    const json = (route: any, status: number, body: object = {}) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    await page.route(`${API}/**`, async (route) => {
      const req = route.request();
      const path = new URL(req.url()).pathname;
      if (req.method() === "OPTIONS") return route.fulfill({ status: 204 });
      if (path === "/api/tv/home") {
        seen.homeReads++;
        const status = seen.homeStatuses.shift() ?? 200;
        return status === 200 ? json(route, 200, seen.home) : json(route, status);
      }
      if (path === "/api/tv/config") { seen.configReads++; return json(route, 200, {}); }
      if (path === "/api/tv/config/rev") return json(route, 200, { rev: 1 });
      if (path === "/api/tv/playstate") return json(route, 200, {});
      if (path.startsWith("/api/tv/movie/")) {
        const id = path.split("/").pop()!;
        return json(route, 200, { card: card(id, `Title ${id}`), synopsis: "x", runtime: 90, cast: [], related: [] });
      }
      if (path === "/api/tv/playback/start") {
        seen.starts++;
        const id = JSON.parse(req.postData()!).item_id;
        return json(route, 200, { jellyfin_base_url: API, access_token: "", item_id: id, container: "ts", direct_play: false,
          hls_url: `${API}/hls/${id}/master.m3u8`, start_position_ms: 0, expires_at: 9_999_999_999 });
      }
      if (path === "/api/tv/playback/stop") { seen.stops++; return route.fulfill({ status: 204 }); }
      if (path.startsWith("/api/tv/")) return route.fulfill({ status: 204 });
      return json(route, 404);
    });
    await page.clock.install();
    await page.goto(SCREEN_URL);
    await expect(page.locator("#shell-body .home-feed")).toBeVisible();
    await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
    return seen;
  }

  const titles = (page: Page) => page.locator("#shell-body .home-tile .tile-title");
  /** Counts what is added to the page from here on: a silent redraw draws its tiles, but never a skeleton and never the 220 ms fade. */
  const watchLoads = (page: Page) => page.evaluate(() => {
    (window as any).__loads = 0;
    new MutationObserver((records) => records.forEach((r) => r.addedNodes.forEach((n) => {
      if (n instanceof HTMLElement && (n.matches(".skeleton, .arrive, .load-failure") || n.querySelector(".skeleton, .arrive, .load-failure"))) (window as any).__loads++;
    }))).observe(document.getElementById("shell-body")!, { childList: true, subtree: true });
  });
  const loads = (page: Page) => page.evaluate(() => (window as any).__loads as number);

  test("a home_changed push reads Home again and draws the new tile with focus on the same media id", async ({ page }) => {
    const seen = await live(page);
    await page.keyboard.press("ArrowRight");
    await expect(page.locator("#home-row-0-tile-1")).toHaveClass(/focused/);          // "b"
    await watchLoads(page);
    seen.home = feedOf("x", "a", "b", "c");
    seen.push({ type: "home_changed", rev: 1 });
    await page.clock.runFor(1_100);
    await expect.poll(() => seen.homeReads).toBe(2);
    await expect(titles(page)).toHaveText(["Title x", "Title a", "Title b", "Title c"]);
    await expect(page.locator("#shell-body .focused")).toHaveAttribute("data-media-id", "b");
    await expect(page.locator("#shell-body .focused")).toHaveId("home-row-0-tile-2");
    expect(await loads(page), "a redraw, not a load: no skeleton, no fade").toBe(0);
  });

  test("the focused tile goes: its index in the row; the row goes: the first tile of the row now there", async ({ page }) => {
    const seen = await live(page);
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("ArrowRight");
    await expect(page.locator("#home-row-0-tile-2")).toHaveClass(/focused/);          // "c"
    seen.home = feedOf("a", "b");                                                      // c is gone
    seen.push({ type: "home_changed", rev: 1 });
    await page.clock.runFor(1_100);
    await expect.poll(() => seen.homeReads).toBe(2);
    await expect(titles(page)).toHaveText(["Title a", "Title b"]);
    await expect(page.locator("#shell-body .focused")).toHaveId("home-row-0-tile-1");  // index 2 -> the row's last
    seen.home = { ...feedOf("p"), rows: [{ id: "other", title: "Other", kind: "CUSTOM", items: [card("q", "Title q"), card("r", "Title r")] }] };
    seen.push({ type: "playstate_changed", patch: {} });
    await page.clock.runFor(1_100);
    await expect.poll(() => seen.homeReads).toBe(3);
    await expect(titles(page)).toHaveText(["Title q", "Title r"]);
    await expect(page.locator("#shell-body .focused")).toHaveId("home-row-0-tile-0");  // row "new" went: the first tile of the row now there
  });

  test("the rail's scroll, the row's focus memory and the hero slide stay where they were", async ({ page }) => {
    const hero = (id: string) => ({ item: card(id, `Hero ${id}`), tagline_kicker: null, backdrop_url: `/api/tv/image/${id}-backdrop`, logo_url: null, badge: null, synopsis: "s" });
    const ids = Array.from({ length: 14 }, (_, i) => `t${i}`);
    const seen = await live(page);
    seen.home = { ...feedOf(...ids), heroes: [hero("h1"), hero("h2")] };
    await page.reload();
    await expect(page.locator("#shell-body .home-feed")).toBeVisible();
    await expect(page.locator("#home-hero")).toHaveClass(/focused/);
    await page.keyboard.press("ArrowRight");                                           // the hero's second slide
    await expect(page.locator("#shell-body .hero-slide").nth(1)).toHaveClass(/\bon\b/);
    await page.keyboard.press("ArrowDown");                                            // into the row
    for (let i = 0; i < 9; i++) await page.keyboard.press("ArrowRight");
    await expect(page.locator("#home-row-0-tile-9")).toHaveClass(/focused/);
    await page.keyboard.press("ArrowUp");                                              // away and back: the row remembers its tile
    await page.keyboard.press("ArrowDown");
    await expect(page.locator("#home-row-0-tile-9")).toHaveClass(/focused/);
    await page.keyboard.press("ArrowUp");                                              // focus rests on the hero; the row's memory is tile 9
    const track = page.locator("#shell-body .rail-track").last();
    const tx = await track.getAttribute("data-tx");
    expect(Number(tx), "the rail has slid").toBeLessThan(0);
    await watchLoads(page);
    seen.home = { ...feedOf(...ids.map((id) => id === "t13" ? "t13b" : id)), heroes: [hero("h1"), hero("h2")] };   // the last tile changed
    const reads = seen.homeReads;
    seen.push({ type: "home_changed", rev: 1 });
    await page.clock.runFor(1_100);
    await expect.poll(() => seen.homeReads).toBe(reads + 1);
    await expect(page.locator("#home-row-0-tile-13 .tile-title")).toHaveText("Title t13b");
    await expect(page.locator("#shell-body .hero-slide").nth(1)).toHaveClass(/\bon\b/);   // the slide stayed
    expect(await page.locator("#shell-body .rail-track").last().getAttribute("data-tx")).toBe(tx);
    await expect(page.locator("#home-hero")).toHaveClass(/focused/);
    await page.keyboard.press("ArrowDown");
    await expect(page.locator("#home-row-0-tile-9")).toHaveClass(/focused/);          // the row's memory survived
    expect(await loads(page)).toBe(0);
  });

  test("several triggers inside one second make one read; an answer equal to what is drawn draws nothing", async ({ page }) => {
    const seen = await live(page);
    await watchLoads(page);
    await page.evaluate(() => {
      (window as any).__draws = 0;
      new MutationObserver((records) => records.forEach((r) => { (window as any).__draws += r.addedNodes.length; }))
        .observe(document.getElementById("shell-body")!, { childList: true, subtree: true });
    });
    seen.push({ type: "home_changed", rev: 1 });
    seen.push({ type: "playstate_changed", patch: {} });
    seen.push({ type: "home_changed", rev: 2 });
    await page.clock.runFor(1_100);
    await expect.poll(() => seen.homeReads).toBe(2);
    await page.clock.runFor(3_000);
    expect(seen.homeReads).toBe(2);
    expect(await page.evaluate(() => (window as any).__draws as number), "the same feed is not drawn again").toBe(0);
  });

  test("a re-read that fails leaves Home as it is and shows nothing", async ({ page }) => {
    const seen = await live(page);
    await watchLoads(page);
    seen.homeStatuses = [500];
    seen.push({ type: "home_changed", rev: 1 });
    await page.clock.runFor(1_100);
    await expect.poll(() => seen.homeReads).toBe(2);
    await page.clock.runFor(6_000);                                                    // no quiet retry either: Home is drawn
    expect(seen.homeReads).toBe(2);
    await expect(titles(page)).toHaveText(["Title a", "Title b", "Title c"]);
    await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
    expect(await loads(page)).toBe(0);
  });

  test("Back from the player onto Home reads it again", async ({ page }) => {
    const seen = await live(page);
    seen.push({ type: "play_item", jellyfin_id: "a", kind: "movie", title: "Title a" });
    await expect.poll(() => seen.starts).toBe(1);
    await expect(page.locator("#shell")).not.toHaveClass(/\bon\b/);                    // the player covers Home
    const before = seen.homeReads;
    seen.home = feedOf("b", "a", "c");                                                 // Continue Watching moved
    await page.keyboard.press("Escape");
    await expect.poll(() => seen.stops).toBe(1);
    await expect(page.locator("#shell")).toHaveClass(/\bon\b/);
    await page.clock.runFor(1_100);
    await expect.poll(() => seen.homeReads).toBe(before + 1);
    await expect(titles(page)).toHaveText(["Title b", "Title a", "Title c"]);
  });

  test("a Home under a title's page is not read until it shows", async ({ page }) => {
    const seen = await live(page);
    await page.keyboard.press("Enter");                                                // "a"'s page
    await expect(page.locator("#shell-body .detail")).toBeVisible();
    const before = seen.homeReads;
    seen.home = feedOf("n", "a", "b", "c");
    seen.push({ type: "home_changed", rev: 1 });
    await page.clock.runFor(2_100);
    expect(seen.homeReads, "covered: not read").toBe(before);
    await page.keyboard.press("Escape");
    await expect(page.locator("#shell-body .home-feed")).toBeVisible();
    await page.clock.runFor(1_100);
    await expect.poll(() => seen.homeReads).toBe(before + 1);
    await expect(titles(page)).toHaveText(["Title n", "Title a", "Title b", "Title c"]);
  });

  test("a Home that failed is asked again every 5 s and draws with no key pressed, focus on its first target", async ({ page }) => {
    const seen = { home: feedOf("a", "b"), statuses: [500] };
    await page.addInitScript(() => {
      localStorage.setItem("ravilo.screen.serverUrl", "http://app:9505");
      localStorage.setItem("ravilo.screen.profiles", JSON.stringify([{ userId: "viewer", displayName: "Test Viewer", token: "test-token" }]));
    });
    let reads = 0;
    await page.routeWebSocket(/\/api\/tv\/events/, () => {});
    await page.route(`${API}/**`, async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (route.request().method() === "OPTIONS") return route.fulfill({ status: 204 });
      if (path === "/api/tv/home") {
        reads++;
        const status = seen.statuses.shift() ?? 200;
        return route.fulfill({ status, contentType: "application/json", body: status === 200 ? JSON.stringify(seen.home) : "{}" });
      }
      return route.fulfill({ status: 204 });
    });
    await page.clock.install();
    await page.goto(SCREEN_URL);
    await expect(page.locator("#shell-body .load-failure")).toBeVisible();
    expect(reads).toBe(1);
    await page.clock.runFor(4_000);
    expect(reads, "not before 5 s").toBe(1);
    await page.clock.runFor(1_200);
    await expect.poll(() => reads).toBe(2);
    await expect(page.locator("#shell-body .home-feed")).toBeVisible();
    await expect(page.locator("#shell-body .load-failure")).toHaveCount(0);
    await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
  });

  test("a socket closed right after each open reconnects at growing delays, not every 2 s", async ({ page }) => {
    const seen = await live(page, { closeSockets: true });
    for (let i = 0; i < 160; i++) { await page.clock.runFor(250); await page.waitForTimeout(25); }   // 40 s of the page's time
    const opens = seen.opens;
    const gaps = opens.slice(1).map((t, i) => t - opens[i]);
    expect(gaps.length).toBeGreaterThanOrEqual(4);
    // R293: 1 s, 2 s, 4 s, 8 s, each ± 20 %, read off a loop that steps the page's clock 250 ms at a time.
    expect(gaps[0], `gaps ${gaps}`).toBeLessThan(2_500);
    expect(gaps[2], `gaps ${gaps}`).toBeGreaterThan(gaps[0]);
    expect(gaps[3], `gaps ${gaps}`).toBeGreaterThan(gaps[1]);
    expect(gaps[3], `gaps ${gaps}`).toBeGreaterThan(5_500);                            // never the old flat 2 s
  });

  test("a config_changed push reads the config again", async ({ page }) => {
    const seen = await live(page);
    await expect.poll(() => seen.configReads).toBeGreaterThanOrEqual(1);              // the start-up pull
    const before = seen.configReads;
    seen.push({ type: "config_changed", rev: 2 });
    await expect.poll(() => seen.configReads).toBe(before + 1);
  });
});
