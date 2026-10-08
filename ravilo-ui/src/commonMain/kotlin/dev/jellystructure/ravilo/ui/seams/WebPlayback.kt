package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.AudioTrack

// R376 — the web player's decisions, kept here as plain functions so they are tested on every platform (the build has
// no browser test runner); the wasmJs actuals feed them what the browser reported.

/**
 * R376 (FR-R376-2) — the `<video>` element's and hls.js's events, turned into the facts R218 reads. The wasmJs player
 * queues every event with its time and hands the queue here on each read, so the poll loop sees what happened in
 * order, never a sample of the element at one instant.
 *
 * - `loadstart` — a new source: the per-load facts start again. R381 (FR-R381-1) — the QoE counts are [qoe]'s, per
 *   item ([QoeCounter.beginItem]); a new load of the same item (a restream) keeps them.
 * - `soundblocked` / `soundon` — the browser refused sound for a play with no gesture, and the viewer's next real
 *   click, tap or key gave it back ([soundBlocked]).
 * - `pause` — a wait the viewer caused is dropped from the QoE counts, never a stall. `variant` (hls.js switched
 *   level, 308) — a wait right after it belongs to the switch.
 * - `loadeddata` / `playing` — a frame is on screen.
 * - `waiting` / `stalled` — buffering (the player only queues `stalled` while the element really lacks data). After the
 *   first frame and outside a seek it is a stall, counted for R216 with its length.
 * - `playing` / `canplay` — the buffering is over.
 * - `seeking` / `seeked` — a seek is in flight; its buffering is R218's moment D, not a stall.
 * - `ended`, cleared by `play`. `error` and hls.js's unrecoverable `hlsfatal` — the start failed (R306).
 */
internal class WebPlaybackEvents(private val qoe: QoeCounter? = null) {
    var firstFrame = false; private set
    var buffering = false; private set
    var seeking = false; private set
    var ended = false; private set
    var failed = false; private set
    /** The browser refused sound for a play that did not come from a gesture; the element plays muted until the
     *  viewer's next real click, tap or key (`soundblocked` → `soundon`). Survives a new source: the element stays
     *  muted until then. */
    var soundBlocked = false; private set
    var stallCount = 0; private set
    var stallMs = 0L; private set
    private var stallStartedAt: Double? = null

    /** [positionMs], [bufferedAheadMs] and [variantBps] are where the player is now; a stall records them. */
    fun on(event: String, atMs: Double, positionMs: Long = 0, bufferedAheadMs: Long = 0, variantBps: Long? = null) {
        val at = atMs.toLong()
        when (event) {
            "loadstart" -> { endStall(atMs); firstFrame = false; buffering = false; seeking = false; ended = false; failed = false; qoe?.load() }
            "loadeddata" -> { firstFrame = true; qoe?.firstFrame(at) }
            "playing" -> { firstFrame = true; buffering = false; endStall(atMs); qoe?.firstFrame(at); qoe?.ready(at) }
            "canplay" -> { buffering = false; endStall(atMs); qoe?.ready(at) }
            "waiting", "stalled" -> {
                if (!buffering && firstFrame && !seeking && stallStartedAt == null) { stallCount++; stallStartedAt = atMs }
                buffering = true
                qoe?.buffering(at, positionMs, bufferedAheadMs, variantBps)
            }
            "seeking" -> { seeking = true; qoe?.seek() }
            "seeked" -> seeking = false
            "play" -> ended = false
            "pause" -> qoe?.interrupted()
            "soundblocked" -> { soundBlocked = true; qoe?.load() }   // the muted retry is this play's real start, not a stall
            "soundon" -> soundBlocked = false
            "variant" -> qoe?.variantSwitched(at)
            "ended" -> { ended = true; buffering = false; endStall(atMs); qoe?.interrupted() }
            "error", "hlsfatal" -> { failed = true; buffering = false; endStall(atMs); qoe?.interrupted() }
        }
    }

    /** A stall still running counts up to [nowMs], so a report sent in the middle of one is not short. */
    fun stallMsAt(nowMs: Double): Long = stallMs + (stallStartedAt?.let { (nowMs - it).toLong().coerceAtLeast(0) } ?: 0)

    private fun endStall(atMs: Double) {
        stallStartedAt?.let { stallMs += (atMs - it).toLong().coerceAtLeast(0) }
        stallStartedAt = null
    }

    /** The queue the wasmJs player keeps: `name@ms` entries joined by commas. */
    fun feed(queue: String, positionMs: Long = 0, bufferedAheadMs: Long = 0, variantBps: Long? = null) {
        if (queue.isEmpty()) return
        for (entry in queue.split(',')) {
            val at = entry.lastIndexOf('@')
            if (at <= 0) continue
            on(entry.substring(0, at), entry.substring(at + 1).toDoubleOrNull() ?: continue, positionMs, bufferedAheadMs, variantBps)
        }
    }
}

/** R291/R376 (FR-R376-3) — the position in a composed master's rendition name: `a3 Dansk` → 3, `a0` → 0, else null. */
internal fun renditionPosition(name: String?): Int? {
    if (name == null || !name.startsWith("a")) return null
    val digits = name.drop(1).takeWhile { it.isDigit() }
    if (digits.isEmpty()) return null
    val rest = name.drop(1 + digits.length)
    if (rest.isNotEmpty() && !rest.startsWith(" ")) return null
    return digits.toIntOrNull()
}

/**
 * R376 (FR-R376-3) — the picker lists what the stream carries: one entry per rendition the browser (hls.js or Safari)
 * lists, by its position, labelled with the server's name for that position. A rendition the server did not name is
 * left out, never invented; an empty answer means the stream has no named renditions.
 */
internal fun audioTracksFromRenditions(renditionNames: List<String?>, server: List<AudioTrack>): List<PlayerAudioTrack> =
    renditionNames.mapNotNull { renditionPosition(it) }.distinct().sorted().mapNotNull { pos ->
        val a = server.getOrNull(pos) ?: return@mapNotNull null
        val label = a.label?.takeIf { it.isNotBlank() } ?: languageName(a.language) ?: a.language ?: "Track ${pos + 1}"
        PlayerAudioTrack(pos, label, a.language, a.channels, a.isDefault)
    }

/**
 * R376 (FR-R376-5) — the containers a browser declares: mp4 always; mkv and webm only where the browser says it opens
 * them (Safari: mp4 only). A container left off is transcoded instead of failing in the element.
 */
internal fun webContainers(mkv: Boolean, webm: Boolean): List<String> =
    listOfNotNull("mp4", "mkv".takeIf { mkv }, "webm".takeIf { webm })

/**
 * R376 (FR-R376-3, owner 2026-10-08: *direct play first; switch to HLS only when another audio track is picked*) — a
 * multi-audio file starts as direct play like any other (≈ 1 s, no Jellyfin job). When the viewer picks another audio
 * track and the player cannot switch tracks inside one file (Chrome and Firefox expose no `audioTracks`), the restream
 * for that pick asks for HLS, where R291's master carries every track as a rendition; from then on the item stays on
 * HLS. A player that switches in the file, or a session already on HLS, keeps what it has.
 */
internal fun audioPickNeedsHls(audioRestream: Boolean, alreadyHlsOnly: Boolean, switchesInFile: Boolean): Boolean =
    audioRestream && !alreadyHlsOnly && !switchesInFile

/**
 * R376 (owner, 2026-10-08; changes R265 FR-R265-8 for Safari) — a Safari start asks for HLS only when the picture is
 * already on AirPlay (the next episode of an AirPlay session); otherwise it negotiates like any browser and
 * direct-plays what it can.
 */
internal fun startsAsAirPlayHls(nativeHlsWithAirPlay: Boolean, onAirPlayNow: Boolean): Boolean =
    nativeHlsWithAirPlay && onAirPlayNow

/** R376 — the viewer just picked AirPlay: a stream that is not HLS yet restarts as HLS, once per item. */
internal fun airplayNeedsHls(onAirPlay: Boolean, streamIsHls: Boolean, alreadyRestartedForAirPlay: Boolean): Boolean =
    onAirPlay && !streamIsHls && !alreadyRestartedForAirPlay

/** R376 — what the AirPlay restart asks for: HLS only, the manifest's subtitles, and H.264 (no HEVC over AirPlay). */
internal fun airplayCapabilities(c: dev.jellystructure.shared.tv.ClientCapabilities): dev.jellystructure.shared.tv.ClientCapabilities =
    c.copy(hlsOnly = true, hlsSubtitles = true, hlsHevc = false)

/**
 * R376 (FR-R376-S1) — sound on the first click in Safari. WebKit lets an element play with sound only once a `play()`
 * on it ran inside a user gesture; after that the element keeps the right for good (WebKit's
 * `removeBehaviorRestrictionsAfterFirstUserGesture`, applied by `play()` even with no source loaded). Ravilo's Play
 * click fetches the ticket first, so the film's own `play()` comes too late. So the page keeps **one** `<video>` element
 * for its lifetime, and every trusted click, tap or key calls `play()` + `pause()` on it while it holds no film — before
 * any network call — which unlocks it ahead of the play that follows.
 */
internal object WebSoundUnlock {
    /** The DOM events WebKit treats as a gesture for media (iOS counts `touchend`/`click`, macOS the mouse and keys). */
    val gestureEvents: List<String> = listOf("pointerdown", "mousedown", "pointerup", "mouseup", "click", "touchend", "keydown")

    /** Unlock on this event? Only a trusted gesture, and only while the element holds no film: a click during a film
     *  must never resume or restart it. */
    fun shouldUnlock(event: String, trusted: Boolean, hasSource: Boolean): Boolean =
        trusted && !hasSource && event in gestureEvents
}

/**
 * R376 (FR-R376-S1) — who holds the page's one `<video>` element. A new player takes it over; an older player's late
 * release (Compose can create the next screen's player before disposing the last one's) must not reset the element the
 * new one is already playing on.
 */
internal class SharedElementOwner {
    private var owner: Any? = null
    val current: Any? get() = owner
    fun acquire(player: Any) { owner = player }
    /** True when [player] still held it (so the element is reset); false for a stale release. */
    fun release(player: Any): Boolean = if (owner === player) { owner = null; true } else false
}
