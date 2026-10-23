package dev.jellystructure.ravilo.ui.seams

/**
 * DEB-2252 / DEB-2294 (FR-R330-7) — when the Mac's cast bar should ask the speaker for its media status again.
 *
 * The sender asks for a media `GET_STATUS` only at join, and after that lives on the receiver's broadcasts. A
 * `BUFFERING` / `LOADING` status that is never followed by its `PLAYING` (found live 2026-10-23, Mac, DEB-2288: 0:00
 * and the equaliser glyph for three songs while the speaker played on; one press of play, which makes the receiver
 * answer with a fresh status, fixed it) then stands for ever. The phone never shows it because the Cast SDK re-asks.
 *
 * Pure and clocked by the caller, so the rules are tested without a socket. The caller tells it when a `MEDIA_STATUS`
 * arrives ([mediaArrived]) and asks on each tick ([due]); a non-null answer is the reason to send one `GET_STATUS`.
 */
internal class CastMediaWatch(
    /** How long the media status may read BUFFERING / LOADING / nothing before it is asked for again. */
    private val staleMs: Long = STALE_MS,
    /** How long a song change on our channel may go without a new media session before it is asked for. */
    private val graceMs: Long = GRACE_MS,
    /** At most one request per this long. */
    private val everyMs: Long = EVERY_MS,
) {
    private var lastMediaAt: Long? = null
    private var session: Int? = null
    private var sessionChangedAt: Long? = null
    private var unsettledSince: Long? = null
    private var songKey: String? = null
    private var songChangedAt: Long = 0L
    private var askedAt: Long? = null
    private var rawSinceSong = 0

    /** A `MEDIA_STATUS` was read at [nowMs]; [mediaSessionId] tells a new song's session from the same one's update. */
    fun mediaArrived(nowMs: Long, mediaSessionId: Int) {
        lastMediaAt = nowMs
        if (mediaSessionId != session) { session = mediaSessionId; sessionChangedAt = nowMs }
    }

    /**
     * DEB-2312 — a `MEDIA_STATUS` came off the wire, counted before the StateFlow that [mediaArrived] is fed from drops an
     * identical one: `age` growing does not by itself say that no reply came.
     */
    fun rawArrived() { rawSinceSong++ }

    /** How many raw `MEDIA_STATUS` messages have arrived since the song last changed on our channel, for the log. */
    fun rawSinceSongChange(): Int = rawSinceSong

    /** Milliseconds since the last `MEDIA_STATUS`, for the log; null when none has come. */
    fun ageMs(nowMs: Long): Long? = lastMediaAt?.let { nowMs - it }

    /**
     * Whether to send a media `GET_STATUS` now, and why: `"buffering"` (the status has read BUFFERING / LOADING /
     * nothing for more than [staleMs]) or `"song"` (our channel names another song, [songKey], and no new media
     * session has come with it). [live] is the link connected and music loaded; [playerState] is the media status's.
     */
    fun due(nowMs: Long, live: Boolean, playerState: String?, songKey: String?): String? {
        if (this.songKey != songKey) { this.songKey = songKey; songChangedAt = nowMs; rawSinceSong = 0 }
        val unsettled = playerState == null || playerState == "BUFFERING" || playerState == "LOADING"
        if (!live || !unsettled) unsettledSince = null else if (unsettledSince == null) unsettledSince = nowMs
        if (!live) return null
        val stale = unsettledSince?.let { nowMs - it > staleMs } == true
        // The receiver names the new song and its media session in two messages; whichever comes first, a session that
        // began near the change has covered it. One that did not leaves the media status on the old song.
        // Any status read since the change (the answer to our ask included) settles it: a queue index that moved with no
        // new song, as shuffle does, is asked about once. No status at all is the "buffering" rule's.
        val behind = songKey != null && nowMs - songChangedAt >= graceMs && (lastMediaAt?.let { it < songChangedAt } == true) &&
            (sessionChangedAt?.let { it < songChangedAt - staleMs } ?: true)
        val reason = if (stale) "buffering" else if (behind) "song" else return null
        if (askedAt?.let { nowMs - it < everyMs } == true) return null
        askedAt = nowMs
        return reason
    }

    companion object {
        const val STALE_MS = 3_000L
        const val GRACE_MS = 1_000L
        const val EVERY_MS = 3_000L
    }
}
