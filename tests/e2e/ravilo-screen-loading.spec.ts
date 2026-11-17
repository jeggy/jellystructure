import { test, expect, type Page, type Locator } from "@playwright/test";

// DEB-4893 (DEB-4889 FR-20) — every page on the Tizen full app that loads draws a skeleton, never a word. The mock's answer is held
// back so the skeleton is on screen: it must hold no text, carry the focus ring on its focus block, and pulse as ONE element. Released,
// the content draws, focus moves to the real target and the skeleton is gone. A failed load shows the failure and Retry instead.
// 1920x1080 is the panel the sizes below are drawn for.

const SCREEN_URL = process.env.RAVILO_SCREEN_CAST_URL ?? "http://localhost:8092";
test.use({ viewport: { width: 1920, height: 1080 } });

const card = (id: string, title: string, kind = "MOVIE") => ({
  id, kind, title, year: 2024, genre: null, rating: null,
  poster_url: `/api/tv/image/${id}-poster`, backdrop_url: `/api/tv/image/${id}-backdrop`,
});
const feed = {
  heroes: [{ item: card("hero-a", "Harbor Lights"), tagline_kicker: "Featured", backdrop_url: "/api/tv/image/hero-a-backdrop", logo_url: null, badge: null, synopsis: "A voyage begins." }],
  channels: [{ id: "forest", name: "Forest Stories", logo_url: null, style: "TEXT", brand_color: null }],
  rows: [{ id: "new", title: "New Arrivals", kind: "NEWLY_ADDED", items: [card("new-a", "Paper Moon")] }],
  tile_shape: "POSTER", hero_height_pct: 48, focus_detail: "none",
};
const movie = {
  card: card("m1", "Paper Moon"), synopsis: "A con artist and a girl cross the plains.", runtime: 90, cast: [], related: [], logo_url: null,
  rating_badge: null, segments: null,
};
const series = {
  card: card("sr1", "Paper Series", "SERIES"), synopsis: "Two seasons of reels.", cast: [], related: [], logo_url: null,
  seasons: [{ index: 1, name: "Season 1", episodes: [{ id: "s1e1", episode_number: 1, title: "Opening Reel", runtime: 40, overview: null, still_url: null }] }],
};

const json = (route: any, status: number, body: object = {}) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });

/** A gate a route handler waits on, so the page is held in its loading state until [release]. */
function gate() {
  let release!: () => void;
  const open = new Promise<void>((resolve) => { release = resolve; });
  return { open, release };
}

type Handler = (route: any) => Promise<unknown> | unknown;
/** One router for the backend: an exact path, else a prefix match for /api/tv/image/ (204) and 404 for everything else. */
async function mock(page: Page, routes: Record<string, Handler>) {
  await page.route("http://app:9505/**", async (route) => {
    const req = route.request();
    if (req.method() === "OPTIONS") return route.fulfill({ status: 204 });
    const path = new URL(req.url()).pathname;
    const handler = routes[path];
    if (handler) return handler(route);
    if (path.startsWith("/api/tv/image/")) return route.fulfill({ status: 204 });
    return json(route, 404);
  });
}

async function start(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem("ravilo.screen.serverUrl", "http://app:9505");
    localStorage.setItem("ravilo.screen.profiles", JSON.stringify([{ userId: "viewer", displayName: "Test Viewer", token: "test-token" }]));
  });
  await page.goto(SCREEN_URL);
  await expect(page.locator("#shell")).toHaveClass(/\bon\b/);
}

const box = async (l: Locator) => (await l.boundingBox())!;
const near = (actual: number, expected: number, tolerance = 1.5) => expect(Math.abs(actual - expected)).toBeLessThanOrEqual(tolerance);
const animationOf = (l: Locator) => l.evaluate((el) => { const s = getComputedStyle(el); return { name: s.animationName, duration: s.animationDuration }; });

/** The whole skeleton is one animated element: nothing inside it animates on its own (FR-14). */
async function expectOnePulsingLayer(skeleton: Locator) {
  expect(await animationOf(skeleton)).toEqual({ name: "skel-pulse", duration: "1.2s" });
  expect(await skeleton.evaluate((el) => Array.from(el.querySelectorAll("*")).filter((e) => getComputedStyle(e).animationName !== "none").length)).toBe(0);
}

test.describe("DEB-4893 — Home and a channel's feed (FR-20)", () => {
  test("Home draws a hero block and two rows of poster blocks, no words, the ring on the hero; the feed replaces it with a fade", async ({ page }) => {
    const held = gate();
    await mock(page, {
      "/api/tv/home": async (route) => { await held.open; await json(route, 200, feed); },
    });
    await start(page);

    const skeleton = page.locator("#shell-body > .skeleton-home");
    await expect(skeleton).toBeVisible();
    expect(await skeleton.evaluate((el) => (el as HTMLElement).innerText.trim())).toBe("");
    expect(await skeleton.evaluate((el) => el.textContent)).toBe("");
    await expect(page.locator(".shell-placeholder")).toHaveCount(0);
    await expectOnePulsingLayer(skeleton);

    const hero = page.locator("#shell-page-target");
    await expect(hero).toHaveClass(/focused/);                          // the ring is on the focus block
    await expect(hero).toHaveClass(/skel-hero/);
    const body = await box(page.locator("#shell-body"));
    const heroBox = await box(hero);
    near(heroBox.height, 1080 * 0.56);                                   // 56 vh
    near(heroBox.width, body.width - 144);                               // the full width of the page
    expect(await hero.evaluate((el) => getComputedStyle(el).backgroundColor)).toBe("rgb(22, 25, 34)");   // #161922

    await expect(skeleton.locator(".home-section")).toHaveCount(2);
    for (const section of await skeleton.locator(".home-section").all()) {
      const bar = await box(section.locator(".skel-heading"));
      near(bar.width, 240); near(bar.height, 26);
      const art = await box(section.locator(".tile-art").first());
      near(art.width, 176); near(art.height, 265);
      expect(await section.locator(".tile-art").first().evaluate((el) => getComputedStyle(el).borderTopLeftRadius)).toBe("11px");
      const caption = await box(section.locator(".skel-caption").first());
      near(caption.width, 130); near(caption.height, 18);
      expect(await section.locator(".home-tile").count()).toBeGreaterThanOrEqual(9);   // as many as fill the width
    }

    held.release();
    await expect(page.locator("#shell-body .home-feed")).toBeVisible();
    await expect(page.locator(".skeleton")).toHaveCount(0);              // removed in the same frame the content drew
    await expect(page.locator("#home-hero-0")).toHaveClass(/focused/);   // focus is on the real target
    expect((await animationOf(page.locator("#shell-body .home-feed"))).name).toBe("arrive-fade");
    expect((await animationOf(page.locator("#shell-body .home-feed"))).duration).toBe("0.22s");
  });

  test("Back to a Home the route already holds does not fade it in again", async ({ page }) => {
    await mock(page, {
      "/api/tv/home": (route) => json(route, 200, feed),
      "/api/tv/movie/new-a": (route) => json(route, 404),
    });
    await start(page);
    await expect(page.locator("#home-hero-0")).toHaveClass(/focused/);
    await page.keyboard.press("ArrowDown"); await page.keyboard.press("ArrowDown");
    await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
    await page.keyboard.press("Enter");
    await expect(page.locator("#shell-body .shell-placeholder")).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
    expect((await animationOf(page.locator("#shell-body .home-feed"))).name).toBe("none");
  });

  test("a hero's backdrop fades in over its surface block once it loads", async ({ page }) => {
    const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==", "base64");
    const image = gate();
    await mock(page, {
      "/api/tv/home": (route) => json(route, 200, feed),
      "/api/tv/image/hero-a-backdrop": async (route) => { await image.open; await route.fulfill({ status: 200, contentType: "image/png", body: png }); },
    });
    await start(page);
    const img = page.locator("#home-hero-0 img").first();
    await expect(img).toHaveClass(/img-fade/);
    expect(await img.evaluate((el) => getComputedStyle(el).opacity)).toBe("0");      // the surface block shows, never a bare black area
    expect(await page.locator("#home-hero-0").evaluate((el) => getComputedStyle(el).backgroundColor)).toBe("rgb(22, 25, 34)");
    image.release();
    await expect(img).toHaveClass(/loaded/);
    await expect.poll(() => img.evaluate((el) => getComputedStyle(el).opacity)).toBe("1");
    expect(await img.evaluate((el) => getComputedStyle(el).transitionDuration)).toBe("0.22s");
  });

  test("a feed that fails shows the failure and Retry in the page's type, and Retry loads it under the skeleton", async ({ page }) => {
    let calls = 0;
    const second = gate();
    await mock(page, {
      "/api/tv/home": async (route) => {
      calls++;
      if (calls === 1) return json(route, 500);
      await second.open;
      return json(route, 200, feed);
    },
    });
    await start(page);

    const failure = page.locator("#shell-body .load-failure");
    await expect(failure).toBeVisible();
    await expect(page.locator(".skeleton")).toHaveCount(0);               // the skeleton never pulses forever
    await expect(page.locator(".shell-placeholder")).toHaveCount(0);      // never the 48 px bare line
    await expect(failure.locator(".load-failure-title")).toHaveText("Couldn't reach the server");
    await expect(failure.locator(".load-failure-body")).toHaveText("Check the connection and try again.");
    expect(await failure.locator(".load-failure-title").evaluate((el) => getComputedStyle(el).fontSize)).not.toBe("48px");
    const retry = page.locator("#shell-page-target");
    await expect(retry).toHaveText("Retry");
    await expect(retry).toHaveClass(/focused/);
    expect(calls).toBe(1);                                                // a failed page does not retry by itself

    await page.keyboard.press("Enter");
    await expect(page.locator("#shell-body > .skeleton-home")).toBeVisible();   // loads again under the skeleton
    second.release();
    await expect(page.locator("#shell-body .home-feed")).toBeVisible();
    expect(calls).toBe(2);
  });

  test("a channel's feed loads under the same skeleton", async ({ page }) => {
    const held = gate();
    await mock(page, {
      "/api/tv/home": (route) => json(route, 200, feed),
      "/api/tv/channel/forest": async (route) => { await held.open; await json(route, 200, { ...feed, heroes: [], channels: [] }); },
    });
    await start(page);
    await expect(page.locator("#home-hero-0")).toHaveClass(/focused/);
    await page.keyboard.press("ArrowDown");
    await expect(page.locator("#home-channel-0")).toHaveClass(/focused/);
    await page.keyboard.press("Enter");
    const skeleton = page.locator("#shell-body > .skeleton-home");
    await expect(skeleton).toBeVisible();
    expect(await skeleton.evaluate((el) => el.textContent)).toBe("");
    await expect(page.locator("#shell-page-target")).toHaveClass(/focused/);
    held.release();
    await expect(page.locator("#shell-body .home-feed")).toBeVisible();
    await expect(page.locator(".skeleton")).toHaveCount(0);
  });
});

test.describe("DEB-4893 — a title's page (FR-20)", () => {
  async function openTile(page: Page, kind: "movie" | "series", answer: (route: any) => Promise<void>) {
    const tile = kind === "movie" ? card("m1", "Paper Moon") : card("sr1", "Paper Series", "SERIES");
    await mock(page, {
      "/api/tv/home": (route) => json(route, 200, { ...feed, heroes: [], channels: [], rows: [{ id: "new", title: "New", kind: "NEWLY_ADDED", items: [tile] }] }),
      [`/api/tv/${kind}/${tile.id}`]: answer,
      "/api/tv/playstate": (route) => json(route, 200, {}),
      "/api/tv/favorite": (route) => json(route, 200, { resume_ms: 0, played: false, played_pct: 0, favorite: true }),
    });
    await start(page);
    await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
    await page.keyboard.press("Enter");
  }

  test("a movie's page draws the backdrop, the header bars and two button blocks, no words; the page replaces it with a fade", async ({ page }) => {
    const held = gate();
    await openTile(page, "movie", async (route) => { await held.open; await json(route, 200, movie); });

    const skeleton = page.locator("#shell-body > .skeleton-detail");
    await expect(skeleton).toBeVisible();
    expect(await skeleton.evaluate((el) => el.textContent)).toBe("");
    await expect(page.locator(".shell-placeholder")).toHaveCount(0);
    await expectOnePulsingLayer(skeleton);
    const backdrop = skeleton.locator(".detail-backdrop");
    expect(await backdrop.evaluate((el) => getComputedStyle(el).backgroundColor)).toBe("rgb(22, 25, 34)");
    const title = await box(skeleton.locator(".skel-title")); near(title.width, 560); near(title.height, 56);
    const meta = await box(skeleton.locator(".skel-meta")); near(meta.width, 320); near(meta.height, 22);
    const column = await box(skeleton.locator(".skel-overview"));
    const [long, short] = [await box(skeleton.locator(".skel-line-long")), await box(skeleton.locator(".skel-line-short"))];
    near(long.height, 21); near(short.height, 21);
    near(long.width / column.width, 0.8, 0.01); near(short.width / column.width, 0.6, 0.01);
    near(column.width / (await box(page.locator("#shell-body"))).width, 0.62, 0.06);
    const primary = page.locator("#shell-page-target");
    await expect(primary).toHaveClass(/focused/);                        // the ring is on the primary button's block
    const second = await box(skeleton.locator(".skel-btn-second"));
    const first = await box(primary);
    near(first.width, 240); near(second.width, 200);
    const skeletonButtonHeight = first.height;

    held.release();
    await expect(page.locator("#detail-play")).toHaveClass(/focused/);   // focus moved to the real target
    await expect(page.locator(".skeleton")).toHaveCount(0);
    near((await box(page.locator("#detail-play"))).height, skeletonButtonHeight);   // the block is the .detail-btn's height
    expect((await animationOf(page.locator("#shell-body .detail"))).name).toBe("arrive-fade");

    // A redraw after a write-through is drawn from what the page holds: no fade.
    await page.keyboard.press("ArrowRight");
    await expect(page.locator("#detail-list")).toHaveClass(/focused/);
    await page.keyboard.press("Enter");
    await expect(page.locator("#detail-list")).toHaveText("− My List");
    expect((await animationOf(page.locator("#shell-body .detail"))).name).toBe("none");
  });

  test("a series gets the same skeleton, with no episode strip", async ({ page }) => {
    const held = gate();
    await openTile(page, "series", async (route) => { await held.open; await json(route, 200, series); });
    const skeleton = page.locator("#shell-body > .skeleton-detail");
    await expect(skeleton).toBeVisible();
    await expect(skeleton.locator(".detail-episodes")).toHaveCount(0);
    expect(await skeleton.evaluate((el) => el.textContent)).toBe("");
    held.release();
    await expect(page.locator("#detail-play")).toHaveClass(/focused/);
    await expect(page.locator(".detail-episodes")).toBeVisible();
    await expect(page.locator(".skeleton")).toHaveCount(0);
  });

  test("a season switch redraws the page without a fade", async ({ page }) => {
    const twoSeasons = { ...series, seasons: [...series.seasons, { index: 2, name: "Season 2", episodes: [{ id: "s2e1", episode_number: 1, title: "Return Reel", runtime: 40, overview: null, still_url: null }] }] };
    await openTile(page, "series", async (route) => { await json(route, 200, twoSeasons); });
    await expect(page.locator("#detail-play")).toHaveClass(/focused/);
    await page.keyboard.press("ArrowDown");
    await expect(page.locator("#detail-season-1")).toHaveClass(/focused/);
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("Enter");
    await expect(page.locator("#detail-season-2")).toHaveClass(/cur/);
    expect((await animationOf(page.locator("#shell-body .detail"))).name).toBe("none");
  });

  test("a page that fails keeps DEB-4660's line and the skeleton is gone", async ({ page }) => {
    await openTile(page, "movie", async (route) => { await json(route, 404); });
    await expect(page.locator("#shell-body .shell-placeholder")).toHaveText("This isn't here any more");
    await expect(page.locator(".skeleton")).toHaveCount(0);
  });
});

test.describe("DEB-4893 — the grids (FR-20)", () => {
  async function openMovies(page: Page, answer: (route: any) => Promise<void>) {
    await mock(page, {
      "/api/tv/home": (route) => json(route, 200, { ...feed, heroes: [], channels: [], rows: [] }),
      "/api/tv/browse": answer,
    });
    await start(page);
    await page.keyboard.press("ArrowUp");
    for (let i = 0; i < 6; i++) await page.keyboard.press("ArrowLeft");
    await page.keyboard.press("ArrowRight");
    await expect(page.locator("#nav-movies")).toHaveClass(/focused/);
    await page.keyboard.press("Enter");
  }
  const items = (n: number) => Array.from({ length: n }, (_, i) => card(`g${i}`, `Story ${i}`));

  test("the heading stays; under it a count bar and six columns of poster blocks, no words; the grid replaces it with a fade", async ({ page }) => {
    const held = gate();
    await openMovies(page, async (route) => { await held.open; await json(route, 200, { query: "", items: items(12), total: 12 }); });

    await expect(page.locator("#shell-body .browse-heading")).toHaveText("Movies");   // the page's real title is not a stand-in
    const skeleton = page.locator("#shell-body .skeleton-grid");
    await expect(skeleton).toBeVisible();
    expect(await skeleton.evaluate((el) => el.textContent)).toBe("");
    await expect(page.locator(".shell-placeholder")).toHaveCount(0);
    await expectOnePulsingLayer(skeleton);
    const count = await box(skeleton.locator(".skel-count")); near(count.width, 200); near(count.height, 22);
    const tiles = await skeleton.locator(".home-tile").all();
    const lefts = new Set<number>();
    for (const tile of tiles.slice(0, 6)) lefts.add(Math.round((await box(tile.locator(".tile-art"))).x));
    expect(lefts.size).toBe(6);                                           // six columns
    expect(tiles.length).toBeGreaterThanOrEqual(12);                      // as many rows as fill the screen
    const first = page.locator("#shell-page-target");
    await expect(first).toHaveClass(/focused/);                          // the ring is on the first poster block
    await expect(first).toHaveClass(/home-tile/);

    held.release();
    await expect(page.locator("#browse-tile-0")).toHaveClass(/focused/);
    await expect(page.locator(".skeleton")).toHaveCount(0);
    await expect(page.locator("#shell-body .browse-heading")).toHaveText("Movies");
    expect((await animationOf(page.locator("#shell-body .browse-grid"))).name).toBe("arrive-fade");
  });

  test("a first page that fails shows the failure and Retry, and Retry loads the grid", async ({ page }) => {
    let calls = 0;
    await openMovies(page, async (route) => {
      calls++;
      if (calls === 1) return json(route, 500);
      return json(route, 200, { query: "", items: items(3), total: 3 });
    });
    await expect(page.locator("#shell-body .load-failure-title")).toHaveText("Couldn't reach the server");
    await expect(page.locator("#shell-body .browse-heading")).toHaveText("Movies");
    await expect(page.locator(".skeleton")).toHaveCount(0);
    await expect(page.locator("#shell-page-target")).toHaveText("Retry");
    await expect(page.locator("#shell-page-target")).toHaveClass(/focused/);
    expect(calls).toBe(1);
    await page.keyboard.press("Enter");
    await expect(page.locator(".browse-grid .tile-title")).toHaveText(["Story 0", "Story 1", "Story 2"]);
    expect(calls).toBe(2);
  });
});
