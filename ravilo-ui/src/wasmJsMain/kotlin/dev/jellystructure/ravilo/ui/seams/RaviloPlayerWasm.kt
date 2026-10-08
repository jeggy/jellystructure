@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.AudioTrack
import dev.jellystructure.shared.tv.SubTrack
import kotlinx.browser.document
import org.w3c.dom.HTMLVideoElement

/**
 * Web actual: a browser `<video>` element, fixed full-window BEHIND the Compose viewport.
 *
 * R376 (FR-R376-1) — the element never moves above the canvas any more. ravilo-web boots with ComposeViewport, the
 * player screen clears its own pixels (PlayerVideoSurface punches a BlendMode.Clear hole), and the picture shows
 * through; every piece of Compose chrome — the phone's or the TV/desktop one, the picker, next-up, the rail, Skip
 * Intro, R218's moments — draws over it as on every other platform. R157's z-index swap and R169's DOM transport bar
 * are gone. `pointer-events:none`: the element never takes input, the canvas does.
 *
 * R376 (FR-R376-2) — the state the poll loop reads comes from the element's and hls.js's own events
 * ([WebPlaybackEvents]), not from constants.
 */
actual class RaviloPlayer actual constructor() {
    internal val video: HTMLVideoElement = (document.createElement("video") as HTMLVideoElement).also { v ->
        // R77: object-fit:contain preserves the video's native DAR, letterboxing/pillarboxing within the viewport.
        // background:#000 fills the bars. z-index 0, under #ComposeTarget's 1 (index.html), for the element's life.
        v.style.cssText = "position:fixed;top:0;left:0;width:100%;height:100%;object-fit:contain;background:#000;z-index:0;pointer-events:none"
        v.controls = false
        // iOS: play inside the page, never in Apple's own player (which would drop every piece of Ravilo chrome).
        v.setAttribute("playsinline", "")
        document.body?.appendChild(v)
        // R265 (FR-R265-4/-8) — offer this element to AirPlay and report what WebKit says about it.
        WebAirPlay.bind(v)
        // R265 — the chosen subtitle is re-applied as Safari adds the manifest's own tracks, which
        // arrive after load() (and one may arrive marked DEFAULT, e.g. a Croatian sidecar in the probe).
        watchTextTracks(v)
        // R376 (FR-R376-2) — every event R218 needs, queued with its time.
        wireEvents(v)
    }

    /** R381 (FR-R381-1) — this item's QoE, counted from the same events as R218's facts. */
    private val qoe = QoeCounter()
    private val events = WebPlaybackEvents(qoe)

    /** R376 (FR-R376-2) — what the element reported since the last read, in order; a stall records where it was. */
    private fun sync() = events.feed(drainEvents(video), positionMs, (bufferedMs - positionMs).coerceAtLeast(0), jsQ308(video, "bps").toLong().takeIf { it > 0 })

    /** R244 (FR-R244-6) — fit/fill is the <video>'s object-fit on the web. */
    internal fun setObjectFit(fill: Boolean) {
        video.style.setProperty("object-fit", if (fill) "cover" else "contain")
    }

    /** R244 (FR-R244-10) — the cue font size follows the phone's S/M/L pick; one style element, replaced. */
    actual fun setSubtitleScale(scale: Float) {
        setCueScale(scale.coerceIn(0.5f, 2f))
    }

    private var loadedSubtitles: List<SubTrack> = emptyList()
    private var subtitleSlots: List<SubtitleSlot> = emptyList()
    private var loadedAudio: List<AudioTrack> = emptyList()

    actual fun load(streamUrl: String, startPositionMs: Long, subtitles: List<SubTrack>, audio: List<AudioTrack>, title: String, subtitle: String?, artworkUrl: String?) {
        // R376 (FR-R376-2) — the outgoing stream's events belong to it; the new one starts with no frame from now,
        // not from whenever the browser gets round to `loadstart`.
        sync()
        events.on("loadstart", nowMs())
        // R284 (FR-R284-4) — only subtitles this player can DRAW are its tracks. A URL-less entry is a
        // burn-in candidate (PGS), which PlayerScreen lists itself from the ticket; keeping it here too
        // showed every PGS track twice on the web, the first copy selecting nothing.
        // R265 (FR-R265-8) — plus the manifest's own subtitles (`hls`): drawn by the browser from the
        // stream itself, which is what an AirPlay hand-over carries to the TV.
        loadedSubtitles = subtitles.filter { it.url != null || it.deliveryMethod == "hls" }
        subtitleSlots = emptyList()
        loadedAudio = audio
        // Remove existing <track> children
        while (video.childElementCount > 0) {
            video.firstChild?.let { video.removeChild(it) }
        }
        // R17: lazy-load hls.js for HLS streams (the R56 transcode / burn-in TranscodingUrl is an
        // .m3u8, which non-Safari browsers can't play natively). Direct-play URLs set video.src.
        // R376 (FR-R376-7) — hls.js starts at the ticket's position itself (`startPosition`).
        attachSource(video, streamUrl, startPositionMs / 1000.0)
        // R44: route browser/OS media keys to the <video> via the Media Session API.
        wireMediaSession(video)
        // R376 (FR-R376-4) — and name what plays, so the OS's media controls and lock screen say it (the research
        // report's owner check, taken at its lean: a browser's own media session is the browser's, not R193's
        // Android service).
        setMediaMetadata(title, subtitle ?: "", artworkUrl ?: "")
        // R110: match the TV's white-text + black-outline caption look for native <track> (VTT) cues.
        installCueStyle()
        // R284 (FR-R284-4) — one slot per entry of [loadedSubtitles], in order: how that subtitle is
        // shown. Either the position of its <track> among the element's text tracks, or an ASS url.
        val slots = mutableListOf<SubtitleSlot>()
        var trackEls = 0
        // The TextTrackList lists the element's <track>s first and the manifest's renditions after them,
        // in manifest order — which is stream order, the order the ticket lists them in.
        val elementTracks = loadedSubtitles.count { s -> s.url?.let { !it.endsWith(".ass", true) && !it.endsWith(".ssa", true) } == true }
        var manifestTracks = 0
        loadedSubtitles.forEach { sub ->
            val url = sub.url ?: run {
                slots += SubtitleSlot(textTrack = elementTracks + manifestTracks++, assUrl = null)
                return@forEach
            }
            // R17: native .ass/.ssa subs render with JASSUB (libass) for full styling; the rest are
            // delivered as VTT and use a plain <track>. JASSUB is lazy-loaded only when first needed.
            if (url.endsWith(".ass", ignoreCase = true) || url.endsWith(".ssa", ignoreCase = true)) {
                slots += SubtitleSlot(textTrack = -1, assUrl = url)
                return@forEach   // mounted by selectSubtitleTrack(), like every other subtitle
            }
            slots += SubtitleSlot(textTrack = trackEls++, assUrl = null)
            val trackEl = document.createElement("track")
            trackEl.setAttribute("kind", if (sub.forced) "forced" else "subtitles")
            sub.language?.let { trackEl.setAttribute("srclang", it) }
            sub.label?.let { trackEl.setAttribute("label", it) }
            // R284 — no `default` attribute any more. It was the ONLY selection mechanism while
            // selectSubtitleTrack() was a stub; now R181's resolver decides (its source-default tier
            // already honours isDefault), and a second, browser-run chooser could re-show a track the
            // resolver just turned off — the web's own version of "two deciders".
            video.appendChild(trackEl)
            // R68: fetch VTT, strip ASS/SSA override tags, attach as blob: URL
            fetchAndCleanVtt(url) { cleanUrl -> trackEl.setAttribute("src", cleanUrl) }
        }
        subtitleSlots = slots
    }

    actual fun play() { playVideo(video) }
    actual fun pause() { video.pause() }
    actual fun seekTo(positionMs: Long) { video.currentTime = positionMs / 1000.0 }
    /** R354 (FR-R354-6) — the video element's own volume. */
    actual fun setVolume(level: Float) { video.volume = level.coerceIn(0f, 1f).toDouble() }

    /**
     * R291 (FR-R291-4) / R376 (FR-R376-3) — a composed master (`audio_renditions`) names every audio rendition
     * `a{position} …`; the pick selects that rendition: hls.js through `hls.audioTrack`, Safari through the element's
     * own `audioTracks`. The picture keeps playing. A pick made before the browser has read the manifest is held and
     * applied when it has. Returns false when nothing could switch (a direct-played file with several tracks in a
     * browser with no `audioTracks`), and PlayerScreen restreams (R284) instead of doing nothing.
     */
    actual fun selectAudioTrack(index: Int): Boolean {
        if (loadedAudio.size <= 1) return true   // one track: nothing to switch, and it is already playing
        return when (selectRendition(video, index)) {
            1, 2 -> { qoe.trackSwitch(); true }   // R381 (FR-R381-2) — the switch's own wait is not a stall
            // Nothing to select: true only when the pick is the track that already plays.
            else -> index == loadedAudio.indexOfFirst { it.isDefault }.coerceAtLeast(0) && !hasRenditionList(video)
        }
    }

    /**
     * R284 (FR-R284-4) — really switches. Until this phase it was an empty stub, so the only subtitle
     * a browser ever showed was whichever `<track>` carried `default`; the picker changed nothing and
     * "Off" did not turn it off. [index] is a position in [subtitleTracks]; -1 = off. Exactly one of
     * {a text track showing, JASSUB mounted, nothing} holds afterwards.
     */
    actual fun selectSubtitleTrack(index: Int) {
        val slot = subtitleSlots.getOrNull(index)
        showTextTrack(video, slot?.textTrack ?: -1)
        if (slot?.assUrl != null) mountAss(video, slot.assUrl) else unmountAss(video)
    }

    actual fun release() {
        WebAirPlay.unbind(video)
        runCatching { clearMediaMetadata() }
        runCatching { destroyOverlays(video) } // R17: tear down any hls.js / JASSUB instance
        runCatching { document.body?.removeChild(video) }
    }

    // R292 — a browser tab has no decoder to leak (the phase's non-goal); the element stays, paused.
    actual fun releaseEngine() { runCatching { video.pause() } }
    actual fun recordRestoredAfterRecreate() {}

    // R192: no OS-level MediaSession/cross-device surfacing on web — nothing to toggle.
    actual fun setSessionActive(active: Boolean) {}

    actual val positionMs: Long get() = (video.currentTime * 1000).toLong()
    actual val durationMs: Long get() {
        val d = video.duration
        return if (d.isNaN() || d.isInfinite()) 0L else (d * 1000).toLong()
    }
    actual val bufferedMs: Long get() {
        val buf = video.buffered
        return if (buf.length > 0) (buf.end(buf.length - 1) * 1000).toLong() else 0L
    }
    actual val isPlaying: Boolean get() { sync(); return !video.paused && !video.ended && !events.failed }
    // R306 (FR-R306-3) — a media error on the current source, or hls.js giving up on it; a new src clears it.
    actual val playbackFailed: Boolean get() { sync(); return video.error != null || events.failed }
    actual val isEnded: Boolean get() { sync(); return video.ended || events.ended }
    // R218 (FR-R218-1) / R376 (FR-R376-2) — the element's own events: B (cold start), C (stall) and D (seek) show on
    // the web exactly as on Android; PlayerScreen applies R218's 400 ms debounce and strings.
    actual val hasRenderedFirstFrame: Boolean get() { sync(); return events.firstFrame }
    actual val isBuffering: Boolean get() { sync(); return events.buffering }
    actual val isSeeking: Boolean get() { sync(); return events.seeking }
    actual val soundBlockedByBrowser: Boolean get() { sync(); return events.soundBlocked }

    /**
     * R376 (FR-R376-3) — the stream's own audio list when it has one (a composed master's renditions, as hls.js or
     * Safari lists them), labelled with the server's names by position. Anything else (direct play, one muxed track)
     * lists the server's tracks, as before (R46). While a master's renditions are not read yet the list is empty, so
     * R181's resolver waits for the real one.
     */
    actual val audioTracks: List<PlayerAudioTrack> get() {
        val names = renditionNames(video)
        if (names.isNotEmpty()) {
            val fromStream = audioTracksFromRenditions(names.split('\u0001'), loadedAudio)
            if (fromStream.isNotEmpty()) return fromStream
        }
        if (loadedAudio.size > 1 && renditionsPending(video)) return emptyList()
        return loadedAudio.mapIndexed { i, a ->
            val label = a.label?.takeIf { it.isNotBlank() } ?: languageName(a.language) ?: a.language ?: "Track ${i + 1}"
            PlayerAudioTrack(i, label, a.language, a.channels, a.isDefault)
        }
    }
    actual val subtitleTracks: List<PlayerSubtitleTrack> get() =
        loadedSubtitles.mapIndexed { i, s ->
            val label = s.label ?: languageName(s.language) ?: s.language ?: "Track ${i + 1}"
            PlayerSubtitleTrack(i, label, s.language, s.forced, s.isDefault)
        }

    /** 308 (FR-308-3) — hls.js's first estimate for the next stream ([attachSource] reads it); Safari starts on the
     *  first variant the server listed, which is the same guess. */
    actual fun seedBandwidthEstimate(bps: Long?) = jsSeedAbr((bps ?: 0L).toDouble())

    /**
     * R216 / R376 (FR-R376-6) — what a browser can know: dropped frames from `getVideoPlaybackQuality()`, stalls and
     * where hls.js plays, its bandwidth estimate and the decoder line (the level hls.js chose and the decoded frame
     * count). R381 (FR-R381-1) — the stalls are [QoeCounter]'s, per item and only after the item's first frame,
     * outside seeks, track and variant switches, like Android's. 308 (FR-308-5) — hls.js's variant switches are counted
     * ([attachSource]); Safari's native HLS exposes none.
     */
    actual fun qoeSnapshot(): PlayerQoeSnapshot {
        sync()
        val bw = hlsBandwidth(video)
        return PlayerQoeSnapshot(
            droppedFrames = droppedFrames(video),
            rebufferCount = qoe.rebufferCount,
            rebufferMs = qoe.rebufferMs,
            bandwidthEstimateBps = bw.takeIf { it > 0 }?.toLong(),
            videoDecoder = decoderLine(video).takeIf { it.isNotEmpty() },
            variantSwitchesDown = jsQ308(video, "down").toInt(),
            variantSwitchesUp = jsQ308(video, "up").toInt(),
            variantBandwidthBps = jsQ308(video, "bps").toLong().takeIf { it > 0 },
            variantHeight = jsQ308(video, "h").toInt().takeIf { it > 0 },
            perItem = true,
            stalls = qoe.stallEvents(),
            sessionRebufferCount = qoe.sessionRebufferCount,
            sessionRebufferMs = qoe.sessionRebufferMs,
            waits = qoe.waitCounts(),
        )
    }

    /** R381 (FR-R381-1) — a new item resets its counts; the same item again (an audio restream, R284) keeps them. */
    actual fun beginQoeItem(itemKey: String) { qoe.beginItem(itemKey) }
}

private fun jsSeedAbr(bps: Double): Unit = js("{ window.__raviloAbrSeed = bps > 0 ? bps : 0; }")

private fun jsQ308(video: HTMLVideoElement, key: String): Double = js("(video._q308 && video._q308[key]) || 0")

/** R376 (FR-R376-8) — the browser's picture-in-picture, where it has one for this element. */
actual fun RaviloPlayer.pictureInPictureAvailable(): Boolean = jsPipAvailable(video)
actual fun RaviloPlayer.togglePictureInPicture() { jsTogglePip(video) }

private fun nowMs(): Double = js("performance.now()")

/** R376 (FR-R376-2) — queue every event R218 needs with its time; [WebPlaybackEvents] reads the queue. A `stalled`
 *  while the element still has data to play is the network pausing, not the viewer waiting, and is left out. */
private fun wireEvents(video: HTMLVideoElement): Unit = js(
    """{
        video._rvq = [];
        video._rvPush = function (n) { if (video._rvq.length < 500) video._rvq.push(n + '@' + performance.now()); };
        ['loadstart','loadeddata','playing','canplay','waiting','stalled','seeking','seeked','play','pause','ended','error'].forEach(function (n) {
            video.addEventListener(n, function () {
                if (n === 'stalled' && video.readyState >= 3) return;
                video._rvPush(n);
            });
        });
    }"""
)

private fun drainEvents(video: HTMLVideoElement): String = js(
    """(function(){ var q = video._rvq || []; video._rvq = []; return q.join(','); })()"""
)

/**
 * play() returns a promise that rejects when the browser refuses it. R376 (2026-10-08, measured on Safari 27): a play
 * that did not come from a real click, tap or key — a play pushed by the server, one started after the ticket's fetch
 * — is refused with sound (`NotAllowedError`) and the element just sits paused. Then: play **muted** (allowed), queue
 * `soundblocked` for [WebPlaybackEvents] (the chrome shows *Click or press a key for sound*), and unmute on the
 * viewer's next trusted click, tap or key, which Safari accepts as the gesture it wanted (`soundon`).
 */
private fun playVideo(video: HTMLVideoElement): Unit = js(
    """{
        try {
            var p = video.play();
            if (p && p.catch) p.catch(function (e) {
                if (!e || e.name !== 'NotAllowedError' || video.muted) return;
                video.muted = true;
                if (video._rvPush) video._rvPush('soundblocked');
                try { var q = video.play(); if (q && q.catch) q.catch(function(){}); } catch (e2) {}
                if (video._rvUnmute) return;
                var names = ['pointerdown', 'keydown', 'touchend'];
                video._rvUnmute = function (ev) {
                    if (!ev.isTrusted) return;
                    names.forEach(function (n) { window.removeEventListener(n, video._rvUnmute, true); });
                    video._rvUnmute = null;
                    video.muted = false;
                    if (video._rvPush) video._rvPush('soundon');
                };
                names.forEach(function (n) { window.addEventListener(n, video._rvUnmute, true); });
            });
        } catch (e) {}
    }"""
)

/**
 * R44: wire the browser Media Session API so OS / keyboard media-transport keys drive the <video>.
 * Handlers operate on the element directly (no WASM↔JS callback bridge); the shared chrome reflects
 * the new state on its next poll. Wrapped in try/catch — `mediaSession` is absent on some browsers.
 */
private fun wireMediaSession(video: HTMLVideoElement): Unit = js(
    """{
        if (typeof navigator !== 'undefined' && navigator.mediaSession) {
            var ms = navigator.mediaSession;
            try {
                ms.setActionHandler('play', function () { video.play(); });
                ms.setActionHandler('pause', function () { video.pause(); });
                ms.setActionHandler('seekforward', function () {
                    var d = video.duration; video.currentTime = Math.min(isFinite(d) ? d : 1e9, video.currentTime + 30);
                });
                ms.setActionHandler('seekbackward', function () {
                    video.currentTime = Math.max(0, video.currentTime - 10);
                });
                ms.setActionHandler('stop', function () { video.pause(); });
            } catch (e) {}
        }
    }"""
)

/** R376 (FR-R376-4) — the title, the series/episode line and the artwork on the OS's media controls. */
private fun setMediaMetadata(title: String, line: String, artwork: String): Unit = js(
    """{
        try {
            if (navigator.mediaSession && typeof MediaMetadata !== 'undefined') {
                var init = { title: title, artist: line };
                if (artwork) init.artwork = [{ src: artwork }];
                navigator.mediaSession.metadata = new MediaMetadata(init);
            }
        } catch (e) {}
    }"""
)

private fun clearMediaMetadata(): Unit = js("""{ try { if (navigator.mediaSession) navigator.mediaSession.metadata = null; } catch (e) {} }""")

/**
 * R17 — set the video source, lazy-loading hls.js the first time an HLS (.m3u8) stream is played. Direct-play URLs
 * just set `video.src`.
 *
 * R376 (FR-R376-7) — **native HLS first on Safari** (AirPlay needs it, R265): WebKit's playback-target event is the
 * Safari tell, and a browser with no MediaSource at all has nothing else. Every other browser that can (Chrome on
 * Android plays HLS natively too, but exposes no audio tracks to switch) gets hls.js 1.7, configured for the
 * household: its transmuxer in a worker (self-hosted — `worker-src 'self'`), a bounded back buffer, the ticket's start
 * position, and Managed Media Source where only that exists (an iPhone that reaches hls.js). A fatal media error is
 * recovered once; anything else fatal is queued as `hlsfatal` (FR-R376-2), which R306 turns into the error card.
 */
private fun attachSource(video: HTMLVideoElement, url: String, startSec: Double): Unit = js(
    """{
        video._rvWantAudio = null;
        function native(){ video.src = url; if (startSec > 0) { try { video.currentTime = startSec; } catch (e) {} } }
        if (video._hls) { try { video._hls.destroy(); } catch(e){} video._hls = null; }
        if (url.indexOf('.m3u8') === -1) { native(); return; }
        var nativeHls = video.canPlayType && video.canPlayType('application/vnd.apple.mpegurl') !== '';
        var safari = typeof window.WebKitPlaybackTargetAvailabilityEvent !== 'undefined';
        var mse = !!(window.MediaSource || window.ManagedMediaSource);
        if (nativeHls && (safari || !mse)) { native(); return; }
        function attach(){
            try {
                if (window.Hls && window.Hls.isSupported()) {
                    var Hls = window.Hls;
                    // R376 (FR-R376-7) — the transmuxer in a worker (self-hosted), a bounded back buffer, the ticket's
                    // start position, Managed Media Source where only that exists.
                    // 308 (FR-308-2/-3) — hls.js's own ABR picks between the master's variants, seeded with what this
                    // device measured; its switches are counted for the QoE report (FR-308-5). Every variant is its own
                    // encode, so keep a minute ahead (the 60 MB default holds 12 s of a 40 Mbps top), step down while
                    // there is still room for a new variant's start, and step up only with real headroom.
                    var cfg = {
                        enableWorker: true,
                        workerPath: 'vendor/hls.worker.js',
                        backBufferLength: 90,
                        maxBufferLength: 60,
                        maxBufferSize: 300 * 1000 * 1000,
                        abrBandWidthFactor: 0.7,
                        abrBandWidthUpFactor: 0.5,
                        startPosition: startSec > 0 ? startSec : -1,
                        preferManagedMediaSource: true
                    };
                    if (window.__raviloAbrSeed > 0) cfg.abrEwmaDefaultEstimate = window.__raviloAbrSeed;
                    var hls = new Hls(cfg);
                    video._hls = hls;
                    var q = video._q308 || (video._q308 = { down: 0, up: 0, bps: 0, h: 0 });
                    q.bps = 0; q.h = 0;
                    hls.on(Hls.Events.LEVEL_SWITCHED, function (e, d) {
                        var l = hls.levels && hls.levels[d.level]; if (!l) return;
                        if (q.bps && l.bitrate !== q.bps) { if (l.bitrate < q.bps) q.down++; else q.up++; if (video._rvPush) video._rvPush('variant'); }
                        q.bps = l.bitrate || 0; q.h = l.height || 0;
                    });
                    hls.on(Hls.Events.ERROR, function (ev, d) {
                        if (!d || !d.fatal) return;
                        if (d.type === Hls.ErrorTypes.MEDIA_ERROR && !hls._rvRecovered) { hls._rvRecovered = true; hls.recoverMediaError(); return; }
                        if (video._rvPush) video._rvPush('hlsfatal');
                    });
                    // R376 (FR-R376-3) — a pick made before the manifest was read is applied once its renditions are.
                    hls.on(Hls.Events.AUDIO_TRACKS_UPDATED, function () {
                        var want = video._rvWantAudio;
                        if (want === null || want === undefined) return;
                        video._rvWantAudio = null;
                        var name = 'a' + want;
                        for (var i = 0; i < hls.audioTracks.length; i++) {
                            var n = hls.audioTracks[i].name;
                            if (n === name || (typeof n === 'string' && n.indexOf(name + ' ') === 0)) { if (hls.audioTrack !== i) hls.audioTrack = i; return; }
                        }
                    });
                    hls.loadSource(url); hls.attachMedia(video);
                } else { native(); }
            } catch(e) { native(); }
        }
        if (window.Hls) { attach(); return; }
        if (!window.__hlsLoading) {
            window.__hlsLoading = true;
            var s = document.createElement('script');
            // FR-235-9 — self-hosted (was cdn.jsdelivr.net): the corrected CSP's script-src 'self'
            // would otherwise fail every transcoded play on the production web origin, see
            // ravilo-web/src/wasmJsMain/resources/vendor/NOTICE.md.
            s.src = 'vendor/hls.min.js';
            s.onload = attach; s.onerror = native;
            document.head.appendChild(s);
        } else {
            var iv = setInterval(function(){ if (window.Hls){ clearInterval(iv); attach(); } }, 100);
            setTimeout(function(){ clearInterval(iv); }, 8000);
        }
    }"""
)

/**
 * R17 — render an .ass/.ssa subtitle with JASSUB (libass in WASM), lazy-loaded on first use so the
 * worker + wasm aren't fetched unless a styled subtitle is actually selected.
 */
private fun mountAss(video: HTMLVideoElement, url: String): Unit = js(
    """{
        function go(){
            try {
                if (video._jassub) { try { video._jassub.destroy(); } catch(e){} video._jassub = null; }
                video._jassub = new window.JASSUB({
                    video: video,
                    subUrl: url,
                    // FR-235-9 — self-hosted (was cdn.jsdelivr.net), see
                    // ravilo-web/src/wasmJsMain/resources/vendor/NOTICE.md. default.woff2 (JASSUB's
                    // fallback font, resolved by the library itself as './default.woff2') sits alongside
                    // these two in the same vendor/ directory.
                    workerUrl: 'vendor/jassub-worker.js',
                    wasmUrl: 'vendor/jassub-worker.wasm'
                });
            } catch(e) {}
        }
        if (window.JASSUB) { go(); return; }
        if (!window.__jassubLoading) {
            window.__jassubLoading = true;
            var s = document.createElement('script');
            s.src = 'vendor/jassub.umd.js';
            s.onload = go;
            document.head.appendChild(s);
        } else {
            var iv = setInterval(function(){ if (window.JASSUB){ clearInterval(iv); go(); } }, 100);
            setTimeout(function(){ clearInterval(iv); }, 8000);
        }
    }"""
)

/**
 * R110 — inject a one-time global ::cue style so native <track> (VTT) captions match the TV's
 * Android look: white text with a GENTLE outline and no background box. CSS ::cue does not reliably
 * honor -webkit-text-stroke across browsers, so the outline is emulated with an 8-direction text-shadow
 * stack at ~1px and ~80% black (rgba(0,0,0,.8)) — defined but soft/easy on the eyes, not a hard border
 * (the design mockup `ravilo-player.css` .pl-sub documents the shadow route). Guarded by an element id
 * so repeated load() calls add it at most once. JASSUB/ASS subs are untouched — they carry their own
 * author styling and must not be overridden.
 */
private fun installCueStyle(): Unit = js(
    """{
        if (document.getElementById('ravilo-cue-style')) return;
        var st = document.createElement('style');
        st.id = 'ravilo-cue-style';
        var c = 'rgba(0,0,0,.8)';
        st.textContent =
            'video::cue{' +
            'color:#fff;' +
            'background:transparent;' +
            'font-weight:600;' +
            'text-shadow:' +
            '-1px -1px 0 ' + c + ',1px -1px 0 ' + c + ',' +
            '-1px 1px 0 ' + c + ',1px 1px 0 ' + c + ',' +
            '0 -1px 0 ' + c + ',0 1px 0 ' + c + ',' +
            '-1px 0 0 ' + c + ',1px 0 0 ' + c + ';' +
            '}';
        document.head.appendChild(st);
    }"""
)

/**
 * R68 — fetch a VTT subtitle URL, strip ASS/SSA override tags from every cue, and return a
 * blob: URL pointing at the cleaned VTT so the browser <track> renders clean text.
 * The callback receives the cleaned URL (blob: or the original on fetch failure).
 */
private fun fetchAndCleanVtt(url: String, callback: (String) -> Unit): Unit = js(
    """{
        fetch(url)
            .then(function(r){ return r.text(); })
            .then(function(vtt){
                // Strip {\...} ASS override blocks and normalize \N/\h escape sequences.
                var cleaned = vtt
                    .replace(/\{\\[^}]*\}/g, '')
                    .replace(/\\N/g, '\n')
                    .replace(/\\n/g, '\n')
                    .replace(/\\h/g, ' ');
                var blob = new Blob([cleaned], {type: 'text/vtt'});
                var blobUrl = URL.createObjectURL(blob);
                callback(blobUrl);
            })
            .catch(function(){ callback(url); }); // fall back to original on error
    }"""
)

/**
 * R291 (FR-R291-4) / R376 (FR-R376-3) — pick the audio rendition named `a{index}` / `a{index} …` (the backend's
 * composeMaster). hls.js lists the master's EXT-X-MEDIA entries, the muxed default included, in `hls.audioTracks`;
 * Safari's native player lists them in `video.audioTracks`, one enabled at a time. 1 = selected; 2 = the browser has
 * not read the renditions yet, so the pick is held and applied when it has; 0 = there is nothing by that name.
 */
private fun selectRendition(video: HTMLVideoElement, index: Int): Int = js(
    """(function(){
        var want = 'a' + index;
        function named(n){ return n === want || (typeof n === 'string' && n.indexOf(want + ' ') === 0); }
        var hls = video._hls;
        if (hls) {
            if (!hls.audioTracks || !hls.audioTracks.length) { video._rvWantAudio = index; return 2; }
            for (var i = 0; i < hls.audioTracks.length; i++) {
                if (named(hls.audioTracks[i].name)) { if (hls.audioTrack !== i) hls.audioTrack = i; return 1; }
            }
            return 0;
        }
        var list = video.audioTracks;
        if (list && list.length) {
            var hit = -1;
            for (var j = 0; j < list.length; j++) if (named(list[j].label)) hit = j;
            if (hit < 0) return 0;
            for (var k = 0; k < list.length; k++) list[k].enabled = (k === hit);
            return 1;
        }
        if (list && video.readyState < 1 && (video.currentSrc || '').indexOf('.m3u8') !== -1) {
            video._rvWantAudio = index;
            video.addEventListener('loadedmetadata', function once() {
                video.removeEventListener('loadedmetadata', once);
                var w = video._rvWantAudio; video._rvWantAudio = null;
                if (w === null || w === undefined) return;
                var name = 'a' + w;
                for (var m = 0; m < video.audioTracks.length; m++) {
                    var l = video.audioTracks[m].label;
                    if (l === name || (typeof l === 'string' && l.indexOf(name + ' ') === 0)) {
                        for (var q = 0; q < video.audioTracks.length; q++) video.audioTracks[q].enabled = (q === m);
                        return;
                    }
                }
            });
            return 2;
        }
        return 0;
    })()"""
)

/** R376 (FR-R376-3) — the names of the audio renditions the browser lists for this stream, joined by U+0001. */
private fun renditionNames(video: HTMLVideoElement): String = js(
    """(function(){
        var out = [];
        var hls = video._hls;
        if (hls && hls.audioTracks) { for (var i = 0; i < hls.audioTracks.length; i++) out.push(hls.audioTracks[i].name || ''); }
        else if (video.audioTracks) { for (var j = 0; j < video.audioTracks.length; j++) out.push(video.audioTracks[j].label || ''); }
        return out.join('\u0001');
    })()"""
)

/** True while an HLS stream's renditions are not known yet (hls.js before its manifest, Safari before metadata). */
private fun renditionsPending(video: HTMLVideoElement): Boolean = js(
    """(function(){
        if (video._hls) return !(video._hls.audioTracks && video._hls.audioTracks.length) && !(video._hls.levels && video._hls.levels.length);
        return (video.currentSrc || video.src || '').indexOf('.m3u8') !== -1 && video.readyState < 1;
    })()"""
)

private fun hasRenditionList(video: HTMLVideoElement): Boolean = js(
    """!!((video._hls && video._hls.audioTracks && video._hls.audioTracks.length) || (video.audioTracks && video.audioTracks.length > 1))"""
)

/** R376 (FR-R376-6) — `getVideoPlaybackQuality()` where the browser has it (all current ones), else 0. */
private fun droppedFrames(video: HTMLVideoElement): Int = js(
    """(function(){ try { var q = video.getVideoPlaybackQuality && video.getVideoPlaybackQuality(); return q ? (q.droppedVideoFrames | 0) : 0; } catch (e) { return 0; } })()"""
)

/**
 * hls.js's live bandwidth estimate — 309 (FR-309-13): only once its estimator rests on real fragment samples. Before
 * that `bandwidthEstimate` is its default (or 308's seed), a guess, and a guess is never reported as a measurement.
 */
private fun hlsBandwidth(video: HTMLVideoElement): Double = js(
    """(function(){
        var h = video._hls; if (!h) return 0;
        var est = h.abrController && h.abrController.bwEstimator;
        if (est && typeof est.canEstimate === 'function' && !est.canEstimate()) return 0;
        var b = h.bandwidthEstimate; return (b && isFinite(b)) ? b : 0;
    })()"""
)

/** The diagnostic line R216 keeps: which engine, the level hls.js chose, and the frames decoded so far. */
private fun decoderLine(video: HTMLVideoElement): String = js(
    """(function(){
        var parts = [];
        var h = video._hls;
        if (h) {
            parts.push('web hls.js');
            var l = h.levels && h.levels[h.currentLevel];
            if (l) parts.push((l.width || 0) + 'x' + (l.height || 0) + ' ' + Math.round((l.bitrate || 0) / 1000) + ' kbps');
        } else parts.push((video.currentSrc || '').indexOf('.m3u8') !== -1 ? 'web native hls' : 'web');
        try { var q = video.getVideoPlaybackQuality && video.getVideoPlaybackQuality(); if (q) parts.push(q.totalVideoFrames + ' frames decoded'); } catch (e) {}
        return parts.join(' · ');
    })()"""
)

/** R376 (FR-R376-8) — absent where the browser has no picture-in-picture for a video (iPhone Safari in a tab, Firefox). */
private fun jsPipAvailable(video: HTMLVideoElement): Boolean = js(
    """!!(document.pictureInPictureEnabled && video.requestPictureInPicture && !video.disablePictureInPicture)"""
)

private fun jsTogglePip(video: HTMLVideoElement): Unit = js(
    """{
        try {
            if (document.pictureInPictureElement === video) { document.exitPictureInPicture().catch(function(){}); }
            else { video.requestPictureInPicture().catch(function(){}); }
        } catch (e) {}
    }"""
)

/** R17 — destroy any hls.js / JASSUB instance attached to the element (called on release). */
/** R284 — how one entry of the player's subtitle list is shown: a `<track>` (by its position among
 *  the element's text tracks) or an ASS file through JASSUB. */
private class SubtitleSlot(val textTrack: Int, val assUrl: String?)

/** R284 (FR-R284-4) — `showing` for text track [show], `disabled` for every other; -1 disables all.
 *  `disabled` rather than `hidden`: a hidden track still fires cue events and keeps its cues loaded.
 *  R265 — remembered on the element, so [watchTextTracks] can apply it to tracks that arrive later. */
private fun showTextTrack(video: HTMLVideoElement, show: Int): Unit = js(
    """{
        video._raviloShow = show;
        var t = video.textTracks;
        for (var i = 0; i < t.length; i++) { t[i].mode = (i === show) ? 'showing' : 'disabled'; }
    }"""
)

/** R265 (FR-R265-8) — a manifest's subtitle renditions become text tracks only once Safari has read the
 *  manifest, after the selection was made; each one that arrives gets the remembered selection (and a
 *  DEFAULT rendition the resolver did not pick is turned off rather than shown by the browser). */
private fun watchTextTracks(video: HTMLVideoElement): Unit = js(
    """{
        video._raviloShow = -1;
        try {
            video.textTracks.addEventListener('addtrack', function () {
                var t = video.textTracks, show = video._raviloShow;
                for (var i = 0; i < t.length; i++) { t[i].mode = (i === show) ? 'showing' : 'disabled'; }
            });
        } catch (e) {}
    }"""
)

private fun unmountAss(video: HTMLVideoElement): Unit = js(
    """{ if (video._jassub) { try { video._jassub.destroy(); } catch(e){} video._jassub = null; } }"""
)

private fun destroyOverlays(video: HTMLVideoElement): Unit = js(
    """{
        if (document.pictureInPictureElement === video) { try { document.exitPictureInPicture().catch(function(){}); } catch (e) {} }
        if (video._hls) { try { video._hls.destroy(); } catch(e){} video._hls = null; }
        if (video._jassub) { try { video._jassub.destroy(); } catch(e){} video._jassub = null; }
    }"""
)

private fun setCueScale(scale: Float): Unit = js(
    """{
        var st = document.getElementById('ravilo-cue-size');
        if (!st) { st = document.createElement('style'); st.id = 'ravilo-cue-size'; document.head.appendChild(st); }
        st.textContent = 'video::cue{font-size:' + Math.round(scale * 100) + '%;}';
    }"""
)
