import { test, expect } from "@playwright/test";

// Phase 235 — the static server behind ravilo.example.net shipped no Content-Security-Policy, no
// compression, an hourly-revalidating cache on content-hashed assets, and a SPA fallback that answered
// a missing manifest/service-worker/icon with the index page instead of 404. Two serving paths carry
// this bundle (the standalone web-static-server container, and the backend's own /tv/** route), and
// they must agree — see web-static-server/Main.kt's own doc comment and Server.kt's serveFrontendFile.
//
// Deliberately request-only, no browser (dev notes: "the header spec needs only request, not a
// browser") — this is entirely a header/status contract, and ravilo-web.spec.ts already covers the
// wasm app actually booting.

const WEB = process.env.RAVILO_WEB_URL ?? "http://localhost:8082";

// The two hash-named .wasm files are the only content-hashed assets today (dev review item 1) — found
// by asking web-static-server's own index for its wasm reference is unreliable (the filename is loaded
// dynamically by ravilo.js, never referenced in index.html), so this pulls it from ravilo.js's own
// source instead, which does reference it directly.
async function findHashedWasmPath(request: import("@playwright/test").APIRequestContext, base: string) {
  const js = await (await request.get(`${base}/ravilo.js`)).text();
  const match = js.match(/[0-9a-f]{16,}\.wasm/);
  if (!match) throw new Error(`No hashed .wasm reference found in ${base}/ravilo.js`);
  return `/${match[0]}`;
}

for (const target of [
  { name: "web-static-server", base: () => WEB },
  // Relative paths below resolve against playwright.config.ts's baseURL (APP_URL) — same pattern
  // ravilo-login.spec.ts uses for the backend.
  { name: "backend /tv/**", base: () => "/tv" },
]) {
  test.describe(`Ravilo web headers — ${target.name}`, () => {
    test("hashed asset: compressed, immutable, Vary'd", async ({ request }) => {
      const base = target.base();
      const wasmPath = await findHashedWasmPath(request, base);

      const res = await request.get(`${base}${wasmPath}`, {
        headers: { "Accept-Encoding": "br, gzip" },
      });
      expect(res.status()).toBe(200);
      const headers = res.headers();
      expect(["br", "gzip"]).toContain(headers["content-encoding"]);
      expect(headers["vary"]?.toLowerCase()).toContain("accept-encoding");
      expect(headers["cache-control"]).toBe("public, max-age=31536000, immutable");
      // The Skiko module — the larger of the two .wasm files (dev review: 8 405 319 bytes identity,
      // ~3.2 MB gzip -9, smaller still under brotli). content-length, not the fetched body's actual
      // size — Playwright's request client transparently decompresses on receipt (confirmed live: the
      // body length always reads as the identity size regardless of Content-Encoding), so the response
      // header is the only place the wire size is still observable from here.
      const wireBytes = Number(headers["content-length"]);
      expect(wireBytes).toBeLessThan(3.5 * 1024 * 1024);
    });

    test("GET / is 200; an asset-shaped 404 is a real 404; an extension-less path falls back to the app", async ({ request }) => {
      const base = target.base();
      const root = await request.get(`${base}/`);
      expect(root.status()).toBe(200);

      // FR-235-3 — a missing manifest/service-worker/icon must 404, never silently become the SPA
      // page. R263 hasn't shipped manifest.webmanifest yet (phase 235's own Non-goals), so this
      // exercises the same rule against a path that will never exist rather than one that should.
      const missingAsset = await request.get(`${base}/nope.png`);
      expect(missingAsset.status()).toBe(404);

      // Ravilo routes on the URL hash, which never reaches the server — an extension-less deep link
      // is "the app itself, at a deep link", not a missing route.
      const deepLink = await request.get(`${base}/some/route`);
      expect(deepLink.status()).toBe(200);
      expect(deepLink.headers()["content-type"]).toContain("text/html");
    });

    test("HEAD matches GET's headers with no body", async ({ request }) => {
      const base = target.base();
      const [get, head] = await Promise.all([request.get(`${base}/`), request.head(`${base}/`)]);
      expect(head.status()).toBe(get.status());
      expect(head.headers()["content-length"]).toBe(get.headers()["content-length"]);
      expect((await head.body()).length).toBe(0);
    });

    test("security headers, incl. manifest-src/worker-src", async ({ request }) => {
      const base = target.base();
      const res = await request.get(`${base}/`);
      const headers = res.headers();
      expect(headers["x-content-type-options"]).toBe("nosniff");
      expect(headers["x-frame-options"]).toBe("DENY");
      const csp = headers["content-security-policy"];
      expect(csp).toBeTruthy();
      expect(csp).toContain("manifest-src 'self'");
      expect(csp).toContain("worker-src 'self'");
      // FR-235-6 — no CDN host: R265 hasn't shipped, so a script-src allowlist entry for a player CDN
      // would be a regression back to the exact defect this phase closes.
      expect(csp).not.toContain("cdn.jsdelivr.net");
    });

    test("index.html carries no injected inline script (FR-235-8)", async ({ request }) => {
      const base = target.base();
      const html = await (await request.get(`${base}/`)).text();
      expect(html).not.toContain("<script>window.__RAVILO");
      expect(html).toContain('<script src="runtime-config.js">');
    });
  });
}

// Parity: both serving paths must answer with the *same* policy (dev notes item 8 — "parity is
// enforced by the e2e spec running the same assertions against both").
test("web-static-server and the backend's /tv/** agree on the security policy", async ({ request }) => {
  const [standalone, embedded] = await Promise.all([
    request.get(`${WEB}/`),
    request.get(`/tv/`),
  ]);
  expect(standalone.headers()["content-security-policy"]).toBe(embedded.headers()["content-security-policy"]);
  expect(standalone.headers()["x-content-type-options"]).toBe(embedded.headers()["x-content-type-options"]);
  expect(standalone.headers()["referrer-policy"]).toBe(embedded.headers()["referrer-policy"]);
});
