package dev.jellystructure.shared.tv

// ─── R380 (FR-R380-7, owner 2026-10-08) — Ravilo's Cast channel, one reading for every receiver ────────────────────
//
// The phone's cast remote (R245/R324/R356) speaks [CAST_NAMESPACE]: it sends a [CastCommand] and builds its screen from
// the [CastReceiverMessage]s it gets back. The web receiver (`ravilo-cast`) answered it on its own; R266's Android TV app
// did not speak it at all, so against a TV running Ravilo the phone showed no queue, and its subtitles, audio and *Next
// episode* went nowhere. The owner: *"the phone keeps the same nice TV remote as it has for Chromecast currently, but it'll
// work against anything"*. So what a command means lives here, once, pure and tested: both receivers ask
// [castChannelStep] and only carry the answer out.

/** What a receiver does with one [CastCommand]. */
sealed interface CastChannelStep {
    /** Nothing to do (an unknown command, or one this kind of play has no use for). */
    data object Ignore : CastChannelStep
    /** Answer with a status; for music with the whole queue in it ([full]). */
    data class Status(val full: Boolean) : CastChannelStep
    /**
     * R369 (dev review item 4c) — a `next`/`prev`/`play_at` for a song that no longer plays: dropped, and a status
     * says where the queue really is (two `next`s on two paths skip once).
     */
    data object Stale : CastChannelStep
    /** R359 — the rest of a long queue. */
    data class QueuePart(val command: CastCommand) : CastChannelStep

    // ── music ──
    data object MusicNext : CastChannelStep
    data object MusicPrevious : CastChannelStep
    data class PlayAt(val index: Int) : CastChannelStep
    /** A queue edit already applied: the queue as it is now, and where the playing song is in it. */
    data class QueueEdited(val tracks: List<CastTrackItem>, val currentIndex: Int) : CastChannelStep
    data class Repeat(val mode: String) : CastChannelStep
    data class Shuffle(val on: Boolean) : CastChannelStep
    data class Lyrics(val on: Boolean) : CastChannelStep

    // ── a film or an episode ──
    data class SubSize(val size: String) : CastChannelStep
    data object EpisodeNext : CastChannelStep
    data object NextUpCancel : CastChannelStep
    data object NextUpPlay : CastChannelStep
    /** A position in the receiver's own `audio_tracks` list. */
    data class Audio(val index: Int) : CastChannelStep
    /** A position in the receiver's own `subtitle_tracks` list; -1 = off. */
    data class Subtitle(val index: Int) : CastChannelStep
}

/** The music a receiver plays, as far as a command needs to know; null fields for a film. */
data class CastChannelMusic(
    val tracks: List<CastTrackItem>,
    /** The playing song's place in [tracks]. */
    val currentIndex: Int,
    val currentItemId: String?,
    /** R359 — where [tracks] starts in the whole queue (0 unless a long queue is still arriving). */
    val queueStart: Int = 0,
)

/**
 * The one reading of a command. [music] is the queue when the receiver plays music, null for a film. A receiver handles
 * R359's `queue_part` and its own deferral while a long queue arrives before it asks (both are about the queue's arrival,
 * which only it can see), and then carries out the step.
 */
fun castChannelStep(cmd: CastCommand, music: CastChannelMusic?): CastChannelStep {
    if (cmd.type == "queue_part") return CastChannelStep.QueuePart(cmd)
    if (music != null) {
        if (cmd.type in STEPS_THAT_NAME_A_SONG && castCommandIsStale(cmd, music)) return CastChannelStep.Stale
        return when (cmd.type) {
            "next" -> CastChannelStep.MusicNext
            "prev" -> CastChannelStep.MusicPrevious
            "play_at" -> cmd.index?.let { CastChannelStep.PlayAt(it) } ?: CastChannelStep.Ignore
            "queue_move", "queue_remove", "queue_add", "queue_play_next" ->
                castQueueEdit(cmd, music.tracks, music.currentIndex, music.currentItemId)?.let { (t, i) -> CastChannelStep.QueueEdited(t, i) }
                    ?: CastChannelStep.Ignore
            "repeat" -> CastChannelStep.Repeat(cmd.mode?.takeIf { it in REPEAT_MODES } ?: "off")
            "shuffle" -> CastChannelStep.Shuffle(cmd.on == true)
            "lyrics" -> CastChannelStep.Lyrics(cmd.on == true)
            // R356 (FR-R356-8) — asked: the answer carries the whole queue.
            "status", "get_queue" -> CastChannelStep.Status(full = true)
            else -> CastChannelStep.Ignore
        }
    }
    return when (cmd.type) {
        "subsize" -> CastChannelStep.SubSize(cmd.size ?: "M")
        "next" -> CastChannelStep.EpisodeNext
        "nextup_cancel" -> CastChannelStep.NextUpCancel
        "nextup_play" -> CastChannelStep.NextUpPlay
        "status" -> CastChannelStep.Status(full = false)
        "audio" -> cmd.index?.let { CastChannelStep.Audio(it) } ?: CastChannelStep.Ignore
        "subtitle" -> CastChannelStep.Subtitle(cmd.index ?: -1)
        else -> CastChannelStep.Ignore
    }
}

private val STEPS_THAT_NAME_A_SONG = setOf("next", "prev", "play_at")
private val REPEAT_MODES = setOf("off", "all", "one")

/** R369 (dev review item 4c) — the sender named a song that no longer plays. Absent expectations are never stale. */
fun castCommandIsStale(cmd: CastCommand, music: CastChannelMusic): Boolean =
    (cmd.expectItem != null && music.currentItemId != null && cmd.expectItem != music.currentItemId) ||
        (cmd.expectIndex != null && cmd.expectIndex != music.queueStart + music.currentIndex)

/**
 * 286/R324 — a queue edit, applied: the new queue and the playing song's new place, or null when the edit names a place
 * outside the queue (or would remove the playing song). The playing song is found again by id after a move.
 */
fun castQueueEdit(cmd: CastCommand, tracks: List<CastTrackItem>, currentIndex: Int, currentItemId: String?): Pair<List<CastTrackItem>, Int>? =
    when (cmd.type) {
        "queue_move" -> {
            val from = cmd.index; val to = cmd.to
            if (from == null || to == null || from !in tracks.indices || to !in tracks.indices) null else {
                val list = tracks.toMutableList(); val item = list.removeAt(from); list.add(to, item)
                val id = currentItemId ?: tracks.getOrNull(currentIndex)?.id
                list to list.indexOfFirst { it.id == id }.coerceAtLeast(0)
            }
        }
        "queue_remove" -> {
            val i = cmd.index
            if (i == null || i !in tracks.indices || i == currentIndex) null else {
                val list = tracks.toMutableList(); list.removeAt(i)
                list to (if (i < currentIndex) currentIndex - 1 else currentIndex)
            }
        }
        "queue_add" -> cmd.track?.let { (tracks + it) to currentIndex }
        "queue_play_next" -> cmd.track?.let { t ->
            val list = tracks.toMutableList(); list.add((currentIndex + 1).coerceIn(0, list.size), t); list to currentIndex
        }
        else -> null
    }

/**
 * R356 (FR-R356-8) — the queue's revision, kept by a receiver: raised whenever the queue's songs or their order change.
 * A status carries the whole queue only when the revision moved since it was last sent whole, or when asked
 * ([askedForQueue]); otherwise the sender keeps its copy. One rule for every receiver.
 */
class CastQueueRevision {
    var rev: Int = 0
        private set
    private var fingerprint: Int? = null
    private var sentRev: Int = -1
    private var fullDue = true

    /** A sender connected, or asked: the next status carries the whole queue. */
    fun askedForQueue() { fullDue = true }

    /** The queue as it is now; returns whether this status should carry it whole. */
    fun next(tracks: List<CastTrackItem>): Boolean {
        val fp = tracks.map { it.id }.hashCode()
        if (fp != fingerprint) { fingerprint = fp; rev++ }
        val full = fullDue || sentRev != rev
        if (full) { sentRev = rev; fullDue = false }
        return full
    }
}
