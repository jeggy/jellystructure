import { test, expect, Browser, BrowserContext, Page, WebSocketRoute } from "@playwright/test";

/**
 * DEB-1500 (specs/requirements/support-is-a-tab-on-the-ai-page.md) — Support as a tab on the admin's AI page, the lane's
 * switch, the list, one ticket, the rail, the forward and the flag, Activity › Support log, and the offline strip.
 * Acceptance 1, 4, 5, 7 and 9, at a phone and a desktop viewport.
 *
 * The e2e stack has no AI key and no tickets, so `/api/ai/page`, `/api/ai/status`, `/api/support/**` and the `/ws` event are
 * stubbed with `page.route` / `page.routeWebSocket`. The page renders server-pushed state only, so the stubs are the whole
 * server: a small in-memory lane that answers each write with the thread it came to, and pushes `support_ticket` on `/ws`.
 * Stand-in data is round 3's only: viewers Jákup Hansen and Ása Dam and Sunnvør Holm, the admin Hallur, tickets #41, #38,
 * #36, and `Nordvest`, the design corpus's own series. No real viewer and no real title.
 *
 * One login, then a context per viewport carrying its storage state (the login rate limiter is shared across the whole run).
 */
const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";
const NOW = Math.floor(Date.now() / 1000);

type Ticket = {
  number: number; ownerId: string; ownerName: string; state: string; holder: string; owes: string; owedSince: number; overdue: boolean;
  class: string | null; englishSummary?: string; englishTitle?: string; title?: string; viewerLanguage: string; flagged: boolean;
  preview: string; lastActivity: number; unread: number;
};
type Message = {
  id: number; kind: string; author: string | null; authorLabel?: string | null; body?: string | null; event?: string | null;
  eventArgs: string[]; visibility: string; devicePlatform?: string | null; createdAt: number;
};

/** A stand-in for the one-time plaintext: the stub server hands out a new one per issue so a replace is visible. */
const seatToken = (n: number) => `jss_e2e-stand-in-token-${n}`;

const FIRST_41 = "Nordvest S02E04 loses its sound about ten minutes in, on the TV in the sitting room";
const TITLE_41 = "Sound drops in Nordvest S02E04 on the sitting-room TV";

function ticket(o: Partial<Ticket> & { number: number }): Ticket {
  return {
    ownerId: "u-jakup", ownerName: "Jákup Hansen", state: "OPEN", holder: "AI_AGENT", owes: "AI_AGENT", owedSince: NOW - 600, overdue: false,
    class: null, viewerLanguage: "en", flagged: false, preview: "", lastActivity: NOW - 600, unread: 0, ...o,
  };
}
function msg(id: number, o: Partial<Message>): Message {
  return { kind: "MESSAGE", author: "VIEWER", eventArgs: [], visibility: "VIEWER", createdAt: NOW - 600 + id, ...o, id };
}

/** The lane as a server would hold it. */
class Lane {
  laneOpen = true;
  hervor = false;
  /** DEB-1508 — Hervør's seat token as the admin reads it; null is "no live token". The plaintext is only ever in an issue's answer. */
  seat: { issuedAt: number; issuedBy: string; lastUsedAt: number | null } | null = null;
  seatIssued = 0;
  tabs: string[] | null = ["support"];
  down = false;
  delayMs = 0;
  nextId = 100;
  writes: { path: string; body: any }[] = [];
  summaryReads = 0;
  tickets = new Map<number, Ticket>();
  threads = new Map<number, Message[]>();
  composing = new Set<number>();
  turns = new Map<number, any[]>();
  log: any[] = [];
  sockets: WebSocketRoute[] = [];

  constructor() {
    this.tickets.set(41, ticket({ number: 41, class: "BUG", englishTitle: TITLE_41, englishSummary: "The sound drops about ten minutes in.", preview: FIRST_41, unread: 1 }));
    this.threads.set(41, [
      msg(1, { body: FIRST_41, devicePlatform: "phone" }),
      msg(2, { kind: "SYSTEM", author: null, event: "RECEIVED", eventArgs: ["41"] }),
    ]);
    this.tickets.set(38, ticket({ number: 38, ownerId: "u-asa", ownerName: "Ása Dam", holder: "ADMIN", owes: "ADMIN", class: "REQUEST", englishTitle: "Remember audio language per series", preview: "could it remember my audio language per series", lastActivity: NOW - 7200 }));
    this.threads.set(38, [msg(1, { body: "could it remember my audio language per series", devicePlatform: "phone" })]);
    this.tickets.set(36, ticket({ number: 36, ownerId: "u-sunnva", ownerName: "Sunnvør Holm", state: "RESOLVED", holder: "ADMIN", owes: "NONE", class: "QUESTION", englishTitle: "Keeping subtitles on by default", preview: "how do I keep subtitles on by default", lastActivity: NOW - 86_400 * 12 }));
    this.threads.set(36, [msg(1, { body: "how do I keep subtitles on by default", devicePlatform: "web" })]);
    this.turns.set(41, [{ at: NOW - 590, model: "claude-opus-5", sent: "<thread>\n[VIEWER] Nordvest S02E04 loses its sound\n</thread>", answer: "{\"class\":\"BUG\"}", verdict: "FORWARDED: class is BUG — a person answers it", inputTokens: 1200, outputTokens: 180, costMicroUsd: 12_345 }]);
    this.log = [
      { id: 83, at: NOW - 60, ticket: 41, actor: "ADMIN", actorLabel: "Hallur", action: "flagged", args: [] },
      { id: 81, at: NOW - 120, ticket: 38, actor: "ADMIN", actorLabel: "Hallur", action: "replied", args: [] },
      { id: 79, at: NOW - 590, ticket: 41, actor: "AI_AGENT", actorLabel: "AI Agent", action: "ai_verdict", args: ["FORWARDED: class is BUG — a person answers it"] },
      { id: 41, at: NOW - 600, ticket: 41, actor: "VIEWER", actorLabel: "Jákup Hansen", action: "received", args: [] },
    ];
  }

  summary() {
    const live = [...this.tickets.values()].filter((t) => !t.flagged);
    return {
      open: live.filter((t) => t.state !== "RESOLVED").length, owedByAdmin: live.filter((t) => t.owes === "ADMIN").length,
      overdue: live.filter((t) => t.overdue).length, laneOpen: this.laneOpen, hervorEnabled: this.hervor,
      owedByHervor: live.filter((t) => t.owes === "HERVOR").length,
    };
  }
  seatState() { return { live: this.seat, past: [] }; }
  thread(n: number) {
    return { ticket: this.tickets.get(n), messages: this.threads.get(n) ?? [], aiComposing: this.composing.has(n) };
  }
  list(q: URLSearchParams) {
    return [...this.tickets.values()]
      .filter((t) => t.flagged === (q.get("flagged") === "true"))
      .filter((t) => !q.get("state") || t.state === q.get("state"))
      .filter((t) => !q.get("owes") || t.owes === q.get("owes"))
      .filter((t) => q.get("overdue") !== "true" || t.overdue)
      .sort((a, b) => b.lastActivity - a.lastActivity);
  }
  /** Pushes `support_ticket` to every `/ws` the page opened. */
  push(n: number, deleted = false) {
    const ev = JSON.stringify({ type: "support_ticket", number: n, ticket: deleted ? null : this.tickets.get(n), summary: this.summary(), deleted });
    for (const s of this.sockets) s.send(ev);
  }
  addMsg(n: number, o: Partial<Message>) {
    const list = this.threads.get(n) ?? [];
    list.push(msg(this.nextId++, { createdAt: NOW, ...o }));
    this.threads.set(n, list);
  }
  touch(n: number, o: Partial<Ticket>) {
    this.tickets.set(n, { ...this.tickets.get(n)!, lastActivity: NOW, ...o });
  }
}

/** Installs the stub server on a page. */
async function newPage(ctx: BrowserContext, lane: Lane): Promise<Page> {
  const page = await ctx.newPage();
  await page.routeWebSocket(/\/ws$/, (ws) => { lane.sockets.push(ws); });
  await page.route(/\/api\/ai\/page$/, (r) => (lane.tabs ? r.fulfill({ json: { tabs: lane.tabs } }) : r.fulfill({ status: 404 })));
  await page.route(/\/api\/suggestions\/summary/, (r) => r.fulfill({ json: { waiting: 2, new_count: 0, top_clusters: [] } }));
  await page.route(/\/api\/ai\/status$/, (r) => r.fulfill({ json: {
    keyHint: "…abcd", pricesAsOf: "2026-10-01",
    models: [{ id: "claude-opus-5", label: "Claude Opus 5", inputPerMTok: 5, outputPerMTok: 25, effort: true }],
    rerank: { spentThisMonthMicroUsd: 0 }, themes: { spentThisMonthMicroUsd: 0 }, clusters: { spentThisMonthMicroUsd: 0 },
    support: { spentThisMonthMicroUsd: 1_230_000, ...(lane.laneOpen ? {} : { inactiveReason: "Support is closed to viewers, so there is nothing to answer. Open it on AI › Support." }) },
  } }));
  await page.route(/\/api\/support\//, async (r) => {
    const req = r.request();
    const url = new URL(req.url());
    const path = url.pathname.replace(/^\/api\/support/, "");
    const method = req.method();
    if (lane.delayMs && method === "POST") await new Promise((res) => setTimeout(res, lane.delayMs));
    const body = method === "POST" ? (() => { try { return req.postDataJSON() ?? {}; } catch { return {}; } })() : {};
    let m: RegExpMatchArray | null;
    if (method === "GET" && path === "/summary") { lane.summaryReads++; return r.fulfill({ json: lane.summary() }); }
    if (method === "GET" && path === "/tickets") return r.fulfill({ json: { tickets: lane.list(url.searchParams) } });
    if (method === "GET" && path === "/log") return r.fulfill({ json: { entries: lane.log } });
    if (method === "GET" && (m = path.match(/^\/tickets\/(\d+)\/transcript$/))) {
      const n = Number(m[1]);
      return lane.tickets.has(n) ? r.fulfill({ json: { ticket: n, turns: lane.turns.get(n) ?? [] } }) : r.fulfill({ status: 404, json: { error: "not_found" } });
    }
    if (method === "GET" && (m = path.match(/^\/tickets\/(\d+)$/))) {
      const n = Number(m[1]);
      return lane.tickets.has(n) ? r.fulfill({ json: lane.thread(n) }) : r.fulfill({ status: 404, json: { error: "not_found" } });
    }
    if (method === "GET" && path === "/seat-token") return r.fulfill({ json: lane.seatState() });
    if (method === "POST" && path === "/seat-token") {
      lane.writes.push({ path, body });
      lane.seat = { issuedAt: NOW, issuedBy: "Hallur", lastUsedAt: null };
      return r.fulfill({ status: 201, json: { token: seatToken(++lane.seatIssued), live: lane.seat } });
    }
    if (method === "DELETE" && path === "/seat-token") {
      lane.writes.push({ path, body });
      lane.seat = null; lane.hervor = false;   // revoking also switches her off (FR-2)
      return r.fulfill({ json: lane.seatState() });
    }
    if (method === "POST" && path === "/hervor") {
      lane.writes.push({ path, body });
      if (body.enabled && !lane.seat) return r.fulfill({ status: 409, json: { error: "no_seat_token" } });
      lane.hervor = !!body.enabled;
      return r.fulfill({ json: lane.summary() });
    }
    if (method === "POST" && path === "/enabled") {
      lane.writes.push({ path, body });
      lane.laneOpen = !!body.enabled;
      return r.fulfill({ json: lane.summary() });
    }
    if (method === "POST" && (m = path.match(/^\/tickets\/(\d+)\/(messages|forward|state|class|flag|read)$/))) {
      const n = Number(m[1]);
      const what = m[2];
      if (what === "read") return r.fulfill({ status: 204 });
      lane.writes.push({ path, body });
      if (!lane.laneOpen) return r.fulfill({ status: 409, json: { error: "support_unavailable" } });
      if (what === "messages") { lane.addMsg(n, { author: "ADMIN", authorLabel: "Hallur", body: body.body }); lane.touch(n, { owes: "NONE", overdue: false }); }
      if (what === "forward") {
        lane.addMsg(n, { kind: "SYSTEM", author: null, event: "FORWARDED", eventArgs: [body.holder] });
        lane.touch(n, { holder: body.holder, owes: body.owes ?? "ADMIN" });
      }
      if (what === "state") {
        lane.addMsg(n, { kind: "SYSTEM", author: null, event: "STATE_CHANGED", eventArgs: [body.state] });
        lane.touch(n, { state: body.state, owes: body.state === "RESOLVED" ? "NONE" : lane.tickets.get(n)!.holder });
      }
      if (what === "class") { lane.addMsg(n, { kind: "SYSTEM", author: null, event: "CLASSIFIED", eventArgs: [body.class] }); lane.touch(n, { class: body.class }); }
      if (what === "flag") {
        lane.addMsg(n, { kind: "SYSTEM", author: null, event: body.flagged ? "FLAGGED" : "UNFLAGGED", eventArgs: [], visibility: "ADMIN_ONLY" });
        lane.tickets.set(n, { ...lane.tickets.get(n)!, flagged: !!body.flagged });
      }
      return r.fulfill({ json: lane.thread(n) });
    }
    return r.fulfill({ status: 404 });
  });
  // Last registered runs first: while the server is "down" every API call gets no response, whatever else stubs it.
  await page.route(/\/api\//, (r) => (lane.down ? r.abort("connectionfailed") : r.fallback()));
  return page;
}

async function login(page: Page) {
  await page.goto("/login");
  await page.fill('[id="username"], input[name="username"], input[type="text"]', JF_USER);
  await page.fill('[id="password"], input[name="password"], input[type="password"]', JF_PASS);
  await page.click('button[type="submit"], button:has-text("Sign in"), button:has-text("Login")');
  await expect(page.locator('.statgrid, h1:has-text("Dashboard")').first()).toBeVisible({ timeout: 15_000 });
}

const hash = (p: Page) => p.evaluate(() => location.hash);
const historyLength = (p: Page) => p.evaluate(() => history.length);
const navAi = (p: Page) => p.locator('.app-side a.nav[href="#/ai"]');
const panel = (p: Page) => p.locator("#ai-support");
const rows = (p: Page) => p.locator("#tk-rows .tk-row");
const row = (p: Page, n: number) => p.locator(`#tk-rows .tk-row[data-tk-open="${n}"]`);
async function openDashboard(p: Page) {
  await p.goto("/#/dashboard");
  await expect(p.locator("#page-content > *").first()).toBeAttached();
  await expect(p.locator("#page-content")).not.toContainText("Loading…");
}

const VIEWPORTS = [
  { name: "phone 412px", viewport: { width: 412, height: 800 }, mobile: true },
  { name: "desktop", viewport: { width: 1366, height: 900 }, mobile: false },
];

test.describe.serial("AI page — Support as a tab (DEB-1500)", () => {
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
      let lane: Lane;
      let page: Page;

      test.beforeAll(async ({ browser }: { browser: Browser }) => {
        ctx = await browser.newContext({ storageState: storage, viewport: vp.viewport, hasTouch: vp.mobile, isMobile: vp.mobile });
      });
      test.afterAll(async () => { await ctx.close(); });
      test.beforeEach(() => { lane = new Lane(); });
      test.afterEach(async () => { await page.close(); });

      test("1 — the owner's case: a key and a closed lane open the AI page on Support, with the switch inside it (FR-1, FR-3)", async () => {
        lane.laneOpen = false;
        page = await newPage(ctx, lane);
        await page.goto("/#/ai");
        await expect.poll(() => hash(page)).toBe("#/ai?tab=support");
        await expect(page.locator("#ai-tabs span")).toHaveText(["Support"]);
        await expect(page.locator("#ai-tabs span.on")).toHaveText("Support");
        const strip = panel(page).locator("#tk-lane .tk-lane.closed");
        await expect(strip).toContainText("Support is closed. Ravilo shows viewers no Support entry, and nothing can be filed. Tickets already filed are kept.");
        await expect(strip.locator("button")).toHaveText("Open support to viewers");
        // A closed lane still has tickets an admin can read: all three, the resolved one included.
        await expect(rows(page)).toHaveCount(3);
        // No count on the AI entry while the lane is closed (FR-2), even though the summary says two are open.
        await expect(navAi(page).locator(".count")).toBeHidden();
      });

      test("1b — Settings ▸ AI's Support card says why it is not running, above its spend, with a link to the tab (FR-5)", async () => {
        lane.laneOpen = false;
        page = await newPage(ctx, lane);
        await page.goto("/#/settings?tab=ai");
        const why = page.locator("#ai-support-inactive");
        await expect(why).toBeVisible({ timeout: 15_000 });
        await expect(why).toContainText("Support is closed to viewers, so there is nothing to answer. Open it on AI › Support.");
        await expect(why.locator("a")).toHaveAttribute("href", "#/ai?tab=support");
        const spent = page.locator("#ai-support-spent");
        await expect(spent).toContainText("Spent this month");
        expect((await why.boundingBox())!.y).toBeLessThan((await spent.boundingBox())!.y);
        // The old sentence is gone from the description, and the other three jobs say nothing.
        await expect(page.locator("#sect-ai")).not.toContainText("Needs [support] enabled = true in config.toml as well.");
        await expect(page.locator("#ai-rerank-inactive")).toBeHidden();
        // Open: the line goes.
        lane.laneOpen = true;
        await page.reload();
        await expect(spent).toContainText("Spent this month", { timeout: 15_000 });
        await expect(page.locator("#ai-support-inactive")).toBeHidden();
      });

      test("2 — Open support to viewers calls the route and the strip renders its answer; Close asks first (FR-3)", async () => {
        lane.laneOpen = false;
        lane.delayMs = 400;
        page = await newPage(ctx, lane);
        await page.goto("/#/ai?tab=support");
        const strip = panel(page).locator("#tk-lane");
        await expect(strip).toContainText("Support is closed.");
        await strip.locator("button").click();
        // Until the server answers the strip still says what it last heard, and the control is parked.
        await expect(strip).toContainText("Support is closed.");
        await expect(strip.locator("button")).toBeDisabled();
        await expect(strip).toContainText("Open to viewers");
        expect(lane.writes.map((w) => [w.path, w.body])).toEqual([["/enabled", { enabled: true }]]);
        await expect(strip.locator("button")).toHaveText("Close support");
        lane.delayMs = 0;

        // Close asks first, in a dialog. Cancel leaves it open and calls nothing.
        await strip.locator("button").click();
        const dlg = panel(page).locator('[data-tk-dlg="close"]');
        await expect(dlg).toContainText("Close support?");
        await expect(dlg).toContainText("Viewers lose the Support entry in Ravilo. Their tickets are kept, and come back when you open it again.");
        await dlg.getByRole("button", { name: "Cancel" }).click();
        await expect(dlg).toHaveCount(0);
        expect(lane.writes).toHaveLength(1);
        await expect(strip).toContainText("Open to viewers");
        await strip.locator("button").click();
        await panel(page).locator('[data-tk-dlg="close"]').getByRole("button", { name: "Close support" }).click();
        await expect(strip).toContainText("Support is closed.");
        expect(lane.writes.map((w) => w.body.enabled)).toEqual([true, false]);
      });

      test("3 — the tab order is Support first, and #/ai lands on it whether or not Seerr is connected (FR-1)", async () => {
        lane.tabs = ["support", "suggestions"];
        page = await newPage(ctx, lane);
        await page.goto("/#/ai");
        await expect.poll(() => hash(page)).toBe("#/ai?tab=support");
        await expect(page.locator("#ai-tabs > span")).toHaveText([/^Support/, /^Suggestions$/]);
        await expect(page.locator("#ai-tabs span.on")).toContainText("Support");
        // The Support tab carries the open count too, as the strip's own badge.
        await expect(page.locator("#ai-tab-count")).toHaveText("2");
      });

      test("4 — live: a viewer's new ticket appears without a reload, the sidebar count follows the event, and a reply lands from the server's answer (FR-2, FR-6, FR-9)", async () => {
        page = await newPage(ctx, lane);
        await page.goto("/#/dashboard");
        await expect(navAi(page).locator(".count")).toHaveText("2", { timeout: 15_000 });
        await page.goto("/#/ai?tab=support");
        await expect(rows(page)).toHaveCount(3);
        await expect(panel(page).locator("#tk-lane")).toContainText("Open to viewers");
        await expect(row(page, 41)).toContainText("Jákup Hansen");
        await expect(row(page, 41)).toContainText(TITLE_41);
        await expect(row(page, 41).locator(".mono").first()).toContainText("#41");
        // Unread: the dot is on #41 only.
        await expect(row(page, 41).locator("u")).toHaveCount(1);
        await expect(row(page, 38).locator("u")).toHaveCount(0);

        // A new ticket from a viewer's phone, pushed on /ws: no GET of the list happens.
        lane.tickets.set(42, ticket({ number: 42, ownerId: "u-asa", ownerName: "Ása Dam", holder: "AI_AGENT", preview: "the app closes when I press play", unread: 1 }));
        lane.threads.set(42, [msg(1, { author: "VIEWER", body: "the app closes when I press play", devicePlatform: "phone" })]);
        lane.push(42);
        await expect(rows(page)).toHaveCount(4);
        await expect(row(page, 42)).toContainText("the app closes when I press play");
        await expect(row(page, 42).locator(".tt")).toHaveClass(/\bpv\b/); // no englishTitle yet: the preview, muted
        await expect(navAi(page).locator(".count")).toHaveText("3");
        await expect(page.locator("#ai-tab-count")).toHaveText("3");

        // Open it: the URL says so with replaceState, and the thread is the server's.
        const len = await historyLength(page);
        await row(page, 42).click();
        expect(await hash(page)).toBe("#/ai?tab=support&ticket=42");
        expect(await historyLength(page)).toBe(len);
        await expect(panel(page).locator(".tk-dh h2")).toHaveText("Ticket #42");
        await expect(panel(page).locator(".tk-dh .kk")).toContainText("#42 · Ása Dam · phone · opened");
        await expect(panel(page).locator(".tk-th")).toContainText("the app closes when I press play");

        // Reply: the composer locks with Sending… until the answer arrives; the message is the server's.
        const compose = panel(page).locator("#tk-reply");
        await expect(compose).toHaveAttribute("placeholder", /^Reply as .* · admin — it sends itself and lands in the thread$/);
        await compose.fill("Try the new build tonight.");
        lane.delayMs = 400;
        await panel(page).getByRole("button", { name: "Send" }).click();
        await expect(panel(page).locator(".tk-cp")).toContainText("Sending…");
        await expect(panel(page).locator(".tk-th")).not.toContainText("Try the new build tonight.");
        await expect(panel(page).locator(".tk-th")).toContainText("Try the new build tonight.");
        await expect(panel(page).locator(".tk-cp")).not.toContainText("Sending…");
        await expect(panel(page).locator("#tk-reply")).toHaveValue("");
        expect(lane.writes.at(-1)).toEqual({ path: "/tickets/42/messages", body: { body: "Try the new build tonight." } });
        lane.delayMs = 0;

        // Back removes ticket from the URL the same way, and the list is still there.
        await panel(page).locator(".tk-back").click();
        expect(await hash(page)).toBe("#/ai?tab=support");
        expect(await historyLength(page)).toBe(len);
        await expect(rows(page)).toHaveCount(4);

        // A ticket deleted by its viewer goes from the list on the event.
        lane.tickets.delete(42);
        lane.push(42, true);
        await expect(rows(page)).toHaveCount(3);
        await expect(navAi(page).locator(".count")).toHaveText("2");
      });

      test("4b — a refresh and a deep link reopen the ticket; an unknown number says so (FR-7)", async () => {
        page = await newPage(ctx, lane);
        await page.goto("/#/ai?tab=support&ticket=41");
        await expect(panel(page).locator(".tk-dh h2")).toHaveText(TITLE_41, { timeout: 15_000 });
        await page.reload();
        await expect(panel(page).locator(".tk-dh h2")).toHaveText(TITLE_41, { timeout: 15_000 });
        expect(await hash(page)).toBe("#/ai?tab=support&ticket=41");
        await page.goto("/#/ai?tab=support&ticket=9999");
        await expect(panel(page)).toContainText("This ticket no longer exists.");
        await panel(page).locator(".tk-back").click();
        expect(await hash(page)).toBe("#/ai?tab=support");
        await expect(rows(page)).toHaveCount(3);
      });

      test("6 — the filters ask the server and count from the summary; narrow, the table stacks (FR-6)", async () => {
        page = await newPage(ctx, lane);
        await page.goto("/#/ai?tab=support");
        await expect(rows(page)).toHaveCount(3);
        await expect(panel(page).locator("#tk-seg span")).toHaveText(["All", "Open · 2", "Needs a reply · 1", "Overdue · 0", "Resolved", "Flagged"]);
        // The six filters are each whole and on one line of their own text, at either width. At ≤ 560 px the row wraps
        // to get there (amendment 2026-10-17): none hangs past the page, none is clipped, the page never scrolls sideways.
        const filters = await panel(page).locator("#tk-seg span").evaluateAll((els: Element[]) => els.map((el) => {
          const r = el.getBoundingClientRect(), cs = getComputedStyle(el);
          const pad = parseFloat(cs.paddingTop) + parseFloat(cs.paddingBottom) + parseFloat(cs.borderTopWidth) + parseFloat(cs.borderBottomWidth);
          return { text: el.textContent, left: r.left, right: r.right, lines: (r.height - pad) / parseFloat(cs.fontSize), clipped: el.scrollWidth > el.clientWidth };
        }));
        expect(filters).toHaveLength(6);
        for (const f of filters) {
          expect(f.left, f.text ?? "").toBeGreaterThanOrEqual(0);
          expect(f.right, f.text ?? "").toBeLessThanOrEqual(vp.viewport.width);
          expect(f.lines, `${f.text} breaks over more than one line`).toBeLessThan(1.8);
          expect(f.clipped, `${f.text} is clipped`).toBe(false);
        }
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
        await panel(page).locator('[data-tk-filter="resolved"]').click();
        await expect(rows(page)).toHaveCount(1);
        await expect(row(page, 36)).toContainText("Keeping subtitles on by default");
        await expect(row(page, 36).locator(".badge")).toHaveText("Resolved");
        await panel(page).locator('[data-tk-filter="flagged"]').click();
        await expect(panel(page).locator("#tk-rows")).toContainText("No tickets match this filter.");
        await panel(page).locator('[data-tk-filter="all"]').click();
        await expect(rows(page)).toHaveCount(3);
        // The columns' header is a row of its own at width, and goes when the table stacks.
        const header = panel(page).locator(".tk-row.hd");
        if (vp.mobile) await expect(header).toBeHidden(); else await expect(header).toBeVisible();
        // With no tickets at all: the empty line.
        lane.tickets.clear();
        await page.reload();
        await expect(panel(page).locator("#tk-rows")).toContainText("No tickets yet.", { timeout: 15_000 });
      });

      test("5 — forward moves a ticket from AI Agent to Admins and Viewer sees changes; flagging hides the composer (FR-9, FR-10, FR-11)", async () => {
        lane.threads.set(41, [
          ...lane.threads.get(41)!,
          msg(3, { author: "AI_AGENT", authorLabel: "AI Agent", body: "I'll forward this and they will answer you soon." }),
          msg(4, { author: "AI_AGENT", authorLabel: "AI Agent", visibility: "ADMIN_ONLY", body: "AI Agent forwarded this: class is BUG." }),
        ]);
        page = await newPage(ctx, lane);
        await page.goto("/#/ai?tab=support&ticket=41");
        const rail = panel(page).locator("#tk-rail");
        await expect(panel(page).locator(".tk-dh h2")).toHaveText(TITLE_41, { timeout: 15_000 });
        await expect(rail.locator(".tk-kv", { hasText: "Holder" })).toContainText("AI Agent");
        await expect(rail.locator(".tk-kv", { hasText: "Viewer sees" })).toContainText("Open · with AI Agent");
        await expect(rail.locator(".tk-kv", { hasText: "Title" })).toContainText("by AI Agent, from the first message");
        await expect(rail.locator(".tk-kv", { hasText: "Class" }).locator("select")).toHaveValue("BUG");
        // The ADMIN_ONLY line carries its tag, and the viewer's own lines do not.
        await expect(panel(page).locator(".tk-m.adm .tk-bdg.adm")).toHaveText("admin only");
        await expect(panel(page).locator(".tk-m.adm")).toContainText("AI Agent forwarded this: class is BUG.");
        await expect(panel(page).locator(".tk-m.vw .tk-bdg.adm")).toHaveCount(0);
        // Hervør is off in this config: Admins is the only destination.
        await expect(rail.locator("[data-tk-fwd]")).toHaveCount(1);
        await expect(rail.locator("[data-tk-fwd]")).toContainText("Admins");

        await rail.locator("#tk-note").fill("Please look at the second audio track.");
        await rail.getByRole("button", { name: "Forward", exact: true }).click();
        await expect(rail.locator(".tk-kv", { hasText: "Holder" })).toContainText("Admins");
        await expect(rail.locator(".tk-kv", { hasText: "Viewer sees" })).toContainText("Open · with support");
        await expect(panel(page).locator(".tk-sys.fwd")).toContainText("Forwarded to admins");
        // The admin's JSON leaves out what equals its default (owes ADMIN), as the server's does.
        expect(lane.writes.at(-1)!.path).toBe("/tickets/41/forward");
        expect(lane.writes.at(-1)!.body).toMatchObject({ holder: "ADMIN", note: "Please look at the second audio track." });
        // Now an admin holds it: Admins is the current holder, so the only destination is disabled.
        await expect(rail.locator("[data-tk-fwd]")).toHaveClass(/\bno\b/);

        // Flag: the composer is replaced, the banner shows, and replying is off. Remove flag brings them back.
        await expect(panel(page).locator("#tk-reply")).toBeVisible();
        await rail.getByRole("button", { name: "Flag as abuse" }).click();
        await expect(panel(page).locator("#tk-reply")).toHaveCount(0);
        await expect(panel(page).locator(".tk-cp")).toContainText("Replies are off while this ticket is flagged.");
        await expect(panel(page).locator(".tk-flag")).toContainText("Flagged as abuse. No reply will be sent, not even AI Agent's. The viewer sees an open ticket and nothing about the flag.");
        await expect(rail.locator(".tk-kv", { hasText: "State" })).toContainText("Flagged");
        // The viewer is told nothing: the presence line is the one from before the flag.
        await expect(rail.locator(".tk-kv", { hasText: "Viewer sees" })).toContainText("Open · with support");
        await expect(rail.getByRole("button", { name: "Remove flag" })).toBeVisible();
        await rail.getByRole("button", { name: "Remove flag" }).click();
        await expect(panel(page).locator("#tk-reply")).toBeVisible();
        await expect(panel(page).locator(".tk-flag")).toHaveCount(0);
      });

      test("5b — Resolve and Reopen, the class select, and a closed lane under a reply (FR-9, FR-10)", async () => {
        page = await newPage(ctx, lane);
        await page.goto("/#/ai?tab=support&ticket=38");
        const rail = panel(page).locator("#tk-rail");
        await expect(rail.getByRole("button", { name: "Resolve" })).toBeVisible({ timeout: 15_000 });
        await rail.getByRole("button", { name: "Resolve" }).click();
        await expect(rail.locator(".tk-kv", { hasText: "State" })).toContainText("Resolved");
        await expect(panel(page).locator(".tk-sys.ok")).toContainText("State: Resolved");
        await rail.getByRole("button", { name: "Reopen" }).click();
        await expect(rail.getByRole("button", { name: "Resolve" })).toBeVisible();
        expect(lane.writes.map((w) => w.body.state).filter(Boolean)).toEqual(["RESOLVED", "OPEN"]);

        await rail.locator("select[data-tk-class]").selectOption("QUESTION");
        await expect(panel(page).locator(".tk-sys", { hasText: "Classified as Question" })).toBeVisible();
        expect(lane.writes.at(-1)).toEqual({ path: "/tickets/38/class", body: { class: "QUESTION" } });

        // The lane is closed from elsewhere; the next reply is refused with a 409 and the composer says so.
        lane.laneOpen = false;
        await panel(page).locator("#tk-reply").fill("Hello");
        await panel(page).getByRole("button", { name: "Send" }).click();
        await expect(panel(page).locator(".tk-cp")).toContainText("Support is closed — open it above to reply.");
        await expect(panel(page).locator("#tk-reply")).toHaveCount(0);
      });

      test("8 — a long body is clamped with Show the rest inside the bubble, and it is text, never markup (FR-8)", async () => {
        const long = Array.from({ length: 20 }, (_, i) => `line ${i + 1} <b>not bold</b>`).join("\n");
        lane.threads.set(38, [msg(1, { body: long, devicePlatform: "phone" })]);
        page = await newPage(ctx, lane);
        await page.goto("/#/ai?tab=support&ticket=38");
        const bubble = panel(page).locator(".tk-m.cl").first();
        await expect(bubble).toBeVisible({ timeout: 15_000 });
        const more = bubble.locator(".more");
        await expect(more).toHaveText("Show the rest");
        // Inside the bubble: the button's box is within the bubble's box.
        const bb = (await bubble.locator(".t").boundingBox())!;
        const mb = (await more.boundingBox())!;
        expect(mb.y).toBeGreaterThanOrEqual(bb.y);
        expect(mb.y + mb.height).toBeLessThanOrEqual(bb.y + bb.height + 1);
        await expect(bubble.locator(".bd b")).toHaveCount(0);
        await expect(bubble.locator(".bd")).toContainText("<b>not bold</b>");
        await more.click();
        await expect(more).toHaveCount(0);
        await expect(panel(page).locator(".tk-m.cl.op")).toHaveCount(1);
        await expect(panel(page).locator(".tk-m.cl.op .bd")).toContainText("line 20");
      });

      test("10 — AI transcript is there only when AI Agent took a turn, and shows the turn as text (FR-10.9)", async () => {
        page = await newPage(ctx, lane);
        await page.goto("/#/ai?tab=support&ticket=38");
        await expect(panel(page).locator("#tk-rail")).toBeVisible({ timeout: 15_000 });
        await expect(panel(page).getByRole("button", { name: /AI transcript for #38/ })).toHaveCount(0);
        await page.goto("/#/ai?tab=support&ticket=41");
        const link = panel(page).getByRole("button", { name: "AI transcript for #41 →" });
        await expect(link).toBeVisible({ timeout: 15_000 });
        await link.click();
        const dlg = panel(page).locator('[data-tk-dlg="transcript"]');
        await expect(dlg).toContainText("claude-opus-5");
        await expect(dlg).toContainText("$0.0123");
        await expect(dlg).toContainText("FORWARDED: class is BUG — a person answers it");
        await dlg.locator("summary", { hasText: "Sent to the model" }).click();
        await expect(dlg.locator("pre").first()).toContainText("<thread>");
        await expect(dlg.locator("pre b")).toHaveCount(0);
        await dlg.locator(".x").click();
        await expect(dlg).toHaveCount(0);
      });

      test("7 — Activity › Support log: history only, newest first, no text, #n links to the ticket (FR-12)", async () => {
        page = await newPage(ctx, lane);
        await page.goto("/#/activity?view=support");
        const log = page.locator("#view-support");
        await expect(log.locator(".tk-lg")).toHaveCount(4, { timeout: 15_000 });
        await expect(page.locator("#viewseg-support")).toHaveText("Support log");
        await expect(page.locator("#view-console")).toBeHidden();
        const first = log.locator(".tk-lg").first();
        await expect(first).toContainText("Hallur");
        await expect(first).toContainText("flagged it as abuse");
        await expect(first.locator("a.tk")).toHaveText("#41");
        await expect(first.locator("a.tk")).toHaveAttribute("href", "#/ai?tab=support&ticket=41");
        await expect(log.locator(".tk-lg").nth(2)).toContainText("AI verdict · forwarded it · class is BUG — a person answers it");
        await expect(log.locator(".tk-lg").last()).toContainText("Jákup Hansen");
        await expect(log.locator(".tk-lg").last()).toContainText("filed the ticket");
        // No text of any ticket is anywhere on the view.
        await expect(log).not.toContainText("Nordvest");
        await expect(log).not.toContainText(TITLE_41);
        // Nothing on it is actionable: no button, no input.
        await expect(log.locator("button, input, textarea, select")).toHaveCount(0);
        // The link lands on the ticket.
        await first.locator("a.tk").click();
        await expect(panel(page).locator(".tk-dh h2")).toHaveText(TITLE_41, { timeout: 15_000 });
        expect(await hash(page)).toBe("#/ai?tab=support&ticket=41");
      });

      test("7b — an event adds an entry on the open log; with no Support tab there is no Support log (FR-12)", async () => {
        page = await newPage(ctx, lane);
        await page.goto("/#/activity?view=support");
        const log = page.locator("#view-support");
        await expect(log.locator(".tk-lg")).toHaveCount(4, { timeout: 15_000 });
        lane.log.unshift({ id: 85, at: NOW, ticket: 38, actor: "ADMIN", actorLabel: "Hallur", action: "state_changed", args: ["RESOLVED"] });
        lane.push(38);
        await expect(log.locator(".tk-lg")).toHaveCount(5);
        await expect(log.locator(".tk-lg").first()).toContainText("resolved it");

        // No key: the AI page has no tabs, and the log is not offered; ?view=support stays on the console.
        lane.tabs = null;
        await page.reload();
        expect(await hash(page)).toBe("#/activity?view=support");
        await expect(page.locator("#view-console")).toBeVisible({ timeout: 15_000 });
        await expect(page.locator("#viewseg-support")).toBeHidden();
        await expect(page.locator("#view-support")).toBeHidden();
      });

      test("9 — losing the server shows the strip and parks every Support write; it clears on its own (FR-16)", async () => {
        lane.laneOpen = false;
        page = await newPage(ctx, lane);
        await page.goto("/#/ai?tab=support");
        const open = panel(page).getByRole("button", { name: "Open support to viewers" });
        await expect(open).toBeVisible({ timeout: 15_000 });
        await expect(page.locator("#ai-offl")).toBeHidden();

        lane.down = true;
        await open.click();
        await expect(page.locator("#ai-offl")).toBeVisible();
        await expect(page.locator("#ai-offl")).toContainText("Connection to this server lost · reconnecting.");
        await expect(page.locator("#ai-offl")).toContainText("Nothing is sent until it’s back.");
        await expect(page.locator("#ai-offl")).not.toContainText("Download");
        expect((await open.evaluate((el) => getComputedStyle(el).pointerEvents))).toBe("none");
        // Even a forced press sends nothing.
        const served = lane.writes.length;
        await open.dispatchEvent("click");
        expect(lane.writes.length).toBe(served);

        lane.down = false;
        await expect(page.locator("#ai-offl")).toBeHidden({ timeout: 12_000 });
        await expect(open).toBeVisible();
        expect((await open.evaluate((el) => getComputedStyle(el).pointerEvents))).not.toBe("none");
        await open.click();
        await expect(panel(page).locator("#tk-lane")).toContainText("Open to viewers");
        expect(lane.writes.map((w) => w.path)).toEqual(["/enabled"]);
      });

      test("11 — Hervør's strip: its four states, the one-time token field, both confirms, and a one-click revoke (DEB-1508 FR-15)", async () => {
        page = await newPage(ctx, lane);
        await page.goto("/#/ai?tab=support");
        const strip = panel(page).locator("#tk-hv");
        const buttons = strip.locator("button");
        const shown = panel(page).locator("#tk-hv-token");

        // State 1 — no token. A second strip under the lane's, built from the same markup (no class of its own).
        await expect(strip.locator(".tk-lane.closed")).toContainText("Hervør has no token, so she can't read or answer tickets.", { timeout: 15_000 });
        await expect(buttons).toHaveText(["Issue a token"]);
        await expect(strip).toContainText("Hervør reads the tickets she holds — their text and the viewer's language, never the viewer's name or id.");
        expect((await panel(page).locator("#tk-lane").boundingBox())!.y).toBeLessThan((await strip.boundingBox())!.y);

        // Issuing shows the plaintext once, in a read-only mono field the admin can select in one go.
        await buttons.click();
        await expect(shown).toContainText("Token issued — copy it now, it won't be shown again:");
        await expect(shown.locator(".input.mono")).toHaveText(seatToken(1));
        expect(await shown.locator(".input.mono").evaluate((el) => getComputedStyle(el).userSelect)).toBe("all");
        expect(lane.writes.map((w) => w.path)).toEqual(["/seat-token"]);

        // State 2 — a live token, the switch off.
        await expect(strip.locator(".tk-lane")).toContainText("Hervør's token was issued");
        await expect(strip.locator(".tk-lane")).toContainText("by Hallur · last used never. She is switched off, so tickets go to admins.");
        await expect(buttons).toHaveText(["Switch Hervør on", "Replace token", "Revoke"]);
        // The token never reaches the strip, only the one-time field.
        await expect(strip).not.toContainText("jss_");

        // State 3 — switched on: the strip renders the summary the server answered with, and Forward to lists her.
        await strip.getByRole("button", { name: "Switch Hervør on" }).click();
        await expect(strip.locator(".tk-lane")).toContainText("Hervør answers what AI Agent forwards to her · token issued");
        await expect(strip.locator(".tk-lane")).toContainText("by Hallur · last used never.");
        await expect(buttons).toHaveText(["Switch Hervør off", "Replace token", "Revoke"]);
        expect(lane.writes.map((w) => [w.path, w.body])).toEqual([["/seat-token", {}], ["/hervor", { enabled: true }]]);
        await row(page, 41).click();
        await expect(panel(page).locator("#tk-rail")).toContainText("Hervør · support");
        await panel(page).locator('[data-tk="back"]').click();

        // Switch off asks first; Cancel calls nothing.
        await strip.getByRole("button", { name: "Switch Hervør off" }).click();
        const off = panel(page).locator('[data-tk-dlg="hv-off"]');
        await expect(off).toContainText("Switch Hervør off?");
        await expect(off).toContainText("Every ticket she holds or owes goes back to admins. The viewers see it forwarded.");
        await expect(off.getByRole("button")).toHaveText(["Switch off", "Cancel"]);
        await off.getByRole("button", { name: "Cancel" }).click();
        await expect(off).toHaveCount(0);
        expect(lane.writes).toHaveLength(2);
        await strip.getByRole("button", { name: "Switch Hervør off" }).click();
        await panel(page).locator('[data-tk-dlg="hv-off"]').getByRole("button", { name: "Switch off" }).click();
        await expect(buttons).toHaveText(["Switch Hervør on", "Replace token", "Revoke"]);
        expect(lane.writes[2]).toEqual({ path: "/hervor", body: { enabled: false } });
        await strip.getByRole("button", { name: "Switch Hervør on" }).click();
        await expect(buttons).toHaveText(["Switch Hervør off", "Replace token", "Revoke"]);

        // Replace asks first, then shows the new token and leaves the switch where it was.
        await strip.getByRole("button", { name: "Replace token" }).click();
        const replace = panel(page).locator('[data-tk-dlg="hv-replace"]');
        await expect(replace).toContainText("Replace Hervør's token?");
        await expect(replace).toContainText("The one she has stops working at once, and she can't answer until she is given the new one.");
        await expect(replace.getByRole("button")).toHaveText(["Replace", "Cancel"]);
        await replace.getByRole("button", { name: "Cancel" }).click();
        expect(lane.seatIssued).toBe(1);
        await strip.getByRole("button", { name: "Replace token" }).click();
        await panel(page).locator('[data-tk-dlg="hv-replace"]').getByRole("button", { name: "Replace" }).click();
        await expect(shown.locator(".input.mono")).toHaveText(seatToken(2));
        await expect(buttons).toHaveText(["Switch Hervør off", "Replace token", "Revoke"]);
        expect(lane.hervor).toBe(true);

        // Revoke is one click, with no dialog: the strip is back to no token, it says what happened, and the token field is gone.
        await strip.getByRole("button", { name: "Revoke" }).click();
        await expect(panel(page).locator('[data-tk-dlg]')).toHaveCount(0);
        await expect(strip.locator(".tk-lane.closed")).toContainText("Hervør has no token, so she can't read or answer tickets.");
        await expect(strip).toContainText("Revoked. Hervør is switched off, and her tickets are with admins.");
        await expect(shown).toBeEmpty();
        expect(lane.hervor).toBe(false);
        expect(lane.writes.at(-1)).toEqual({ path: "/seat-token", body: {} });
      });

      test("11b — a token already issued is read on open, and the offline strip parks every Hervør button (DEB-1508 FR-15)", async () => {
        lane.seat = { issuedAt: NOW - 86_400, issuedBy: "Hallur", lastUsedAt: NOW - 3_600 };
        lane.hervor = true;
        page = await newPage(ctx, lane);
        await page.goto("/#/ai?tab=support");
        const strip = panel(page).locator("#tk-hv");
        await expect(strip.locator(".tk-lane")).toContainText("Hervør answers what AI Agent forwards to her · token issued", { timeout: 15_000 });
        await expect(strip.locator(".tk-lane")).toContainText("last used 1h ago.");
        // A reload shows only the issued-by line: the plaintext is not anywhere.
        await expect(panel(page).locator("#tk-hv-token")).toBeEmpty();
        await expect(panel(page)).not.toContainText("jss_");

        lane.down = true;
        await strip.getByRole("button", { name: "Revoke" }).click();
        await expect(page.locator("#ai-offl")).toBeVisible();
        for (const name of ["Switch Hervør off", "Replace token", "Revoke"]) {
          expect(await strip.getByRole("button", { name: name }).evaluate((el) => getComputedStyle(el).pointerEvents), name).toBe("none");
        }
        const served = lane.writes.length;
        await strip.getByRole("button", { name: "Revoke" }).dispatchEvent("click");
        expect(lane.writes.length).toBe(served);
        lane.down = false;
        await expect(page.locator("#ai-offl")).toBeHidden({ timeout: 12_000 });
      });
    });
  }

  test("2b — the narrow layout puts the rail under the thread, and the wide one beside it (FR-10)", async ({ browser }: { browser: Browser }) => {
    for (const vp of VIEWPORTS) {
      const ctx = await browser.newContext({ storageState: storage, viewport: vp.viewport, hasTouch: vp.mobile, isMobile: vp.mobile });
      const lane = new Lane();
      const page = await newPage(ctx, lane);
      try {
        await page.goto("/#/ai?tab=support&ticket=41");
        const thread = panel(page).locator(".tk-th");
        const rail = panel(page).locator("#tk-rail");
        await expect(thread).toBeVisible({ timeout: 15_000 });
        const t = (await thread.boundingBox())!;
        const r = (await rail.boundingBox())!;
        if (vp.viewport.width <= 900) expect(r.y, vp.name).toBeGreaterThanOrEqual(t.y + t.height - 1);
        else expect(r.x, vp.name).toBeGreaterThanOrEqual(t.x + t.width - 1);
      } finally {
        await ctx.close();
      }
    }
  });
});
