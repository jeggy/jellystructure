import { test, expect, type Page } from "@playwright/test";

// DEB-5631 (DEB-5621 FR-49) — OK on a face on a title's page opens that person's titles: Android TV's openPersonBrowse. The backend
// is stood in for by page.route, so this proves what the page asks for (the seeded browse with the cast_crew condition) and what it
// does with the answer (header, grid, a tile opens its title, Back lands on the same face), not what the server says.

const SCREEN_URL = process.env.RAVILO_SCREEN_CAST_URL ?? "http://localhost:8092";
const API = "http://app:9505";

const card = (id: string, title: string) => ({
  id, kind: "MOVIE", title, year: 2024, genre: null, rating: null,
  poster_url: `/api/tv/image/${id}-poster`, backdrop_url: null,
});
const detailOf = (id: string, title: string) => ({
  card: card(id, title), synopsis: "A story.", runtime: 90, related: [], logo_url: null,
  cast: [
    { id: "101", name: "Ada Quill", role: "Lead", image_url: null },
    { id: "102", name: "Bo Marsh", role: "Director", image_url: null },
  ],
});

type Seen = { seeded: any[] };
type Opts = { seeded?: (n: number) => { status: number; body?: object } };

async function boot(page: Page, opts: Opts = {}): Promise<Seen> {
  const seen: Seen = { seeded: [] };
  await page.routeWebSocket(/\/api\/tv\/events/, () => {});
  const feed = { heroes: [], channels: [], rows: [{ id: "new", title: "New Arrivals", kind: "NEWLY_ADDED", items: [card("m1", "Paper Moon")] }],
    tile_shape: "POSTER", hero_height_pct: 48, focus_detail: "none" };
  await page.addInitScript(() => {
    localStorage.setItem("ravilo.screen.serverUrl", "http://app:9505");
    localStorage.setItem("ravilo.screen.profiles", JSON.stringify([{ userId: "viewer", displayName: "Test Viewer", token: "test-token" }]));
  });
  const json = (route: any, status: number, body: object = {}) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
  await page.route(`${API}/**`, async (route) => {
    const req = route.request();
    const path = new URL(req.url()).pathname;
    if (req.method() === "OPTIONS") return route.fulfill({ status: 204 });
    if (path === "/api/tv/home") return json(route, 200, feed);
    if (path === "/api/tv/movie/m1") return json(route, 200, detailOf("m1", "Paper Moon"));
    if (path === "/api/tv/movie/m2") return json(route, 200, detailOf("m2", "Second Feature"));
    if (path === "/api/tv/playstate") return json(route, 200, {});
    if (path === "/api/tv/browse/seeded") {
      seen.seeded.push(JSON.parse(req.postData()!));
      const answer = opts.seeded?.(seen.seeded.length) ?? {
        status: 200, body: { items: [{ card: card("m2", "Second Feature") }, { card: card("m3", "Third Feature") }], total: 2 },
      };
      return json(route, answer.status, answer.body ?? {});
    }
    if (path.startsWith("/api/tv/image/")) return route.fulfill({ status: 204 });
    if (path.startsWith("/api/tv/playback/")) return route.fulfill({ status: 204 });
    return json(route, 404);
  });
  await page.goto(SCREEN_URL);
  await expect(page.locator("#shell-body .home-feed")).toBeVisible();
  return seen;
}

/** Home → the title's page → the second face focused. */
const toFace = async (page: Page) => {
  await expect(page.locator("#home-row-0-tile-0")).toHaveClass(/focused/);
  await page.keyboard.press("Enter");
  await expect(page.locator("#detail-play")).toHaveClass(/focused/);
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("ArrowRight");
  await expect(page.locator("#detail-cast-1")).toHaveClass(/focused/);
};

test.describe("DEB-5631 FR-49 — a face opens the person's titles", () => {
  test("OK on a face posts the seeded browse with the cast_crew condition and no media kind", async ({ page }) => {
    const seen = await boot(page);
    await toFace(page);
    await page.keyboard.press("Enter");
    await expect(page.locator(".browse-grid .tile-title")).toHaveText(["Second Feature", "Third Feature"]);
    expect(seen.seeded).toHaveLength(1);
    expect(seen.seeded[0].media_kind ?? null).toBeNull();
    const [condition] = seen.seeded[0].query.children;
    // The shared client omits `op` when it is the default, is_any_of: the same body Android TV sends.
    expect(condition).toMatchObject({ facet: "cast_crew", values: ["102"] });
    expect(condition.op ?? "is_any_of").toBe("is_any_of");
  });

  test("the page shows the breadcrumb, the name, the role and the count; no facet bar", async ({ page }) => {
    await boot(page);
    await toFace(page);
    await page.keyboard.press("Enter");
    await expect(page.locator(".browse-crumb")).toHaveText("Paper Moon");
    await expect(page.locator(".browse-heading")).toHaveText("Bo Marsh");
    await expect(page.locator(".browse-role")).toHaveText("Director");
    await expect(page.locator(".browse-count")).toHaveText("2 titles");
    await expect(page.locator(".browse-page [class*=filter], .browse-page [class*=facet]")).toHaveCount(0);
    await expect(page.locator("#nav-home")).toHaveClass(/cur/);
  });

  test("OK on a tile opens that title's page; Back, then Back, lands on the same face", async ({ page }) => {
    await boot(page);
    await toFace(page);
    await page.keyboard.press("Enter");
    await expect(page.locator("#browse-tile-0")).toHaveClass(/focused/);
    await page.keyboard.press("Enter");
    await expect(page.locator(".detail-title")).toHaveText("Second Feature");
    await page.keyboard.press("Escape");
    await expect(page.locator(".browse-heading")).toHaveText("Bo Marsh");
    await expect(page.locator("#browse-tile-0")).toHaveClass(/focused/);
    await page.keyboard.press("Escape");
    await expect(page.locator(".detail-title")).toHaveText("Paper Moon");
    await expect(page.locator("#detail-cast-1")).toHaveClass(/focused/);
    await expect(page.locator(".shell-target.focused")).toHaveCount(1);
  });

  test("a person with no titles says so; a failed read shows the failure and Retry asks again", async ({ page }) => {
    const seen = await boot(page, { seeded: (n) => n === 1 ? { status: 500 } : { status: 200, body: { items: [], total: 0 } } });
    await toFace(page);
    await page.keyboard.press("Enter");
    await expect(page.locator(".load-failure")).toBeVisible();
    await expect(page.locator(".browse-heading")).toHaveText("Bo Marsh");     // the real heading stays under the failure
    await page.keyboard.press("Enter");                                          // Retry holds focus
    await expect(page.locator(".browse-count")).toHaveText("0 titles");
    await expect(page.locator(".browse-empty")).toHaveText("Nothing in this collection yet.");
    expect(seen.seeded).toHaveLength(2);
  });
});
