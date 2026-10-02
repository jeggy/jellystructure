package dev.jellystructure.subtitles

import dev.jellystructure.db.Subtitle_check
import kotlin.math.abs

/**
 * Phase 302 (FR-302-1/2/3) — a sidecar's verdict as one cause, with the fix Bazarr can make for it. One group per
 * Dashboard row and per list on the Subtitles page; `in_sync` and `cant_tell` belong to none.
 */
enum class FitGroup(val id: String, val fix: Fix, val label: String, val sentence: String, val action: String) {
    SLIGHT("slight", Fix.SYNC, "Subtitles slightly early or late",
        "Under 2 s anywhere in the file; readable. Bazarr’s sync lines them up, and each is checked again after.",
        "Sync them in Bazarr"),
    SPEED("speed", Fix.SYNC, "Subtitles at the wrong speed",
        "Timed for video at another frame rate (25 instead of 23.976, or 24 instead of 23.976), so they drift further off through the file: up to a minute by the end of an episode at 25. Bazarr’s sync fixes the speed; each is checked again after, and one that still doesn’t fit is replaced.",
        "Sync them in Bazarr"),
    SHIFT("shift", Fix.SYNC, "Subtitles early or late by 2 s or more",
        "The whole file is early or late by the same amount. Bazarr’s sync moves it into place; each is checked again after, and one that still doesn’t fit is replaced.",
        "Sync them in Bazarr"),
    CUT("cut", Fix.REPLACE, "Subtitles that fit only part of the video",
        "Timed for another cut: they drift apart partway through, which a sync can’t fix. Bazarr blacklists each one and searches again.",
        "Replace them in Bazarr"),
    LONGER("longer", Fix.REPLACE, "Subtitles made for a longer video",
        "They run on past the end: a double episode, a whole disc, or a longer cut. Bazarr blacklists each one and searches again. When the video itself is much shorter than the episode (see the list), the video is what needs replacing.",
        "Replace them in Bazarr"),
    WRONG("wrong", Fix.REPLACE, "Subtitles made for another video",
        "They match nothing in this video. Bazarr blacklists each one and searches again.",
        "Replace them in Bazarr"),
    EPISODE("episode", Fix.MOVE, "Subtitles filed under the wrong episode",
        "Each matches another episode of the same series. Bazarr gives it to that episode, then searches again for this one.",
        "Move them in Bazarr");

    enum class Fix { SYNC, REPLACE, MOVE }

    companion object {
        fun byId(id: String?): FitGroup? = entries.firstOrNull { it.id == id }

        fun of(r: Subtitle_check): FitGroup? = when (r.verdict) {
            Verdict.OFF.wire -> when {
                (r.worst_ms ?: 0L) < VerdictRules.HIDE_OFF_MS -> SLIGHT
                abs((r.scale ?: 1.0) - 1.0) > 1e-6 -> SPEED
                else -> SHIFT
            }
            Verdict.OFF_MID_FILE.wire -> CUT
            Verdict.LONGER_VIDEO.wire -> LONGER
            Verdict.NOT_THIS_VIDEO.wire -> WRONG
            Verdict.OTHER_EPISODE.wire -> EPISODE
            else -> null
        }

        /** FR-302-2 — what a viewer meets today: critical while any is offered, warning once all are hidden. */
        fun severity(group: FitGroup, rows: List<Subtitle_check>, reportOnly: Boolean): String = when {
            group == SLIGHT -> "info"
            reportOnly || rows.any { !SubtitleVerdicts.isHidden(it) } -> "critical"
            else -> "warning"
        }

        /** FR-302-3 — what every replacing row adds. */
        fun replaceNote(dailyBudget: Int): String =
            "A language with nothing right stays empty: rather nothing than wrong. Downloads count against the daily budget ($dailyBudget); the rest continue the next day."

        fun sentence(group: FitGroup, dailyBudget: Int): String =
            if (group.fix == Fix.SYNC) group.sentence else group.sentence + " " + replaceNote(dailyBudget)
    }
}
