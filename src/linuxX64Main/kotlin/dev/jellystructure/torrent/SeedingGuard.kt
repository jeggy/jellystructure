package dev.jellystructure.torrent

import dev.jellystructure.config.AppConfig

sealed class SeedingCheckResult {
    object Unconfigured : SeedingCheckResult()
    object Allowed : SeedingCheckResult()
    /**
     * The file may not be changed in place. [torrentName] names the seeding torrent the path belongs to; for a hard link
     * found by phase 315 it is blank and [hardLink] is true (the torrent's copy shares the bytes under another name).
     */
    data class Blocked(val torrentName: String, val hardLink: Boolean = false) : SeedingCheckResult() {
        /** FR-315-3 — the one sentence every caller shows. */
        val message: String get() = if (hardLink) HARD_LINK_REFUSAL else "File is seeded by '$torrentName'"
    }
    data class Unreachable(val reason: String) : SeedingCheckResult()
}

/**
 * The one answer to "may this file be changed in place?" (phase 315 FR-315-1/-2). Every in-place writer asks it before
 * writing; `scripts/check-inplace-guard.sh` fails CI on one that doesn't.
 */
class SeedingGuard(private val snapshot: SeedingSnapshot) {
    /**
     * [inPlace] — true for a writer that changes the file's own bytes (`mkvpropedit`): a hard link to a torrent's copy
     * then blocks it. A writer that writes a new file and renames it over the library name (a remux, the music and
     * audiobook tag writers, Convert…) passes false: the rename gives the library a new inode and leaves the torrent's
     * bytes alone, so only the path check applies (phase 315's open question 2, the lean).
     */
    // [countRefusal] — a refused edit is counted for the Dashboard (FR-315-3); a preview that only asks passes false.
    suspend fun check(localFilePath: String, config: AppConfig, inPlace: Boolean = true, countRefusal: Boolean = inPlace): SeedingCheckResult =
        snapshot.checkPath(localFilePath, config, linkCheck = inPlace).also { if (countRefusal && it is SeedingCheckResult.Blocked) SeedingRefusals.record(localFilePath) }
}
