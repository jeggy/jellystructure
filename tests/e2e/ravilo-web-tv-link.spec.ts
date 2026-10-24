import { test, expect } from "@playwright/test";

// DEB-2233 (FR-6, FR-7) — the web image serves the app at the TV's QR address, and the Digital Asset Links statement that lets the
// phone app claim that address. Request-only, like ravilo-web-headers.spec.ts: the app reading `?c=` is a unit test
// (tvCodeFromLocation), and the app booting is ravilo-web.spec.ts.

const WEB = process.env.RAVILO_WEB_URL ?? "http://localhost:8082";
const SCREEN = process.env.RAVILO_SCREEN_URL ?? "http://localhost:8083"; // the same server binary, with no certificates configured

test("the QR's address /tv?c=<code> is the app shell", async ({ request }) => {
  const res = await request.get(`${WEB}/tv?c=K7P4QX`);
  expect(res.status()).toBe(200);
  expect(res.headers()["content-type"]).toContain("text/html");
  expect(await res.text()).toContain("ComposeTarget");
});

test("assetlinks.json names the package and both signing certificates", async ({ request }) => {
  const res = await request.get(`${WEB}/.well-known/assetlinks.json`);
  expect(res.status()).toBe(200);
  expect(res.headers()["content-type"]).toContain("application/json");
  expect(res.headers()["cache-control"]).toContain("no-cache");
  const statements = await res.json();
  expect(statements).toHaveLength(1);
  expect(statements[0].relation).toEqual(["delegate_permission/common.handle_all_urls"]);
  expect(statements[0].target.namespace).toBe("android_app");
  expect(statements[0].target.package_name).toBe("dev.jellystructure.ravilo");
  expect(statements[0].target.sha256_cert_fingerprints).toHaveLength(2);
  for (const f of statements[0].target.sha256_cert_fingerprints) expect(f).toMatch(/^([0-9A-F]{2}:){31}[0-9A-F]{2}$/);
});

test("an image with no certificates configured has no assetlinks.json", async ({ request }) => {
  const res = await request.get(`${SCREEN}/.well-known/assetlinks.json`);
  expect(res.status()).toBe(404);
});
