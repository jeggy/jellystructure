package dev.jellystructure.music

import dev.jellystructure.media.FileIntegrityService
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * Phase 288 — does this FLAC have a seek table?
 *
 * A FLAC's header is a run of metadata blocks, each a four-byte header (last-block flag, type, length) and its body.
 * A `SEEKTABLE` (type 3, 18 bytes a point) is the file's index. Without one a player finds a time by reading the file
 * from its start; a Chromecast asked to begin at 2:40 through the server's public address read a little, gave up and
 * reported the end of the song (bedroom TV, 2026-09-30). Only the headers are read here — a cover picture is skipped.
 */
object FlacIndex {
    private const val SEEKTABLE = 3
    private const val POINT_BYTES = 18
    private const val MAX_BLOCKS = 64   // a sane file has a handful; a broken header must not walk the whole file

    /** True / false for a FLAC that could be read; null for anything else (not a FLAC, not there, not readable). */
    fun hasSeekTable(source: Source): Boolean? {
        if (!source.request(4) || source.readByteArray(4).decodeToString() != "fLaC") return null
        repeat(MAX_BLOCKS) {
            if (!source.request(4)) return false
            val h = source.readByteArray(4)
            val last = h[0].toInt() and 0x80 != 0
            val type = h[0].toInt() and 0x7f
            val length = ((h[1].toInt() and 0xff) shl 16) or ((h[2].toInt() and 0xff) shl 8) or (h[3].toInt() and 0xff)
            if (type == SEEKTABLE && length >= POINT_BYTES) return true
            if (last) return false
            if (!source.request(length.toLong())) return false
            source.skip(length.toLong())
        }
        return false
    }

    private class Known(val size: Long, val mtime: Long, val has: Boolean?)
    private val known = HashMap<String, Known>()

    /** [hasSeekTable] for a path, remembered while the file's size and modification time stay what they were. */
    fun hasSeekTable(path: String?): Boolean? {
        val p = path?.takeIf { it.isNotBlank() } ?: return null
        val stamp = FileIntegrityService.stampOf(p) ?: return null
        known[p]?.takeIf { it.size == stamp.size && it.mtime == stamp.mtime }?.let { return it.has }
        val has = runCatching { SystemFileSystem.source(Path(p)).buffered().use { hasSeekTable(it) } }.getOrNull()
        if (known.size > 4_000) known.clear()
        known[p] = Known(stamp.size, stamp.mtime, has)
        return has
    }

    /**
     * FR-288-3 — the capabilities a song is negotiated with. A player that cannot seek without an index
     * (`seek_needs_index`) is, for a FLAC with no seek table, a player that does not play FLAC: Jellyfin then answers
     * with the HLS / AAC stream, which seeks by segment. Anything unknown (FR-288-4) leaves the capabilities alone.
     */
    fun capabilitiesFor(capabilities: dev.jellystructure.shared.tv.ClientCapabilities, container: String?, codec: String?, path: String?): dev.jellystructure.shared.tv.ClientCapabilities {
        if (!capabilities.seekNeedsIndex) return capabilities
        val flac = container.equals("flac", ignoreCase = true) || codec.equals("flac", ignoreCase = true)
        if (!flac || hasSeekTable(path) != false) return capabilities
        return capabilities.copy(
            // An empty list means "the server's defaults", which include FLAC: say what is left, or MP3 if nothing is.
            containers = capabilities.containers.filterNot { it.equals("flac", true) }.ifEmpty { listOf("mp3") },
            audioCodecs = capabilities.audioCodecs.filterNot { it.equals("flac", true) }.ifEmpty { listOf("mp3") },
        )
    }
}
