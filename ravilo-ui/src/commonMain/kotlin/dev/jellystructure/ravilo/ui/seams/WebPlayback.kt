package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.AudioTrack

// R376 — the web player's decisions, kept here as plain functions so they are tested on every platform (the build has
// no browser test runner); the wasmJs actuals feed them what the browser reported.

/**
 * R376 (FR-R376-2) — the `<video>` element's and hls.js's events, turned into the facts R218 reads. The wasmJs player
 * queues every event with its time and hands the queue here on each read, so the poll loop sees what happened in
 * order, never a sample of the element at one instant.
 *
 * - `loadstart` — a new source: the per-item facts start again (the stall counters do not; they are the session's,
 *   like Android's QoE counters).
 * - `loadeddata` / `playing` — a frame is on screen.
 * - `waiting` / `stalled` — buffering (the player only queues `stalled` while the element really lacks data). After the
 *   first frame and outside a seek it is a stall, counted for R216 with its length.
 * - `playing` / `canplay` — the buffering is over.
 * - `seeking` / `seeked` — a seek is in flight; its buffering is R218's moment D, not a stall.
 * - `ended`, cleared by `play`. `error` and hls.js's unrecoverable `hlsfatal` — the start failed (R306).
 */
internal class WebPlaybackEvents {
    var firstFrame = false; private set
    var buffering = false; private set
    var seeking = false; private set
    var ended = false; private set
    var failed = false; private set
    var stallCount = 0; private set
    var stallMs = 0L; private set
    private var stallStartedAt: Double? = null

    fun on(event: String, atMs: Double) {
        when (event) {
            "loadstart" -> { endStall(atMs); firstFrame = false; buffering = false; seeking = false; ended = false; failed = false }
            "loadeddata" -> firstFrame = true
            "playing" -> { firstFrame = true; buffering = false; endStall(atMs) }
            "canplay" -> { buffering = false; endStall(atMs) }
            "waiting", "stalled" -> {
                if (!buffering && firstFrame && !seeking && stallStartedAt == null) { stallCount++; stallStartedAt = atMs }
                buffering = true
            }
            "seeking" -> seeking = true
            "seeked" -> seeking = false
            "play" -> ended = false
            "ended" -> { ended = true; buffering = false; endStall(atMs) }
            "error", "hlsfatal" -> { failed = true; buffering = false; endStall(atMs) }
        }
    }

    /** A stall still running counts up to [nowMs], so a report sent in the middle of one is not short. */
    fun stallMsAt(nowMs: Double): Long = stallMs + (stallStartedAt?.let { (nowMs - it).toLong().coerceAtLeast(0) } ?: 0)

    private fun endStall(atMs: Double) {
        stallStartedAt?.let { stallMs += (atMs - it).toLong().coerceAtLeast(0) }
        stallStartedAt = null
    }

    /** The queue the wasmJs player keeps: `name@ms` entries joined by commas. */
    fun feed(queue: String) {
        if (queue.isEmpty()) return
        for (entry in queue.split(',')) {
            val at = entry.lastIndexOf('@')
            if (at <= 0) continue
            on(entry.substring(0, at), entry.substring(at + 1).toDoubleOrNull() ?: continue)
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
 * R376 (FR-R376-3) — a direct-play ticket for a file with two or more audio tracks, on a player that cannot switch
 * tracks inside one file (Chrome and Firefox expose no `audioTracks`), is asked for again as HLS, where R291's master
 * carries every track as a rendition. A single-audio file keeps direct play; a client that already asked for HLS
 * only is never asked twice.
 */
internal fun asksHlsForAudio(directPlay: Boolean, audioTracks: Int, alreadyHlsOnly: Boolean, switchesInFile: Boolean): Boolean =
    directPlay && audioTracks >= 2 && !alreadyHlsOnly && !switchesInFile
