import { test, expect } from "@playwright/test";

// R302 — the web app declares only the audio this browser can decode. It used to hard-code AC-3 and
// E-AC-3 for every browser; desktop Chrome/Firefox cannot decode them in MSE, so such titles
// direct-played silent. The probe runs at boot and publishes its answer on window.__raviloAudioCodecs
// (the same pattern as the install prompt's state), which is all these tests read: they never touch
// the canvas (see ravilo-web.spec.ts for why).
const WEB_URL = process.env.RAVILO_WEB_URL ?? "http://localhost:8082";

async function probedList(page: import("@playwright/test").Page): Promise<string[]> {
  await page.goto(WEB_URL, { waitUntil: "domcontentloaded", timeout: 10_000 });
  await expect.poll(() => page.evaluate(() => typeof (window as any).__raviloAudioCodecs === "string"), { timeout: 45_000 }).toBe(true);
  return (await page.evaluate(() => (window as any).__raviloAudioCodecs as string)).split(",");
}

test.describe("Ravilo web audio capabilities (R302)", () => {
  test("the declared list follows what this browser really decodes", async ({ page }) => {
    test.setTimeout(60_000);
    const list = await probedList(page);
    expect(list).toContain("aac");
    expect(list).toContain("mp3");
    // The browser's own answers, asked the same way — the list must agree with them, whichever way
    // this build of Chromium answers (headless Linux Chromium: no AC-3, no E-AC-3).
    const browser = await page.evaluate(() => ({
      ac3: MediaSource.isTypeSupported('audio/mp4; codecs="ac-3"'),
      eac3: MediaSource.isTypeSupported('audio/mp4; codecs="ec-3"'),
      opus: MediaSource.isTypeSupported('audio/mp4; codecs="opus"') || MediaSource.isTypeSupported('audio/webm; codecs="opus"'),
    }));
    expect(list.includes("ac3")).toBe(browser.ac3);
    expect(list.includes("eac3")).toBe(browser.eac3);
    expect(list.includes("opus")).toBe(browser.opus);
  });

  test("it is a probe, not a list: a browser that decodes AC-3 gets AC-3 declared", async ({ page }) => {
    test.setTimeout(60_000);
    await page.addInitScript(() => {
      const real = MediaSource.isTypeSupported.bind(MediaSource);
      MediaSource.isTypeSupported = (type: string) => /ac-3|ec-3/.test(type) || real(type);
    });
    const list = await probedList(page);
    expect(list).toContain("ac3");
    expect(list).toContain("eac3");
  });
});
