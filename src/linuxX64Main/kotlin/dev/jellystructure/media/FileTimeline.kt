package dev.jellystructure.media

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.nowEpochSec
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * DEB-3637 (DEB-3633 FR-5) — the process-wide handle Triage, the Library filter and the Dashboard read, the
 * role [FileDamage] plays for phase 254. [revision] bumps on every stored result so a count cached against the
 * library version alone cannot outlive a new finding.
 */
object TimelineHolesFlags {
    var service: FileTimeline? = null
    private val rev = kotlin.concurrent.AtomicInt(0)
    val revision: Int get() = rev.value
    internal fun bump() { rev.incrementAndGet() }

    const val TYPE_TIMELINE_HOLES = "timeline_holes"
    const val LABEL = "Picture and sound skip ahead"
    const val DESCRIPTION =
        "These files are missing stretches of picture and sound. Players jump over them, so viewers lose those seconds of the episode. Nothing in the file can put them back."

    /** Current paths with holes, or `null` while no sweep has stored anything (unknown, never "none"). */
    fun holePathsOrNull(): Set<String>? = service?.holePathsOrNull()
}

/** Three states, never two. `UNCHECKED` is never rendered or counted as `WHOLE`. */
enum class TimelineState { HOLES, WHOLE, UNCHECKED }

data class TimelineResult(val state: TimelineState, val report: TimelineReport? = null, val checkedAt: Long? = null)

/** One file's block on the title page (FR-6): the state and every figure the words need, decided here. */
@Serializable
data class TimelineFileDto(
    val path: String,
    val state: String,
    val season: Int? = null,
    val episodeTag: String? = null,
    val checkedAt: Long? = null,
    val holeCount: Int = 0,
    val missingMs: Long = 0,
    val longestMs: Long = 0,
    val worstTrackMissingMs: Long? = null,
    val holes: List<TimelineHole> = emptyList(),
    /** `m:ss (n.n s)`, one per entry of [holes]. */
    val holeLines: List<String> = emptyList(),
    val what: String = "",
    val experience: String = "",
    val suggestion: String = "",
)

/** *S01: 5 episodes, 2 to 9 minutes missing each* — one season's line; [season] null = a film or unnumbered. */
@Serializable
data class TimelineSeasonDto(val season: Int? = null, val line: String)

@Serializable
data class TimelineStatusDto(val total: Int, val whole: Int, val unchecked: Int, val files: List<TimelineFileDto>, val seasons: List<TimelineSeasonDto> = emptyList())

/**
 * DEB-3637 — the stored packet timelines (`file_timeline`). It is written by [FileIntegrityService.check] from
 * the same single read as `file_integrity`, and read by everything that says what it found.
 */
class FileTimeline(private val db: JellystructureDb) {
    private val q get() = db.fileTimelineQueries
    private val json = Json { ignoreUnknownKeys = true }
    private val holesSer = ListSerializer(TimelineHole.serializer())
    private val streamsSer = ListSerializer(Int.serializer())

    fun rowsByPath(): Map<String, dev.jellystructure.db.File_timeline> = q.all().executeAsList().associateBy { it.path }

    fun rowFor(path: String): dev.jellystructure.db.File_timeline? = q.byPath(path).executeAsOneOrNull()

    /** FR-3 — a pass that did not finish never reaches here, so it stores nothing. */
    fun store(path: String, size: Long, mtime: Long, report: TimelineReport) {
        q.put(
            path, size, mtime, nowEpochSec(), report.holeCount.toLong(), report.missingMs, report.longestMs, report.worstTrackMissingMs,
            json.encodeToString(holesSer, report.holes), json.encodeToString(streamsSer, report.emptyStreams),
        )
        TimelineHolesFlags.bump()
    }

    fun remove(path: String) {
        q.remove(path)
        TimelineHolesFlags.bump()
    }

    fun reportOf(row: dev.jellystructure.db.File_timeline): TimelineReport = TimelineReport(
        holeCount = row.hole_count.toInt(), missingMs = row.missing_ms, longestMs = row.longest_ms, worstTrackMissingMs = row.worst_track_missing_ms,
        holes = runCatching { json.decodeFromString(holesSer, row.holes) }.getOrDefault(emptyList()),
        emptyStreams = runCatching { json.decodeFromString(streamsSer, row.empty_streams) }.getOrDefault(emptyList()),
    )

    /** FR-3 — current only while size and mtime both still match; anything else is unchecked. */
    fun resultFor(path: String, rows: Map<String, dev.jellystructure.db.File_timeline> = rowsByPath()): TimelineResult {
        val row = rows[path] ?: return TimelineResult(TimelineState.UNCHECKED)
        val stamp = FileIntegrityService.stampOf(path) ?: return TimelineResult(TimelineState.UNCHECKED)
        if (!FileIntegrityService.isCurrent(row.size, row.mtime, stamp)) return TimelineResult(TimelineState.UNCHECKED)
        val report = reportOf(row)
        return TimelineResult(if (row.hole_count > 0) TimelineState.HOLES else TimelineState.WHOLE, report, row.checked_at)
    }

    /** A row for [path] exists and still describes the file — FR-4's "has a current row". */
    fun hasCurrent(path: String, stamp: FileStamp, rows: Map<String, dev.jellystructure.db.File_timeline>): Boolean =
        rows[path]?.let { FileIntegrityService.isCurrent(it.size, it.mtime, stamp) } == true

    /** Paths whose stored row has holes and still describes the file: one query plus one `stat` per such row. */
    fun holePaths(): Set<String> = q.withHoles().executeAsList()
        .filter { row -> FileIntegrityService.stampOf(row.path)?.let { FileIntegrityService.isCurrent(row.size, row.mtime, it) } == true }
        .mapTo(mutableSetOf()) { it.path }

    fun holePathsOrNull(): Set<String>? = if (q.rowCount().executeAsOne() == 0L) null else holePaths()

    /** FR-4 — `item`'s files by the last time a device played them (epoch s), for the backfill's order. */
    fun lastPlayedByJellyfinId(sinceEpochSec: Long): Map<String, Long> =
        q.lastPlayedSince(sinceEpochSec).executeAsList().associate { it.jellyfin_id to (it.played_at ?: 0L) }

    // ── saying (FR-6) ───────────────────────────────────────────────────────────────────────────

    fun statusFor(item: MediaItem, paths: List<String>): TimelineStatusDto {
        val rows = rowsByPath()
        val results = paths.associateWith { resultFor(it, rows) }
        val withHoles = holePaths()
        val files = results.filterValues { it.state == TimelineState.HOLES }.map { (path, r) ->
            val report = r.report!!
            val episode = item.episodes.firstOrNull { it.path == path }
            val others = withHoles.any { it != path && TimelineHolesAdvice.sameRelease(path, it) }
            val advice = TimelineHolesAdvice.advise(report, others)
            TimelineFileDto(
                path = path, state = "holes", season = if (item.kind == MediaKind.TV_SHOW) episode?.seasonNumber else null,
                episodeTag = episode?.let { ep -> ep.seasonNumber?.let { s -> "S${s.toString().padStart(2, '0')}" + (ep.episodeNumber?.let { e -> "E${e.toString().padStart(2, '0')}" } ?: "") } },
                checkedAt = r.checkedAt, holeCount = report.holeCount, missingMs = report.missingMs, longestMs = report.longestMs,
                worstTrackMissingMs = report.worstTrackMissingMs, holes = report.holes, holeLines = report.holes.map(TimelineHolesAdvice::holeLine),
                what = advice.what, experience = advice.experience, suggestion = advice.suggestion,
            )
        }
        val seasons = files.groupBy { it.season }.map { (season, fs) ->
            TimelineSeasonDto(season, TimelineHolesAdvice.seasonLine(season?.let { "S${it.toString().padStart(2, '0')}" } ?: "Files", fs.map { it.missingMs }))
        }.sortedBy { it.season ?: Int.MAX_VALUE }
        return TimelineStatusDto(
            total = paths.size,
            whole = results.values.count { it.state == TimelineState.WHOLE },
            unchecked = results.values.count { it.state == TimelineState.UNCHECKED },
            files = files.sortedWith(compareBy({ it.season ?: Int.MAX_VALUE }, { it.episodeTag ?: it.path })),
            seasons = seasons,
        )
    }

    /** The Checks card's one line for a file with holes (FR-6), in the advice's own words. */
    fun summaryOf(row: dev.jellystructure.db.File_timeline): String =
        "${row.hole_count} gap${if (row.hole_count == 1L) "" else "s"}, ${TrackCoverage.mmss(row.missing_ms)} missing"
}
