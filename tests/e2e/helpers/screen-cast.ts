import { expect, type APIRequestContext, type Page } from "@playwright/test";

// Shared by the receiver specs (phase 248's cast spec, R285's track spec): a fake `webapis.avplay` —
// the one thing CI cannot have, Samsung's own video pipeline — in front of the REAL ravilo-screen.js
// bundle. Moved here verbatim from ravilo-screen-cast.spec.ts on 2026-09-24.

// Records every call the real AvPlayBackend (MediaBackend.kt) makes, in order, onto window.__avplayCalls
// — read back via page.evaluate rather than asserting on transient DOM state (the loading screen closes
// again within milliseconds of the fake's prepareAsync succeeding; polling for the call log is the
// deterministic signal FR-248-3 asks for).
export function fakeAvPlayInitScript() {
  (window as any).__avplayCalls = [];
  let state = "NONE";
  let posMs = 0;
  let ticking: any = null;
  let listener: any = {};
  // DEB-5024 — AVPlay's buffering, its `onevent` and its two streaming properties, driven by the test (`window.__avplay`): the
  // fake never buffers on its own, so a test says exactly when a wait begins and ends. While buffering, the position stands still.
  let buffering = false;
  // DEB-5024 (FR-32) — AVPlay can hold its position while `getState()` still says PLAYING and fire no event at all (DEB-5067):
  // `hold()` is that, `release()` lets it move again. A stream that is closed or opened again moves, as a new stream does.
  let held = false;
  // DEB-5024 (FR-34) — how fast the playhead moves against wall time (1 = real time); `setRate(0.9)` is a stream that slips behind.
  let rate = 1;
  // DEB-5396 — the stream's length (the default stays 60 s) and a playhead the test moves to as natural play would, with no `seekTo`
  // call recorded: `setDuration(ms)` before a start, `jump(ms)` while it plays.
  let durationMs = 60_000;
  // DEB-5394 (FR-36) — AVPlay re-preparing after a seek reads position 0 for a moment: `zeroAfterSeek(ms)` makes `getCurrentTime` say 0
  // for that long after each `seekTo`, as QA saw it on the set.
  let zeroAfterSeekMs = 0;
  let seekedAt = 0;
  const properties: Record<string, string | undefined> = {};
  // DEB-5503 (FR-44) — the tracks `getTotalTrackInfo()` lists: `setAudioTracks(n)` is a file with n audio tracks (indexed per type, as the
  // ticket's container order is), the default being none; a test that sets another count is a file the player lists differently.
  let audioTracks = 0;
  (window as any).__avplay = {
    setAudioTracks(n: number) { audioTracks = n; },
    bufferStart() { buffering = true; listener.onbufferingstart?.(); },
    bufferComplete() { buffering = false; listener.onbufferingcomplete?.(); },
    hold() { held = true; },
    release() { held = false; },
    setRate(r: number) { rate = r; },
    setDuration(ms: number) { durationMs = ms; },
    jump(ms: number) { posMs = ms; },
    // DEB-5024 (FR-32) — AVPlay's own end of stream.
    complete() { listener.onstreamcompleted?.(); },
    zeroAfterSeek(ms: number) { zeroAfterSeekMs = ms; },
    event(type: string, data?: unknown) { listener.onevent?.(type, data); },
    // DEB-5081 (FR-33) — AVPlay's `onerror` while a stream is open (after its first frame, or before it for a start that was already prepared).
    error(e: string) { listener.onerror?.(e); },
    setProperty(name: string, value: string | undefined) { properties[name] = value; },
    failPrepareWith: undefined as string | undefined,
    // DEB-5081 (FR-33) — the next n `prepareAsync` calls fail with this error and later ones succeed: a start that fails once and is asked again.
    failPrepareTimes: 0,
    failPrepareTimesWith: "Unknown error" as string,
  };
  (window as any).webapis = {
    avplay: {
      open(url: string) {
        (window as any).__avplayCalls.push(["open", url]);
        // DEB-4874 — the device's rule: open() is only legal from NONE (nothing open, or after close()). Over an open stream the
        // real AVPlay throws PLAYER_ERROR_INVALID_STATE, and Chromium would otherwise never show the app doing that.
        if (state !== "NONE") {
          const e: any = new Error("PLAYER_ERROR_INVALID_STATE");
          e.name = "InvalidStateError";
          throw e;
        }
        state = "IDLE";
      },
      setDisplayRect(...args: number[]) {
        (window as any).__avplayCalls.push(["setDisplayRect", ...args]);
      },
      setDisplayMethod(method: string) {
        (window as any).__avplayCalls.push(["setDisplayMethod", method]);
      },
      setListener(l: any) {
        listener = l;
      },
      prepareAsync(onSuccess: () => void, onError: (e: unknown) => void) {
        (window as any).__avplayCalls.push(["prepareAsync"]);
        const fake = (window as any).__avplay;
        const failure = fake.failPrepareTimes > 0 ? (fake.failPrepareTimes--, fake.failPrepareTimesWith) : fake.failPrepareWith;
        if (failure) { setTimeout(() => onError(failure), 10); return; }
        state = "READY";
        setTimeout(() => { try { onSuccess(); } catch { /* Kotlin's own callback, not ours to swallow */ } }, 10);
      },
      play() {
        (window as any).__avplayCalls.push(["play"]);
        state = "PLAYING";
        if (!ticking) ticking = setInterval(() => {
          if (buffering || held) return;
          posMs += Math.round(250 * rate);
          listener.oncurrentplaytime?.(posMs);
        }, 250);
      },
      pause() {
        (window as any).__avplayCalls.push(["pause"]);
        state = "PAUSED";
        if (ticking) { clearInterval(ticking); ticking = null; }
      },
      stop() {
        (window as any).__avplayCalls.push(["stop"]);
        state = "IDLE";
      },
      seekTo(ms: number, onSuccess?: () => void) {
        (window as any).__avplayCalls.push(["seekTo", ms]);
        posMs = ms;
        seekedAt = Date.now();
        onSuccess?.();
      },
      close() {
        (window as any).__avplayCalls.push(["close"]);
        if (ticking) { clearInterval(ticking); ticking = null; }
        state = "NONE";
        posMs = 0;
        buffering = false;
        held = false;
      },
      getState() { return state; },
      getCurrentTime() { return Date.now() - seekedAt < zeroAfterSeekMs ? 0 : posMs; },
      getDuration() { return durationMs; },
      getTotalTrackInfo() {
        return [{ index: 0, type: "VIDEO", extra_info: "{}" }, ...Array.from({ length: audioTracks }, (_, i) => ({ index: i, type: "AUDIO", extra_info: "{}" }))];
      },
      setSelectTrack(type: string, index: number) {
        (window as any).__avplayCalls.push(["setSelectTrack", type, index]);
      },
      setStreamingProperty(type: string, value: string) {
        (window as any).__avplayCalls.push(["setStreamingProperty", type, value]);
      },
      getStreamingProperty(type: string) { return properties[type]; },
    },
  };
}

/** DEB-5025 (FR-29) — open the audio & subtitles picker the way Android TV's model reaches it: reveal the controls (↓ only reveals),
 *  walk the row to Audio & subtitles, OK. ↓ no longer opens it directly. */
export async function openPickerFromChrome(page: Page) {
  const up = () => page.locator("#overlay").evaluate((e) => e.classList.contains("on"));
  const at = (id: string) => page.locator(`#${id}`).evaluate((e) => e.classList.contains("foc"));
  if (!(await up())) await page.keyboard.press("ArrowDown");
  for (let i = 0; i < 6 && !(await at("ov-tracks")); i++) await page.keyboard.press((await at("ov-next")) ? "ArrowLeft" : "ArrowRight");
  await page.keyboard.press("Enter");
}

export async function avplayCalls(page: Page): Promise<Array<[string, ...unknown[]]>> {
  return page.evaluate(() => (window as any).__avplayCalls ?? []);
}

/** Waits for a call named [name] to appear at index >= [fromIndex] in the fake's recorded call log —
 *  the "no fixed sleeps" rule (FR-248-3) applied to a DOM-external signal, same shape as
 *  screens.spec.ts's waitForType/fromIndex pattern. */
export async function waitForAvplayCall(page: Page, name: string, fromIndex = 0, timeoutMs = 10_000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const calls = await avplayCalls(page);
    const idx = calls.findIndex((c, i) => i >= fromIndex && c[0] === name);
    if (idx >= 0) return { index: idx, call: calls[idx] };
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error(`avplay never called "${name}" (from index ${fromIndex}) within ${timeoutMs}ms — got: ${JSON.stringify(await avplayCalls(page))}`);
}

/** A real, scanned library item (requireVisible() checks jellystructure's own store). Polls search
 *  rather than waiting on /api/scan/status: this suite's backend is shared, another spec may have just
 *  started a full pipeline run, and a second scan behind it outlasted 90 s locally. A scan is
 *  started only if the item is not searchable yet — never assuming spec file order. */
export async function scannedItemId(request: APIRequestContext, phoneToken: string, title: string, admin: { username: string; password: string }): Promise<string> {
  const R252 = { "x-ravilo-platform": "phone", "x-ravilo-version": "9.99" };
  const find = async (): Promise<string | undefined> => {
    const res = await request.get(`/api/tv/search?q=${encodeURIComponent(title)}`, { headers: { authorization: `Bearer ${phoneToken}`, ...R252 } });
    return res.ok() ? ((await res.json()).items?.[0]?.id as string | undefined) : undefined;
  };
  let id = await find();
  if (!id) {
    await request.post("/api/auth/login", { data: admin });
    await request.post("/api/scan"); // a 409 (a scan already running) is as good as a start
    await expect.poll(async () => (id = await find()) ?? "", { timeout: 180_000, intervals: [2_000] }).not.toBe("");
  }
  expect(id, `${title} was not found via /api/tv/search`).toBeTruthy();
  return id!;
}

/** Gives this page its own bucket in the backend's sign-in limits. `/api/tv/signin/code` allows 10 mints a minute and
 *  `/poll` 40, each per client IP (TvSignInLimits keyed by LoginRateLimiter.clientKey, which prefers X-Forwarded-For), and
 *  every spec in this stack comes from one IP: ravilo-screen-signin.spec.ts alone starts a dozen screens in half a minute,
 *  so the next spec's mint was refused, the screen stopped on Retry and its code stayed empty (DEB-4856). `fallback`, not
 *  `continue`, so a test's own mock of the same route still answers first. Call before `page.goto`. */
export async function ownSignInBucket(page: Page): Promise<void> {
  const client = `10.${Math.floor(Math.random() * 256)}.${Math.floor(Math.random() * 256)}.${1 + Math.floor(Math.random() * 254)}`;
  await page.route(/\/api\/tv\/signin\/(code|poll)$/, (route) =>
    route.fallback({ headers: { ...route.request().headers(), "x-forwarded-for": client } }));
}
