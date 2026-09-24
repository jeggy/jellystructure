import { test, expect, type APIRequestContext, type Page } from "@playwright/test";
import { fakeAvPlayInitScript, avplayCalls, waitForAvplayCall, scannedItemId } from "./helpers/screen-cast";

// R285 in CI (2026-09-24) — audio and subtitles on the Samsung TV receiver, driven through the REAL
// ravilo-screen.js bundle. The owner has no Samsung TV, so this is the only place R285 runs at all.
//
// Phase 248's spec proves pair/play/pause/seek/stop. Everything R285 added was still untested
// anywhere: the phone's set_audio / set_subtitle / set_subtitle_size / skip reaching the receiver
// (they matched names nothing sent until R285), text subtitles drawn by the receiver itself from a
// VTT, a picture subtitle burned in by a restream that keeps the audio, an un-burn that goes back to
// the text track, and the status the phone reads (R282's "the burned track IS the selection").
//
// The mock Jellyfin gives Big Buck Bunny two audio tracks (eng default, dan), an English text
// subtitle and a Danish PGS subtitle, and negotiates a transcode whose TranscodingUrl carries the
// AudioStreamIndex / SubtitleStreamIndex it was asked for — see TRACKS_ITEM_* in its server.js.
//
// What only a real Samsung TV can answer is out of reach here and stays "won't test" (owner,
// 2026-09-24): whether AVPlay really plays the HLS it is handed, whether HTML can be layered over
// AVPlay's video plane on the RU7440, and fMP4-HEVC HLS (R285 FR-R285-5 keeps hls_hevc off there).

const SCREEN_URL = process.env.RAVILO_SCREEN_CAST_URL ?? "http://localhost:8092";
const JF_USER = process.env.JELLYFIN_USER ?? "admin";
const JF_PASS = process.env.JELLYFIN_PASS ?? "password";
const R252 = { "x-ravilo-platform": "phone", "x-ravilo-version": "9.99" };

type Status = {
  audio_tracks?: Array<{ index: number; language?: string }>;
  subtitle_tracks?: Array<{ index: number; language?: string }>;
  selected_audio?: number;
  selected_sub?: number;
  sub_size?: string;
  transcoding?: boolean;
  loaded?: boolean;
};

test.describe("R285 — audio and subtitles on the TV receiver, driven through the real bundle", () => {
  test("text subtitles are drawn, audio restreams, a PGS burn-in keeps the audio, and an un-burn returns the text", async ({ page, request }) => {
    test.setTimeout(150_000);
    await page.addInitScript(fakeAvPlayInitScript);
    const pageErrors: string[] = [];
    page.on("pageerror", (err) => pageErrors.push(err.message));

    const { phoneToken, screenId } = await bootAndPair(page, request);
    const auth = { authorization: `Bearer ${phoneToken}` };
    const status = async (): Promise<Status> =>
      (await (await request.get(`/api/remote/devices/${screenId}`, { headers: auth })).json())?.now_playing ?? {};
    const command = async (data: Record<string, unknown>) => {
      const res = await request.post("/api/remote/command", { headers: auth, data: { device_id: screenId, ...data } });
      expect(res.status(), await res.text()).toBe(202);
    };
    const cues = page.locator("#cues");

    // ── 1. Play the transcoding item. The first ticket carries Jellyfin's default audio. ─────────
    const itemId = await scannedItemId(request, phoneToken, "Big Buck Bunny", { username: JF_USER, password: JF_PASS });
    const play = await request.post("/api/remote/play", { headers: auth, data: { device_id: screenId, jellyfin_item_id: itemId, start_position_ms: 0 } });
    expect(play.status(), await play.text()).toBe(202);
    const opened = await waitForAvplayCall(page, "open");
    expect(String(opened.call[1])).toContain("master.m3u8");
    expect(String(opened.call[1])).toContain("AudioStreamIndex=1");
    await waitForAvplayCall(page, "play", opened.index);

    // The phone sees both audio tracks and both subtitles — text first, the picture subtitle appended
    // (FR-R285-1: appended, so a text index keeps its meaning mid-session).
    await expect.poll(async () => (await status()).audio_tracks?.map((t) => t.language), { timeout: 10_000 }).toEqual(["eng", "dan"]);
    const first = await status();
    expect(first.subtitle_tracks?.map((t) => t.language)).toEqual(["eng", "dan"]);
    expect(first.transcoding).toBe(true);
    expect(first.selected_audio ?? 0).toBe(0);
    expect(first.selected_sub ?? -1).toBe(-1);
    await expect(cues).not.toHaveClass(/\bon\b/);

    // ── 2. set_subtitle 0 — a text track: no restream, the receiver draws the VTT itself (FR-R285-3). ──
    let from = (await avplayCalls(page)).length;
    await command({ command: "set_subtitle", index: 0 });
    await expect(cues).toHaveClass(/\bon\b/, { timeout: 10_000 });
    await expect(cues).toHaveText("Mock subtitle line");
    await expect.poll(async () => (await status()).selected_sub ?? -1).toBe(0);
    expect((await avplayCalls(page)).slice(from).some((c) => c[0] === "open"), "a text subtitle must not restream").toBe(false);

    // ── 3. set_subtitle_size L — the phone's size row reaches the layer and comes back on the status. ──
    await command({ command: "set_subtitle_size", size: "L" });
    await expect(cues).toHaveClass(/\bsize-l\b/);
    await expect.poll(async () => ((await status()).sub_size ?? "M").toUpperCase()).toBe("L");

    // ── 4. set_audio 1 — a single-audio stream changes audio by restream (FR-R285-2), at the same
    //    position, and the text subtitle the viewer chose stays on screen. ──────────────────────
    from = (await avplayCalls(page)).length;
    await command({ command: "set_audio", index: 1 });
    const audioOpen = await waitForAvplayCall(page, "open", from);
    expect(String(audioOpen.call[1])).toContain("AudioStreamIndex=2");
    expect(String(audioOpen.call[1])).not.toContain("SubtitleStreamIndex=");
    await expect.poll(async () => (await status()).selected_audio ?? 0).toBe(1);
    await expect(cues).toHaveClass(/\bon\b/, { timeout: 10_000 });
    expect((await status()).selected_sub ?? -1).toBe(0);

    // ── 5. set_subtitle 1 — the PGS track: a burn-in restream that KEEPS the Danish audio, and the
    //    receiver's own layer goes dark (R282: one subtitle on screen, ever). The status names the
    //    burned track as the selection. ──────────────────────────────────────────────────────────
    from = (await avplayCalls(page)).length;
    await command({ command: "set_subtitle", index: 1 });
    const burnOpen = await waitForAvplayCall(page, "open", from);
    expect(String(burnOpen.call[1])).toContain("SubtitleStreamIndex=4");
    expect(String(burnOpen.call[1])).toContain("AudioStreamIndex=2");
    await expect(cues).not.toHaveClass(/\bon\b/, { timeout: 10_000 });
    await expect.poll(async () => (await status()).selected_sub ?? -1).toBe(1);
    expect((await status()).selected_audio ?? 0).toBe(1);

    // ── 6. set_subtitle 0 while burned in — un-burn first (a restream with no burn-in, audio kept),
    //    then the text track is drawn again. ───────────────────────────────────────────────────
    from = (await avplayCalls(page)).length;
    await command({ command: "set_subtitle", index: 0 });
    const unburnOpen = await waitForAvplayCall(page, "open", from);
    expect(String(unburnOpen.call[1])).not.toContain("SubtitleStreamIndex=");
    expect(String(unburnOpen.call[1])).toContain("AudioStreamIndex=2");
    await expect(cues).toHaveClass(/\bon\b/, { timeout: 10_000 });
    await expect(cues).toHaveText("Mock subtitle line");
    await expect.poll(async () => (await status()).selected_sub ?? -1).toBe(0);

    // ── 7. set_subtitle Off (no index) — the layer goes dark, nothing restreams. ──────────────────
    from = (await avplayCalls(page)).length;
    await command({ command: "set_subtitle" });
    await expect(cues).not.toHaveClass(/\bon\b/, { timeout: 10_000 });
    await expect.poll(async () => (await status()).selected_sub ?? -1).toBe(-1);
    expect((await avplayCalls(page)).slice(from).some((c) => c[0] === "open"), "Off on a text track must not restream").toBe(false);

    // ── 8. skip — 236's `skip {delta_ms}`, which the receiver ignored until R285. ────────────────
    from = (await avplayCalls(page)).length;
    await command({ command: "skip", delta_ms: 30_000 });
    const skipped = await waitForAvplayCall(page, "seekTo", from);
    expect(Number(skipped.call[1])).toBeGreaterThanOrEqual(30_000);

    // ── 9. Stop, and nothing thrown on the way. ─────────────────────────────────────────────────
    from = (await avplayCalls(page)).length;
    await request.post("/api/remote/command", { headers: auth, data: { device_id: screenId, command: "stop" } });
    await waitForAvplayCall(page, "close", from);
    await expect(page.locator("#idle")).toHaveClass(/\bon\b/, { timeout: 10_000 });
    expect(pageErrors).toEqual([]);
  });
});

/** Boots the real bundle, completes R269's setup, and pairs it to a fresh fake phone. The screen is
 *  identified by the device list's DIFFERENCE across the pairing — this stack also holds phase 248's
 *  screen, paired by the same Jellyfin user and named the same. */
async function bootAndPair(page: Page, request: APIRequestContext): Promise<{ phoneToken: string; screenId: string }> {
  await page.goto(SCREEN_URL, { waitUntil: "domcontentloaded", timeout: 10_000 });
  await page.locator("#setupHost").fill(process.env.APP_URL_FROM_SCREEN ?? "http://app:9505");
  await page.locator("#setupConnect").click();
  await expect(page.locator("#idle")).toHaveClass(/\bon\b/, { timeout: 15_000 });
  const codeLocator = page.locator("#idle-code");
  await expect(codeLocator).not.toHaveText("", { timeout: 10_000 });
  const code = await codeLocator.textContent();

  const login = await request.post("/api/tv/login", {
    data: { username: JF_USER, password: JF_PASS, device_id: "e2e-tracks-phone-r285", device_name: "E2E Tracks Phone" },
    headers: R252,
  });
  expect(login.status(), await login.text()).toBe(200);
  const phoneToken = (await login.json()).device_token as string;
  const auth = { authorization: `Bearer ${phoneToken}` };
  const screenIds = async (): Promise<string[]> =>
    ((await (await request.get("/api/remote/devices", { headers: auth })).json()) as any[]).filter((d) => d.kind === "screen").map((d) => d.device_id);
  const before = new Set(await screenIds());

  const pair = await request.post("/api/remote/pair", { headers: auth, data: { code } });
  expect(pair.status(), await pair.text()).toBe(200);

  let screenId = "";
  await expect
    .poll(async () => {
      const devices = (await (await request.get("/api/remote/devices", { headers: auth })).json()) as any[];
      const mine = devices.find((d) => d.kind === "screen" && !before.has(d.device_id) && d.online === true);
      screenId = mine?.device_id ?? "";
      return screenId !== "";
    }, { timeout: 15_000 })
    .toBe(true);
  return { phoneToken, screenId };
}
