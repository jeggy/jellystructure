package dev.jellystructure.autofetch

import dev.jellystructure.config.LibraryMapping
import dev.jellystructure.db.closeLastDatabaseForTests
import dev.jellystructure.db.createDatabase
import dev.jellystructure.filefix.PosixFileFixShell

/** DEB-2476 — the stand-in corpus the engine tests run against: `Dagbók` and `Royndin` on `varpid.example.fo`, the manifests on `media.example.fo`. */
class FakeClock : LaneClock {
    var ms = 0L
    var wall = 1_800_000_000L
    override fun nowSec() = wall + ms / 1000
    override fun monoMs() = ms
    override suspend fun sleepMs(ms: Long) { this.ms += ms }
    fun advanceHours(h: Long) { ms += h * 3_600_000L }
}

/** A site the test serves itself. Records every request with the clock reading, so pacing and "listing only" are assertions. */
class StandInSite(private val clock: FakeClock) : Transport {
    val requests = ArrayList<Pair<String, Long>>()
    /** Days of October 2026 on the page, newest first as a broadcaster lists them. */
    var days: List<Int> = (16..27).toList().reversed()
    var listingStatus = 200
    var listingHeaders: Map<String, String> = emptyMap()
    var empty = false
    var drm = false
    var badMedia: Set<Int> = emptySet()
    var onlyOnePage = false
    /** DEB-2698 — the master playlist is served from a redirect to this host (a CDN edge); the reply says where it ended. */
    var masterEdge: String? = null
    /** Extra `<li>` markup appended to Dagbók's list (a second item on a date already used). */
    var extraItems = ""
    /** DEB-2478 — markup after Dagbók's list (a player config that names the media host, which `probe` may then reach). */
    var listingExtra = ""
    var robots = "User-agent: *\nDisallow: /private\n"
    /** Royndin: year-seasons with a title like `Royndin 2026 · 1. partur`. */
    var royndinParts: List<Int> = listOf(1, 2, 3)

    fun nonRobots() = requests.filter { !it.first.endsWith("/robots.txt") }.map { it.first }
    fun reset() = requests.clear()

    private fun month(d: Int) = "oktober"

    fun dagbokListing(): String {
        if (empty) return "<html><body><h1>Dagbók</h1><p class=\"about\">Dagligur tátturin.</p><ul></ul></body></html>"
        val shown = if (onlyOnePage) days.take(1) else days
        val items = shown.joinToString("\n") { d ->
            """<li class="ep"><a href="/sendingar/dagbok/$d">Dagbók · $d. oktober</a><time datetime="2026-10-$d">$d.10.2026</time><p>Lýsing $d</p></li>"""
        }
        return """<html><body><h1>Dagbók</h1><p class="about">Dagligur tátturin.</p><img class="poster" src="/img/dagbok.jpg"><ul>$items$extraItems</ul>$listingExtra</body></html>"""
    }

    fun royndinListing(): String = royndinParts.joinToString("\n", "<html><body><h1>Royndin</h1><ul>", "</ul></body></html>") { n ->
        """<li class="ep" data-id="R$n"><a href="/sendingar/royndin/$n">Royndin 2026 · $n. partur</a><time datetime="2026-0${n}-10">10.0$n.2026</time></li>"""
    }

    private val master = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,AUDIO="a"
low.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080,AUDIO="a"
high.m3u8
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="fo",LANGUAGE="fo",DEFAULT=YES,URI="audio.m3u8"
"""

    override suspend fun get(url: String): HttpReply {
        requests += url to clock.ms
        val path = Urls.path(url)
        return when {
            path == "/robots.txt" -> HttpReply(200, emptyMap(), robots)
            path == "/sendingar/dagbok" -> if (listingStatus != 200) HttpReply(listingStatus, listingHeaders, "") else HttpReply(200, emptyMap(), dagbokListing())
            path == "/sendingar/royndin" -> HttpReply(200, emptyMap(), royndinListing())
            path.startsWith("/sendingar/dagbok/") -> {
                val d = path.substringAfterLast('/').toInt()
                val media = if (d in badMedia) "../etc/passwd" else "DOV73$d"
                HttpReply(200, emptyMap(), "<html><script>var media = '$media';</script></html>")
            }
            path.endsWith("/master.m3u8") -> HttpReply(200, emptyMap(), if (drm) master + "#EXT-X-KEY:METHOD=SAMPLE-AES,KEYFORMAT=\"com.apple.streamingkeydelivery\",URI=\"skd://x\"\n" else master, masterEdge?.let { "https://$it/video/x.smil/playlist.m3u8" })
            else -> HttpReply(404, emptyMap(), "")
        }
    }
}

class FakeTools(private val clock: FakeClock, private val dir: String) : MediaTools {
    var downloads = ArrayList<Pair<String, Long>>()
    var overlapping = false
    private var active = 0
    var audioLanguage = "fao"
    var first = "Tracks"
    var duration = 1800.0
    var failDownload = false
    /** What a successful download reports back, like the subtitle note of FR-6.4 (DEB-2872). */
    var downloadNote = ""
    /** Called with the work path just before the download "finishes" — lets a test put something at the final path. */
    var onDownload: (String) -> Unit = {}

    val probed = ArrayList<String>()

    // ---- DEB-2480 — yt-dlp, as the fake binds it ----
    /** The entries yt-dlp's flat playlist gives: (id, url, title, upload_date). Newest first. */
    var ytdlpEntries: List<List<String>> = (16..27).toList().reversed().map { d -> listOf("N$d", "https://varpid.example.fo/sendingar/dagbok/$d", "Dagbók · $d. oktober", "202610$d") }
    var ytdlpListingOut: YtDlpOutput? = null
    var ytdlpInfoOut: YtDlpOutput? = null
    var ytdlpFail: ToolResult? = null
    val ytdlpListings = ArrayList<String>()
    val ytdlpInfos = ArrayList<String>()
    val ytdlpDownloads = ArrayList<YtDlpDownload>()

    override suspend fun ytdlpListing(url: String): YtDlpOutput {
        ytdlpListings += url
        return ytdlpListingOut ?: YtDlpOutput(true, body = """{"_type":"playlist","entries":[""" + ytdlpEntries.joinToString(",") { e ->
            """{"id":"${e[0]}","url":"${e[1]}","title":"${e[2]}","upload_date":"${e[3]}"}"""
        } + "]}")
    }

    override suspend fun ytdlpInfo(url: String): YtDlpOutput {
        ytdlpInfos += url
        return ytdlpInfoOut ?: YtDlpOutput(true, body = """{"id":"x","duration":$duration,"formats":[{"vcodec":"none","acodec":"mp4a"},{"vcodec":"avc1","acodec":"none"}]}""")
    }

    override suspend fun ytdlpDownload(d: YtDlpDownload, stop: () -> Boolean): ToolResult {
        ytdlpDownloads += d
        downloads += d.link to clock.ms
        clock.ms += 5_000
        ytdlpFail?.let { return it }
        onDownload(d.outPath)
        PosixFileFixShell.writeText(d.outPath, "mkv:" + d.link)
        return ToolResult(true, downloadNote)
    }

    override suspend fun probeManifest(url: String, text: String, kind: String): ManifestFacts { probed += url; return ManifestFacts(true, duration) }

    override suspend fun download(spec: DownloadSpec, stop: () -> Boolean): ToolResult {
        if (++active > 1) overlapping = true
        downloads += spec.manifestUrl to clock.ms
        clock.ms += 5_000
        active--
        if (failDownload) return ToolResult(false, "boom")
        onDownload(spec.outPath)
        PosixFileFixShell.writeText(spec.outPath, "mkv:" + spec.manifestUrl)
        return ToolResult(true, downloadNote)
    }

    override suspend fun inspect(path: String) = FileFacts(duration, listOf(audioLanguage), emptyList(), 1080, 1_900_000_000L)
    override suspend fun firstTopLevel(path: String) = first
    override suspend fun thumbnail(path: String, durationSec: Double, out: String): Boolean = PosixFileFixShell.writeText(out, "jpg")
    /** The moments the fixed rule would pick, so a test can see them stored; [blackAt] is a moment every cut there refuses (FR-9.4). */
    var blackAt: Int? = null
    override suspend fun frameAt(path: String, atSec: Int, out: String): Boolean = atSec != blackAt && PosixFileFixShell.writeText(out, "jpg@$atSec")
    override suspend fun stills(path: String, durationSec: Double, outFiles: List<String>): List<Int> =
        outFiles.mapIndexedNotNull { i, f -> val at = (durationSec * (i + 1) / (outFiles.size + 1)).toInt(); if (frameAt(path, at, f)) at else null }
}

class FakeWorld(private val clock: FakeClock, val lib: String) : AutofetchWorld {
    var on = true
    var viewers = false
    val playingPaths = HashSet<String>()
    var kind = TmdbKind.SERIES
    var tmdbName: String? = "Royndin"
    var tmdbEps: List<TmdbEpisodeFact>? = emptyList()
    var sonarr: String? = null
    var tmdbCalls = 0
    val announced = ArrayList<Pair<String, String>>()
    val activities = ArrayList<String>()
    val histories = ArrayList<String>()
    val forgotten = ArrayList<String>()
    val stops = ArrayList<Pair<String, String>>()
    val images = ArrayList<String>()
    /** FR-9.3 — what an explicit re-sync of a season finds: per episode path. Null = the library has not got the series / TMDB unreachable. */
    var resync: (List<String>) -> Map<String, EpisodeAfterSync>? = { null }
    val resyncs = ArrayList<Triple<String, Int, Int>>()
    val runsStarted = ArrayList<String>()
    val runsEnded = ArrayList<String>()
    val activityRuns = ArrayList<Pair<String?, String>>()

    override fun enabled() = on
    override fun libraries() = listOf(LibraryMapping(jellyfinId = "tv", name = "TV", collectionType = "tvshows", localPath = lib))
    override fun nowSec() = clock.nowSec()
    override fun waitForViewers() = viewers
    override suspend fun episodePlaying(path: String) = path in playingPaths
    override suspend fun tmdbKind(id: Int): TmdbKind { tmdbCalls++; return kind }
    override suspend fun tmdbSeriesName(id: Int): String? { tmdbCalls++; return tmdbName }
    override suspend fun tmdbEpisodes(id: Int): List<TmdbEpisodeFact>? { tmdbCalls++; return tmdbEps }
    override suspend fun sonarrReason(tmdbId: Int?, seriesPath: String) = sonarr
    override suspend fun announce(path: String, updateType: String) { announced += path to updateType }
    override suspend fun history(path: String, text: String) { histories += text }
    override suspend fun activity(level: String, message: String, runId: String?) { activities += message; activityRuns += runId to message }
    override suspend fun activityStart(runId: String, trigger: String) { runsStarted += runId }
    override suspend fun activityEnd(runId: String) { runsEnded += runId }
    override suspend fun resyncSeason(seriesPath: String, tmdbId: Int, season: Int, episodePaths: List<String>): Map<String, EpisodeAfterSync>? {
        resyncs += Triple(seriesPath, tmdbId, season); return resync(episodePaths)
    }
    override fun stillsDir() = "${lib.substringBeforeLast('/')}/stills"
    override suspend fun forgetEpisode(path: String) { forgotten += path }
    override suspend fun fetchImage(url: String, dest: String, lane: SiteLane): Boolean { images += url; return PosixFileFixShell.writeText(dest, "img") }
    override suspend fun notifySiteStopped(site: String, reason: String) { stops += site to reason }
}

/** Everything one engine test needs: a real SQLite file, a real temp library, and the fakes. */
class Harness {
    val dir = "/tmp/autofetch-engine-${platform.posix.getpid()}-${platform.posix.rand()}"
    val lib = "$dir/tv"
    val clock = FakeClock()
    val site = StandInSite(clock)
    val tools = FakeTools(clock, dir)
    val world = FakeWorld(clock, lib)
    val store: AutofetchStore
    val lanes: SiteLanes
    val engine: AutofetchEngine
    val service: AutofetchService
    private var ids = 0

    init {
        platform.posix.system("rm -rf '$dir'; mkdir -p '$lib'")
        store = AutofetchStore(createDatabase("$dir/test.db"))
        lanes = SiteLanes(store, site, clock)
        engine = AutofetchEngine(store, lanes, tools, world)
        service = AutofetchService(store, engine, lanes, tools, world) { "f${++ids}" }
    }

    fun close() {
        closeLastDatabaseForTests()
        platform.posix.system("rm -rf '$dir'")
    }

    val dagbokRecipe = Recipe(
        source = RecipeSource("https://varpid.example.fo/sendingar/dagbok", listOf("varpid.example.fo", "media.example.fo")),
        list = ListSpec(
            kind = "html", itemSelector = "li.ep",
            fields = mapOf("title" to Field("a"), "link" to Field("a", "href"), "published" to Field("time", "datetime"), "description" to Field("p")),
        ),
        media = MediaSpec(kind = "hls", manifest = "https://media.example.fo/{value}/master.m3u8", itemPage = ItemPageSpec(pattern = """var media = '(?<value>[A-Za-z0-9._-]+)'""")),
        numbering = NumberingSpec("date"),
        series = SeriesSpec(name = Field("h1"), description = Field("p.about"), image = Field("img.poster", "src")),
        language = LanguageSpec("fao", ""),
    )

    val royndinRecipe = Recipe(
        source = RecipeSource("https://varpid.example.fo/sendingar/royndin", listOf("varpid.example.fo", "media.example.fo")),
        list = ListSpec(
            kind = "html", itemSelector = "li.ep",
            fields = mapOf("title" to Field("a"), "link" to Field("a", "href"), "published" to Field("time", "datetime"), "id" to Field("", "data-id")),
        ),
        media = MediaSpec(kind = "hls", manifest = "https://media.example.fo/{id}/master.m3u8"),
        pattern = listOf(PatternRule("title", """(?<season>\d{4})"""), PatternRule("title", """(?<episode>\d+)\. partur""")),
        language = LanguageSpec("fao", ""),
    )

    val dagbokUrl = "https://varpid.example.fo/sendingar/dagbok"
    suspend fun saveDagbok(retention: RetentionSpec? = null): Fetcher =
        when (val r = service.save(SaveRequest(dagbokUrl, null, true, dagbokRecipe, retention = retention))) {
            is SaveResult.Saved -> r.fetcher
            is SaveResult.Refused -> error("save refused: ${r.reason}")
        }

    fun seasonDir(f: Fetcher) = "${f.seriesPath}/Season 2026"
    fun files(f: Fetcher): List<String> = store.items(f.id).mapNotNull { it.placedPath }.map { it.substringAfterLast('/') }.sorted()
    fun onDisk(path: String) = PosixFileFixShell.exists(path)
}
