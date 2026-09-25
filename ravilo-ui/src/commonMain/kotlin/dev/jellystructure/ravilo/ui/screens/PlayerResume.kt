package dev.jellystructure.ravilo.ui.screens

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * R292 (FR-R292-2/5/6) — what survives a background, and an activity recreation: a small, explicit
 * record captured from the LIVE engine before it is released, never the engine, a `MediaItem`, a
 * `Surface`, a ticket or a URL. The item and the episode context the binge needs, the position (the same
 * value the session stop reports, so the two can never disagree), the play intent, and the viewer's own
 * track choices (audio, subtitle, the burn-in state, a single-audio session's server-side audio, the
 * handset subtitle size) — re-applied on the new engine, not re-resolved (FR-R292-5).
 *
 * Saved state (FR-R292-6, dev review item 4) is this record as ONE string through `rememberSaveable`:
 * a String needs no platform `Saver`, survives a low-memory destroy, a configuration recreation and a
 * recents restore alike, and nothing about the other destinations changes. On restore the stack is
 * rebuilt as `[Home, Player]` from it. Nothing of this lives in `PlayerScreen`'s body (item 5).
 */
@Serializable
data class ResumeRecord(
    val itemId: String,
    val positionMs: Long,
    val playIntent: Boolean,
    /** Epoch ms of the background — the 30-minute rule (open question 2) and the restore age cap read it. */
    val awayAtMs: Long,
    val audioIndex: Int = 0,
    val subIndex: Int = -1,
    val burnedSubIndex: Int? = null,
    val sessionAudioIndex: Int? = null,
    val subtitleScale: Float = 1f,
    // The Dest.Player fields a restore needs; the episode rail is not saved (a low-mem destroy loses it).
    val title: String = "",
    val kicker: String? = null,
    val seriesId: String? = null,
    val originalLanguage: String? = null,
    val posterUrl: String? = null,
    val logoUrl: String? = null,
    val logoInk: String? = null,
    val seriesName: String? = null,
    val nextEpId: String? = null,
    val nextEpLabel: String? = null,
    val nextEpTitle: String? = null,
    val displayName: String = "",
)

/** Open question 2 (owner, 2026-09-24): under 30 minutes away ⇒ playing; longer ⇒ paused at the position. */
fun resumePlayIntent(record: ResumeRecord, nowMs: Long): Boolean =
    record.playIntent && nowMs - record.awayAtMs < RESUME_PLAY_WINDOW_MS

/** FR-R292-6 — a saved record older than this is not restored into the player (the viewer moved on). */
fun resumeRestorable(record: ResumeRecord, nowMs: Long): Boolean = nowMs - record.awayAtMs < RESUME_RESTORE_MAX_MS

const val RESUME_PLAY_WINDOW_MS = 30 * 60_000L
const val RESUME_RESTORE_MAX_MS = 12 * 60 * 60_000L

/**
 * The holder: one record at a time, mirrored into saved state through [persist] on every change.
 * [restoredFromSavedState] is true exactly once, when the app composed with a saved record (a recreation
 * or a recents restore) — the player consumes it to count FR-R292-6's restore and to start from the record.
 */
class PlayerResumeStore(initialJson: String, private val persist: (String) -> Unit) {
    var record: ResumeRecord? = decode(initialJson)
        private set
    private var restoredPending = record != null

    fun capture(r: ResumeRecord) { record = r; persist(json.encodeToString(ResumeRecord.serializer(), r)) }
    fun clear() { record = null; restoredPending = false; persist("") }
    fun recordFor(itemId: String): ResumeRecord? = record?.takeIf { it.itemId == itemId }
    /** True once, for the first player that starts after a restore. */
    fun consumeRestored(): Boolean = restoredPending.also { restoredPending = false }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
        fun decode(s: String): ResumeRecord? = s.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(ResumeRecord.serializer(), it) }.getOrNull() }
    }
}
