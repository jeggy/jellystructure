import { test, expect } from "@playwright/test";

// Phase 237 (FR-237-5) — the 512² icon Google's Cast Developer Console requires for Listing Details.
//
// It is a COMMITTED artefact (cast-receiver/icon-512.png), not something generated at build time, for
// two reasons the dev review established: nothing in the build can rasterize an SVG, and the only
// vector master lived under `design/`, which the design sync overwrites wholesale — so a build step
// reading from there is a build the next design export can break silently, in CI, at image-build time.
//
// What these assertions protect is the failure mode that would otherwise be invisible: the route
// serving *something* that is not the icon. `/cast/**` is a plain static route outside the API auth
// plugin, and an asset-shaped path that does not exist returns a real 404 rather than falling back to
// index.html (FR-235-3) — so "HTML saved as a .png" is the thing to rule out, and the byte signature
// is what rules it out.

test.describe("Cast receiver icon (237)", () => {
  test("GET /cast/icon-512.png is a real PNG, served without a credential", async ({ request }) => {
    const res = await request.get("/cast/icon-512.png");
    expect(res.status()).toBe(200);
    expect(res.headers()["content-type"]).toContain("image/png");

    const body = await res.body();
    // PNG magic: \x89 P N G \r \n \x1a \n. An HTML error page would start with '<'.
    expect(Array.from(body.subarray(0, 8))).toEqual([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

    // IHDR carries width and height as big-endian uint32 at offsets 16 and 20.
    expect(body.readUInt32BE(16), "icon must be 512 wide").toBe(512);
    expect(body.readUInt32BE(20), "icon must be 512 tall").toBe(512);
  });

  test("it is byte-identical across requests", async ({ request }) => {
    // FR-237-5's "never rendered per request" — true by construction now that the file is committed,
    // and asserted so that a future "helpful" dynamic renderer cannot quietly reintroduce the
    // per-request cost (or a per-request difference) without this failing.
    const a = await (await request.get("/cast/icon-512.png")).body();
    const b = await (await request.get("/cast/icon-512.png")).body();
    expect(a.equals(b)).toBe(true);
  });

  test("a missing asset under /cast/ is an honest 404, not an HTML page", async ({ request }) => {
    // The reason the assertion above can trust its own 200. If the static route fell back to
    // index.html for an asset-shaped path, the admin's Download would save an HTML file named
    // icon-512.png and Google's console would reject it with no clue why.
    const res = await request.get("/cast/not-a-real-asset-237.png");
    expect(res.status()).toBe(404);
  });
});
