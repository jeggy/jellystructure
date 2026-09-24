import { expect, type Page } from "@playwright/test";

// R297 / R299 — a fake `cast.framework` (the Google Cast Application Framework, CAF) in front of the
// REAL ravilo-cast.js bundle, the way helpers/screen-cast.ts fakes Samsung's `webapis.avplay` for the
// Tizen receiver. Everything the receiver does with the SDK is recorded on window.__cast*; nothing is
// rendered or fetched. The one thing CI cannot have is a Chromecast: the SDK's own player. Everything
// else — enrolment, negotiation with the real backend, the capability probe, the message the phone
// gets — is the shipped receiver code.
//
// What the fake answers is what a Sony BRAVIA's built-in Chromecast answered on 2026-09-24 through
// its DevTools: `canDisplayType('audio/mp4','ac-3'|'ec-3')` false, everything else true. That answer
// is what R297 exists for.
export function fakeCafInitScript() {
  const w = window as any;
  w.__castSent = [];            // messages the receiver sent to the phone (sendCustomMessage)
  w.__castCanDisplay = [];      // every canDisplayType(mime, codec) probe
  w.__castState = "IDLE";       // what getPlayerState() answers; a test flips it
  w.__castLoads = [];           // playerManager.load(request) calls
  const interceptors: Record<string, (req: any) => any> = {};
  const listeners: Record<string, Array<(ev: any) => void>> = {};
  const noop = () => {};
  const pm = {
    setMessageInterceptor(type: string, fn: (req: any) => any) { interceptors[type] = fn; },
    addEventListener(type: string, fn: (ev: any) => void) { (listeners[type] ??= []).push(fn); },
    getPlayerState() { return w.__castState; },
    getMediaInformation() { return null; },
    getStats() { return {}; },
    getTextTracksManager() { return { getActiveIds: () => [], setActiveByIds: noop, getTracks: () => [] }; },
    getCurrentTimeSec() { return 0; },
    getDurationSec() { return 0; },
    seek: noop,
    setTextTrackStyle: noop,
    load(req: any) { w.__castLoads.push(req); },
  };
  const ctx = {
    getPlayerManager() { return pm; },
    canDisplayType(mime: string, codec: string) {
      w.__castCanDisplay.push([mime, codec]);
      return !(/ac-3|ec-3/.test(codec));
    },
    addEventListener: noop,
    addCustomMessageListener(_ns: string, cb: (ev: any) => void) { w.__castCommand = (data: any) => cb({ data }); },
    sendCustomMessage(_ns: string, _sender: unknown, msg: any) { w.__castSent.push(msg); },
    start(opts: any) { w.__castStarted = opts; },
  };
  function ctor(this: any) { return this; }
  w.cast = {
    framework: {
      CastReceiverContext: { getInstance: () => ctx },
      messages: {
        MessageType: { LOAD: "LOAD" },
        StreamType: { BUFFERED: "BUFFERED" },
        TrackType: { TEXT: "TEXT" },
        TextTrackType: { SUBTITLES: "SUBTITLES", FORCED: "FORCED" },
        HlsSegmentFormat: { FMP4: "FMP4" },
        HlsVideoSegmentFormat: { FMP4: "FMP4" },
        Track: ctor, TextTrackStyle: ctor, GenericMediaMetadata: ctor, MediaInformation: ctor, LoadRequestData: ctor,
      },
      events: {
        EventType: { TIME_UPDATE: "TIME_UPDATE", PLAYING: "PLAYING", PAUSE: "PAUSE", BUFFERING: "BUFFERING", MEDIA_FINISHED: "MEDIA_FINISHED", ERROR: "ERROR", SEEKED: "SEEKED" },
        EndedReason: { END_OF_STREAM: "END_OF_STREAM", ERROR: "ERROR", STOPPED: "STOPPED" },
      },
      system: { EventType: { SHUTDOWN: "SHUTDOWN" } },
    },
  };
  // Test entry points. A LOAD goes through the receiver's own interceptor, exactly as CAF would route
  // a sender's load request; the interceptor answers a Promise (Receiver.kt's GlobalScope.promise).
  w.__castLoad = (customData: any) =>
    Promise.resolve(interceptors.LOAD({ media: { customData }, customData }))
      .then((r: any) => (r ? { contentId: r.media?.contentId ?? null, contentType: r.media?.contentType ?? null, autoplay: r.autoplay } : null));
  w.__castFire = (type: string, ev: any) => { for (const fn of listeners[type] ?? []) fn(ev); return (listeners[type] ?? []).length; };
  w.__castHasInterceptor = () => typeof interceptors.LOAD === "function";
}

export async function castSent(page: Page): Promise<any[]> {
  return page.evaluate(() => (window as any).__castSent ?? []);
}

/** Loads the receiver page with the fake in place of Google's SDK script, and waits for the real
 *  bundle to register its LOAD interceptor (Receiver.start() runs on window load). */
export async function openReceiver(page: Page, appUrl: string) {
  await page.addInitScript(fakeCafInitScript);
  await page.route("https://www.gstatic.com/**", (route) => route.fulfill({ status: 200, contentType: "application/javascript", body: "/* CAF stubbed by the e2e suite */" }));
  await page.goto(`${appUrl}/cast/`, { waitUntil: "load", timeout: 15_000 });
  await expect.poll(() => page.evaluate(() => (window as any).__castHasInterceptor?.() === true), { timeout: 10_000 }).toBe(true);
}
