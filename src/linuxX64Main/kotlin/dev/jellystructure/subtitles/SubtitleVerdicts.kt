package dev.jellystructure.subtitles

import dev.jellystructure.db.Subtitle_check
import kotlin.concurrent.AtomicReference

/**
 * Phase 273 (FR-273-17, dev review item 2) — the one rule for which sidecars a viewer is offered, read by every
 * surface that names a subtitle: the player's list (`PlaybackService.buildSubtracks`), the title's language flags
 * (`DetailService`) and the admin's subtitle strip. Kept as the set of sidecar **file names** not offered: Jellyfin,
 * Bazarr and jellystructure each see the media under a different root, and a sidecar's name carries its video's
 * name, so the name alone identifies it.
 *
 * On *Only report* nothing is hidden (FR-273-23): the first production run changes nothing a viewer sees.
 */
object SubtitleVerdicts {
    private val hidden = AtomicReference<Map<String, Set<String>>>(emptyMap())
    private val hiddenNames = AtomicReference<Set<String>>(emptySet())

    /** Set at startup from the config; read on every call so a change on the Bazarr card applies at once. */
    var reportOnly: () -> Boolean = { false }

    fun isHidden(row: Subtitle_check): Boolean =
        !VerdictRules.offered(Verdict.of(row.verdict) ?: Verdict.CANT_TELL, row.reason, RefKind.of(row.reference?.substringBefore(':')), row.worst_ms)

    fun load(rows: List<Subtitle_check>) {
        val byVideo = rows.filter(::isHidden).groupBy { it.video_path }
            .mapValues { (_, rs) -> rs.map { it.sidecar_path.substringAfterLast('/') }.toSet() }
        hidden.value = byVideo
        hiddenNames.value = byVideo.values.flatten().toSet()
    }

    fun updateVideo(videoPath: String, rows: List<Subtitle_check>) {
        while (true) {
            val before = hidden.value
            val names = rows.filter(::isHidden).map { it.sidecar_path.substringAfterLast('/') }.toSet()
            val after = if (names.isEmpty()) before - videoPath else before + (videoPath to names)
            if (hidden.compareAndSet(before, after)) {
                hiddenNames.value = after.values.flatten().toSet()
                return
            }
        }
    }

    /** Whether a sidecar (by path or bare file name) is offered to viewers. */
    fun isOffered(sidecarPathOrName: String?): Boolean {
        if (sidecarPathOrName == null || reportOnly()) return true
        return sidecarPathOrName.substringAfterLast('/') !in hiddenNames.value
    }

    fun hiddenCount(): Int = if (reportOnly()) 0 else hiddenNames.value.size
}
