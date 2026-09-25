package dev.jellystructure.media

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.log.Logger
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.TrackKind
import dev.jellystructure.nowEpochSec
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen

/**
 * Phase 255 (FR-255-7) — the process-wide handle Triage and the Library filter read, the role [FileDamage]
 * plays for phase 254: [revision] bumps on every stored finding so a count cached against the library
 * version alone cannot outlive one.
 */
object TrackCoverageFlags {
    var service: TrackCoverageService? = null
    private val rev = kotlin.concurrent.AtomicInt(0)
    val revision: Int get() = rev.value
    internal fun bump() { rev.incrementAndGet() }

    /** Current findings per path (`null` = no service wired, never an empty map pretending "clean"). */
    fun flaggedOrNull(): Map<String, List<CoverageFinding>>? = service?.flaggedPaths()

    const val TYPE_TRACK_ENDS_EARLY = "track_ends_early"
    const val TYPE_DURATION_HEADER_WRONG = "duration_header_wrong"
    fun endsEarly(findings: List<CoverageFinding>): Boolean = findings.any { it.kind != "E" }
    fun headerWrong(findings: List<CoverageFinding>): Boolean = findings.any { it.kind == "E" }
}

/** Phase 255 (FR-255-4) — three states, never two. `UNCHECKED` is never rendered or counted as `CLEAN`. */
enum class TrackCoverageState { FINDINGS, CLEAN, UNCHECKED }

data class CoverageResult(val state: TrackCoverageState, val findings: List<CoverageFinding> = emptyList(), val contentEndMs: Long? = null, val headerMs: Long? = null, val checkedAt: Long? = null)

@Serializable
data class TrackCoverageFindingDto(
    val kind: String, val streamIndex: Int?, val trackKind: String?, val language: String?, val codec: String?,
    val endsMs: Long, val referenceMs: Long, val headerMs: Long?,
    val what: String, val experience: String, val who: String, val suggestion: String, val command: String?,
)

@Serializable
data class TrackCoverageFileDto(
    val path: String,
    val state: String,
    val contentEndMs: Long? = null,
    val headerMs: Long? = null,
    val checkedAt: Long? = null,
    /** Which season the file belongs to (TV), for the page's per-season grouping. */
    val season: Int? = null,
    val findings: List<TrackCoverageFindingDto> = emptyList(),
)

@Serializable
data class TrackCoverageStatusDto(val total: Int, val clean: Int, val unchecked: Int, val files: List<TrackCoverageFileDto>)

@Serializable
private data class CovProbe(val streams: List<CovStream> = emptyList(), val format: CovFormat = CovFormat())

@Serializable
private data class CovStream(
    val index: Int,
    @SerialName("codec_type") val codecType: String = "",
    @SerialName("codec_name") val codecName: String = "unknown",
    val disposition: Map<String, Int> = emptyMap(),
    val tags: Map<String, String> = emptyMap(),
    val duration: String? = null,
    @SerialName("channel_layout") val channelLayout: String? = null,
)

@Serializable
private data class CovFormat(val duration: String? = null)

/**
 * Phase 255 — a track that stops before the file does: found (a bounded tail probe per file, persisted),
 * said (Triage counts, the title page's warning, the track row) and explained (the advice). Nothing here
 * writes a media file. See `specs/requirements/phase-255-a-track-that-stops-before-the-file-does.md`.
 */
class TrackCoverageService(private val db: JellystructureDb, private val integrity: FileIntegrityService) {
    private val q get() = db.fileTrackCoverageQueries
    private val json = Json { ignoreUnknownKeys = true }
    private val findingsSer = ListSerializer(CoverageFinding.serializer())

    // ── finding ─────────────────────────────────────────────────────────────────────────────────

    fun rowsByPath(): Map<String, dev.jellystructure.db.File_track_coverage> = q.all().executeAsList().associateBy { it.path }

    fun resultFor(path: String, rows: Map<String, dev.jellystructure.db.File_track_coverage> = rowsByPath()): CoverageResult {
        val row = rows[path] ?: return CoverageResult(TrackCoverageState.UNCHECKED)
        val stamp = FileIntegrityService.stampOf(path) ?: return CoverageResult(TrackCoverageState.UNCHECKED)
        if (!FileIntegrityService.isCurrent(row.size, row.mtime, stamp)) return CoverageResult(TrackCoverageState.UNCHECKED)
        val findings = runCatching { json.decodeFromString(findingsSer, row.findings) }.getOrDefault(emptyList())
        return CoverageResult(if (findings.isEmpty()) TrackCoverageState.CLEAN else TrackCoverageState.FINDINGS, findings, row.content_end_ms, row.header_ms, row.checked_at)
    }

    /** FR-255-7 — currently-flagged paths with their findings: a stored row that still describes the file. */
    fun flaggedPaths(): Map<String, List<CoverageFinding>> = q.flagged().executeAsList()
        .filter { row -> FileIntegrityService.stampOf(row.path)?.let { FileIntegrityService.isCurrent(row.size, row.mtime, it) } == true }
        .associate { row -> row.path to runCatching { json.decodeFromString(findingsSer, row.findings) }.getOrDefault(emptyList()) }
        .filterValues { it.isNotEmpty() }

    /** FR-255-5 — the sweep's worklist: unchecked files, most recently modified first. */
    fun uncheckedMostRecentFirst(items: List<MediaItem>): List<String> {
        val rows = rowsByPath()
        return integrity.videoPaths(items).mapNotNull { path ->
            val stamp = FileIntegrityService.stampOf(path) ?: return@mapNotNull null
            val row = rows[path]
            if (row != null && FileIntegrityService.isCurrent(row.size, row.mtime, stamp)) null else path to stamp.mtime
        }.sortedByDescending { it.second }.map { it.first }
    }

    /** FR-255-3 — one coverage check, persisted. Null when the file cannot be stat-ed or probed. */
    suspend fun check(path: String): CoverageResult? {
        val before = FileIntegrityService.stampOf(path) ?: return null
        val probe = probeStreams(path) ?: return null
        val headerSec = probe.format.duration?.toDoubleOrNull()?.takeIf { it > 0 }
        val real = probe.streams.filter { it.codecType == "audio" || it.codecType == "video" }
            .map { s ->
                CoverageStream(
                    index = s.index, kind = if (s.codecType == "video") TrackKind.VIDEO else TrackKind.AUDIO, codec = s.codecName,
                    language = s.tags.entries.firstOrNull { it.key.equals("language", true) }?.value?.takeIf { it.isNotBlank() && it != "und" },
                    title = s.tags.entries.firstOrNull { it.key.equals("title", true) }?.value?.takeIf { it.isNotBlank() },
                    default = (s.disposition["default"] ?: 0) != 0,
                    attachedPic = (s.disposition["attached_pic"] ?: 0) != 0,
                    hintMs = TrackCoverage.parseDurationHint(s.tags.entries.firstOrNull { it.key.equals("DURATION", true) || it.key.startsWith("DURATION-", true) }?.value, s.duration),
                    channels = s.channelLayout,
                )
            }.filterNot { TrackCoverage.isCoverArt(it) }
        if (real.isEmpty() || headerSec == null) return null
        // The tail window: any stream with packets in [D−60, D] reaches the end. `-read_intervals` reads
        // from wherever the seek LANDS, not from the requested time (measured 2026-09-25: a matroska whose
        // cues stop at 700 s answers every seek past it with packets ending at 702 s, while its content runs
        // to 2558 s), so a window's packets count only when they sit at or after the requested start.
        val tailStart = (headerSec - 60.0).coerceAtLeast(0.0)
        val ends = HashMap<Int, Double>(windowEnds(path, null, tailStart, 60.0).filterValues { it >= tailStart })
        // A stream absent from the tail: the tag's own end first, accepted only when the packets really stop
        // inside that window (a landing before it, or a stream that runs on past it, both say nothing).
        for (s in real) if (s.index !in ends) {
            val hs = s.hintMs?.let { it / 1000.0 } ?: continue
            val start = (hs - 10.0).coerceAtLeast(0.0)
            windowEnds(path, s.index, start, 20.0)[s.index]?.takeIf { it >= start && it < start + 19.0 }?.let { ends[s.index] = it }
        }
        // Everything still unresolved gets the one honest answer when seeking cannot reach the end — a
        // cue-less matroska, an AVI, a header shorter than the content, or a track that truly stops early:
        // a single sequential read of the file, reduced to one last timestamp per stream.
        if (real.any { it.index !in ends }) sequentialEnds(path)?.forEach { (i, e) -> if (i !in ends) ends[i] = e }
        val measured = real.map { it.copy(measuredEndMs = ends[it.index]?.let { e -> (e * 1000).toLong() }) }
        val findings = TrackCoverage.classify((headerSec * 1000).toLong(), measured)
        val after = FileIntegrityService.stampOf(path) ?: return null
        if (after.size != before.size || after.mtime != before.mtime) return CoverageResult(TrackCoverageState.UNCHECKED)
        val contentEndMs = measured.mapNotNull { it.measuredEndMs }.maxOrNull()
        q.put(path, after.size, after.mtime, nowEpochSec(), contentEndMs, (headerSec * 1000).toLong(), json.encodeToString(findingsSer, findings))
        if (findings.isNotEmpty()) {
            TrackCoverageFlags.bump()
            Logger.warn("track coverage: ${findings.joinToString { it.kind + (it.streamIndex?.let { i -> "#$i@${TrackCoverage.mmss(it.endsMs)}" } ?: "@${TrackCoverage.mmss(it.endsMs)}") }} in $path", "integrity")
        }
        return CoverageResult(if (findings.isEmpty()) TrackCoverageState.CLEAN else TrackCoverageState.FINDINGS, findings, contentEndMs, (headerSec * 1000).toLong(), nowEpochSec())
    }

    /** The last packet time per stream inside `[startSec, startSec+lenSec]`; [streamIndex] narrows to one. */
    private suspend fun windowEnds(path: String, streamIndex: Int?, startSec: Double, lenSec: Double): Map<Int, Double> {
        val sel = streamIndex?.let { "-select_streams $it " } ?: ""
        val at = fmt(startSec)
        val cmd = "nice -n 19 ffprobe -v error $sel-show_entries packet=stream_index,pts_time -read_intervals '$at%+${lenSec.toInt()}' -of csv=p=0 '${FileIntegrity.esc(path)}' 2>/dev/null"
        val out = dev.jellystructure.ops.SegmentProcessGate.withPermit { captureShell(cmd) } ?: return emptyMap()
        val ends = HashMap<Int, Double>()
        for (line in out.lineSequence()) {
            val parts = line.trim().split(',')
            if (parts.size < 2) continue
            val idx = parts[0].toIntOrNull() ?: continue
            val pts = parts[1].toDoubleOrNull() ?: continue
            if ((ends[idx] ?: -1.0) < pts) ends[idx] = pts
        }
        return ends
    }

    /** The last packet time per stream over the whole file: `ffprobe` streaming every packet through `awk`
     *  inside the shell, so the process reads the file once and jellystructure reads a dozen lines. */
    private suspend fun sequentialEnds(path: String): Map<Int, Double>? {
        val cmd = "nice -n 19 ffprobe -v error -show_entries packet=stream_index,pts_time -of csv=p=0 '${FileIntegrity.esc(path)}' 2>/dev/null" +
            " | awk -F, '\$2!=\"N/A\" && \$2+0>m[\$1]+0 {m[\$1]=\$2} END {for (k in m) print k\",\"m[k]}'"
        val out = dev.jellystructure.ops.SegmentProcessGate.withPermit { captureShell(cmd) } ?: return null
        val ends = HashMap<Int, Double>()
        for (line in out.lineSequence()) {
            val parts = line.trim().split(',')
            if (parts.size < 2) continue
            val idx = parts[0].toIntOrNull() ?: continue
            val pts = parts[1].toDoubleOrNull() ?: continue
            ends[idx] = pts
        }
        return ends.takeIf { it.isNotEmpty() }
    }

    private suspend fun probeStreams(path: String): CovProbe? {
        val cmd = "nice -n 19 ffprobe -v quiet -show_streams -show_format -of json '${FileIntegrity.esc(path)}' 2>/dev/null"
        val out = dev.jellystructure.ops.SegmentProcessGate.withPermit { captureShell(cmd) } ?: return null
        return runCatching { json.decodeFromString(CovProbe.serializer(), out) }.getOrNull()?.takeIf { it.streams.isNotEmpty() }
    }

    private fun fmt(sec: Double): String { val ms = (sec * 1000).toLong(); return "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}" }

    // ── saying ──────────────────────────────────────────────────────────────────────────────────

    fun statusFor(item: MediaItem): TrackCoverageStatusDto {
        val paths = integrity.videoPaths(listOf(item))
        val rows = rowsByPath()
        val results = paths.associateWith { resultFor(it, rows) }
        val files = results.filterValues { it.state != TrackCoverageState.CLEAN }.map { (path, r) ->
            val episode = item.episodes.firstOrNull { it.path == path }
            val runtime = if (item.kind == MediaKind.TV_SHOW) episode?.runtime else item.runtime
            TrackCoverageFileDto(
                path = path, state = r.state.name.lowercase(), contentEndMs = r.contentEndMs, headerMs = r.headerMs, checkedAt = r.checkedAt,
                season = episode?.seasonNumber,
                findings = r.findings.map { f ->
                    val a = TrackCoverageAdvice.advise(f, path, runtime)
                    TrackCoverageFindingDto(f.kind, f.streamIndex, f.trackKind, f.language, f.codec, f.endsMs, f.referenceMs, f.headerMs, a.what, a.experience, a.who, a.suggestion, a.command)
                },
            )
        }
        return TrackCoverageStatusDto(
            total = paths.size,
            clean = results.values.count { it.state == TrackCoverageState.CLEAN },
            unchecked = results.values.count { it.state == TrackCoverageState.UNCHECKED },
            files = files,
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun captureShell(cmd: String): String? = memScoped {
    val pipe = popen(cmd, "r") ?: return@memScoped null
    val sb = StringBuilder()
    val buf = allocArray<ByteVar>(4096)
    while (fgets(buf, 4096, pipe) != null) sb.append(buf.toKString())
    pclose(pipe)
    sb.toString()
}
