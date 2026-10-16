import { test, expect, devices, Page, WebSocketRoute } from "@playwright/test";

/**
 * DEB-1501 (specs/ravilo/requirements/a-support-ticket-reads-as-a-chat.md, FR-1, FR-2, FR-7) — what Ravilo web does about
 * support on the wire, in a real browser, at a phone viewport and a desktop viewport.
 *
 * The Ravilo UI is a Compose canvas with no DOM to click (see ravilo-web.spec.ts: CI never touches its rendering, and a
 * spec that did hung the job once), so the screens themselves — the list, the thread, the composer, the clamp, the
 * strings — are covered by `SupportScreensTest` (Compose, in `:ravilo-ui:testDebugUnitTest`) and by the on-device QA
 * plan. What only a browser can show is wired here: with a signed-in session and `support_enabled` on, the app
 *
 *   - reads `GET /api/tv/support/unread` when the flag is on (FR-2), and never when it is off or absent;
 *   - opens its events socket with `support` in `features=` (FR-7);
 *   - reads the unread count again on `{"type":"support_changed"}`, and on a reconnect — and nothing else from the event.
 *
 * The whole server is stubbed (`page.route` and `page.routeWebSocket`): the e2e stack has no AI key, so the real lane is
 * closed. The viewer is round 3's *Eyð*; no real viewer, no real title.
 */
const WEB = process.env.RAVILO_WEB_URL ?? "http://localhost:8082";
const ORIGIN = new URL(WEB).origin;

const phone = (() => {
  const { defaultBrowserType: _unused, ...pixel5 } = devices["Pixel 5"];
  return pixel5;
})();

type Server = { unreadHits: number; ticketHits: number; sockets: WebSocketRoute[]; socketUrls: string[]; unread: number };

async function boot(page: Page, supportEnabled: boolean | undefined): Promise<Server> {
  const srv: Server = { unreadHits: 0, ticketHits: 0, sockets: [], socketUrls: [], unread: 2 };

  // A signed-in web session, as `MultiTokenStoreWasm` writes it, pointing at this origin.
  await page.addInitScript((origin) => {
    localStorage.setItem("ravilo_sessions", '[{"u":"u-eyd","n":"Eyð","t":"e2e-support-token","a":false,"k":false}]');
    localStorage.setItem("ravilo_active_user", "u-eyd");
    localStorage.setItem("ravilo_token", "e2e-support-token");
    localStorage.setItem("ravilo_base_url", origin);
    localStorage.setItem("ravilo_device_id", "e2e-support-device");
  }, ORIGIN);

  await page.route(`${ORIGIN}/api/tv/**`, async (route) => {
    const url = new URL(route.request().url());
    const json = (status: number, body: unknown) =>
      route.fulfill({ status, contentType: "application/json", headers: { "access-control-allow-origin": "*" }, body: JSON.stringify(body) });
    switch (url.pathname) {
      case "/api/tv/config":
        return json(200, supportEnabled === undefined ? { ui_language: "en" } : { ui_language: "en", support_enabled: supportEnabled });
      case "/api/tv/config/rev":
        return json(200, { rev: 1 });
      case "/api/tv/support/unread":
        srv.unreadHits++;
        return json(200, { tickets: srv.unread });
      case "/api/tv/support/tickets":
        srv.ticketHits++;
        return json(200, { tickets: [] });
      default:
        return json(404, { error: "not_stubbed" });
    }
  });

  await page.routeWebSocket(/\/api\/tv\/events/, (ws) => {
    srv.sockets.push(ws);
    srv.socketUrls.push(ws.url());
  });
  return srv;
}

async function open(page: Page) {
  await page.goto(WEB, { waitUntil: "domcontentloaded", timeout: 10_000 });
}

const viewports = [
  { name: "phone", use: phone },
  { name: "desktop", use: { viewport: { width: 1280, height: 800 } } },
] as const;

for (const vp of viewports) {
  test.describe(`Ravilo web support wiring — ${vp.name}`, () => {
    test.use(vp.use);

    test("with the flag on, it reads the unread count and asks for support events", async ({ page }) => {
      test.setTimeout(60_000);
      const srv = await boot(page, true);
      await open(page);
      await expect.poll(() => srv.unreadHits, { timeout: 40_000 }).toBeGreaterThan(0);
      await expect.poll(() => srv.socketUrls.length, { timeout: 40_000 }).toBeGreaterThan(0);
      const features = new URL(srv.socketUrls[0]).searchParams.get("features") ?? "";
      expect(features.split(",")).toContain("support");
    });

    test("a support_changed push reads the unread count again and does nothing else", async ({ page }) => {
      test.setTimeout(60_000);
      const srv = await boot(page, true);
      await open(page);
      await expect.poll(() => srv.unreadHits, { timeout: 40_000 }).toBeGreaterThan(0);
      await expect.poll(() => srv.sockets.length, { timeout: 40_000 }).toBeGreaterThan(0);
      const before = srv.unreadHits;
      const ticketsBefore = srv.ticketHits;
      srv.sockets[0].send(JSON.stringify({ type: "support_changed", ticket: 41 }));
      await expect.poll(() => srv.unreadHits, { timeout: 20_000 }).toBeGreaterThan(before);
      // The event carries no content, and no list or thread is open: only the count is read.
      expect(srv.ticketHits).toBe(ticketsBefore);
    });

    test("a reconnect reads the unread count again", async ({ page }) => {
      test.setTimeout(90_000);
      const srv = await boot(page, true);
      await open(page);
      await expect.poll(() => srv.sockets.length, { timeout: 40_000 }).toBeGreaterThan(0);
      await expect.poll(() => srv.unreadHits, { timeout: 40_000 }).toBeGreaterThan(0);
      const before = srv.unreadHits;
      await srv.sockets[0].close({ code: 1001, reason: "server restart" });
      await expect.poll(() => srv.sockets.length, { timeout: 60_000 }).toBeGreaterThan(1);
      await expect.poll(() => srv.unreadHits, { timeout: 20_000 }).toBeGreaterThan(before);
    });

    test("with the flag off, or absent as on every server before 320, it asks nothing of the support routes", async ({ page, context }) => {
      test.setTimeout(60_000);
      for (const flag of [false, undefined]) {
        const p = await context.newPage();
        const srv = await boot(p, flag);
        await open(p);
        await expect.poll(() => srv.socketUrls.length, { timeout: 40_000 }).toBeGreaterThan(0);
        // Long enough for the config read and the first unread read to have happened if they were going to.
        await p.waitForTimeout(4_000);
        expect(srv.unreadHits, `support_enabled=${flag}`).toBe(0);
        expect(srv.ticketHits).toBe(0);
        await p.close();
      }
      await page.close();
    });
  });
}
