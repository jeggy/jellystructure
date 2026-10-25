package dev.jellystructure.shared.tv

/** DEB-2456 (FR-R330-7a) — a load's first position report goes once its time is this far past where the load started. */
const val CAST_POSITION_FIRST_AFTER_MS = 1_000L

/** DEB-2456 (FR-R330-7a) — while a song plays, a position report goes this often. */
const val CAST_POSITION_EVERY_MS = 15_000L

/**
 * DEB-2456 (DEB-2252, FR-R330-7a) — whether the receiver sends its (b) status now: the first `TIME_UPDATE` of a load whose
 * time ([timeMs]) is at least [CAST_POSITION_FIRST_AFTER_MS] past where the load started ([startMs]). [sentKey] is the
 * load last reported for, [songKey] this load's (the song's id plus the load). Music only, once per load.
 *
 * CAF's media channel can go quiet after the receiver's own load of the next song; this status, on our own channel, is
 * what tells the Mac's bar the song has begun.
 */
fun castFirstPositionDue(music: Boolean, startMs: Long, timeMs: Long, sentKey: String?, songKey: String): Boolean =
    music && sentKey != songKey && timeMs - startMs >= CAST_POSITION_FIRST_AFTER_MS

/**
 * DEB-2456 (FR-R330-7a) — whether the receiver sends its (c) status now: a song plays and the last status of any kind went
 * [CAST_POSITION_EVERY_MS] ago or more ([lastSentAtMs], [nowMs] on the same clock).
 */
fun castPeriodicPositionDue(music: Boolean, playing: Boolean, lastSentAtMs: Long, nowMs: Long): Boolean =
    music && playing && nowMs - lastSentAtMs >= CAST_POSITION_EVERY_MS
