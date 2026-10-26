import { test, expect, Page } from "@playwright/test";

/**
 * DEB-2544 (restores DEB-2196 amendment 2026-10-25, The toasts; DEB-2470, FR-15.4) — a run stopped, continued from the *Scan cancelled* toast and
 * stopped again toasts *Scan cancelled* a second time, and the Dashboard follows the resumed run (*Stop scan*). The server is a stub (status, the
 * start, `resume`, `cancel` and the `/ws` pushes), so no real run starts; what the server does with a stop and a resume is proved by
 * `CancelledRunFinishTest` and the ScanTracker tests (linuxX64Test).
 */
const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";

test("a resumed run that is stopped again toasts Scan cancelled, and the Dashboard follows it after Continue scan", async ({ page }: { page: Page }) => {
  const sockets: any[] = [];
  let status: any = { running: false, status: "IDLE" };
  let resumes = 0;
  await page.routeWebSocket(/\/ws$/, (ws) => { sockets.push(ws); });
  await page.route("**/api/scan/status", (r) => r.fulfill({ json: status }));
  await page.route("**/api/processing/scan", async (route) => {
    if (route.request().method() !== "POST") return route.continue();
    status = { running: true, status: "RUNNING", jobId: "scan-test" };
    await route.fulfill({ status: 202, json: { status: "started", jobId: "scan-test", library: "all", skipped: [], skippedText: "", skippedNote: "" } });
  });
  await page.route("**/api/scan/resume", async (route) => {
    resumes++;
    status = { running: true, status: "RUNNING", jobId: "scan-test" };
    await route.fulfill({ status: 202, json: { status: "resumed" } });
  });
  const finish = (cancelled: boolean) => {
    status = cancelled ? { running: false, status: "CANCELLED", jobId: "scan-test", processedCount: 3, canResume: true } : { running: false, status: "IDLE" };
    for (const s of sockets) s.send(JSON.stringify({ type: "finished", jobId: "scan-test", succeeded: 0, failed: 0 }));
  };

  await page.goto("/login");
  await page.fill('input[type="text"]', JF_USER);
  await page.fill('input[type="password"]', JF_PASS);
  await page.click('button[type="submit"], button:has-text("Sign in"), button:has-text("Login")');
  await expect(page.locator(".statgrid")).toBeVisible({ timeout: 15_000 });

  const cancelled = page.locator(".pf-toast", { hasText: "Scan cancelled" });
  await page.locator("#dash-scan").click();
  await page.locator('#scan-dialog-scrim [data-act="start"]').click();
  await expect(page.locator(".pf-toast", { hasText: "Scan started" })).toBeVisible();
  await expect(page.locator("#cancel-scan-btn")).toBeVisible();

  // Stop → the first Scan cancelled toast, with Continue scan.
  finish(true);
  await expect(cancelled).toHaveCount(1);
  await cancelled.locator("[data-pf-continue]").click();
  await expect(cancelled).toHaveCount(0);
  expect(resumes).toBe(1);
  // The Dashboard follows the run the toast resumed.
  await expect(page.locator("#cancel-scan-btn")).toBeVisible();

  // Stop again → the second toast, and it can be continued again (FR-15.4).
  finish(true);
  await expect(cancelled).toHaveCount(1);
  await expect(cancelled.locator("[data-pf-continue]")).toBeVisible();
});

test("Continue scan on the Dashboard banner arms the toast for the resumed run too", async ({ page }: { page: Page }) => {
  const sockets: any[] = [];
  let status: any = { running: false, status: "CANCELLED", jobId: "scan-test", processedCount: 3, canResume: true };
  await page.routeWebSocket(/\/ws$/, (ws) => { sockets.push(ws); });
  await page.route("**/api/scan/status", (r) => r.fulfill({ json: status }));
  await page.route("**/api/scan/resume", async (route) => {
    status = { running: true, status: "RUNNING", jobId: "scan-test" };
    await route.fulfill({ status: 202, json: { status: "resumed" } });
  });

  await page.goto("/login");
  await page.fill('input[type="text"]', JF_USER);
  await page.fill('input[type="password"]', JF_PASS);
  await page.click('button[type="submit"], button:has-text("Sign in"), button:has-text("Login")');
  await expect(page.locator(".statgrid")).toBeVisible({ timeout: 15_000 });

  await page.getByText("Continue scan").first().click();
  await expect(page.locator("#cancel-scan-btn")).toBeVisible();
  status = { running: false, status: "CANCELLED", jobId: "scan-test", processedCount: 4, canResume: true };
  for (const s of sockets) s.send(JSON.stringify({ type: "finished", jobId: "scan-test", succeeded: 0, failed: 0 }));
  await expect(page.locator(".pf-toast", { hasText: "Scan cancelled" })).toHaveCount(1);
});
