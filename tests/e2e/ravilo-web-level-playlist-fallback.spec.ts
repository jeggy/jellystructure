import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";

// DEB-3543 — trouble-never-reaches-the-viewer.md FR-4 (DEB-3213's 2026-11-04 note): a level playlist
// that fails falls to the rung below at once, so the first retry already asks the lower rung. hls.js
// 1.7.3 names the rung of a level-playlist error in `context.level` and has no `level` of its own;
// the player read `d.level`, found nothing, and asked the same rung's playlist for 12 s.
//
// The player's hls.js config and ERROR handler live in a `js()` string of the wasm bundle, so this
// lifts the handler out of RaviloPlayerWasm.kt itself and runs it on the vendored hls.js against a
// ladder whose top rung's playlist is a connection reset. Its own HTTP server, no backend, no app: a reset socket is what QA made, and
// Playwright's route.abort fails the request in a way hls.js moves down on at once.

const ROOT = resolve(__dirname, "../..");
// the Playwright container sees only tests/, so docker-compose.test.yml mounts the two files and names them here
const HLS = readFileSync(
  process.env.LAB_HLS_JS ?? resolve(ROOT, "ravilo-web/src/wasmJsMain/resources/vendor/hls.min.js"),
  "utf8",
);
const WASM_SRC = readFileSync(
  process.env.LAB_PLAYER_SRC ?? resolve(ROOT, "ravilo-ui/src/wasmJsMain/kotlin/dev/jellystructure/ravilo/ui/seams/RaviloPlayerWasm.kt"),
  "utf8",
);
const HANDLER = (() => {
  const from = WASM_SRC.indexOf("hls.on(Hls.Events.ERROR");
  const to = WASM_SRC.indexOf("// R376 (FR-R376-3)", from);
  if (from < 0 || to < 0) throw new Error("the ERROR handler is no longer where this spec lifts it from");
  return WASM_SRC.slice(from, to);
})();

const RUNGS = [18_384_000, 8_000_000, 4_000_000, 1_500_000]; // v/0 is the top rung; hls.js orders its levels ascending

let server: Server;
let origin = "";
const asked: string[] = [];

test.beforeAll(async () => {
  server = createServer((req, res) => {
    const path = (req.url ?? "").split("?")[0];
    if (path === "/") return void res.writeHead(200, { "content-type": "text/html" }).end("<video id=v muted></video>");
    if (path === "/hls.js") return void res.writeHead(200, { "content-type": "text/javascript" }).end(HLS);
    if (path === "/master.m3u8") {
      const body =
        "#EXTM3U\n" +
        RUNGS.map((b, i) => `#EXT-X-STREAM-INF:BANDWIDTH=${b},CODECS="avc1.640028,mp4a.40.2"\nv/${i}/main.m3u8\n`).join("");
      return void res.writeHead(200, { "content-type": "application/vnd.apple.mpegurl" }).end(body);
    }
    const m = path.match(/^\/v\/(\d)\/main\.m3u8$/);
    if (m) {
      asked.push(path);
      if (m[1] === "0") return void req.socket.destroy();
      return void res
        .writeHead(200, { "content-type": "application/vnd.apple.mpegurl" })
        .end("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:6.0,\nseg0.ts\n#EXT-X-ENDLIST\n");
    }
    res.writeHead(404).end();
  });
  await new Promise<void>((ok) => server.listen(0, "127.0.0.1", ok));
  origin = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});
test.afterAll(() => void server.close());

test("a failed level playlist asks the rung below on its first retry, not the same rung again", async ({ page }) => {
  await page.goto(`${origin}/`);
  await page.addScriptTag({ url: `${origin}/hls.js` });

  await page.evaluate((handler) => {
    const w = window as any;
    const video = document.getElementById("v") as any;
    // the player's retry policy: five scheduled retries 0.5/1/2/4/5 s apart (WebHlsPolicy), so a same-rung retry takes seconds
    // and any status is retried (hls.js's own rule would not retry a reset or a 4xx); nothing is buffered here
    const retry = () => ({
      maxNumRetry: 5,
      retryDelayMs: 500,
      maxRetryDelayMs: 5000,
      backoff: "exponential",
      shouldRetry: (_c: unknown, n: number) => n < 5,
    });
    const policy = () => ({
      default: { maxTimeToFirstByteMs: 20000, maxLoadTimeMs: 30000, timeoutRetry: retry(), errorRetry: retry() },
    });
    const hls = new w.Hls({
      enableWorker: false,
      abrEwmaDefaultEstimate: 60e6, // start on the top rung, as a play on a fast link does
      fragLoadPolicy: policy(),
      manifestLoadPolicy: policy(),
      playlistLoadPolicy: policy(),
    });
    video._hls = hls;
    video._rvFailed = [];
    video._rvLoad = null;
    new Function("hls", "video", "Hls", handler)(hls, video, w.Hls);
    hls.loadSource("/master.m3u8");
    hls.attachMedia(video);
  }, HANDLER);

  // the spec's "first retry" is ≈0.5 s after the failure; the bug took 12 s, so 4 s tells them apart
  await expect.poll(() => asked.includes("/v/1/main.m3u8"), { timeout: 4_000 }).toBe(true);
  expect(asked.filter((p) => p === "/v/0/main.m3u8").length).toBeLessThanOrEqual(2);
  expect(await page.evaluate(() => (document.getElementById("v") as any)._rvFailed.length)).toBeGreaterThan(0);
});
