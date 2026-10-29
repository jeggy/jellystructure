package dev.jellystructure.autofetch

/**
 * DEB-2476 (DEB-2474, FR-3.3, FR-6.3) — what the engine reads of a manifest itself: whether it carries a DRM key system,
 * and which HLS variant and renditions are the highest. The download is the image's own `ffmpeg`, handed the variant's
 * URL; this code only chooses it.
 */
data class HlsVariant(val url: String, val bandwidth: Long, val width: Int, val height: Int, val audioGroup: String?, val subtitleGroup: String?)
data class HlsRendition(val type: String, val groupId: String, val uri: String?, val language: String?, val name: String?, val default: Boolean)
data class HlsMaster(val variants: List<HlsVariant>, val renditions: List<HlsRendition>) {
    /** The highest variant by pixels then bandwidth (FR-6.3: highest quality, always). */
    fun best(): HlsVariant? = variants.maxWithOrNull(compareBy({ it.width.toLong() * it.height }, { it.bandwidth }))
    fun audioFor(v: HlsVariant): HlsRendition? = renditions.filter { it.type == "AUDIO" && it.groupId == v.audioGroup && it.uri != null }
        .let { g -> g.firstOrNull { it.default } ?: g.firstOrNull() }
    fun subtitleFor(v: HlsVariant, language: String): HlsRendition? = renditions.filter { it.type == "SUBTITLES" && it.groupId == v.subtitleGroup && it.uri != null }
        .let { g -> g.firstOrNull { it.language.equals(language, true) } ?: if (language.isBlank()) g.firstOrNull() else null }
}

object Manifests {
    private val ATTR = Regex("""([A-Z0-9-]+)=("([^"]*)"|[^,]*)""")

    private fun attrs(line: String): Map<String, String> =
        ATTR.findAll(line.substringAfter(':')).associate { it.groupValues[1] to (if (it.groupValues[3].isNotEmpty() || it.groupValues[2].startsWith("\"")) it.groupValues[3] else it.groupValues[2]) }

    /** An HLS master playlist, or null when [text] is a media playlist or not HLS. URLs are made absolute against [baseUrl]. */
    fun parseHlsMaster(text: String, baseUrl: String): HlsMaster? {
        if (!text.trimStart().startsWith("#EXTM3U")) return null
        val lines = text.lines().map { it.trim() }
        val variants = ArrayList<HlsVariant>(); val renditions = ArrayList<HlsRendition>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val a = attrs(l)
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                val uri = lines.getOrNull(j)?.takeIf { !it.startsWith("#") && it.isNotEmpty() }
                val res = a["RESOLUTION"]?.split('x')
                if (uri != null) variants += HlsVariant(Urls.resolve(baseUrl, uri) ?: uri, a["BANDWIDTH"]?.toLongOrNull() ?: 0, res?.getOrNull(0)?.toIntOrNull() ?: 0, res?.getOrNull(1)?.toIntOrNull() ?: 0, a["AUDIO"], a["SUBTITLES"])
                i = j
            } else if (l.startsWith("#EXT-X-MEDIA:")) {
                val a = attrs(l)
                renditions += HlsRendition(a["TYPE"].orEmpty(), a["GROUP-ID"].orEmpty(), a["URI"]?.let { Urls.resolve(baseUrl, it) ?: it }, a["LANGUAGE"], a["NAME"], a["DEFAULT"] == "YES")
            }
            i++
        }
        return HlsMaster(variants, renditions).takeIf { it.variants.isNotEmpty() }
    }

    /** FR-3.3: a key system (Widevine, PlayReady, FairPlay) is DRM; plain AES-128 any player decrypts with the key the manifest names is not. */
    fun hasDrm(manifest: String): Boolean {
        val m = manifest.lowercase()
        if ("urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed" in m || "urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95" in m) return true
        if ("com.apple.streamingkeydelivery" in m || "com.microsoft.playready" in m || "com.widevine" in m) return true
        if (Regex("""#ext-x-(session-)?key:[^\n]*method=sample-aes""").containsMatchIn(m)) return true
        if ("<contentprotection" in m) {
            // Common encryption's marker alone (`urn:mpeg:dash:mp4protection:2011`) names no key system, but a stream that is encrypted is not one we can read.
            return true
        }
        return false
    }
}

/** The ffmpeg command lines (FR-6.3/6.4). Pure, so the preview and the test see what runs. */
object FetchCommands {
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Highest variant plus its best audio rendition (and the subtitle rendition when one exists), copied into one MKV with the
     * audio tagged [audioLang] in this same mux — never after with `mkvpropedit` (FR-6.4). Inputs: 0 = the variant, then the
     * audio and subtitle renditions when they are separate playlists.
     */
    fun hlsToMkv(variantUrl: String, audioUrl: String?, subtitleUrl: String?, out: String, audioLang: String, subtitleLang: String): String {
        val inputs = StringBuilder("-i ${q(variantUrl)}")
        var idx = 1
        val maps = StringBuilder("-map 0:v:0")
        var audioIn = 0
        if (audioUrl != null) { inputs.append(" -i ${q(audioUrl)}"); audioIn = idx++; maps.append(" -map $audioIn:a:0") } else maps.append(" -map 0:a:0?")
        var subIn = -1
        if (subtitleUrl != null) { inputs.append(" -extension_picky 0 -i ${q(subtitleUrl)}"); subIn = idx++; maps.append(" -map $subIn:s:0?") }
        val meta = StringBuilder()
        if (audioLang.isNotBlank()) meta.append(" -metadata:s:a:0 language=$audioLang")
        if (subIn >= 0 && subtitleLang.isNotBlank()) meta.append(" -metadata:s:s:0 language=$subtitleLang")
        val subCodec = if (subIn >= 0) " -c:s srt" else ""
        return "nice -n 19 ionice -c3 ffmpeg -nostdin -y -v error -protocol_whitelist file,http,https,tcp,tls,crypto $inputs $maps -c:v copy -c:a copy$subCodec$meta -map_metadata -1 -f matroska ${q(out)}"
    }

    /** A media playlist or an MPD handed to ffmpeg whole: its first video and first audio stream (the caller chose by `ffprobe`). */
    fun streamsToMkv(url: String, videoIndex: Int, audioIndex: Int?, out: String, audioLang: String, subtitleLang: String, subtitleIndex: Int?): String {
        val maps = StringBuilder("-map 0:$videoIndex")
        if (audioIndex != null) maps.append(" -map 0:$audioIndex")
        if (subtitleIndex != null) maps.append(" -map 0:$subtitleIndex")
        val meta = StringBuilder()
        if (audioLang.isNotBlank() && audioIndex != null) meta.append(" -metadata:s:a:0 language=$audioLang")
        if (subtitleLang.isNotBlank() && subtitleIndex != null) meta.append(" -metadata:s:s:0 language=$subtitleLang")
        val sub = if (subtitleIndex != null) " -c:s srt" else ""
        return "nice -n 19 ionice -c3 ffmpeg -nostdin -y -v error -protocol_whitelist file,http,https,tcp,tls,crypto -i ${q(url)} $maps -c:v copy -c:a copy$sub$meta -map_metadata -1 -f matroska ${q(out)}"
    }

    /** `mkvinfo -v` prints the segment's children in file order; the first of `Tracks` / `Cluster` is what FR-6.5 needs. */
    fun firstTopLevel(path: String): String =
        "mkvinfo -v ${q(path)} 2>/dev/null | grep -E -m1 '^\\|?\\+ (Tracks|Cluster)'"

    fun frame(input: String, atSec: Int, out: String): String =
        "nice -n 19 ffmpeg -nostdin -y -ss $atSec -i ${q(input)} -frames:v 1 -vf \"scale='min(1920,iw)':-2,signalstats,metadata=mode=print:key=lavfi.signalstats.YAVG\" -q:v 3 ${q(out)} 2>&1"
}
