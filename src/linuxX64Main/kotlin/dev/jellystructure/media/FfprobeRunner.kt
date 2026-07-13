package dev.jellystructure.media

import dev.jellystructure.log.Logger
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen

@Serializable
private data class FfprobeOutput(
    val streams: List<FfprobeStream> = emptyList(),
)

@Serializable
private data class FfprobeStream(
    val index: Int,
    @SerialName("codec_name") val codecName: String = "unknown",
    @SerialName("codec_type") val codecType: String = "unknown",
    val disposition: FfprobeDisposition = FfprobeDisposition(),
    val tags: FfprobeTags = FfprobeTags(),
)

@Serializable
private data class FfprobeDisposition(
    val default: Int = 0,
    val forced: Int = 0,
)

@Serializable
private data class FfprobeTags(
    val language: String? = null,
    val title: String? = null,
)

/** Phase 149: one chapter marker's start/end offset within a file, in whole milliseconds. [title] is
 *  the chapter's own free-text name when the container has one (Phase 150: some rips literally name a
 *  chapter "Credits"/"Recap" — previously discarded here, now the input to chapter-title pattern
 *  matching, see `SegmentDetection.kt`). */
@Serializable
data class ChapterMarker(val startMs: Long, val endMs: Long, val title: String? = null)

@Serializable
private data class FfprobeChaptersOutput(
    val chapters: List<FfprobeChapterEntry> = emptyList(),
)

@Serializable
private data class FfprobeChapterEntry(
    @SerialName("start_time") val startTime: String = "0",
    @SerialName("end_time") val endTime: String = "0",
    val tags: FfprobeChapterTags = FfprobeChapterTags(),
)

@Serializable
private data class FfprobeChapterTags(
    val title: String? = null,
)

private val json = Json { ignoreUnknownKeys = true }

/** Phase 128: why a file's track probe came back the way it did — surfaced to the operator instead of
 *  silently collapsing every failure mode into an empty track list (which used to read as "no audio",
 *  masking a corrupt/unreadable file or a missing ffprobe binary). */
enum class ProbeStatus { OK, NO_AUDIO, CORRUPT, UNREADABLE, PROBE_MISSING, UNKNOWN }

/** [managed] defaults false — `FfprobeRunner` has no Sonarr/Radarr knowledge; the route handler that
 *  knows the item's `*arr` status fills it in via `.copy(managed = ...)`. */
@Serializable
data class ProbeDiagnosis(
    val status: ProbeStatus,
    val detail: String,
    val streamCounts: Map<TrackKind, Int> = emptyMap(),
    val managed: Boolean = false,
)

object FfprobeRunner {
    suspend fun probe(filePath: String): List<Track> {
        val escaped = filePath.replace("'", "'\\''")
        val command = "ffprobe -v quiet -print_format json -show_streams '$escaped' 2>/dev/null"
        val output = runCommand(command) ?: run {
            Logger.warn("ffprobe returned no output for: $filePath")
            return emptyList()
        }
        val result = runCatching {
            val probe = json.decodeFromString(FfprobeOutput.serializer(), output)
            val typeCounters = mutableMapOf<String, Int>()
            probe.streams.map { stream ->
                val typeChar = when (stream.codecType) {
                    "audio" -> "a"
                    "subtitle" -> "s"
                    "video" -> "v"
                    else -> "d"
                }
                val typeIndex = typeCounters[stream.codecType] ?: 0
                typeCounters[stream.codecType] = typeIndex + 1
                val kind = when (stream.codecType) {
                    "audio" -> TrackKind.AUDIO
                    "subtitle" -> TrackKind.SUBTITLE
                    "video" -> TrackKind.VIDEO
                    else -> TrackKind.DATA
                }
                // Treat "und" (undetermined) and blank as untagged
                val lang = stream.tags.language
                    ?.takeIf { it.isNotBlank() && it != "und" }
                Track(
                    streamIndex = stream.index,
                    specifier = "0:$typeChar:$typeIndex",
                    kind = kind,
                    codec = stream.codecName,
                    language = lang,
                    title = stream.tags.title?.takeIf { it.isNotBlank() },
                    default = stream.disposition.default != 0,
                    forced = stream.disposition.forced != 0,
                )
            }
        }
        if (result.isFailure) Logger.warn("Failed to parse ffprobe output for $filePath: ${result.exceptionOrNull()?.message}")
        return result.getOrElse { emptyList() }
    }

    /**
     * Phase 128 — a diagnostic probe that keeps ffprobe's stderr (`2>&1`, unlike [probe]'s `2>/dev/null`
     * on the scan hot path) so a corrupt/unreadable file or a missing ffprobe binary is distinguishable
     * from a genuinely audio-less container instead of collapsing into the same empty track list.
     */
    suspend fun diagnose(filePath: String): ProbeDiagnosis {
        if (!SystemFileSystem.exists(Path(filePath))) {
            return ProbeDiagnosis(ProbeStatus.UNREADABLE, "File not found: $filePath")
        }
        val escaped = filePath.replace("'", "'\\''")
        val command = "ffprobe -hide_banner -show_streams -print_format json '$escaped' 2>&1"
        val output = runCommand(command)
            ?: return ProbeDiagnosis(ProbeStatus.UNKNOWN, "ffprobe produced no output")

        // A clean JSON parse (streams present or not) wins over any warning noise ffprobe printed to
        // stderr alongside otherwise-valid output.
        val parsed = runCatching { json.decodeFromString(FfprobeOutput.serializer(), output) }.getOrNull()
        if (parsed != null) {
            val counts = parsed.streams.groupingBy {
                when (it.codecType) {
                    "audio" -> TrackKind.AUDIO
                    "subtitle" -> TrackKind.SUBTITLE
                    "video" -> TrackKind.VIDEO
                    else -> TrackKind.DATA
                }
            }.eachCount()
            val status = if ((counts[TrackKind.AUDIO] ?: 0) > 0) ProbeStatus.OK else ProbeStatus.NO_AUDIO
            return ProbeDiagnosis(status, "Parsed ${parsed.streams.size} stream(s)", counts)
        }

        // Parse failed — classify from the raw (stdout+stderr) text.
        val firstLine = output.lineSequence().firstOrNull { it.isNotBlank() } ?: output
        val status = when {
            output.contains("No such file or directory") || output.contains("Permission denied") -> ProbeStatus.UNREADABLE
            output.contains("moov atom not found") || output.contains("Invalid data found") || output.contains("Invalid NAL") -> ProbeStatus.CORRUPT
            output.contains("ffprobe: not found") || output.contains("command not found") -> ProbeStatus.PROBE_MISSING
            else -> ProbeStatus.UNKNOWN
        }
        return ProbeDiagnosis(status, firstLine)
    }

    /**
     * Phase 149: a multi-episode file's chapter markers (only meaningful when the caller already knows
     * the file contains N>1 episodes — a separate ffprobe invocation from [probe], since chapters are
     * rare and this would otherwise add a process spawn to every single-episode scan). Returns an empty
     * list when the container has no chapters or the read fails — the caller treats that as "no usable
     * chapters," never an error.
     */
    suspend fun chapters(filePath: String): List<ChapterMarker> {
        val escaped = filePath.replace("'", "'\\''")
        val command = "ffprobe -v quiet -print_format json -show_chapters '$escaped' 2>/dev/null"
        val output = runCommand(command) ?: return emptyList()
        val result = runCatching {
            json.decodeFromString(FfprobeChaptersOutput.serializer(), output).chapters.map { ch ->
                val startSec = ch.startTime.toDoubleOrNull() ?: 0.0
                val endSec = ch.endTime.toDoubleOrNull() ?: 0.0
                ChapterMarker(
                    startMs = (startSec * 1000).toLong(),
                    endMs = (endSec * 1000).toLong(),
                    title = ch.tags.title?.takeIf { it.isNotBlank() },
                )
            }
        }
        if (result.isFailure) Logger.warn("Failed to parse ffprobe chapters for $filePath: ${result.exceptionOrNull()?.message}")
        return result.getOrElse { emptyList() }
    }

    /** R131: media duration in seconds, or null if unknown — used to pick a screen-grab timestamp. */
    suspend fun duration(filePath: String): Double? {
        val escaped = filePath.replace("'", "'\\''")
        return runCommand("ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 '$escaped' 2>/dev/null")
            ?.trim()?.toDoubleOrNull()
    }

    // Phase 118 (FR C.3) — shared ProcessGate on top of Screengrabber's own Semaphore(2). No bare
    // `return` inside the gated block — ProcessGate.withPermit's lambda isn't inline, so a non-local
    // return isn't allowed there; a nullable pipe + if/else avoids it.
    @OptIn(ExperimentalForeignApi::class)
    private suspend fun runCommand(command: String): String? = dev.jellystructure.ops.ProcessGate.withPermit {
        memScoped {
            val pipe = popen(command, "r")
            if (pipe == null) {
                null
            } else {
                val result = StringBuilder()
                val bufSize = 8192
                val buffer = allocArray<ByteVar>(bufSize)
                try {
                    while (fgets(buffer, bufSize, pipe) != null) {
                        result.append(buffer.toKString())
                    }
                } finally {
                    pclose(pipe)
                }
                result.toString().takeIf { it.isNotBlank() }
            }
        }
    }
}
