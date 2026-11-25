import { test, expect } from "@playwright/test";
import { openReceiver, castSent } from "./helpers/cast-receiver";
import { scannedItemId } from "./helpers/screen-cast";

// R297 + R299 — the Chromecast receiver, driven through the REAL ravilo-cast.js bundle with a fake CAF
// (helpers/cast-receiver.ts). Two things went wrong on real TVs on 2026-09-24 and both are pinned here:
//   R297: the receiver declared AC-3/E-AC-3 it could not play, the backend's transcoding profile
//         repeated the claim, Jellyfin copied AC-3 through, and every cast died two seconds in.
//   R299: a load that failed was reported to the phone as "ended", which the phone showed as
//         "Lost contact… it may still be playing" — with the receiver reachable and idle.
const APP_URL = process.env.APP_URL ?? "http://localhost:9505";
const JF_URL = process.env.JELLYFIN_URL ?? "http://localhost:8096";
const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";
const R252 = { "x-ravilo-platform": "phone", "x-ravilo-version": "9.99" };

test.describe("Chromecast receiver (R297, R299)", () => {
  test("declares only the audio the device can play, and a failed load is said once and honestly", async ({ page, request }) => {
    test.setTimeout(120_000);
    const pageErrors: string[] = [];
    page.on("pageerror", (err) => pageErrors.push(err.message));

    // ── A fake phone: signs in, and mints the hand-off code the receiver enrols with (218 FR-218-9). ──
    const login = await request.post("/api/tv/login", {
      data: { username: JF_USER, password: JF_PASS, device_id: "e2e-cast-phone-r297", device_name: "E2E Cast Phone" }, headers: R252,
    });
    expect(login.status(), await login.text()).toBe(200);
    const phoneToken = (await login.json()).device_token as string;
    const handoff = await request.post("/api/tv/cast/handoff", { headers: { authorization: `Bearer ${phoneToken}`, ...R252 } });
    expect(handoff.status(), await handoff.text()).toBe(200);
    const code = (await handoff.json()).code as string;
    const itemId = await scannedItemId(request, phoneToken, "Sintel", { username: JF_USER, password: JF_PASS });

    // ── The receiver boots and, on LOAD, enrols, probes the device and negotiates with the real backend. ──
    await openReceiver(page, APP_URL);
    const statsBefore = await (await request.get(`${JF_URL}/__mock/stats`)).json();
    const load = await page.evaluate(
      (d) => (window as any).__castLoad(d),
      { server_url: APP_URL, code, item_id: itemId, title: "Sintel", device_name: "E2E Chromecast", lang: "en", sub_size: "M" },
    );
    expect(load, "the interceptor should hand CAF a request with a stream").not.toBeNull();
    expect(load.contentId).toBeTruthy();

    // R297 FR-R297-1 — audio is probed like video, and the answer decides what is declared.
    const probes = await page.evaluate(() => (window as any).__castCanDisplay as string[][]);
    expect(probes.some(([m, c]) => m === "audio/mp4" && c === "ac-3")).toBe(true);
    expect(probes.some(([m, c]) => m === "audio/mp4" && c === "ec-3")).toBe(true);
    expect(probes.some(([m, c]) => m === "audio/mp4" && c === "opus")).toBe(true);

    // R297 FR-R297-4 — the backend's transcoding profile carries only audio the client declared. The
    // mock Jellyfin records the DeviceProfile the backend negotiated with.
    const negotiated = await (await request.get(`${JF_URL}/__mock/last-playback-info`)).json();
    expect(negotiated.itemId).toBe(itemId);
    const profile = negotiated.body.DeviceProfile;
    expect(profile, "the backend must send a DeviceProfile").toBeTruthy();
    expect(profile.DirectPlayProfiles, "an HLS-only receiver gets no direct-play profile (R245 amendment)").toEqual([]);
    const audio = String(profile.TranscodingProfiles[0].AudioCodec).split(",");
    expect(audio).toContain("aac");
    expect(audio).not.toContain("ac3");
    expect(audio).not.toContain("eac3");

    // The receiver told the phone it has media (a "status" message), nothing else so far.
    await expect.poll(async () => (await castSent(page)).map((m) => m.type)).toContain("status");
    expect((await castSent(page)).map((m) => m.type)).not.toContain("failed");

    // ── R299 FR-R299-1 — a load error is reported as "failed", once, and the receiver goes idle. ──
    // CAF reports a failed load on ERROR (player still IDLE) and again on MEDIA_FINISHED(ERROR).
    await page.evaluate(() => { (window as any).__castState = "IDLE"; (window as any).__castFire("ERROR", { detailedErrorCode: 905, reason: "e2e" }); });
    await expect.poll(async () => (await castSent(page)).filter((m) => m.type === "failed").length, { timeout: 10_000 }).toBe(1);
    await page.evaluate(() => (window as any).__castFire("MEDIA_FINISHED", { endedReason: "ERROR" }));
    await page.waitForTimeout(500); // bounded: a second report would arrive within a tick of the event
    const afterFailure = (await castSent(page)).map((m) => m.type);
    expect(afterFailure.filter((t) => t === "failed")).toHaveLength(1);
    expect(afterFailure).not.toContain("ended");
    const failedMsg = (await castSent(page)).find((m) => m.type === "failed");
    expect(failedMsg.item_id).toBe(itemId);
    expect(failedMsg.title).toBe("Sintel");
    await expect(page.locator("#idle")).toHaveClass(/\bon\b/);
    // …and the backend was told to stop the session exactly once (phase 180 teardown).
    await expect.poll(async () => (await (await request.get(`${JF_URL}/__mock/stats`)).json()).stopped - statsBefore.stopped, { timeout: 10_000 }).toBe(1);

    // ── A real end is still an end: a second load, then MEDIA_FINISHED(END_OF_STREAM) → "ended". ──
    const handoff2 = await request.post("/api/tv/cast/handoff", { headers: { authorization: `Bearer ${phoneToken}`, ...R252 } });
    const load2 = await page.evaluate(
      (d) => (window as any).__castLoad(d),
      { server_url: APP_URL, code: (await handoff2.json()).code, item_id: itemId, title: "Sintel", device_name: "E2E Chromecast", lang: "en", sub_size: "M" },
    );
    expect(load2).not.toBeNull();
    await page.evaluate(() => { (window as any).__castState = "PLAYING"; (window as any).__castFire("MEDIA_FINISHED", { endedReason: "END_OF_STREAM" }); });
    await expect.poll(async () => (await castSent(page)).filter((m) => m.type === "ended").length, { timeout: 10_000 }).toBe(1);
    expect((await castSent(page)).filter((m) => m.type === "failed")).toHaveLength(1);

    expect(pageErrors).toEqual([]);
  });

  // 289 — a LOAD over something that is playing. CAF ends the item before with MEDIA_FINISHED(INTERRUPTED), and it
  // does so after the receiver has taken the new item as its own: on a real speaker (2026-09-30) the receiver then
  // stopped the NEW item on the server, said "failed" and went idle — Next, an album over an album and a film's
  // track change all ended in silence.
  test("a load that replaces what is playing is neither an end nor a failure (289)", async ({ page, request }) => {
    test.setTimeout(120_000);
    const pageErrors: string[] = [];
    page.on("pageerror", (err) => pageErrors.push(err.message));
    const login = await request.post("/api/tv/login", {
      data: { username: JF_USER, password: JF_PASS, device_id: "e2e-cast-phone-289", device_name: "E2E Cast Phone 289" }, headers: R252,
    });
    expect(login.status(), await login.text()).toBe(200);
    const phoneToken = (await login.json()).device_token as string;
    const code = async () => (await (await request.post("/api/tv/cast/handoff", { headers: { authorization: `Bearer ${phoneToken}`, ...R252 } })).json()).code as string;
    const itemId = await scannedItemId(request, phoneToken, "Sintel", { username: JF_USER, password: JF_PASS });
    const data = async () => ({ server_url: APP_URL, code: await code(), item_id: itemId, title: "Sintel", device_name: "E2E Chromecast 289", lang: "en", sub_size: "M" });

    await openReceiver(page, APP_URL);
    expect(await page.evaluate((d) => (window as any).__castLoad(d), await data())).not.toBeNull();
    await page.evaluate(() => { (window as any).__castState = "PLAYING"; });
    const statsBefore = await (await request.get(`${JF_URL}/__mock/stats`)).json();

    // The second LOAD, with the framework's INTERRUPTED both while the load is being prepared and once it is handed over.
    const second = await page.evaluate(async (d) => {
      const w = window as any;
      const pending = w.__castLoad(d);
      w.__castFire("MEDIA_FINISHED", { endedReason: "INTERRUPTED" });
      await new Promise((r) => setTimeout(r, 0));
      w.__castFire("MEDIA_FINISHED", { endedReason: "INTERRUPTED" });
      const r = await pending;
      w.__castFire("MEDIA_FINISHED", { endedReason: "INTERRUPTED" });
      return r;
    }, await data());
    expect(second, "the new item is handed to the player").not.toBeNull();
    expect(second.contentId).toBeTruthy();
    await page.waitForTimeout(1_000); // bounded: a "failed" or a second stop would follow the event within a tick
    const types = (await castSent(page)).map((m) => m.type);
    expect(types).not.toContain("failed");
    expect(types).not.toContain("ended");
    await expect(page.locator("#idle")).not.toHaveClass(/\bon\b/);
    // The item before was stopped once, under its own session; the new one was not stopped at all.
    expect((await (await request.get(`${JF_URL}/__mock/stats`)).json()).stopped - statsBefore.stopped).toBe(1);

    // The new item is alive: its own end is still an end.
    await page.evaluate(() => (window as any).__castFire("MEDIA_FINISHED", { endedReason: "END_OF_STREAM" }));
    await expect.poll(async () => (await castSent(page)).filter((m) => m.type === "ended").length, { timeout: 10_000 }).toBe(1);
    expect((await castSent(page)).filter((m) => m.type === "failed")).toHaveLength(0);
    expect(pageErrors).toEqual([]);
  });

  // 289 (FR-289-5) — a speaker has no display, and the receiver used to empty the whole page there. That took the
  // framework's own <cast-media-player> with it: on a real speaker every load after the first failed with 905, and
  // every converted song with Shaka's "Cannot read property 'insertRule' of null".
  test("on a speaker the receiver removes its own screens and leaves the framework's player (289)", async ({ page }) => {
    await openReceiver(page, APP_URL, { headless: true });
    await expect(page.locator("cast-media-player")).toHaveCount(1);
    await expect(page.locator("#idle")).toHaveCount(1);   // nothing is removed before the first LOAD asks what the device is
    // What the device is gets asked at the top of the LOAD, before any request: a server nothing answers on is enough
    // (and keeps this test out of the sign-in limiter's count).
    const load = await page.evaluate((d) => (window as any).__castLoad(d), { server_url: "http://127.0.0.1:9", code: "000000", item_id: "none", title: "—", device_name: "E2E Speaker", lang: "en", sub_size: "M" });
    expect(load, "no server, no stream").toBeNull();
    await expect(page.locator("cast-media-player")).toHaveCount(1);
    await expect(page.locator("body > section, body > div")).toHaveCount(0);
    // The log channel says what was loaded, apart from the messages a phone builds its state from.
    const notes = await page.evaluate(() => ((window as any).__castNotes as any[]).map((n) => String(n.note)));
    expect(notes.some((n) => n.startsWith("load none"))).toBe(true);
    expect((await castSent(page)).map((m) => m.type)).toEqual(["noserver"]);
  });

  // DEB-4994 (R285 FR-R285-4 b′) — the receiver keeps the active text subtitle itself. Its fake CAF reports no active track
  // at all (`getActiveIds()` is `[]` throughout, as it is when CAF drops a track it could not load), and a pick still
  // reaches the layer by every path: the LOAD's default, a phone's EDIT_TRACKS_INFO, our `subtitle` command, and — the one
  // thing that does come from CAF — the platform's own caption control.
  test("the receiver draws the text subtitle it was given, picked and cleared without CAF reporting any (DEB-4994)", async ({ page, request }) => {
    test.setTimeout(120_000);
    const pageErrors: string[] = [];
    page.on("pageerror", (err) => pageErrors.push(err.message));
    const login = await request.post("/api/tv/login", {
      data: { username: JF_USER, password: JF_PASS, device_id: "e2e-cast-phone-4994", device_name: "E2E Cast Phone 4994" }, headers: R252,
    });
    expect(login.status(), await login.text()).toBe(200);
    const phoneToken = (await login.json()).device_token as string;
    const handoff = await request.post("/api/tv/cast/handoff", { headers: { authorization: `Bearer ${phoneToken}`, ...R252 } });
    const code = (await handoff.json()).code as string;
    const itemId = await scannedItemId(request, phoneToken, "Sintel", { username: JF_USER, password: JF_PASS });

    // The ticket the backend hands the receiver gets two text tracks (the first the default), each a path on this server
    // that needs the device token — the shape DEB-2205 gave an embedded track. Everything else in it is the real ticket.
    const files: Record<string, string> = {
      "/api/tv/subtitles/e2e4994/0.vtt": "WEBVTT\n\n1\n00:00:01.000 --> 00:00:50.000\nFirst track line\n",
      "/api/tv/subtitles/e2e4994/1.vtt": "WEBVTT\n\n1\n00:00:01.000 --> 00:00:50.000\nSecond track line\n",
    };
    const asked: Array<{ path: string; authorization: string | undefined }> = [];
    await page.route("**/api/tv/playback/start", async (route) => {
      const real = await route.fetch();
      const ticket = await real.json();
      ticket.burned_subtitle_index = null;
      ticket.subtitles = [
        { index: 3, language: "eng", label: "English", is_default: true, url: "/api/tv/subtitles/e2e4994/0.vtt", delivery_method: "external" },
        { index: 4, language: "dan", label: "Dansk", is_default: false, url: "/api/tv/subtitles/e2e4994/1.vtt", delivery_method: "external" },
      ];
      await route.fulfill({ response: real, json: ticket });
    });
    await page.route("**/api/tv/subtitles/e2e4994/*.vtt", async (route) => {
      const path = new URL(route.request().url()).pathname;
      asked.push({ path, authorization: route.request().headers()["authorization"] });
      await route.fulfill({ status: 200, contentType: "text/vtt", body: files[path] ?? "WEBVTT\n\n" });
    });

    await openReceiver(page, APP_URL);
    const load = await page.evaluate(
      (d) => (window as any).__castLoad(d),
      { server_url: APP_URL, code, item_id: itemId, title: "Sintel", device_name: "E2E Chromecast", lang: "en", sub_size: "M" },
    );
    expect(load, "the interceptor should hand CAF a request with a stream").not.toBeNull();
    const cue = page.locator("#subtitle");
    const clock = (sec: number) => page.evaluate((s) => { (window as any).__castTime = s; }, sec);
    const edit = (ids: number[] | undefined) => page.evaluate((a) => (window as any).__castEdit(a === undefined ? { type: "EDIT_TRACKS_INFO" } : { type: "EDIT_TRACKS_INFO", activeTrackIds: a }), ids);
    const command = (index: number) => page.evaluate((i) => (window as any).__castCommand({ type: "subtitle", index: i }), index);
    const cueElements = () => page.locator("#subtitle").count();

    // The LOAD's shown track is drawn at its cue's time — and not before it.
    await clock(0);
    await expect(cue).toHaveText("");
    await clock(2);
    await expect(cue).toHaveText("First track line", { timeout: 10_000 });
    await expect(cue).toHaveClass(/\bon\b/);
    // …fetched by the receiver with the device token, once (CAF is handed an empty file, never this URL).
    expect(asked.map((a) => a.path)).toEqual(["/api/tv/subtitles/e2e4994/0.vtt"]);
    expect(asked[0].authorization).toMatch(/^Bearer \S+/);

    // A phone's EDIT_TRACKS_INFO: [101] → track 1, [] → off, [100] → track 0 again; each is handed back to CAF as it came.
    expect(await edit([101])).toBe("same");
    await expect(cue).toHaveText("Second track line", { timeout: 10_000 });
    expect(await edit([])).toBe("same");
    await expect(cue).toHaveText("");
    await expect(cue).not.toHaveClass(/\bon\b/);
    expect(await edit([100])).toBe("same");
    await expect(cue).toHaveText("First track line");
    // A style or language-only edit has no activeTrackIds and leaves the pick as it is.
    expect(await edit(undefined)).toBe("same");
    await page.waitForTimeout(800); // bounded: a clear would land within a tick or two
    await expect(cue).toHaveText("First track line");

    // Our own `subtitle` command: a text index, then Off.
    await command(1);
    await expect(cue).toHaveText("Second track line");
    await command(-1);
    await expect(cue).toHaveText("");
    await command(0);
    await expect(cue).toHaveText("First track line");

    // CAF's report stays empty all through: the receiver asked it for each pick, and none was needed for the layer.
    expect(await page.evaluate(() => (window as any).__castActive)).toEqual([]);
    expect(await page.evaluate(() => (window as any).__castSetActive)).toEqual([[101], [], [100]]);

    // The platform's caption control: CAF's report turning to [101] on its own switches the layer…
    await page.evaluate(() => { (window as any).__castActive = [101]; });
    await expect(cue).toHaveText("Second track line", { timeout: 10_000 });
    // …and its report going empty again clears nothing the receiver holds.
    await page.evaluate(() => { (window as any).__castActive = []; });
    await page.waitForTimeout(800);
    await expect(cue).toHaveText("Second track line");

    // One cue element at a time, and the clock past the cue's end draws nothing.
    expect(await cueElements()).toBe(1);
    await clock(60);
    await expect(cue).toHaveText("");

    // The play ends as a real one does, so its session is not left counting against the receivers' ceiling (`max_sessions`)
    // for the next spec: the layer clears with it, and the backend is told to stop.
    const stoppedBefore = (await (await request.get(`${JF_URL}/__mock/stats`)).json()).stopped as number;
    await page.evaluate(() => { (window as any).__castState = "PLAYING"; (window as any).__castFire("MEDIA_FINISHED", { endedReason: "END_OF_STREAM" }); });
    await expect.poll(async () => (await castSent(page)).filter((m) => m.type === "ended").length, { timeout: 10_000 }).toBe(1);
    await expect.poll(async () => (await (await request.get(`${JF_URL}/__mock/stats`)).json()).stopped - stoppedBefore, { timeout: 10_000 }).toBe(1);
    expect(pageErrors).toEqual([]);
  });
});
