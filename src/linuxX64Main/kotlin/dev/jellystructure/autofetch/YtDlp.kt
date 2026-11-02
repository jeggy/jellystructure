package dev.jellystructure.autofetch

import dev.jellystructure.filefix.FileFixShell
import dev.jellystructure.filefix.ShellResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * DEB-2480 (DEB-2474, FR-6.3) — the yt-dlp reader: a site whose pages are built in the browser and that yt-dlp's own extractor already
 * understands. yt-dlp is a program making its own requests, so everything here is arranged to keep it inside what the lane promises (FR-2):
 * our User-Agent, a pause between its requests, no `generic` extractor, no config file, no cache, and every file under the work folder.
 */
object YtDlpTexts {
    const val NOT_INSTALLED = "yt-dlp is not installed on this server"
    const val NO_EXTRACTOR = "yt-dlp has no extractor for this site"
}

/**
 * What one yt-dlp run answered. [body] is its JSON on success; on a failure [message] is its last error line, [status] the HTTP status it
 * names (`HTTP Error 429`), and [drm] whether it refused the video as DRM protected (FR-3.3).
 */
data class YtDlpOutput(
    val ok: Boolean,
    val body: String = "",
    val message: String = "",
    val status: Int? = null,
    val drm: Boolean = false,
) {
    companion object {
        val NOT_INSTALLED = YtDlpOutput(false, message = YtDlpTexts.NOT_INSTALLED)
    }
}

/** One item to download: yt-dlp is handed the [link] (an `http(s)` address on a recorded host) and writes [outPath] (`….mkv`) and nothing else. */
data class YtDlpDownload(
    val link: String,
    val outPath: String,
    val audioLang: String,
    val subtitleLang: String,
    val userAgent: String,
)

object YtDlpErrors {
    private val HTTP = Regex("""HTTP Error (\d{3})""")
    private val DRM = Regex("""(?i)\bDRM\b""")
    private val SUBTITLE = Regex("""(?i)subtitle|subs\b|\.vtt|\.srt|webvtt|postprocessing""")

    /** Reads a failed run's output: the status it names, whether it is DRM, and the line to show. */
    fun classify(exit: Int, output: String): YtDlpOutput {
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (exit == 127 || lines.any { it.contains("yt-dlp: not found") || it.contains("yt-dlp: command not found") })
            return YtDlpOutput.NOT_INSTALLED
        val errors = lines.filter { it.startsWith("ERROR:") }
        val last = (errors.lastOrNull() ?: lines.lastOrNull().orEmpty()).removePrefix("ERROR:").trim()
        val status = HTTP.findAll(output).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
        val drm = errors.any { DRM.containsMatchIn(it) }
        val message = when {
            drm -> RecipeRefusal.DRM
            output.contains("No suitable extractor") || output.contains("Unsupported URL") -> YtDlpTexts.NO_EXTRACTOR
            last.isEmpty() -> "yt-dlp exited with status $exit"
            else -> "yt-dlp: ${last.take(300)}"
        }
        return YtDlpOutput(false, message = message, status = status, drm = drm)
    }

    /** Whether a failure's output blames a subtitle, which is the one reason to run the download again without them (FR-6.4). */
    fun blamesSubtitle(output: String): Boolean = SUBTITLE.containsMatchIn(output)
}

/** The command lines (FR-6.3). Pure, so a test sees what runs. */
object YtDlpCommands {
    /** At most this many entries of a playlist are read; a listing that reaches it is not read in full (FR-16.5). */
    const val PLAYLIST_END = 100
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun base(userAgent: String) =
        "nice -n 19 ionice -c3 env PYTHONDONTWRITEBYTECODE=1 yt-dlp --ignore-config --no-cache-dir --no-plugin-dirs --use-extractors default,-generic " +
            "--user-agent ${q(userAgent)} --sleep-requests ${LaneRules.PAGE_INTERVAL_SEC} --socket-timeout 30 --retries 3 --no-colors --no-warnings"

    /** The playlist at [url], one JSON document on stdout. */
    fun listing(url: String, userAgent: String): String =
        "${base(userAgent)} --flat-playlist --playlist-end $PLAYLIST_END -J ${q(url)}"

    /** One item's metadata, no download. */
    fun info(url: String, userAgent: String): String = "${base(userAgent)} --no-playlist -J ${q(url)}"

    /** The folder yt-dlp keeps its intermediate files in: beside the work file, so it is on the same filesystem and under the work folder. */
    fun tempDir(outPath: String): String = "$outPath.part"

    /**
     * Highest video and best audio into one MKV, the audio tagged [YtDlpDownload.audioLang] in yt-dlp's own ffmpeg step (never after),
     * the source's subtitles embedded when [withSubs]. `-o` takes the work file without its extension; a literal `%` in it is doubled.
     */
    fun download(d: YtDlpDownload, withSubs: Boolean): String {
        val stem = d.outPath.removeSuffix(".mkv").replace("%", "%%")
        val ppa = if (d.audioLang.isNotBlank()) {
            val args = "-metadata:s:a:0 language=${d.audioLang}"
            " --postprocessor-args ${q("Merger+ffmpeg_o:$args")} --postprocessor-args ${q("VideoRemuxer+ffmpeg_o:$args")}"
        } else ""
        val subs = if (withSubs) {
            val langs = if (d.subtitleLang.isBlank()) "all,-live_chat" else "${d.subtitleLang}.*"
            " --write-subs --embed-subs --sub-langs ${q(langs)}"
        } else ""
        val tmp = tempDir(d.outPath)
        return "${base(d.userAgent)} --no-playlist --no-part --no-mtime --no-progress -f ${q("bv*+ba/b")} --merge-output-format mkv --remux-video mkv" +
            "$ppa$subs -P ${q("temp:$tmp")} -P ${q("subtitle:$tmp")} -o ${q("$stem.%(ext)s")} ${q(d.link)}"
    }
}

/** What `-J` of one item says (FR-4.2's probe): a video format, a duration, and whether every format is DRM. */
object YtDlpInfo {
    private val json = Json { ignoreUnknownKeys = true }

    /** The JSON document inside [body] (yt-dlp may print a line before it), or null. */
    fun parse(body: String): JsonObject? {
        val start = body.indexOf('{')
        if (start < 0) return null
        return runCatching { json.parseToJsonElement(body.substring(start)) as? JsonObject }.getOrNull()
    }

    fun facts(body: String): ManifestFacts? {
        val o = parse(body) ?: return null
        val formats = (o["formats"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        // An unknown codec (`null`) is not `none`: only a format that says it has no video is audio-only.
        val video = formats.any { f -> (f["vcodec"] as? JsonPrimitive)?.contentOrNull != "none" } || (formats.isEmpty() && (o["vcodec"] as? JsonPrimitive)?.contentOrNull.let { it != null && it != "none" })
        return ManifestFacts(video, (o["duration"] as? JsonPrimitive)?.doubleOrNull)
    }

    /** Every format yt-dlp lists is marked DRM: it will download none of them. */
    fun allDrm(body: String): Boolean {
        val formats = (parse(body)?.get("formats") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        return formats.isNotEmpty() && formats.all { (it["has_drm"] as? JsonPrimitive)?.booleanOrNull == true }
    }
}

/** The fields a `ytdlp` listing reads when the recipe leaves one out (FR-4.1). */
object YtDlpListing {
    val DEFAULT_FIELDS: Map<String, Field> = mapOf(
        "title" to Field(path = "title"), "link" to Field(path = "url"), "id" to Field(path = "id"),
        "published" to Field(path = "upload_date"), "description" to Field(path = "description"), "duration" to Field(path = "duration"),
        "season" to Field(path = "season_number"), "episode" to Field(path = "episode_number"), "image" to Field(path = "thumbnail"),
    )

    /** [list] as the JSON reader needs it: yt-dlp's names under whatever the recipe overrides, and `entries` as the items. */
    fun asJsonList(list: ListSpec): ListSpec =
        list.copy(kind = "json", itemsPath = list.itemsPath.ifBlank { "entries" }, fields = DEFAULT_FIELDS + list.fields, next = null)

    /** The fields a recipe effectively has: a `ytdlp` list's are completed with the defaults. */
    fun fieldsOf(list: ListSpec): Map<String, Field> = if (list.kind == "ytdlp") DEFAULT_FIELDS + list.fields else list.fields
}

/** The real binding: the `yt-dlp` on PATH, run through the same shell as ffmpeg, so it sits under the same [dev.jellystructure.ops.ProcessGate]. */
class ProductionYtDlp(private val shell: FileFixShell) {
    suspend fun listing(url: String, userAgent: String): YtDlpOutput = json(shell.run(YtDlpCommands.listing(url, userAgent)))
    suspend fun info(url: String, userAgent: String): YtDlpOutput = json(shell.run(YtDlpCommands.info(url, userAgent)))

    private fun json(r: ShellResult): YtDlpOutput =
        if (r.ok) YtDlpOutput(true, body = r.output) else YtDlpErrors.classify(r.exit, r.output)

    suspend fun download(d: YtDlpDownload, stop: () -> Boolean): ToolResult {
        val subs = shell.runStoppable(YtDlpCommands.download(d, withSubs = true), d.outPath, stop)
        try {
            val first = subs ?: return ToolResult(false, "stopped")
            if (first.ok && shell.exists(d.outPath)) return ToolResult(true)
            val why = YtDlpErrors.classify(first.exit, first.output)
            // A subtitle never fails the episode (FR-6.4): when the output blames one, the same download is run once more without them.
            if (why.drm || why.status != null || why.message == YtDlpTexts.NOT_INSTALLED || !YtDlpErrors.blamesSubtitle(first.output))
                return ToolResult(false, why.message, drm = why.drm, status = why.status)
            shell.run("rm -rf ${q(YtDlpCommands.tempDir(d.outPath))}")
            shell.remove(d.outPath)
            val second = shell.runStoppable(YtDlpCommands.download(d, withSubs = false), d.outPath, stop) ?: return ToolResult(false, "stopped")
            if (second.ok && shell.exists(d.outPath)) return ToolResult(true, "no subtitle: yt-dlp could not embed it: ${lastLine(first.output)}")
            val why2 = YtDlpErrors.classify(second.exit, second.output)
            return ToolResult(false, why2.message, drm = why2.drm, status = why2.status)
        } finally {
            shell.run("rm -rf ${q(YtDlpCommands.tempDir(d.outPath))}")
        }
    }

    private fun lastLine(output: String) = output.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty().removePrefix("ERROR:").trim()
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
