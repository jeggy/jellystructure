package dev.jellystructure.autofetch

import dev.jellystructure.filefix.FileFixShell
import dev.jellystructure.filefix.ShellResult
import dev.jellystructure.resolver.LanguageResolver
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

    /**
     * Whether a failure's last output blames a subtitle, which is the one reason to run the download again without them (FR-6.3, FR-6.4).
     * Only the line [classify] would show is read: yt-dlp's `[info] Downloading subtitles: en` and `There are no subtitles for the requested
     * languages` come earlier in the same output and must not make an unrelated failure run twice.
     */
    fun blamesSubtitle(output: String): Boolean {
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val last = lines.lastOrNull { it.startsWith("ERROR:") } ?: lines.lastOrNull() ?: return false
        return SUBTITLE.containsMatchIn(last)
    }
}

/** The recipe's subtitle language under the codes a site names its tracks by, and what a source's own subtitle list says of it (FR-6.3, DEB-3434). */
object YtDlpSubtitles {
    /** The ISO 639-1 form first, then the recipe's own 639-2 code, then its other 639-2 form: `eng` → `en`, `eng`; `ger` → `de`, `ger`, `deu`. */
    fun forms(lang: String): List<String> {
        val two = LanguageResolver.normalize(lang)
        if (two.length != 2) return listOf(lang)
        return listOfNotNull(two, lang, LanguageResolver.toIso6392(two)).distinct()
    }

    /** `--sub-langs` for [lang]: each form with an optional region, never `.*` (yt-dlp full-matches, and `fo.*` takes `fon`). */
    fun subLangs(lang: String): String = forms(lang).joinToString(",") { "$it(-.+)?" }

    /** Whether a source's track [code] is the language [lang]: one of its forms, with or without a region, ignoring case. */
    fun matches(lang: String, code: String): Boolean {
        val c = code.lowercase()
        return forms(lang).any { c == it || c.startsWith("$it-") }
    }

    /** The track codes in the `%(subtitles)j` file: its keys, without the `live_chat` pseudo track. Empty when it cannot be read. */
    fun codes(json: String): List<String> =
        ((runCatching { Json.parseToJsonElement(json.trim()) }.getOrNull()) as? JsonObject)?.keys?.filter { it != "live_chat" }.orEmpty()

    /**
     * The run-line note for a download that was asked for [lang]: set only when the source lists subtitles and none is in that language.
     * A source that lists none, or a file that could not be read, says nothing (FR-6.4).
     */
    fun absentNote(lang: String, listJson: String): String {
        val codes = codes(listJson)
        if (codes.isEmpty() || codes.any { matches(lang, it) }) return ""
        val sorted = codes.sorted()
        val shown = sorted.take(5).joinToString(", ") + if (sorted.size > 5) ", +${sorted.size - 5} more" else ""
        return "no subtitle: none in $lang; the source has $shown"
    }

    /** Where `--print-to-file` writes the source's subtitle list: in the `.part` folder, removed with it. */
    fun listPath(outPath: String): String = "${YtDlpCommands.tempDir(outPath)}/subtitles.json"
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

    /**
     * One item's metadata, no download: only what the proof reads (FR-5.1) — the duration and top-level codec on one line, each format's codec
     * and DRM flag as an array on the next, and the item's date fields on a third (FR-4.1, DEB-3407: the probe dates its own item). `-J` of one YouTube video is 11 MB, past the shell's capture cap (DEB-3404).
     */
    fun info(url: String, userAgent: String): String =
        "${base(userAgent)} --no-playlist --skip-download --print ${q("%(.{duration,vcodec})j")} --print ${q("%(formats.:.{vcodec,has_drm})j")} " +
            "--print ${q("%(.{upload_date,release_date,timestamp})j")} ${q(url)}"

    /**
     * One item's own date, no download (DEB-3407, FR-4.1): the three fields a listing's `published` defaults to, on one line, and nothing else.
     * Never `-J`: the answer for one YouTube video is 11 MB (DEB-3404).
     */
    fun date(url: String, userAgent: String): String =
        "${base(userAgent)} --no-playlist --skip-download --print ${q("%(.{upload_date,release_date,timestamp})j")} ${q(url)}"

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
            val langs = if (d.subtitleLang.isBlank()) "all,-live_chat" else YtDlpSubtitles.subLangs(d.subtitleLang)
            // The source's own subtitle list, kept beside the download (not --print, which would make it a simulation): it says why a language is absent.
            val list = if (d.subtitleLang.isBlank()) "" else
                " --print-to-file ${q("before_dl:%(subtitles)j")} ${q(YtDlpSubtitles.listPath(d.outPath).replace("%", "%%"))}"
            " --write-subs --embed-subs --sub-langs ${q(langs)}$list"
        } else ""
        val tmp = tempDir(d.outPath)
        return "${base(d.userAgent)} --no-playlist --no-part --no-mtime --no-progress -f ${q("bv*+ba/b")} --merge-output-format mkv --remux-video mkv" +
            "$ppa$subs -P ${q("temp:$tmp")} -P ${q("subtitle:$tmp")} -o ${q("$stem.%(ext)s")} ${q(d.link)}"
    }
}

/** What [YtDlpCommands.info] says of one item (FR-4.2's probe): a video format, a duration, and whether every format is DRM. */
object YtDlpInfo {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The item's facts as one object: the line starting `{` (yt-dlp may print lines before it), its `formats` taken from the line starting `[`
     * when it has none of its own. A whole `-J` document is still read.
     */
    fun parse(body: String): JsonObject? {
        val lines = body.lines().map { it.trim() }
        val obj = lines.firstOrNull { it.startsWith("{") }?.let { l -> runCatching { json.parseToJsonElement(l) as? JsonObject }.getOrNull() }
        val arr = lines.firstOrNull { it.startsWith("[") }?.let { l -> runCatching { json.parseToJsonElement(l) as? JsonArray }.getOrNull() }
        if (obj != null) return if (arr != null && "formats" !in obj) JsonObject(obj + ("formats" to arr)) else obj
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

/** DEB-3407 (FR-4.1) — the date in a yt-dlp answer: the first of `upload_date`, `release_date` and `timestamp` (its UTC date) that it has. */
object YtDlpDate {
    private val json = Json { ignoreUnknownKeys = true }

    /** The calendar date as yt-dlp gives it, with no time-zone shift, as `yyyy-MM-dd`; null when the answer has none of the three. */
    fun of(obj: JsonObject): String? = firstDate { k -> (obj[k] as? JsonPrimitive)?.contentOrNull }

    /** The date in [body]'s `{…}` lines (`--print` of [YtDlpCommands.date]); null when there is none. */
    fun parse(body: String): String? {
        val objs = body.lines().map { it.trim() }.filter { it.startsWith("{") }
            .mapNotNull { l -> runCatching { json.parseToJsonElement(l) as? JsonObject }.getOrNull() }
        return firstDate { k -> objs.firstNotNullOfOrNull { o -> (o[k] as? JsonPrimitive)?.contentOrNull } }
    }

    /** [get] gives a field's text; the three fields are tried in order and the first that reads as a date wins. */
    fun firstDate(get: (String) -> String?): String? {
        Numbering.isoDate(get("upload_date"))?.let { return it }
        Numbering.isoDate(get("release_date"))?.let { return it }
        return get("timestamp")?.trim()?.toDoubleOrNull()?.let { Numbering.dateOfEpoch(it.toLong()) }
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

    /**
     * A flat entry's `published` is the first of `upload_date`, `release_date` and `timestamp` it has (FR-4.1, DEB-3407). [list] reads the first;
     * this fills the others in for an item that read none, unless the recipe names its own `published` path. [json] is the listing document.
     */
    fun fillDates(list: ListSpec, json: String, items: List<ListedItem>): List<ListedItem> {
        if ("published" in list.fields || items.none { Numbering.isoDate(it.published) == null }) return items
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return items
        val selected = JsonPaths.select(root, list.itemsPath.ifBlank { "entries" })
        val arr: List<kotlinx.serialization.json.JsonElement> = (selected.singleOrNull() as? JsonArray)?.toList() ?: selected
        return items.mapIndexed { i, it ->
            if (Numbering.isoDate(it.published) != null) it
            else (arr.getOrNull(i) as? JsonObject)?.let { o -> YtDlpDate.of(o) }?.let { d -> it.copy(published = d) } ?: it
        }
    }

    /** The fields a recipe effectively has: a `ytdlp` list's are completed with the defaults. */
    fun fieldsOf(list: ListSpec): Map<String, Field> = if (list.kind == "ytdlp") DEFAULT_FIELDS + list.fields else list.fields
}

/** The real binding: the `yt-dlp` on PATH, run through the same shell as ffmpeg, so it sits under the same [dev.jellystructure.ops.ProcessGate]. */
class ProductionYtDlp(private val shell: FileFixShell) {
    suspend fun listing(url: String, userAgent: String): YtDlpOutput = json(shell.run(YtDlpCommands.listing(url, userAgent)))
    suspend fun info(url: String, userAgent: String): YtDlpOutput = json(shell.run(YtDlpCommands.info(url, userAgent)))
    suspend fun date(url: String, userAgent: String): YtDlpOutput = json(shell.run(YtDlpCommands.date(url, userAgent)))

    private fun json(r: ShellResult): YtDlpOutput =
        if (r.ok) YtDlpOutput(true, body = r.output) else YtDlpErrors.classify(r.exit, r.output)

    suspend fun download(d: YtDlpDownload, stop: () -> Boolean): ToolResult {
        val subs = shell.runStoppable(YtDlpCommands.download(d, withSubs = true), d.outPath, stop)
        try {
            val first = subs ?: return ToolResult(false, "stopped")
            if (first.ok && shell.exists(d.outPath)) return ToolResult(true, absentNote(d))
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

    /** FR-6.3 — the note for a requested language the source does not have; empty when there is none, or the list cannot be read. */
    private suspend fun absentNote(d: YtDlpDownload): String {
        if (d.subtitleLang.isBlank()) return ""
        val r = shell.run("cat ${q(YtDlpSubtitles.listPath(d.outPath))} 2>/dev/null")
        return if (r.ok) YtDlpSubtitles.absentNote(d.subtitleLang, r.output) else ""
    }

    private fun lastLine(output: String) = output.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty().removePrefix("ERROR:").trim()
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
