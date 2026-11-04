import { test, expect, Browser, BrowserContext, Page } from "@playwright/test";

/**
 * DEB-3558 (specs/requirements/support-is-a-tab-on-the-ai-page.md, amendment 2026-11-04, FR-3 / FR-5; and
 * hervor-answers-support-tickets-with-a-seat-token.md, amendment 2026-11-04, FR-15) — the lane's switch and Hervør's switch
 * and token live on the Support card of Settings ▸ AI. Every press calls its route at once and draws the server's answer; none
 * is part of Save.
 *
 * The e2e stack has no AI key and no tickets, so `/api/ai/status` and `/api/support/**` are stubbed with `page.route`, and
 * `PUT /api/config` is answered 204 after its body is kept (a Save must not rewrite the stack's config). The page renders
 * server-pushed state only, so the stub is the whole server. Stand-in data only: the admin is Hallur, the token is a made-up
 * string. No real viewer and no real title.
 *
 * One login, then a context per viewport carrying its storage state (the login rate limiter is shared across the whole run).
 */
const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";
const NOW = Math.floor(Date.now() / 1000);

const CLOSED_REASON = "Support is closed to viewers, so there is nothing to answer.";
const seatToken = (n: number) => `jss_e2e-stand-in-token-${n}`;

/** The lane and Hervør's seat as a server would hold them. */
class Server {
  laneOpen = true;
  hervor = false;
  seat: { issuedAt: number; issuedBy: string; lastUsedAt: number | null } | null = null;
  seatIssued = 0;
  failNext = false;
  /** Held until released: the reads the card waits for before it draws anything. */
  gate: Promise<void> | null = null;
  writes: { method: string; path: string; body: any }[] = [];
  statusReads = 0;
  saves: string[] = [];

  summary() {
    return { open: 2, owedByAdmin: 1, overdue: 0, laneOpen: this.laneOpen, hervorEnabled: this.hervor, owedByHervor: 0 };
  }
  seatState() { return { live: this.seat, past: [] }; }
}

async function newPage(ctx: BrowserContext, srv: Server): Promise<Page> {
  const page = await ctx.newPage();
  await page.route(/\/api\/ai\/status$/, (r) => {
    srv.statusReads++;
    return r.fulfill({ json: {
      keyHint: "…abcd", pricesAsOf: "2026-10-01",
      models: [{ id: "claude-opus-5", label: "Claude Opus 5", inputPerMTok: 5, outputPerMTok: 25, effort: true }],
      rerank: { spentThisMonthMicroUsd: 0 }, themes: { spentThisMonthMicroUsd: 0 }, clusters: { spentThisMonthMicroUsd: 0 },
      support: { spentThisMonthMicroUsd: 1_230_000, ...(srv.laneOpen ? {} : { inactiveReason: CLOSED_REASON }) },
    } });
  });
  await page.route(/\/api\/config$/, (r) => {
    if (r.request().method() !== "PUT") return r.fallback();
    srv.saves.push(r.request().postData() ?? "");
    return r.fulfill({ status: 204 });
  });
  await page.route(/\/api\/support\//, async (r) => {
    const req = r.request();
    const path = new URL(req.url()).pathname.replace(/^\/api\/support/, "");
    const method = req.method();
    if (srv.gate && method === "GET") await srv.gate;
    const body = method === "POST" ? (() => { try { return req.postDataJSON() ?? {}; } catch { return {}; } })() : {};
    if (method === "GET" && path === "/summary") return r.fulfill({ json: srv.summary() });
    if (method === "GET" && path === "/seat-token") return r.fulfill({ json: srv.seatState() });
    if (method === "GET" && path === "/tickets") return r.fulfill({ json: { tickets: [] } });
    if (method === "POST" || method === "DELETE") {
      srv.writes.push({ method, path, body });
      if (srv.failNext) { srv.failNext = false; return r.fulfill({ status: 500, json: { error: "boom" } }); }
    }
    if (method === "POST" && path === "/enabled") { srv.laneOpen = !!body.enabled; return r.fulfill({ json: srv.summary() }); }
    if (method === "POST" && path === "/hervor") {
      if (body.enabled && !srv.seat) return r.fulfill({ status: 409, json: { error: "no_seat_token" } });
      srv.hervor = !!body.enabled;
      return r.fulfill({ json: srv.summary() });
    }
    if (method === "POST" && path === "/seat-token") {
      srv.seat = { issuedAt: NOW, issuedBy: "Hallur", lastUsedAt: null };
      return r.fulfill({ status: 201, json: { token: seatToken(++srv.seatIssued), live: srv.seat } });
    }
    if (method === "DELETE" && path === "/seat-token") {
      srv.seat = null; srv.hervor = false;   // revoking also switches her off
      return r.fulfill({ json: srv.seatState() });
    }
    return r.fulfill({ status: 404 });
  });
  return page;
}

async function login(page: Page) {
  await page.goto("/login");
  await page.fill('[id="username"], input[name="username"], input[type="text"]', JF_USER);
  await page.fill('[id="password"], input[name="password"], input[type="password"]', JF_PASS);
  await page.click('button[type="submit"], button:has-text("Sign in"), button:has-text("Login")');
  await expect(page.locator('.statgrid, h1:has-text("Dashboard")').first()).toBeVisible({ timeout: 15_000 });
}

const card = (p: Page) => p.locator("#ai-support-acts");
const lane = (p: Page) => p.locator("#ai-support-lane");
const hv = (p: Page) => p.locator("#ai-support-hv");
const token = (p: Page) => p.locator("#ai-support-hv-token");
const hvButtons = (p: Page) => hv(p).locator("button");

async function openSettingsAi(p: Page) {
  await p.goto("/#/settings?tab=ai");
  await expect(p.locator("#ai-support-spent")).toContainText("Spent this month", { timeout: 15_000 });
}
const switchTab = (p: Page, tab: string) => p.locator(`.settings-nav-item[data-tab="${tab}"]`).click();

const VIEWPORTS = [
  { name: "phone 412px", viewport: { width: 412, height: 800 }, mobile: true },
  { name: "desktop", viewport: { width: 1366, height: 900 }, mobile: false },
];

test.describe.serial("Settings ▸ AI — the Support card's lane and Hervør lines (DEB-3558)", () => {
  let storage: Awaited<ReturnType<BrowserContext["storageState"]>>;

  test.beforeAll(async ({ browser }: { browser: Browser }) => {
    const ctx = await browser.newContext();
    await login(await ctx.newPage());
    storage = await ctx.storageState();
    await ctx.close();
  });

  for (const vp of VIEWPORTS) {
    test.describe(vp.name, () => {
      let ctx: BrowserContext;
      let srv: Server;
      let page: Page;

      test.beforeAll(async ({ browser }: { browser: Browser }) => {
        ctx = await browser.newContext({ storageState: storage, viewport: vp.viewport, hasTouch: vp.mobile, isMobile: vp.mobile });
      });
      test.afterAll(async () => { await ctx.close(); });
      test.beforeEach(() => { srv = new Server(); });
      test.afterEach(async () => { await page.close(); });

      test("1 — a closed lane: the card's reason has no link, the line reads Closed, and Open support to viewers acts at once and outside Save (FR-3, FR-5)", async () => {
        srv.laneOpen = false;
        let release!: () => void;
        srv.gate = new Promise<void>((res) => { release = res; });
        page = await newPage(ctx, srv);
        await page.goto("/#/settings?tab=ai");
        const why = page.locator("#ai-support-inactive");
        await expect(why).toBeVisible({ timeout: 15_000 });
        await expect(why).toHaveText(CLOSED_REASON);
        await expect(why.locator("a")).toHaveCount(0);
        // Nothing is drawn until the reads have answered.
        await expect(card(page)).toBeAttached();
        await expect(card(page)).not.toContainText("These act at once");
        await expect(card(page).locator("button")).toHaveCount(0);
        release();

        await expect(card(page).locator("p.hint").first()).toHaveText("These act at once — they are not part of Save.");
        await expect(lane(page)).toContainText("Closed to viewers — Ravilo shows them no Support entry, and nothing can be filed. Tickets already filed are kept.");
        await expect(lane(page).locator("button")).toHaveText("Open support to viewers");
        // The line sits under the status lines of the card, and no `.tk-*` class is on Settings.
        const spent = (await page.locator("#ai-support-estimate").boundingBox())!;
        expect((await card(page).boundingBox())!.y).toBeGreaterThan(spent.y);
        await expect(card(page).locator('[class*="tk-"]')).toHaveCount(0);

        const reads = srv.statusReads;
        await lane(page).getByRole("button", { name: "Open support to viewers" }).click();
        await expect(lane(page)).toContainText("Open to viewers — Ravilo shows them a Support entry.");
        await expect(lane(page).locator("button")).toHaveText("Close support");
        expect(srv.writes).toEqual([{ method: "POST", path: "/enabled", body: { enabled: true } }]);
        // The card's status is read again, and the closed reason goes with it.
        await expect.poll(() => srv.statusReads).toBeGreaterThan(reads);
        await expect(why).toBeHidden();

        // Not part of Save: the form is unchanged and a Save sends no `support` key.
        await page.locator("#save-settings").click();
        await expect.poll(() => srv.saves.length).toBe(1);
        expect(Object.keys(JSON.parse(srv.saves[0]))).not.toContain("support");
        expect(srv.writes).toHaveLength(1);
      });

      test("2 — an open lane: Close support asks first, Cancel calls nothing, and the confirm closes it (FR-3)", async () => {
        page = await newPage(ctx, srv);
        await openSettingsAi(page);
        await expect(lane(page)).toContainText("Open to viewers — Ravilo shows them a Support entry.");
        await expect(page.locator("#ai-support-inactive")).toBeHidden();

        await lane(page).getByRole("button", { name: "Close support" }).click();
        const dlg = page.locator('[data-ai-dlg="close"]');
        await expect(dlg).toContainText("Close support?");
        await expect(dlg).toContainText("Viewers lose the Support entry in Ravilo. Their tickets are kept, and come back when you open it again.");
        await expect(dlg.getByRole("button")).toHaveText(["Close support", "Cancel"]);
        await dlg.getByRole("button", { name: "Cancel" }).click();
        await expect(dlg).toHaveCount(0);
        expect(srv.writes).toHaveLength(0);
        await expect(lane(page)).toContainText("Open to viewers");

        await lane(page).getByRole("button", { name: "Close support" }).click();
        await page.locator('[data-ai-dlg="close"]').getByRole("button", { name: "Close support" }).click();
        await expect(lane(page)).toContainText("Closed to viewers");
        expect(srv.writes).toEqual([{ method: "POST", path: "/enabled", body: { enabled: false } }]);
        // The inactive line follows the server's answer to the status read, in the same card.
        await expect(page.locator("#ai-support-inactive")).toHaveText(CLOSED_REASON);
      });

      test("3 — a failed write says Not saved and leaves the line as the server last said (FR-3)", async () => {
        page = await newPage(ctx, srv);
        await openSettingsAi(page);
        await expect(lane(page)).toContainText("Open to viewers");
        srv.failNext = true;
        await lane(page).getByRole("button", { name: "Close support" }).click();
        await page.locator('[data-ai-dlg="close"]').getByRole("button", { name: "Close support" }).click();
        await expect(lane(page)).toContainText("Not saved — try again.");
        await expect(lane(page)).toContainText("Open to viewers");
        await expect(page.locator('[data-ai-dlg]')).toHaveCount(0);
        expect(srv.laneOpen).toBe(true);
      });

      test("4 — Hervør's four states, both confirms, a one-click Revoke, and the token cleared on leaving the tab (FR-15)", async () => {
        page = await newPage(ctx, srv);
        await openSettingsAi(page);

        // State 1 — no token. The line is drawn with the lane open or closed, and the sentence is followed by the primary button.
        await expect(hv(page)).toContainText("Hervør has no token, so she can't read or answer tickets.");
        await expect(hv(page)).toContainText("Hervør reads the tickets she holds — their text and the viewer's language, never the viewer's name or id.");
        await expect(hvButtons(page)).toHaveText(["Issue a token"]);
        expect((await lane(page).boundingBox())!.y).toBeLessThan((await hv(page).boundingBox())!.y);

        // Issuing shows the plaintext once, in a read-only mono field the admin can select in one go.
        await hvButtons(page).click();
        await expect(token(page)).toContainText("Token issued — copy it now, it won't be shown again:");
        await expect(token(page).locator(".input.mono")).toHaveText(seatToken(1));
        expect(await token(page).locator(".input.mono").evaluate((el) => getComputedStyle(el).userSelect)).toBe("all");
        expect(srv.writes.map((w) => w.path)).toEqual(["/seat-token"]);

        // State 2 — a live token, the switch off. The token never reaches the line, only the one-time field.
        await expect(hv(page)).toContainText("Hervør's token was issued");
        await expect(hv(page)).toContainText("by Hallur · last used never. She is switched off, so tickets go to admins.");
        await expect(hvButtons(page)).toHaveText(["Switch Hervør on", "Replace token", "Revoke"]);
        await expect(hv(page)).not.toContainText("jss_");

        // Another Settings tab: the field is gone; the token is in no Save body.
        await switchTab(page, "processing");
        await switchTab(page, "ai");
        await expect(token(page)).toBeEmpty();
        await expect(page.locator("#sect-ai")).not.toContainText("jss_");
        await page.locator("#save-settings").click();
        await expect.poll(() => srv.saves.length).toBe(1);
        expect(srv.saves[0]).not.toContain("jss_");
        expect(Object.keys(JSON.parse(srv.saves[0]))).not.toContain("support");

        // State 3 — switched on: the line renders the summary the server answered with.
        await hv(page).getByRole("button", { name: "Switch Hervør on" }).click();
        await expect(hv(page)).toContainText("Hervør answers what AI Agent forwards to her · token issued");
        await expect(hvButtons(page)).toHaveText(["Switch Hervør off", "Replace token", "Revoke"]);

        // Switch off asks first; Cancel calls nothing.
        await hv(page).getByRole("button", { name: "Switch Hervør off" }).click();
        const off = page.locator('[data-ai-dlg="hv-off"]');
        await expect(off).toContainText("Switch Hervør off?");
        await expect(off).toContainText("Every ticket she holds or owes goes back to admins. The viewers see it forwarded.");
        await expect(off.getByRole("button")).toHaveText(["Switch off", "Cancel"]);
        await off.getByRole("button", { name: "Cancel" }).click();
        await expect(off).toHaveCount(0);
        expect(srv.writes).toHaveLength(2);
        await hv(page).getByRole("button", { name: "Switch Hervør off" }).click();
        await page.locator('[data-ai-dlg="hv-off"]').getByRole("button", { name: "Switch off" }).click();
        await expect(hvButtons(page)).toHaveText(["Switch Hervør on", "Replace token", "Revoke"]);
        expect(srv.writes[2]).toEqual({ method: "POST", path: "/hervor", body: { enabled: false } });
        await hv(page).getByRole("button", { name: "Switch Hervør on" }).click();
        await expect(hvButtons(page)).toHaveText(["Switch Hervør off", "Replace token", "Revoke"]);

        // Replace asks first, then shows the new token and leaves the switch where it was.
        await hv(page).getByRole("button", { name: "Replace token" }).click();
        const replace = page.locator('[data-ai-dlg="hv-replace"]');
        await expect(replace).toContainText("Replace Hervør's token?");
        await expect(replace).toContainText("The one she has stops working at once, and she can't answer until she is given the new one.");
        await expect(replace.getByRole("button")).toHaveText(["Replace", "Cancel"]);
        await replace.getByRole("button", { name: "Cancel" }).click();
        expect(srv.seatIssued).toBe(1);
        await hv(page).getByRole("button", { name: "Replace token" }).click();
        await page.locator('[data-ai-dlg="hv-replace"]').getByRole("button", { name: "Replace" }).click();
        await expect(token(page).locator(".input.mono")).toHaveText(seatToken(2));
        await expect(hvButtons(page)).toHaveText(["Switch Hervør off", "Replace token", "Revoke"]);
        expect(srv.hervor).toBe(true);

        // Revoke is one click, with no dialog: back to no token, it says what happened, and the token field is gone.
        await hv(page).getByRole("button", { name: "Revoke" }).click();
        await expect(page.locator("[data-ai-dlg]")).toHaveCount(0);
        await expect(hv(page)).toContainText("Hervør has no token, so she can't read or answer tickets.");
        await expect(hv(page)).toContainText("Revoked. Hervør is switched off, and her tickets are with admins.");
        await expect(token(page)).toBeEmpty();
        expect(srv.hervor).toBe(false);
        expect(srv.writes.at(-1)).toEqual({ method: "DELETE", path: "/seat-token", body: {} });
      });

      test("5 — a token already issued is read when the section opens; with the lane closed the line is still drawn (FR-15)", async () => {
        srv.laneOpen = false;
        srv.seat = { issuedAt: NOW - 86_400, issuedBy: "Hallur", lastUsedAt: NOW - 3_600 };
        srv.hervor = true;
        page = await newPage(ctx, srv);
        await openSettingsAi(page);
        await expect(lane(page)).toContainText("Closed to viewers");
        await expect(hv(page)).toContainText("Hervør answers what AI Agent forwards to her · token issued");
        await expect(hv(page)).toContainText("last used 1h ago.");
        await expect(token(page)).toBeEmpty();
        await expect(page.locator("#sect-ai")).not.toContainText("jss_");
      });

      test("6 — the 409 no_seat_token answer says Not saved and the line stays as the server last said (FR-8, FR-15)", async () => {
        srv.seat = { issuedAt: NOW - 86_400, issuedBy: "Hallur", lastUsedAt: null };
        page = await newPage(ctx, srv);
        await openSettingsAi(page);
        await expect(hvButtons(page)).toHaveText(["Switch Hervør on", "Replace token", "Revoke"]);
        srv.seat = null;   // revoked elsewhere since the section opened
        await hv(page).getByRole("button", { name: "Switch Hervør on" }).click();
        await expect(hv(page)).toContainText("Not saved — try again.");
        await expect(hvButtons(page)).toHaveText(["Switch Hervør on", "Replace token", "Revoke"]);
        expect(srv.writes).toEqual([{ method: "POST", path: "/hervor", body: { enabled: true } }]);
        expect(srv.hervor).toBe(false);
      });
    });
  }
});
