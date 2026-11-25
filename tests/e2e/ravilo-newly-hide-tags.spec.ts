import { test, expect, Page, Browser } from "@playwright/test";

/**
 * DEB-5015 (DEB-5003, FR-7, FR-8) — the admin's second *Hide titles tagged* sub-row, under the Newly Added system row:
 * Layout → Content rows (global) and Edit collection → Custom → Rows. Continue Watching's own sub-row
 * (ravilo-continue-hide-tags.spec.ts) sits on the same page, so these tests also pin that each edits only its own list,
 * that one picker is open at a time across both, and that a Save redraws both from the server's read-back.
 *
 * The tag catalogue is stubbed, the layout is the real server's: saved with the page's own Save and read back through
 * the admin config route. Tag names are the spec's stand-ins (`kids-tv`…) — this repository is public.
 * Serial + one shared login: the login rate limiter is shared across the whole Playwright run.
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

async function stubUsers(page: Page) {
  await page.route("**/api/jellyfin/users", (route) =>
    route.fulfill({ contentType: "application/json", body: JSON.stringify([{ id: "u-fixture", display_name: "Fixture User" }]) }));
}

async function stubCatalogue(page: Page) {
  await page.route("**/api/metadata/tags**", (route) =>
    route.fulfill({ contentType: "application/json", body: JSON.stringify(CATALOGUE) }));
}

async function openLayout(page: Page) {
  await page.goto("/#/ravilo?tab=layout");
  await page.click("#rav-scope-global");
  await expect(page.locator("#sect-rows .cfg-row.hascond").first()).toBeVisible({ timeout: 15_000 });
}

async function openCollection(page: Page) {
  await page.goto("/#/ravilo?tab=layout");
  await page.click("#rav-scope-global");
  await page.locator("[data-ch-edit]").last().click();
  await expect(page.locator("#ch-ed-save")).toBeVisible({ timeout: 15_000 });
}

type Row = { id: string; kind: string; enabled?: boolean; order?: number; media_kind?: string | null; hide_tags?: string[] };
const globalConfig = async (page: Page) => {
  const r = await page.request.get("/api/tv/admin/config?scope=global");
  expect(r.ok()).toBeTruthy();
  return (await r.json()).config as { rows: Row[]; channels?: any[]; merge_newly_added?: boolean };
};
const putGlobal = async (page: Page, cfg: unknown) =>
  expect((await page.request.put("/api/tv/admin/config?scope=global", { data: cfg })).ok()).toBeTruthy();

test.describe.serial("Newly Added — Hide titles tagged (Layout → Content rows)", () => {
  let page: Page;
  const newlyRow = () => page.locator('#sect-rows .cfg-row.hascond:has(.nm:text-is("Newly Added"))');
  const contRow = () => page.locator('#sect-rows .cfg-row.hascond:has(.nm:text-is("Continue Watching"))');
  const nsub = () => newlyRow().locator(".cw-cond");
  const csub = () => contRow().locator(".cw-cond");
  const nchips = () => nsub().locator(".cw-chip");
  const cchips = () => csub().locator(".cw-chip");

  test.beforeAll(async ({ browser }: { browser: Browser }) => {
    page = await browser.newPage();
    await login(page);
    // A known layout: Continue and one all-media Newly Added row, both on, nothing hidden, Merge off.
    const cfg = await globalConfig(page);
    const rows: Row[] = [
      { id: "continue", kind: "CONTINUE", enabled: true, order: 0, hide_tags: [] },
      { id: "newly-all", kind: "NEWLY_ADDED", enabled: true, order: 1, hide_tags: [] },
    ];
    await putGlobal(page, { ...cfg, rows, merge_newly_added: false });
    await stubUsers(page);
    await stubCatalogue(page);
    await openLayout(page);
  });

  test.afterAll(async () => { await page.close(); });

  // FR-7 — the second sub-row sits under the Newly Added row, and the Continue one stays where it was.
  test("each of the two rows carries its own sub-row", async () => {
    await expect(page.locator("#sect-rows .cw-cond")).toHaveCount(2);
    await expect(nsub().locator(".cw-facet")).toHaveText("Hide titles tagged");
    await expect(nsub().locator(".cw-add")).toHaveText("＋ Add tag");
    await expect(nchips()).toHaveCount(0);
    await expect(csub().locator(".cw-facet")).toHaveText("Hide titles tagged");
  });

  // FR-7 — each reads and writes only its own list.
  test("a tag picked under Newly Added lands in that list only", async () => {
    await nsub().locator(".cw-add").click();
    await expect(nsub().locator(".cw-q")).toBeFocused();
    await nsub().locator('.cw-pi[data-pick="kids-tv"]').click();
    await expect(nchips().locator(".nm")).toHaveText(["kids-tv"]);
    await expect(nchips().locator(".n")).toHaveText(["27"]);
    await expect(cchips()).toHaveCount(0);
  });

  // FR-7 — only one picker is open at a time across both. Continue's popup hangs over the Newly Added row under it
  // (the DEB-1264 popup, unchanged), so the reachable direction is Newly Added's picker open, then Continue's button.
  test("opening Continue's picker closes Newly Added's", async () => {
    await expect(nsub().locator(".cw-pick")).toBeVisible();
    await csub().locator(".cw-add").click();
    await expect(csub().locator(".cw-pick")).toBeVisible();
    await expect(nsub().locator(".cw-pick")).toHaveCount(0);
    await csub().locator('.cw-pi[data-pick="cartoons"]').click();
    await expect(cchips().locator(".nm")).toHaveText(["cartoons"]);
    await expect(nchips().locator(".nm")).toHaveText(["kids-tv"]);   // untouched by the other list's pick
    await page.keyboard.press("Escape");
    await expect(csub().locator(".cw-pick")).toHaveCount(0);
  });

  // FR-7 — while the Newly Added row is switched off and nothing else shows Newly Added, the sub-row is hidden and
  // its tags are kept; Merge, or a typed row that is on, keeps it visible.
  test("switching Newly Added off hides its sub-row unless Merge keeps a Newly Added row showing", async () => {
    const toggle = newlyRow().locator("[data-row-toggle]");
    await toggle.click();
    await expect(newlyRow()).toHaveClass(/\boff\b/);
    await expect(nsub()).toBeHidden();
    await expect(csub()).toBeVisible();   // Continue's is not touched by the other row's switch
    // Merge on: the layout still shows a (merged) Newly Added row, so the list is not hidden from the admin.
    await page.locator("#merge-newly-added").check();
    await expect(newlyRow()).not.toHaveClass(/\boff\b/);
    await expect(nsub()).toBeVisible();
    await expect(nchips().locator(".nm")).toHaveText(["kids-tv"]);
    await page.locator("#merge-newly-added").uncheck();
    await expect(newlyRow()).toHaveClass(/\boff\b/);
    await expect(nsub()).toBeHidden();
    await toggle.click();
    await expect(nsub()).toBeVisible();
    await expect(nchips().locator(".nm")).toHaveText(["kids-tv"]);
  });

  // FR-8 — nothing before the layout Save; then each list is on its own row, and a fresh load shows both.
  test("nothing is saved until Save, then each list is on its own row and both read back", async () => {
    let rows = (await globalConfig(page)).rows;
    expect(rows.find((r) => r.kind === "NEWLY_ADDED")!.hide_tags ?? []).toEqual([]);
    await page.click("#rav-save");
    await expect(page.locator("#rav-msg")).toContainText("Saved.");
    rows = (await globalConfig(page)).rows;
    expect(rows.find((r) => r.kind === "NEWLY_ADDED")!.hide_tags).toEqual(["kids-tv"]);
    expect(rows.find((r) => r.kind === "CONTINUE")!.hide_tags).toEqual(["cartoons"]);
    await openLayout(page);
    await expect(nchips().locator(".nm")).toHaveText(["kids-tv"]);
    await expect(cchips().locator(".nm")).toHaveText(["cartoons"]);
  });

  // FR-8 — after a Save the editor draws both lists from what the server stored, not from what it sent.
  test("a Save redraws both lists from the server's read-back", async () => {
    await nsub().locator(".cw-add").click();
    await nsub().locator('.cw-pi[data-pick="noir"]').click();
    await page.keyboard.press("Escape");
    await page.click("#rav-save");
    await expect(page.locator("#rav-msg")).toContainText("Saved.");
    const rows = (await globalConfig(page)).rows;
    expect(rows.find((r) => r.kind === "NEWLY_ADDED")!.hide_tags).toEqual(["kids-tv", "noir"]);
    await expect(nchips().locator(".nm")).toHaveText(["kids-tv", "noir"]);
    await expect(cchips().locator(".nm")).toHaveText(["cartoons"]);
    await expect(nsub().locator(".cw-pick")).toHaveCount(0);
  });
});

test.describe.serial("Newly Added — Hide titles tagged (a Custom collection)", () => {
  let page: Page;
  const CH = "ch-newly-hide-tags-fixture";
  const nsub = () => page.locator('#ch-rows-list .cfg-row.hascond:has([data-systog="newly"]) .cw-cond');
  const csub = () => page.locator('#ch-rows-list .cfg-row.hascond:has([data-systog="continue"]) .cw-cond');

  test.beforeAll(async ({ browser }: { browser: Browser }) => {
    page = await browser.newPage();
    await login(page);
    const cfg = await globalConfig(page);
    const channels = [
      ...((cfg.channels ?? []) as any[]).filter((c) => c.id !== CH),
      { id: CH, name: "Fixture Collection", enabled: true, rows: { mode: "custom", items: [], system: { continue: { show: true, hide_tags: ["cartoons"] }, newly: { show: true, merge: false, hide_tags: ["noir"] } } } },
    ];
    await putGlobal(page, { ...cfg, channels });
    await stubUsers(page);
    await stubCatalogue(page);
    await openCollection(page);
  });

  test.afterAll(async () => {
    const cfg = await globalConfig(page);
    await putGlobal(page, { ...cfg, channels: ((cfg.channels ?? []) as any[]).filter((c) => c.id !== CH) });
    await page.close();
  });

  // FR-7 — under that collection's Newly Added row, with the tags it already holds; Continue keeps its own.
  test("a Custom collection's Newly Added row has its own sub-row", async () => {
    await expect(nsub()).toBeVisible({ timeout: 15_000 });
    await expect(nsub().locator(".cw-chip .nm")).toHaveText(["noir"]);
    await expect(csub().locator(".cw-chip .nm")).toHaveText(["cartoons"]);
    await expect(page.locator(".cw-cond")).toHaveCount(2);
  });

  // FR-7 — one picker at a time across both.
  test("one picker is open at a time", async () => {
    await nsub().locator(".cw-add").click();
    await expect(nsub().locator(".cw-pick")).toBeVisible();
    await csub().locator(".cw-add").click();
    await expect(csub().locator(".cw-pick")).toBeVisible();
    await expect(nsub().locator(".cw-pick")).toHaveCount(0);
    await page.keyboard.press("Escape");
  });

  // FR-7 — the row's toggle hides its sub-row and keeps the tags.
  test("switching the collection's Newly Added row off hides the sub-row", async () => {
    await page.locator('[data-systog="newly"]').click();
    await expect(page.locator('#ch-rows-list .cfg-row:has([data-systog="newly"])')).toHaveClass(/\boff\b/);
    await expect(nsub()).toBeHidden();
    await expect(csub()).toBeVisible();
    await page.locator('[data-systog="newly"]').click();
    await expect(nsub().locator(".cw-chip .nm")).toHaveText(["noir"]);
  });

  // FR-8 — pick a tag, Save the collection, read it back; the Continue list is untouched.
  test("a picked tag is saved in system.newly and reads back", async () => {
    await nsub().locator(".cw-add").click();
    await nsub().locator('.cw-pi[data-pick="kids-tv"]').click();
    await expect(nsub().locator(".cw-chip .nm")).toHaveText(["noir", "kids-tv"]);
    await expect(csub().locator(".cw-chip .nm")).toHaveText(["cartoons"]);
    await page.click("#ch-ed-save");
    await expect.poll(async () => {
      const ch = ((await globalConfig(page)).channels ?? []).find((c) => c.id === CH);
      return [ch?.rows?.system?.newly?.hide_tags, ch?.rows?.system?.continue?.hide_tags];
    }, { timeout: 15_000 }).toEqual([["noir", "kids-tv"], ["cartoons"]]);
  });

  // FR-7 — "Same as Home": no sub-row at all, since Home's list applies.
  test("a collection on Same as Home shows no sub-row", async () => {
    await openCollection(page);
    await page.locator('input[name="ch-rows-mode"][value="inherit"]').check();
    await expect(page.locator("#ch-rows-custom-body")).toBeHidden();
    await expect(nsub()).toBeHidden();
  });
});
