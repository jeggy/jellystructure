package dev.jellystructure.shared.tv

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

    /** The first [count] segments of a media playlist, absolute. Empty for a master or an empty playlist. */
    fun firstSegments(media: String, mediaUrl: String, count: Int): List<String> {
        val lines = media.lines().map { it.trim() }
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) return emptyList()
        val out = ArrayList<String>()
        var pending = false
        for (line in lines) {
            when {
                line.startsWith("#EXTINF") -> pending = true
                line.isEmpty() || line.startsWith("#") -> {}
                pending -> { out += resolve(mediaUrl, line); pending = false; if (out.size >= count) break }
            }
        }
        return out
    }

    /** [uri] against the URL it was listed in: absolute stays, `/path` takes the origin, anything else the directory. */
    fun resolve(baseUrl: String, uri: String): String = when {
        uri.startsWith("http://") || uri.startsWith("https://") -> uri
        uri.startsWith("/") -> baseUrl.substringBefore("://") + "://" + baseUrl.substringAfter("://").substringBefore("/") + uri
        else -> baseUrl.substringBefore("?").substringBeforeLast("/") + "/" + uri
    }
}
