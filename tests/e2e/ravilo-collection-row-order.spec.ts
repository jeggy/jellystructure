import { test, expect, Page, Browser } from "@playwright/test";

/**
 * DEB-2850 (DEB-2848 FR-4..FR-6) — a Custom collection's rows are one draggable list in the admin: Continue Watching,
 * Newly Added and the filter rows move among each other, the whole order is saved with the collection's own Save, and
 * it reads back after a reload. With no drag nothing is written, so `rows.order` stays empty.
 *
 * Real server, real admin page: the collection is put in the global layout through the admin config route and read
 * back through the same route, which is the round trip the spec asks for. The collection is removed afterwards.
 * Serial + one shared login: the login rate limiter (5 attempts/60s) is shared across the whole Playwright run.
 */
const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";
const CH = "ch-row-order-fixture";

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

async function openCollection(page: Page) {
  await page.goto("/#/ravilo?tab=layout");
  await page.click("#rav-scope-global");
  await page.locator("[data-ch-edit]").last().click();
  await expect(page.locator("#ch-ed-save")).toBeVisible({ timeout: 15_000 });
}

const fixtureRows = (order?: string[]) => ({
  mode: "custom",
  items: [
    { id: "row-a", kind: "CUSTOM", title: "Alpha row", order: 0 },
    { id: "row-b", kind: "CUSTOM", title: "Bravo row", order: 1 },
  ],
  system: { continue: { show: true }, newly: { show: true, merge: false } },
  ...(order ? { order } : {}),
});

async function putCollection(page: Page, rows: unknown) {
  const cfg = await (await page.request.get("/api/tv/admin/config?scope=global")).json();
  const channels = [
    ...((cfg.config.channels ?? []) as any[]).filter((c) => c.id !== CH),
    { id: CH, name: "Order Fixture", enabled: true, rows },
  ];
  expect((await page.request.put("/api/tv/admin/config?scope=global", { data: { ...cfg.config, channels } })).ok()).toBeTruthy();
}

const storedRows = async (page: Page) => {
  const cfg = await (await page.request.get("/api/tv/admin/config?scope=global")).json();
  return ((cfg.config.channels ?? []) as any[]).find((c) => c.id === CH)?.rows;
};

test.describe.serial("A Custom collection's rows in one order (editor)", () => {
  let page: Page;
  const labels = async () => page.locator("#ch-rows-list > .cfg-row").evaluateAll((els) =>
    els.map((e) => (e.querySelector(".nm")?.textContent ?? (e.querySelector("input[data-row-ch-title]") as HTMLInputElement)?.value ?? "").trim()));

  test.beforeAll(async ({ browser }: { browser: Browser }) => {
    page = await browser.newPage();
    await login(page);
    await stubUsers(page);
  });

  test.afterAll(async () => {
    const cfg = await (await page.request.get("/api/tv/admin/config?scope=global")).json();
    await page.request.put("/api/tv/admin/config?scope=global", { data: { ...cfg.config, channels: ((cfg.config.channels ?? []) as any[]).filter((c) => c.id !== CH) } });
    await page.close();
  });

  // FR-4 / FR-2 — one list, system rows on top by default, each with a grab handle; the old two blocks are gone.
  test("the rows are one list with the system rows on top", async () => {
    await putCollection(page, fixtureRows());
    await openCollection(page);
    await expect(page.locator("#ch-rows-list > .cfg-row")).toHaveCount(4);
    expect(await labels()).toEqual(["Continue Watching", "Newly Added", "Alpha row", "Bravo row"]);
    await expect(page.locator("#ch-rows-list > .cfg-row .grab")).toHaveCount(4);
    await expect(page.locator("#ch-rows-list > .cfg-row[draggable='true']")).toHaveCount(4);
    await expect(page.locator(".cf-sysrows")).toHaveCount(0);
    await expect(page.getByText("Drag rows to reorder them.")).toBeVisible();
  });

  // FR-5 — no drag, no write: saving leaves `order` empty.
  test("saving without a drag leaves the order empty", async () => {
    await page.click("#ch-ed-save");
    await expect.poll(async () => (await storedRows(page))?.order ?? [], { timeout: 15_000 }).toEqual([]);
    expect((await storedRows(page)).items.map((r: any) => r.id)).toEqual(["row-a", "row-b"]);
  });

  // FR-4 / FR-5 / FR-6 — drag Newly Added below a filter row, Save, reload, reopen: the order holds.
  test("a drag writes the whole order and it reads back after a reload", async () => {
    await openCollection(page);
    await page.locator("#ch-rows-list > .cfg-row", { hasText: "Newly Added" }).dragTo(page.locator("#ch-rows-list > .cfg-row").nth(2));
    expect(await labels()).toEqual(["Continue Watching", "Alpha row", "Newly Added", "Bravo row"]);
    await page.click("#ch-ed-save");
    await expect.poll(async () => (await storedRows(page))?.order, { timeout: 15_000 })
      .toEqual(["system:continue", "row-a", "system:newly", "row-b"]);
    const stored = await storedRows(page);
    expect(stored.items.map((r: any) => [r.id, r.order ?? 0])).toEqual([["row-a", 0], ["row-b", 1]]);
    await page.reload();
    await openCollection(page);
    expect(await labels()).toEqual(["Continue Watching", "Alpha row", "Newly Added", "Bravo row"]);
  });

  // FR-4 — a system row is moved and hidden, never removed; the merge switch travels with Newly Added.
  test("Newly Added keeps its merge switch and no system row has a delete button", async () => {
    const newly = page.locator("#ch-rows-list > .cfg-row", { hasText: "Newly Added" });
    await expect(newly.locator("[data-sysmerge]")).toHaveCount(1);
    await expect(page.locator("#ch-rows-list > .cfg-row", { hasText: "Continue Watching" }).locator("[data-row-ch-del]")).toHaveCount(0);
    await expect(newly.locator("[data-row-ch-del]")).toHaveCount(0);
  });

  // FR-5 — deleting a filter row removes its id from a non-empty order.
  test("deleting a filter row prunes it from the order", async () => {
    await page.locator("#ch-rows-list > .cfg-row", { has: page.locator('input[data-row-ch-title]').and(page.locator('[value="Alpha row"]')) }).locator("[data-row-ch-del]").click();
    await page.click("#ch-ed-save");
    await expect.poll(async () => (await storedRows(page))?.order, { timeout: 15_000 })
      .toEqual(["system:continue", "system:newly", "row-b"]);
  });
});
