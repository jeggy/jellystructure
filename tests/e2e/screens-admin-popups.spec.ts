import { test, expect, Page } from "@playwright/test";

/**
 * DEB-5688 (FR-1, FR-2, FR-5) — an open admin popup is drawn above the page. On a title page the add-a-tag list and the
 * genre suggestions hang out of the card that holds them; every `.card` has a `backdrop-filter`, so each card is its own
 * stacking context and the Collections card (which comes straight after the Tags card) painted over the list and took its
 * clicks, and the list's own `.card` glass let what was under it show through.
 *
 * The server is the real one: the title is a fixture film from scan-fixture.spec.ts's scan, which is why this file is named to
 * sort after it (workers: 1, filename order). Three routes are stubbed so the page is the one prod draws — the collections
 * answer, so `#jc-section` is there; enough in-scope tags that the open list runs well past the bottom of the Tags card; and
 * enough library genres that the suggestions fill their scrolling box. `document.elementFromPoint` is the instrument: whatever
 * it returns at a row's centre is what a mouse there would hit.
 */
const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";

const TAGS = Array.from({ length: 16 }, (_, i) => ({ name: `popup-tag-${String(i + 1).padStart(2, "0")}`, color: "#6b7280", description: "", scopes: [] as string[] }));
const GENRES = Array.from({ length: 60 }, (_, i) => ({ value: `Popup genre ${String(i + 1).padStart(2, "0")}`, count: 60 - i }));

async function stubPage(page: Page) {
  await page.route("**/api/tags", (route) => route.fulfill({ json: TAGS }));
  await page.route("**/api/media/meta-facets", (route) => route.fulfill({ json: { genres: GENRES } }));
  await page.route("**/api/jellyfin-collections", (route) =>
    route.fulfill({ json: { configured: true, collections: [{ id: "col-popups", name: "Popup fixture collection", members: [], unmapped_count: 0 }] } }));
}

async function openTitle(page: Page) {
  await page.goto("/#/library");
  await page.waitForSelector(".poster", { timeout: 30_000 });
  await page.locator(".ttl", { hasText: "Big Buck Bunny" }).first().click();
  await expect(page.locator("#tag-input")).toBeVisible({ timeout: 15_000 });
  await expect(page.locator("#jc-section")).toBeVisible({ timeout: 15_000 });
}

/** True when what a mouse at the centre of [selector]'s [n]th match would hit is inside [within]. */
async function hitsInside(page: Page, selector: string, n: number, within: string): Promise<boolean> {
  const box = await page.locator(selector).nth(n).boundingBox();
  expect(box, `${selector}[${n}] is laid out`).not.toBeNull();
  return page.evaluate(([x, y, inside]) => !!document.elementFromPoint(x, y)?.closest(inside), [box!.x + box!.width / 2, box!.y + box!.height / 2, within] as const);
}

/** The popup is painted opaque: nothing under it shows through. */
async function opaque(page: Page, selector: string) {
  const alpha = await page.locator(selector).evaluate((el) => {
    const m = getComputedStyle(el).backgroundColor.match(/[\d.]+/g) ?? [];
    return m.length > 3 ? Number(m[3]) : 1;
  });
  expect(alpha, `${selector} has an opaque surface`).toBe(1);
  expect(await page.locator(selector).evaluate((el) => el.classList.contains("card") || el.classList.contains("box")), `${selector} is not a .card or .box`).toBe(false);
}

test.describe.serial("An open admin popup is drawn above the page", () => {
  let page: Page;

  test.beforeAll(async ({ browser }) => {
    page = await browser.newPage({ viewport: { width: 1280, height: 2400 } });
    const login = await page.request.post("/api/auth/login", { data: { username: JF_USER, password: JF_PASS } });
    expect(login.ok()).toBeTruthy();
    await stubPage(page);
    await openTitle(page);
  });

  test.afterAll(async () => page?.close());

  test("the add-a-tag list is drawn whole over the Collections card, and a click on its last row adds that tag", async () => {
    await page.locator("#tag-input").focus();
    const list = page.locator("#tag-dropdown");
    await expect(list).toBeVisible();
    await expect(list.locator(".tag-suggest-item")).toHaveCount(TAGS.length);

    // The list must really run past the Tags card and into the Collections card, or the case proves nothing.
    const listBox = (await list.boundingBox())!;
    const tags = (await page.locator("#tags-section").boundingBox())!;
    const coll = (await page.locator("#jc-section").boundingBox())!;
    expect(listBox.y + listBox.height, "the list runs past the bottom of the Tags card").toBeGreaterThan(tags.y + tags.height);
    expect(listBox.y + listBox.height, "the list runs past the top of the Collections card").toBeGreaterThan(coll.y + 8);

    await opaque(page, "#tag-dropdown");
    // Every row, not only the first and the last: the rows in between are the ones that sit over the Collections card.
    for (let i = 0; i < TAGS.length; i++) {
      expect(await hitsInside(page, "#tag-dropdown .tag-suggest-item", i, "#tag-dropdown"), `row ${i + 1} takes its own hover and clicks`).toBe(true);
    }

    const last = TAGS[TAGS.length - 1].name;
    const box = (await list.locator(".tag-suggest-item").last().boundingBox())!;
    await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
    await expect(page.locator("#tags-chips")).toContainText(last);
    await expect(list).toBeHidden();
    // The marker goes with the popup, so the lift does too.
    await expect(page.locator("#tag-dropdown.pop-open")).toHaveCount(0);
  });

  test("the genre suggestions are drawn whole over the card below, and a click on one adds it", async () => {
    await page.locator("#genre-add-chip").click();
    await page.locator("#genre-input").focus();
    const box = page.locator("#genre-suggest");
    await expect(box).toBeVisible();
    await expect(box.locator(".genre-sug").first()).toBeVisible({ timeout: 10_000 });
    await opaque(page, "#genre-suggest");
    expect(await hitsInside(page, "#genre-suggest .genre-sug", 0, "#genre-suggest"), "the first suggestion takes its own hover and clicks").toBe(true);

    // The box's bottom edge is where the Tags card, painted after the one that holds the genres, used to cover it.
    const edge = (await box.boundingBox())!;
    const hit = await page.evaluate(([x, y]) => !!document.elementFromPoint(x, y)?.closest("#genre-suggest"), [edge.x + edge.width / 2, edge.y + edge.height - 6] as const);
    expect(hit, "the bottom of the suggestions is not under the card below").toBe(true);

    const first = box.locator(".genre-sug").first();
    const name = (await first.getAttribute("data-g"))!;
    const at = (await first.boundingBox())!;
    await page.mouse.click(at.x + at.width / 2, at.y + at.height / 2);
    await expect(page.locator("#genres-chips .chip", { hasText: name })).toHaveCount(1);
  });

  test("the card holding an open popup is lifted, and drops back when it closes", async () => {
    const z = (sel: string) => page.locator(sel).evaluate((el) => getComputedStyle(el).zIndex);
    await page.locator("#tag-input").focus();
    await expect(page.locator("#tag-dropdown")).toBeVisible();
    expect(await z("#tags-section")).toBe("30");
    await page.keyboard.press("Escape");
    await expect(page.locator("#tag-dropdown")).toBeHidden();
    expect(await z("#tags-section")).toBe("auto");
  });
});
