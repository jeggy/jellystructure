import { test, expect, Page, Browser } from "@playwright/test";

/**
 * DEB-1264 slice 3 (FR-7..FR-10) — the admin's *Hide titles tagged* sub-row and picker under a Continue Watching
 * system row: Layout → Content rows (global) and Edit collection → Custom → System rows.
 *
 * The tag catalogue is stubbed, not produced: this stack's library is a handful of fixture files with no tags, and
 * the picker's behaviour is entirely about what it draws over the list `GET /api/metadata/tags` hands it. A second
 * stub makes that route fail, to pin FR-9's `–`. Everything else is the real server: the layout is saved with the
 * page's own Save and read back through the admin config route, which is the round trip the spec asks for.
 *
 * Serial + one shared login: the login rate limiter (5 attempts/60s) is shared across the whole Playwright run.
 * Tag names are the spec's stand-ins (`kids-tv`…) — this repository is public.
 */
const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";

const CATALOGUE = {
  jsTags: [
    { name: "kids-tv", color: "#6b7280", description: "", count: 27 },
    { name: "cartoons", color: "#6b7280", description: "", count: 14 },
  ],
  otherTags: [
    { name: "kidnapping", count: 8 },
    { name: "kids", count: 6 },
    { name: "børnefilm", count: 1 },
    { name: "animated-family-christmas-specials-from-the-archive", count: 2 },
    { name: "old-kids-block", count: 0 },
    { name: "noir", count: 5 },
  ],
};

async function login(page: Page) {
  await page.goto("/login");
  await page.fill('input[type="text"]', JF_USER);
  await page.fill('input[type="password"]', JF_PASS);
  await page.click('button[type="submit"], button:has-text("Sign in"), button:has-text("Login")');
  await expect(page.locator('.statgrid, h1:has-text("Dashboard")').first()).toBeVisible({ timeout: 15_000 });
}

// The Ravilo editor refuses to open without a Jellyfin user list, and the Jellyfin mock has no /Users.
async function stubUsers(page: Page) {
  await page.route("**/api/jellyfin/users", (route) =>
    route.fulfill({ contentType: "application/json", body: JSON.stringify([{ id: "u-fixture", display_name: "Fixture User" }]) }));
}

async function stubCatalogue(page: Page) {
  await page.unroute("**/api/metadata/tags**");
  await page.route("**/api/metadata/tags**", (route) =>
    route.fulfill({ contentType: "application/json", body: JSON.stringify(CATALOGUE) }));
}

async function openLayout(page: Page) {
  await page.goto("/#/ravilo?tab=layout");
  await page.click("#rav-scope-global");
  await expect(page.locator("#sect-rows .cfg-row.hascond")).toBeVisible({ timeout: 15_000 });
}

// The collection editor saves only in the global scope, so go through the layout page's own switcher and its Edit
// button; the fixture collection is the last one in the list.
async function openCollection(page: Page) {
  await page.goto("/#/ravilo?tab=layout");
  await page.click("#rav-scope-global");
  await page.locator("[data-ch-edit]").last().click();
  await expect(page.locator("#ch-ed-save")).toBeVisible({ timeout: 15_000 });
}

const globalRowsFromServer = async (page: Page) => {
  const r = await page.request.get("/api/tv/admin/config?scope=global");
  expect(r.ok()).toBeTruthy();
  return (await r.json()).config.rows as { id: string; kind: string; hide_tags?: string[] }[];
};

test.describe.serial("Continue Watching — Hide titles tagged (editor)", () => {
  let page: Page;
  const sub = () => page.locator("#sect-rows .cfg-row.hascond .cw-cond");
  const chips = () => sub().locator(".cw-chip");

  test.beforeAll(async ({ browser }: { browser: Browser }) => {
    page = await browser.newPage();
    await login(page);
    // Start from a known layout: the global Continue row hides nothing.
    const cfg = await (await page.request.get("/api/tv/admin/config?scope=global")).json();
    const rows = (cfg.config.rows as any[]).map((r) => (r.kind === "CONTINUE" ? { ...r, hide_tags: [] } : r));
    const put = await page.request.put("/api/tv/admin/config?scope=global", { data: { ...cfg.config, rows } });
    expect(put.ok()).toBeTruthy();
    await stubUsers(page);
    await stubCatalogue(page);
    await openLayout(page);
  });

  test.afterAll(async () => { await page.close(); });

  // FR-7 — the sub-row sits directly under the Continue row: label, no chips, ＋ Add tag.
  test("the sub-row is under the Continue row with the label and Add tag", async () => {
    await expect(sub()).toHaveCount(1);
    await expect(sub().locator(".cw-facet")).toHaveText("Hide titles tagged");
    await expect(chips()).toHaveCount(0);
    await expect(sub().locator(".cw-add")).toHaveText("＋ Add tag");
    // Only the first Continue row has one — not Newly Added.
    await expect(page.locator("#sect-rows .cw-cond")).toHaveCount(1);
  });

  // FR-8 — nothing typed: the header and the six most used tags, the footer always.
  test("the picker opens on the six most used tags with its footer", async () => {
    await sub().locator(".cw-add").click();
    await expect(sub().locator(".cw-q")).toBeFocused();
    await expect(sub().locator(".cw-q")).toHaveAttribute("placeholder", "Search 8 tags…");
    await expect(sub().locator(".cw-ph")).toHaveText("Most used tags");
    await expect(sub().locator(".cw-pi .nm")).toHaveText(
      ["kids-tv", "cartoons", "kidnapping", "kids", "noir", "animated-family-christmas-specials-from-the-archive"]);
    await expect(sub().locator(".cw-pf")).toHaveText("Hides the exact tag only — never tags that merely contain it.");
  });

  // FR-8 — typing: substrings, prefix matches first and then by count, the matched text marked, "{n} tags contain".
  test("typing filters by substring, prefixes first, with the matched text marked", async () => {
    await sub().locator(".cw-q").fill("kid");
    await expect(sub().locator(".cw-ph")).toHaveText("4 tags contain “kid”");
    await expect(sub().locator(".cw-pi .nm")).toHaveText(["kids-tv", "kidnapping", "kids", "old-kids-block"]);
    await expect(sub().locator(".cw-pi mark").first()).toHaveText("kid");
    await expect(sub().locator(".cw-pi .n")).toHaveText(["27 titles", "8 titles", "6 titles", "0 titles"]);
    await expect(sub().locator(".cw-pi .n.zero")).toHaveCount(1);
  });

  // FR-8 — a click picks the exact tag, the picker stays open, a second click un-picks it.
  test("a click toggles the exact tag and the picker stays open", async () => {
    await sub().locator('.cw-pi[data-pick="kids-tv"]').click();
    await expect(sub().locator(".cw-pick")).toBeVisible();
    await expect(sub().locator('.cw-pi[data-pick="kids-tv"]')).toHaveClass(/\bon\b/);
    await expect(chips()).toHaveCount(1);
    await expect(chips().first().locator(".nm")).toHaveText("kids-tv");
    await expect(chips().first().locator(".n")).toHaveText("27");
    await expect(sub().locator(".cw-q")).toBeFocused();
    await sub().locator('.cw-pi[data-pick="kids-tv"]').click();
    await expect(chips()).toHaveCount(0);
    await sub().locator('.cw-pi[data-pick="kids-tv"]').click();
    await expect(chips()).toHaveCount(1);
    // The lowest row hangs past the bottom of the Content rows card; the next section must not cover it.
    await sub().locator('.cw-pi[data-pick="old-kids-block"]').click();
    await expect(chips()).toHaveCount(2);
    await sub().locator('.cw-pi[data-pick="old-kids-block"]').click();
    await expect(chips()).toHaveCount(1);
  });

  // FR-8 — ↑/↓ move the highlight, Enter toggles it, Esc closes and returns focus to ＋ Add tag.
  test("the keys: arrows move, Enter toggles, Esc closes and returns focus", async () => {
    await sub().locator(".cw-q").focus();
    await expect(sub().locator(".cw-pi.hov .nm")).toHaveText("kids-tv");
    await page.keyboard.press("ArrowDown");
    await expect(sub().locator(".cw-pi.hov .nm")).toHaveText("kidnapping");
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("ArrowUp");
    await expect(sub().locator(".cw-pi.hov .nm")).toHaveText("kidnapping");
    await page.keyboard.press("Enter");
    await expect(chips().locator(".nm")).toHaveText(["kids-tv", "kidnapping"]);
    await page.keyboard.press("Escape");
    await expect(sub().locator(".cw-pick")).toHaveCount(0);
    await expect(sub().locator(".cw-add")).toBeFocused();
  });

  // FR-8 — no match says so, offers nothing to create, and the footer stays.
  test("no match shows the two lines and never creates a tag", async () => {
    await sub().locator(".cw-add").click();
    await sub().locator(".cw-q").fill("zzz");
    await expect(sub().locator(".cw-none b")).toHaveText("No tag called “zzz”.");
    await expect(sub().locator(".cw-none span")).toHaveText("Tags come from your library — nothing is created here.");
    await expect(sub().locator(".cw-pi")).toHaveCount(0);
    await expect(sub().locator(".cw-pf")).toBeVisible();
    await page.keyboard.press("Enter");
    await expect(chips()).toHaveCount(2);
  });

  // FR-8 — a click outside closes it.
  test("a click outside closes the picker", async () => {
    await page.locator("#sect-rows .card > div").first().click();
    await expect(sub().locator(".cw-pick")).toHaveCount(0);
  });

  // FR-7 — a chip's ✕ is named "Remove {tag}" and a tag no title carries keeps its amber 0.
  test("a chip's remove button is named, and a 0-title tag is amber", async () => {
    await sub().locator(".cw-add").click();
    await sub().locator(".cw-q").fill("old-kids");
    await sub().locator('.cw-pi[data-pick="old-kids-block"]').click();
    await page.keyboard.press("Escape");
    const zero = sub().locator('.cw-chip:has(.nm:text-is("old-kids-block"))');
    await expect(zero.locator(".n")).toHaveText("0");
    await expect(zero.locator(".n")).toHaveClass(/\bzero\b/);
    await expect(sub().getByRole("button", { name: "Remove kids-tv" })).toBeVisible();
    await sub().getByRole("button", { name: "Remove kidnapping" }).click();
    await expect(chips().locator(".nm")).toHaveText(["kids-tv", "old-kids-block"]);
  });

  // FR-7 — switched off, the sub-row is not shown; the tags are kept and return when it is switched back on.
  test("switching the row off hides the sub-row and keeps its tags", async () => {
    const toggle = page.locator("#sect-rows .cfg-row.hascond [data-row-toggle]");
    const enabled = page.locator("#sect-rows .cfg-row.hascond input[data-row-enabled]");
    await toggle.click();
    await expect(page.locator("#sect-rows .cfg-row.hascond")).toHaveClass(/\boff\b/);
    await expect(sub()).toBeHidden();
    await expect(enabled).not.toBeChecked();   // the row's own checkbox follows the toggle, so Save sends what is shown
    await toggle.click();
    await expect(sub()).toBeVisible();
    await expect(enabled).toBeChecked();
    await expect(chips().locator(".nm")).toHaveText(["kids-tv", "old-kids-block"]);
  });

  // FR-9 — no autosave: nothing reaches the server before the layout Save …
  test("nothing is saved until the layout Save, then the list round-trips", async () => {
    const continueRow = (rows: { kind: string; hide_tags?: string[] }[]) => rows.find((r) => r.kind === "CONTINUE")!;
    expect(continueRow(await globalRowsFromServer(page)).hide_tags ?? []).toEqual([]);
    await page.click("#rav-save");
    await expect(page.locator("#rav-msg")).toContainText("Saved.");
    expect(continueRow(await globalRowsFromServer(page)).hide_tags).toEqual(["kids-tv", "old-kids-block"]);
    // … and a fresh load of the editor shows it, with the counts read from the catalogue.
    await openLayout(page);
    await expect(chips().locator(".nm")).toHaveText(["kids-tv", "old-kids-block"]);
    await expect(chips().locator(".n")).toHaveText(["27", "0"]);
  });

  // FR-9 — if the catalogue cannot be read, the chips keep their names and show `–`, and the picker has no rows.
  test("a catalogue that fails to load shows – for the counts", async ({ browser }) => {
    await page.unroute("**/api/metadata/tags**");
    await page.route("**/api/metadata/tags**", (route) => route.fulfill({ status: 500, body: "boom" }));
    // The catalogue is cached for a minute; a fresh page has none.
    const ctx = await browser.newContext({ baseURL: page.url().split("#")[0], storageState: await page.context().storageState() });
    const failing = await ctx.newPage();
    await stubUsers(failing);
    await failing.route("**/api/metadata/tags**", (route) => route.fulfill({ status: 500, body: "boom" }));
    await failing.goto("/#/ravilo?tab=layout");
    await failing.click("#rav-scope-global");
    const fsub = failing.locator("#sect-rows .cfg-row.hascond .cw-cond");
    await expect(fsub.locator(".cw-chip .nm")).toHaveText(["kids-tv", "old-kids-block"]);
    await expect(fsub.locator(".cw-chip .n")).toHaveText(["–", "–"]);
    await fsub.locator(".cw-add").click();
    await fsub.locator(".cw-q").fill("kids");
    await expect(fsub.locator(".cw-none b")).toHaveText("No tag called “kids”.");
    await ctx.close();
    await stubCatalogue(page);
  });
});

test.describe.serial("Continue Watching — Hide titles tagged (a Custom collection)", () => {
  let page: Page;
  const CH = "ch-hide-tags-fixture";
  const sub = () => page.locator(".cf-sysrow.hascond .cw-cond");

  test.beforeAll(async ({ browser }: { browser: Browser }) => {
    page = await browser.newPage();
    await login(page);
    const cfg = await (await page.request.get("/api/tv/admin/config?scope=global")).json();
    const channels = [
      ...((cfg.config.channels ?? []) as any[]).filter((c) => c.id !== CH),
      { id: CH, name: "Fixture Collection", enabled: true, rows: { mode: "custom", items: [], system: { continue: { show: true, hide_tags: ["cartoons"] }, newly: { show: true, merge: false } } } },
    ];
    expect((await page.request.put("/api/tv/admin/config?scope=global", { data: { ...cfg.config, channels } })).ok()).toBeTruthy();
    await stubUsers(page);
    await stubCatalogue(page);
    await openCollection(page);
  });

  test.afterAll(async () => {
    // Leave the layout as it was found.
    const cfg = await (await page.request.get("/api/tv/admin/config?scope=global")).json();
    await page.request.put("/api/tv/admin/config?scope=global", { data: { ...cfg.config, channels: ((cfg.config.channels ?? []) as any[]).filter((c) => c.id !== CH) } });
    await page.close();
  });

  // FR-7 — under that collection's Continue row, with the tags it already holds.
  test("a Custom collection's Continue row has its own sub-row", async () => {
    await expect(sub()).toBeVisible({ timeout: 15_000 });
    await expect(sub().locator(".cw-chip .nm")).toHaveText(["cartoons"]);
    await expect(sub().locator(".cw-chip .n")).toHaveText("14");
    // Newly Added has none.
    await expect(page.locator(".cw-cond")).toHaveCount(1);
  });

  // FR-7 — the toggle hides it (the page's own `off` class) and the tags stay.
  test("switching the collection's Continue row off hides the sub-row", async () => {
    await page.locator('[data-systog="continue"]').click();
    await expect(page.locator(".cf-sysrow.hascond")).toHaveClass(/\boff\b/);
    await expect(sub()).toBeHidden();
    await page.locator('[data-systog="continue"]').click();
    await expect(sub().locator(".cw-chip .nm")).toHaveText(["cartoons"]);
  });

  // FR-8 / FR-9 — pick a tag, Save the collection, read it back from the server.
  test("a picked tag is saved with the collection and reads back", async () => {
    await sub().locator(".cw-add").click();
    await sub().locator(".cw-q").fill("kids-tv");
    await sub().locator('.cw-pi[data-pick="kids-tv"]').click();
    await expect(sub().locator(".cw-chip .nm")).toHaveText(["cartoons", "kids-tv"]);
    await page.click("#ch-ed-save");
    await expect(page.locator("#rav-msg, #ch-ed-msg").first()).toBeAttached();
    await expect.poll(async () => {
      const cfg = await (await page.request.get("/api/tv/admin/config?scope=global")).json();
      return ((cfg.config.channels ?? []) as any[]).find((c) => c.id === CH)?.rows?.system?.continue?.hide_tags;
    }, { timeout: 15_000 }).toEqual(["cartoons", "kids-tv"]);
  });

  // FR-7 — "Same as Home": no sub-row (Home's row and its tags apply), and saving drops the collection's own list.
  test("a collection on Same as Home shows no sub-row", async () => {
    await openCollection(page);
    await page.locator('input[name="ch-rows-mode"][value="inherit"]').check();
    await expect(page.locator("#ch-rows-custom-body")).toBeHidden();
    await expect(sub()).toBeHidden();
  });
});
