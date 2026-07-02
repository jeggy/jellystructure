package dev.jellystructure.media

import dev.jellystructure.log.Logger
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
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

private val json = Json { ignoreUnknownKeys = true }

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
