import { test, expect, Page, Browser } from "@playwright/test";

/**
 * DEB-1788 (FR-4, FR-5) — the stored-logo picker's ✕: a tile nothing uses has one, the confirm removes it through
 * `DELETE /api/tv/admin/channel-logos/{name}` and the picker re-renders from a fresh GET; a tile a collection uses
 * has none and its title names the collection. The two cases at the foot of the file.
 *
 * Earlier header, DEB-1511:
 * DEB-1511 FR-1 (restored by DEB-1619) — the collection editor's logo upload says WHY a file was refused, and the
 * refusal does not outlive the next upload that works.
 *
 * Real server, no stubs for the upload itself: the SVG with live `<text>` is refused by the real
 * `POST /api/tv/admin/channel-logos`, and the PNG after it is stored by the same route. The Jellyfin user list is
 * stubbed because the mock has no /Users (the Ravilo editor refuses to open without one).
 *
 * The Workbench's own logo upload (`wbc-file`) has the same failure line, but no admin screen opens the Workbench in
 * channel mode today, so it has no page to drive from here.
 *
 * Serial + one shared login: the login rate limiter (5 attempts/60s) is shared across the whole Playwright run.
 */
const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";

const LIVE_TEXT_SVG = Buffer.from(
  `<svg xmlns="http://www.w3.org/2000/svg" width="8" height="8"><path d="M0 0h4v4z"/><text x="1" y="2">Hi</text></svg>`);
const PATH_SVG = Buffer.from(
  `<svg xmlns="http://www.w3.org/2000/svg" width="8" height="8"><path d="M0 0h4v4z"/></svg>`);
const CH = "logo-upload-fixture";
const PNG_1X1 = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==", "base64");

async function login(page: Page) {
  await page.goto("/login");
  await page.fill('input[type="text"]', JF_USER);
  await page.fill('input[type="password"]', JF_PASS);
  await page.click('button[type="submit"], button:has-text("Sign in"), button:has-text("Login")');
  await expect(page.locator('.statgrid, h1:has-text("Dashboard")').first()).toBeVisible({ timeout: 15_000 });
}

test.describe.serial("Collection editor — logo upload refusal", () => {
  let page: Page;

  test.beforeAll(async ({ browser }: { browser: Browser }) => {
    page = await browser.newPage();
    await login(page);
    // The collection editor needs a collection to open; a fresh stack has none.
    const cfg = await (await page.request.get("/api/tv/admin/config?scope=global")).json();
    const channels = [
      ...((cfg.config.channels ?? []) as any[]).filter((c) => c.id !== CH),
      { id: CH, name: "Fixture Collection", enabled: true },
    ];
    expect((await page.request.put("/api/tv/admin/config?scope=global", { data: { ...cfg.config, channels } })).ok()).toBeTruthy();
    await page.route("**/api/jellyfin/users", (route) =>
      route.fulfill({ contentType: "application/json", body: JSON.stringify([{ id: "u-fixture", display_name: "Fixture User" }]) }));
    await page.goto("/#/ravilo?tab=layout");
    await page.click("#rav-scope-global");
    await page.locator("[data-ch-edit]").last().click();
    await expect(page.locator("#ch-ed-save")).toBeVisible({ timeout: 15_000 });
  });

  test.afterAll(async () => {
    // Leave the layout as it was found.
    const cfg = await (await page.request.get("/api/tv/admin/config?scope=global")).json();
    await page.request.put("/api/tv/admin/config?scope=global", { data: { ...cfg.config, channels: ((cfg.config.channels ?? []) as any[]).filter((c) => c.id !== CH) } });
    await page.close();
  });

  test("a refused SVG says why, not the raw JSON body", async () => {
    await page.setInputFiles("#ch-logo-file", { name: "live-text.svg", mimeType: "image/svg+xml", buffer: LIVE_TEXT_SVG });
    const msg = page.locator("#ch-ed-msg");
    await expect(msg).toBeVisible({ timeout: 10_000 });
    await expect(msg).toContainText("Upload failed:");
    await expect(msg).toContainText(/outline/i);
    await expect(msg).not.toContainText('{"error"');
  });

  test("an upload that works clears the refusal", async () => {
    await page.setInputFiles("#ch-logo-file", { name: "ok.png", mimeType: "image/png", buffer: PNG_1X1 });
    await expect(page.locator("#ch-ed-msg")).toBeHidden({ timeout: 10_000 });
    await expect(page.locator("#ch-ed-msg")).toHaveText("");
  });

  // DEB-1511 FR-3 (DEB-1736) — the line describes the last upload; picking a stored logo is a change of logo too.
  test("a click on a stored logo clears the refusal", async () => {
    await page.setInputFiles("#ch-logo-file", { name: "live-text.svg", mimeType: "image/svg+xml", buffer: LIVE_TEXT_SVG });
    const msg = page.locator("#ch-ed-msg");
    await expect(msg).toContainText("Upload failed:", { timeout: 10_000 });
    const tile = page.locator("#ch-logo-library [data-logo-pick]").first();
    await expect(tile).toBeVisible();
    const url = await tile.getAttribute("data-logo-pick");
    await tile.click();
    await expect(page.locator("#ch-ed-logo")).toHaveValue(url!);
    await expect(msg).toBeHidden();
    await expect(msg).toHaveText("");
  });
  // DEB-1788 FR-4 — a path-only SVG nothing uses gets a ✕; the tile the editor has picked does not.
  test("a stored logo nothing uses is removed from the picker with its ✕", async () => {
    await page.setInputFiles("#ch-logo-file", { name: "removable.svg", mimeType: "image/svg+xml", buffer: PATH_SVG });
    await expect(page.locator("#ch-ed-logo")).toHaveValue(/removable\.svg$/, { timeout: 10_000 });
    const svgUrl = await page.locator("#ch-ed-logo").inputValue();
    await page.setInputFiles("#ch-logo-file", { name: "picked.png", mimeType: "image/png", buffer: PNG_1X1 });
    await expect(page.locator("#ch-ed-logo")).toHaveValue(/picked\.png$/, { timeout: 10_000 });
    const pngUrl = await page.locator("#ch-ed-logo").inputValue();

    const lib = page.locator("#ch-logo-library");
    const svgCell = lib.locator(`.logo-cell:has([data-logo-pick="${svgUrl}"])`);
    const pngCell = lib.locator(`.logo-cell:has([data-logo-pick="${pngUrl}"])`);
    await expect(svgCell.locator(".logo-x")).toBeVisible();
    await expect(svgCell.locator(".logo-x")).toHaveAttribute("title", "Remove this logo");
    await expect(pngCell.locator(".logo-x")).toHaveCount(0); // the logo in the editor's field is not removable from under it

    page.once("dialog", (d) => { expect(d.message()).toContain("Remove the logo"); void d.accept(); });
    await svgCell.locator(".logo-x").click();
    await expect(lib.locator(`[data-logo-pick="${svgUrl}"]`)).toHaveCount(0, { timeout: 10_000 });
    expect((await page.request.get(svgUrl)).status()).toBe(404);
    await expect(page.locator("#ch-ed-logo")).toHaveValue(pngUrl);
  });

  // DEB-1788 FR-4/FR-5 — a logo a collection holds has no ✕ and says who holds it; the server refuses it anyway.
  test("a stored logo a collection uses has no ✕ and names the collection", async () => {
    const pngUrl = await page.locator("#ch-ed-logo").inputValue();
    const cfg = await (await page.request.get("/api/tv/admin/config?scope=global")).json();
    const channels = ((cfg.config.channels ?? []) as any[]).map((c) => (c.id === CH ? { ...c, logo_url: pngUrl } : c));
    expect((await page.request.put("/api/tv/admin/config?scope=global", { data: { ...cfg.config, channels } })).ok()).toBeTruthy();
    // Any upload re-renders the picker from a fresh GET, which carries the new usedBy.
    await page.setInputFiles("#ch-logo-file", { name: "other.png", mimeType: "image/png", buffer: PNG_1X1 });
    await expect(page.locator("#ch-ed-logo")).toHaveValue(/other\.png$/, { timeout: 10_000 });

    const lib = page.locator("#ch-logo-library");
    const used = lib.locator(`.logo-cell:has([data-logo-pick="${pngUrl}"])`);
    await expect(used).toBeVisible();
    await expect(used.locator(".logo-x")).toHaveCount(0);
    await expect(used.locator("[data-logo-pick]")).toHaveAttribute("title", /In use by the collection ‘Fixture Collection’/);
    const refused = await page.request.delete(`/api/tv/admin/channel-logos/${pngUrl.split("/").pop()}`);
    expect(refused.status()).toBe(409);
  });
});
