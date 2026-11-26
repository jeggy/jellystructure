import { test, expect, type Page } from "@playwright/test";

// DEB-4662 (FR-8) — Search on the full Tizen app: a real <input>, results from `search` 400 ms after the last keystroke,
// Down into the results, Back to the field with the text kept. The backend is stood in for by page.route; what only the
// TV can show (the IME rising, the real Back key) is on the QA ticket.
const SCREEN_URL = process.env.RAVILO_SCREEN_CAST_URL ?? "http://localhost:8092";
const card = (id: string, kind = "MOVIE") => ({ id, kind, title: `Story ${id}`, year: 2024,
  genre: null, rating: null, poster_url: `/api/tv/image/${id}`, backdrop_url: null });

// DEB-5685 (FR-57) — the empty field asks `search("")` once for its suggestions; `asked` lists the typed queries only, `empties`
// the empty asks, and `fail` makes a typed query answer 500.
async function boot(page: Page, answer: (q: string) => unknown[], opts: { suggestions?: unknown[]; empties?: string[]; fail?: (q: string) => boolean } = {}) {
  const asked: string[] = [];
  await page.route("**/api/tv/home", route => route.fulfill({ status: 200, contentType: "application/json",
    body: JSON.stringify({ heroes: [], channels: [], rows: [], tile_shape: "POSTER", hero_height_pct: 56 }) }));
  await page.route("**/api/tv/search?**", route => {
    const q = new URL(route.request().url()).searchParams.get("q")!;
    if (q === "") opts.empties?.push(q); else asked.push(q);
    if (q !== "" && opts.fail?.(q)) return route.fulfill({ status: 500, contentType: "application/json", body: "{}" });
    const items = q === "" ? (opts.suggestions ?? []) : answer(q);
    return route.fulfill({ status: 200, contentType: "application/json",
      body: JSON.stringify({ query: q, items, total: items.length }) });
  });
  await page.addInitScript(() => {
    localStorage.setItem("ravilo.screen.serverUrl", "http://app:9505");
    localStorage.setItem("ravilo.screen.profiles", JSON.stringify([
      { userId: "viewer", displayName: "Test Viewer", token: "test-token" },
    ]));
  });
  await page.goto(SCREEN_URL);
  await expect(page.locator("#nav-home.focused")).toBeVisible();
  for (let i = 0; i < 3; i++) await page.keyboard.press("ArrowRight");
  await page.keyboard.press("Enter");
  await expect(page.locator("#search-input.focused")).toBeFocused();
  return asked;
}

test("a typing burst asks once, 400 ms after the last key, and lists movies and series only (DEB-4662 FR-8)", async ({ page }) => {
  const asked = await boot(page, () => [card("amber"), card("birch", "SERIES"), card("clip", "MUSIC_VIDEO")]);
  await expect(page.locator(".search-hint")).toHaveText("Start typing to search");
  await page.keyboard.type("amb", { delay: 60 });
  await page.waitForTimeout(250);
  expect(asked).toEqual([]);
  await expect(page.locator(".browse-grid .tile-title")).toHaveText(["Story amber", "Story birch"]);
  expect(asked).toEqual(["amb"]);
  await expect(page.locator(".browse-count")).toHaveText("2 results");
  await page.keyboard.type("e", { delay: 60 });
  await expect.poll(() => asked.length).toBe(2);
  expect(asked).toEqual(["amb", "ambe"]);
});

test("Down enters the results and Back returns to the field with the text kept (DEB-4662 FR-8)", async ({ page }) => {
  const asked = await boot(page, () => [card("amber"), card("birch")]);
  await page.keyboard.type("am");
  await expect(page.locator("#search-tile-0")).toBeVisible();
  await page.keyboard.press("ArrowLeft");
  await page.keyboard.press("Backspace");
  await expect(page.locator("#search-input")).toHaveValue("m");
  // Left moved the caret, so the next letter goes in front of what is left.
  await page.keyboard.type("a");
  await expect.poll(() => asked.length).toBe(2);
  await expect(page.locator("#search-input")).toHaveValue("am");
  await page.keyboard.press("ArrowDown");
  await expect(page.locator("#search-tile-0.focused")).toBeVisible();
  await page.keyboard.press("ArrowRight");
  await expect(page.locator("#search-tile-1.focused")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.locator("#search-input.focused")).toBeVisible();
  await expect(page.locator("#search-input")).toHaveValue("am");
  await expect(page.locator("#search-tile-0")).toBeVisible();
});

test("a result opens its page and Back redraws the same search without asking again (DEB-4662 FR-8)", async ({ page }) => {
  const asked = await boot(page, () => [card("amber"), card("birch")]);
  await page.keyboard.type("st");
  await expect(page.locator("#search-tile-0")).toBeVisible();
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("ArrowRight");
  await page.keyboard.press("Enter");
  await expect(page.locator("#shell-body .shell-placeholder")).toHaveText("Couldn't reach the server");   // the page opened; its facts are not mocked (FR-20: loading is a wordless skeleton)
  await page.keyboard.press("Escape");
  await expect(page.locator("#search-tile-1.focused")).toBeVisible();
  await expect(page.locator("#search-input")).toHaveValue("st");
  await page.waitForTimeout(600);
  expect(asked).toEqual(["st"]);
});

test("a search with no titles says so, and clearing the field goes back to the hint (DEB-4662 FR-8)", async ({ page }) => {
  const asked = await boot(page, () => []);
  await page.keyboard.type("zz");
  await expect(page.locator(".browse-empty")).toHaveText("No results for \"zz\"");
  await expect(page.locator(".browse-grid")).toHaveCount(0);
  await page.keyboard.press("Backspace");
  await page.keyboard.press("Backspace");
  await expect(page.locator(".search-hint")).toHaveText("Start typing to search");
  await page.waitForTimeout(600);
  expect(asked).toEqual(["zz"]);
});

test("Back from the empty field leaves Search for the page before it (DEB-4662 FR-8)", async ({ page }) => {
  await boot(page, () => []);
  await page.keyboard.press("Escape");
  await expect(page.locator("#search-input")).toHaveCount(0);
  await expect(page.locator("#nav-search.focused")).toBeVisible();
});

test("the empty field asks for suggestions once and draws them under their label (DEB-5685 FR-57)", async ({ page }) => {
  const empties: string[] = [];
  await boot(page, () => [], { suggestions: [card("amber"), card("birch", "SERIES"), card("clip", "MUSIC_VIDEO")], empties });
  await expect(page.locator(".browse-count")).toHaveText("Suggestions");
  await expect(page.locator(".browse-grid .tile-title")).toHaveText(["Story amber", "Story birch"]);
  await expect(page.locator(".search-hint")).toHaveCount(0);
  await expect(page.locator("#search-clear")).toBeHidden();
  expect(empties).toEqual([""]);
  await page.keyboard.press("ArrowDown");
  await expect(page.locator("#search-tile-0.focused")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.locator("#search-input.focused")).toBeVisible();
  expect(empties).toEqual([""]);
});

test("an empty suggestions answer keeps the hint line (DEB-5685 FR-57)", async ({ page }) => {
  const empties: string[] = [];
  await boot(page, () => [], { suggestions: [], empties });
  await expect(page.locator(".search-hint")).toHaveText("Start typing to search");
  expect(empties).toEqual([""]);
});

test("Right from the field reaches Clear, OK empties the field, focuses it and shows the suggestions (DEB-5685 FR-57)", async ({ page }) => {
  const empties: string[] = [];
  const asked = await boot(page, () => [card("amber")], { suggestions: [card("sug1"), card("sug2")], empties });
  await expect(page.locator("#search-clear")).toBeHidden();
  await page.keyboard.type("am");
  await expect(page.locator("#search-clear")).toBeVisible();
  await expect(page.locator("#search-clear")).toHaveText("Clear");
  await expect(page.locator(".browse-grid .tile-title")).toHaveText(["Story amber"]);
  await page.keyboard.press("ArrowRight");
  await expect(page.locator("#search-clear.focused")).toBeVisible();
  await page.keyboard.press("ArrowLeft");
  await expect(page.locator("#search-input.focused")).toBeVisible();
  await page.keyboard.press("ArrowRight");
  await page.keyboard.press("Enter");
  await expect(page.locator("#search-input")).toHaveValue("");
  await expect(page.locator("#search-input.focused")).toBeFocused();
  await expect(page.locator("#search-clear")).toBeHidden();
  await expect(page.locator(".browse-count")).toHaveText("Suggestions");
  await expect(page.locator(".browse-grid .tile-title")).toHaveText(["Story sug1", "Story sug2"]);
  expect(empties).toEqual([""]);
  expect(asked).toEqual(["am"]);
});

test("a failed search drops the old results for error.generic and Retry asks the same text again (DEB-5685 FR-57)", async ({ page }) => {
  let failing = false;
  const asked = await boot(page, q => [card(`hit-${q}`)], { fail: () => failing });
  await page.keyboard.type("am");
  await expect(page.locator(".browse-grid .tile-title")).toHaveText(["Story hit-am"]);
  failing = true;
  await page.keyboard.type("b");
  await expect(page.locator(".browse-empty")).toHaveText("Something went wrong");
  await expect(page.locator(".browse-grid")).toHaveCount(0);
  await expect(page.locator(".tile")).toHaveCount(0);
  await expect(page.locator("#search-retry")).toHaveText("Retry");
  expect(asked).toEqual(["am", "amb"]);
  await page.keyboard.press("ArrowDown");
  await expect(page.locator("#search-retry.focused")).toBeVisible();
  failing = false;
  await page.keyboard.press("Enter");
  await expect(page.locator(".browse-grid .tile-title")).toHaveText(["Story hit-amb"]);
  expect(asked).toEqual(["am", "amb", "amb"]);
  await expect(page.locator("#search-input.focused")).toBeVisible();
  await expect(page.locator("#search-retry")).toHaveCount(0);
});

test("Back from Retry returns to the field with its text, and a later keystroke asks as before (DEB-5685 FR-57)", async ({ page }) => {
  let failing = true;
  const asked = await boot(page, q => [card(`hit-${q}`)], { fail: () => failing });
  await page.keyboard.type("zz");
  await expect(page.locator("#search-retry")).toBeVisible();
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Escape");
  await expect(page.locator("#search-input.focused")).toBeVisible();
  await expect(page.locator("#search-input")).toHaveValue("zz");
  failing = false;
  await page.keyboard.type("y");
  await expect(page.locator(".browse-grid .tile-title")).toHaveText(["Story hit-zzy"]);
  expect(asked).toEqual(["zz", "zzy"]);
});
