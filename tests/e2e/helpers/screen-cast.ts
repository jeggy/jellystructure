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
  let state = "IDLE";
  let posMs = 0;
  let ticking: any = null;
  let listener: any = {};
  (window as any).webapis = {
    avplay: {
      open(url: string) {
        (window as any).__avplayCalls.push(["open", url]);
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
      prepareAsync(onSuccess: () => void, _onError: (e: unknown) => void) {
        (window as any).__avplayCalls.push(["prepareAsync"]);
        state = "READY";
        setTimeout(() => { try { onSuccess(); } catch { /* Kotlin's own callback, not ours to swallow */ } }, 10);
      },
      play() {
        (window as any).__avplayCalls.push(["play"]);
        state = "PLAYING";
        if (!ticking) ticking = setInterval(() => { posMs += 250; }, 250);
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
        onSuccess?.();
      },
      close() {
        (window as any).__avplayCalls.push(["close"]);
        if (ticking) { clearInterval(ticking); ticking = null; }
        state = "NONE";
        posMs = 0;
      },
      getState() { return state; },
      getCurrentTime() { return posMs; },
      getDuration() { return 60_000; },
      getTotalTrackInfo() { return []; },
      setSelectTrack(type: string, index: number) {
        (window as any).__avplayCalls.push(["setSelectTrack", type, index]);
      },
      setStreamingProperty() {},
    },
  };
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
