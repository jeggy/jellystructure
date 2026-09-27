package dev.jellystructure.shared.tv

/**
 * R309 — an episode id belongs to exactly one FILE, not to exactly one entry.
 *
 * The auto-play-next loop fix (2026-07-26) made "an id occupies exactly one entry" a rule on both the
 * server and the app: two different files claiming one `(season, episode)` had been handed one Jellyfin
 * id, and the rail made two slots of them. Phase 152 (2026-07-30) then matched a multi-episode file's
 * parts 2 and 3 by path, so every part of `S01E01E02E03.mkv` correctly carries the file's one Jellyfin
 * id — and "one id, one entry" silently kept only part 1. Production had 283 episodes in 118 such files
 * and Ravilo showed 118 of them.
 *
 * The guarantee the loop fix needs is one id, one file. Several entries of ONE file share its id on
 * purpose (FR-R309-3: the file is one Jellyfin item, so play, resume and watched act on the file). An
 * entry is dropped only when its id already belongs to a DIFFERENT file earlier in the list. An entry
 * with no file is its own file, so two file-less entries with one id still collapse to the first.
 *
 * Shared so the series payload (`DetailService`) and the app's rail and player list
 * (`SeriesDetailScreen`) cannot apply two different rules.
 */
fun List<Episode>.oneIdPerFile(): List<Episode> {
    val ownerOf = HashMap<String, String>()
    return filterIndexed { index, ep ->
        val fileKey = ep.file.ifBlank { "#entry:$index" }
        ownerOf.getOrPut(ep.id) { fileKey } == fileKey
    }
}

/**
 * R309 (FR-R309-2) — the season's episodes as one group per file, in first-seen order, after
 * [oneIdPerFile]. A multi-episode file (Phase 149) is one group of N; a lone episode is a group of 1.
 * Every id sits in exactly one group, so a group's first id is a unique key across groups.
 */
fun List<Episode>.groupedByFile(): List<List<Episode>> =
    oneIdPerFile()
        .groupBy { if (it.file.isBlank()) "#single:${it.id}" else it.file }
        .values.toList()
