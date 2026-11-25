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
  w.__castNotes = [];           // what it said on its log channel (289)
  w.__castCanDisplay = [];      // every canDisplayType(mime, codec) probe
  w.__castState = "IDLE";       // what getPlayerState() answers; a test flips it
  w.__castLoads = [];           // playerManager.load(request) calls
  w.__castTime = 0;             // what getCurrentTimeSec() answers (DEB-4994): the player's clock, in seconds
  w.__castActive = [];          // what the text-track manager's getActiveIds() answers: [] unless a test is the platform's caption control
  w.__castSetActive = [];       // every setActiveByIds(ids) the receiver asked CAF for
  const interceptors: Record<string, (req: any) => any> = {};
  const listeners: Record<string, Array<(ev: any) => void>> = {};
  const noop = () => {};
  const pm = {
    setMessageInterceptor(type: string, fn: (req: any) => any) { interceptors[type] = fn; },
    addEventListener(type: string, fn: (ev: any) => void) { (listeners[type] ??= []).push(fn); },
    getPlayerState() { return w.__castState; },
    getMediaInformation() { return null; },
    getStats() { return {}; },
    getTextTracksManager() { return { getActiveIds: () => w.__castActive, setActiveByIds: (ids: number[]) => { w.__castSetActive.push(Array.from(ids)); }, getTracks: () => [] }; },
    getCurrentTimeSec() { return w.__castTime; },
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
    // 289 — the receiver's log channel (…ravilo.log) is kept apart: notes are text, never what a phone's state is built from.
    addCustomMessageListener(ns: string, cb: (ev: any) => void) { if (!/\.log$/.test(ns)) w.__castCommand = (data: any) => cb({ data }); },
    sendCustomMessage(ns: string, _sender: unknown, msg: any) { (/\.log$/.test(ns) ? w.__castNotes : w.__castSent).push(msg); },
    getDeviceCapabilities() { return { display_supported: !w.__castHeadless }; },
    start(opts: any) { w.__castStarted = opts; },
  };
  function ctor(this: any) { return this; }
  w.cast = {
    framework: {
      CastReceiverContext: { getInstance: () => ctx },
      messages: {
        MessageType: { LOAD: "LOAD", EDIT_TRACKS_INFO: "EDIT_TRACKS_INFO" },
        StreamType: { BUFFERED: "BUFFERED" },
        TrackType: { TEXT: "TEXT" },
        TextTrackType: { SUBTITLES: "SUBTITLES", FORCED: "FORCED" },
        HlsSegmentFormat: { FMP4: "FMP4" },
        HlsVideoSegmentFormat: { FMP4: "FMP4" },
        Track: ctor, TextTrackStyle: ctor, GenericMediaMetadata: ctor, MediaInformation: ctor, LoadRequestData: ctor,
      },
      events: {
        EventType: { TIME_UPDATE: "TIME_UPDATE", PLAYING: "PLAYING", PAUSE: "PAUSE", BUFFERING: "BUFFERING", MEDIA_FINISHED: "MEDIA_FINISHED", ERROR: "ERROR", SEEKED: "SEEKED" },
        EndedReason: { END_OF_STREAM: "END_OF_STREAM", ERROR: "ERROR", STOPPED: "STOPPED", INTERRUPTED: "INTERRUPTED" },
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
  // DEB-4994 — a phone's or a Mac's EDIT_TRACKS_INFO, through the receiver's interceptor as CAF routes it. Answers what
  // the interceptor hands back to CAF (the request itself, unchanged) or null.
  w.__castEdit = (req: any) => { const r = interceptors.EDIT_TRACKS_INFO(req); return r === req ? "same" : r ? "other" : null; };
  w.__castHasInterceptor = () => typeof interceptors.LOAD === "function" && typeof interceptors.EDIT_TRACKS_INFO === "function";
}

export async function castSent(page: Page): Promise<any[]> {
  return page.evaluate(() => (window as any).__castSent ?? []);
}

/** Loads the receiver page with the fake in place of Google's SDK script, and waits for the real
 *  bundle to register its LOAD interceptor (Receiver.start() runs on window load). */
export async function openReceiver(page: Page, appUrl: string, opts: { headless?: boolean } = {}) {
  await page.addInitScript(fakeCafInitScript);
  // One receiver for every spec of the suite (the backend takes any `cast-…` id of 10 to 64 characters on enrolment and
  // reuses that device). A receiver that has just ended a play still counts against `max_sessions` for 120 s
  // (PLAYBACK_DEFER_GRACE_MS), so a fresh receiver per spec made the third one wait out the first two's grace.
  await page.addInitScript(() => { localStorage.setItem("ravilo.cast.receiverId", "cast-e2e-receiver"); });
  if (opts.headless) await page.addInitScript(() => { (window as any).__castHeadless = true; });   // a speaker: no display
  await page.route("https://www.gstatic.com/**", (route) => route.fulfill({ status: 200, contentType: "application/javascript", body: "/* CAF stubbed by the e2e suite */" }));
  await page.goto(`${appUrl}/cast/`, { waitUntil: "load", timeout: 15_000 });
  await expect.poll(() => page.evaluate(() => (window as any).__castHasInterceptor?.() === true), { timeout: 10_000 }).toBe(true);
}
