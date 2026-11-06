package dev.jellystructure.shared.tv

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

/**
 * DEB-3103 (FR-6) — what to fetch so a replacement stream's first segments exist before the player is reset to it: the rung the
 * stream starts on out of a master, and the first [count] segments of that rung's media playlist. Pure text work; the fetching is
 * [TvApiClient.warmHlsStart]'s.
 */
object HlsWarm {
    /** One `#EXT-X-STREAM-INF` entry of a master: its BANDWIDTH and the absolute URL of its media playlist. */
    data class Variant(val bandwidth: Long, val url: String)

    fun variants(master: String, masterUrl: String): List<Variant> {
        val lines = master.lines().map { it.trim() }
        val out = ArrayList<Variant>()
        for ((i, line) in lines.withIndex()) {
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            val bw = Regex("""(?:^|[:,])BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            out += Variant(bw, resolve(masterUrl, uri))
        }
        return out
    }

    /** The rung the stream starts on: the one whose BANDWIDTH is [startBps], else the first listed (what the player picks without an estimate). */
    fun startVariant(variants: List<Variant>, startBps: Long?): Variant? =
        variants.firstOrNull { startBps != null && it.bandwidth == startBps } ?: variants.firstOrNull()

    /**
     * DEB-3330 (DEB-3093 FR-6, 2026-10-31) — the rungs a warm-up asks, in order: the start rung, the next one down, then the lowest.
     * A rung is asked only when the one before it failed to fetch its first segment in time; fewer than three when the ladder has
     * fewer (or when the start rung is already the lowest, nothing lower is asked).
     */
    fun fallbackOrder(variants: List<Variant>, startBps: Long?): List<Variant> {
        val start = startVariant(variants, startBps) ?: return emptyList()
        val below = variants.filter { it.bandwidth < start.bandwidth }.sortedByDescending { it.bandwidth }
        return listOfNotNull(start, below.firstOrNull(), below.lastOrNull()).distinctBy { it.url }
    }

    /** What a warm-up fetches from one rung: its init segment (`#EXT-X-MAP`, null where the rung has none) and then [segments], absolute. */
    data class Warm(val init: String?, val segments: List<String>) {
        /** Init first, as the player reads it first. */
        val urls: List<String> get() = listOfNotNull(init) + segments
    }

    /**
     * DEB-3549 (DEB-3093 FR-6, 2026-11-04 note) — the [count] segments a player starting at [positionMs] reads first: the one that holds
     * the position and the ones after it, plus the init segment in effect there. Our encoder's playlists and Jellyfin's list the whole
     * film from segment 0, so the position is found by adding up the `#EXTINF` durations; a position at or past the end gives the last
     * segment. Empty for a master or an empty playlist.
     */
    fun segmentsAt(media: String, mediaUrl: String, positionMs: Long, count: Int): Warm {
        val lines = media.lines().map { it.trim() }
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) return Warm(null, emptyList())
        class Entry(val uri: String, val durationMs: Double, val map: String?)
        val entries = ArrayList<Entry>()
        var map: String? = null
        var durationMs: Double? = null
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MAP") -> map = Regex("""URI="([^"]*)"""").find(line)?.groupValues?.get(1)
                line.startsWith("#EXTINF") -> durationMs = (line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0) * 1000.0
                line.isEmpty() || line.startsWith("#") -> {}
                durationMs != null -> { entries += Entry(line, durationMs, map); durationMs = null }
            }
        }
        if (entries.isEmpty()) return Warm(null, emptyList())
        var start = entries.lastIndex
        var elapsed = 0.0
        for ((i, e) in entries.withIndex()) {
            if (positionMs < elapsed + e.durationMs) { start = i; break }
            elapsed += e.durationMs
        }
        val chosen = entries.subList(start, minOf(entries.size, start + count))
        return Warm(entries[start].map?.let { resolve(mediaUrl, it) }, chosen.map { resolve(mediaUrl, it.uri) })
    }

    /** [uri] against the URL it was listed in: absolute stays, `/path` takes the origin, anything else the directory. */
    fun resolve(baseUrl: String, uri: String): String = when {
        uri.startsWith("http://") || uri.startsWith("https://") -> uri
        uri.startsWith("/") -> baseUrl.substringBefore("://") + "://" + baseUrl.substringAfter("://").substringBefore("/") + uri
        else -> baseUrl.substringBefore("?").substringBeforeLast("/") + "/" + uri
    }

    /** Chunk size [drain] reads in: the only memory a warm-up segment holds, however large the segment is (DEB-3169). */
    const val DRAIN_CHUNK = 16 * 1024

    /**
     * Reads [body] to its end and keeps none of it, returning the byte count. A 2160p segment is 8 MB or more; holding it whole
     * took 9.5 MB from a TV heap that a full ladder buffer had left 6.5 MB of (DEB-3169). [chunk] is reused for every read.
     */
    suspend fun drain(body: ByteReadChannel, chunk: ByteArray = ByteArray(DRAIN_CHUNK)): Long {
        var total = 0L
        while (true) {
            val n = body.readAvailable(chunk, 0, chunk.size)
            if (n < 0) return total
            total += n
        }
    }
}
