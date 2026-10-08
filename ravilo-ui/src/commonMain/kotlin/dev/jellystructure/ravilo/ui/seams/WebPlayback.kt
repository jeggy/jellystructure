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
