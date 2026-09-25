import { test, expect } from "@playwright/test";

// R265 (FR-R265-8) — the web app sends what this browser really plays. Video codecs are asked of the
// browser (it used to declare h264/hevc/vp9/av1 everywhere), and only Safari — HLS natively AND
// AirPlay — says it takes ONLY HLS with the subtitles inside the manifest, so an AirPlay hand-over has a
// stream to give the TV with its subtitles in it. Both answers are published at boot on window (the R302
// pattern); these tests never touch the canvas (see ravilo-web.spec.ts for why).
const WEB_URL = process.env.RAVILO_WEB_URL ?? "http://localhost:8082";

async function probed(page: import("@playwright/test").Page) {
  await page.goto(WEB_URL, { waitUntil: "domcontentloaded", timeout: 10_000 });
  await expect.poll(() => page.evaluate(() => typeof (window as any).__raviloVideoCodecs === "string"), { timeout: 45_000 }).toBe(true);
  return page.evaluate(() => ({
    video: ((window as any).__raviloVideoCodecs as string).split(","),
    airplayHls: (window as any).__raviloAirPlayHls as boolean,
  }));
}

test.describe("Ravilo web video + AirPlay capabilities (R265)", () => {
  test("video codecs follow the browser, and Chromium is not an AirPlay browser", async ({ page }) => {
    test.setTimeout(60_000);
    const { video, airplayHls } = await probed(page);
    expect(video).toContain("h264");
    const browser = await page.evaluate(() => ({
      hevc: MediaSource.isTypeSupported('video/mp4; codecs="hvc1.1.6.L120.90"') || MediaSource.isTypeSupported('video/mp4; codecs="hev1.1.6.L120.90"'),
      av1: MediaSource.isTypeSupported('video/mp4; codecs="av01.0.05M.08"'),
    }));
    expect(video.includes("hevc")).toBe(browser.hevc);
    expect(video.includes("av1")).toBe(browser.av1);
    // No WebKit playback-target events here: the negotiation is exactly what it was before R265.
    expect(airplayHls).toBe(false);
  });

  test("a browser with WebKit's AirPlay and native HLS takes only HLS", async ({ page }) => {
    test.setTimeout(60_000);
    await page.addInitScript(() => {
      (window as any).WebKitPlaybackTargetAvailabilityEvent = function () {};
      const real = HTMLMediaElement.prototype.canPlayType;
      HTMLMediaElement.prototype.canPlayType = function (type: string) {
        return type === "application/vnd.apple.mpegurl" ? "maybe" : real.call(this, type);
      };
    });
    const { airplayHls } = await probed(page);
    expect(airplayHls).toBe(true);
  });

  test("native HLS without AirPlay (Chrome on Android) keeps its negotiation", async ({ page }) => {
    test.setTimeout(60_000);
    await page.addInitScript(() => {
      const real = HTMLMediaElement.prototype.canPlayType;
      HTMLMediaElement.prototype.canPlayType = function (type: string) {
        return type === "application/vnd.apple.mpegurl" ? "maybe" : real.call(this, type);
      };
    });
    const { airplayHls } = await probed(page);
    expect(airplayHls).toBe(false);
  });
});
