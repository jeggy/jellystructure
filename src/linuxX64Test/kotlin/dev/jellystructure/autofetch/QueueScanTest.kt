package dev.jellystructure.autofetch

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinItem
import dev.jellystructure.auth.JellyfinProviderIds
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.LibraryMapping
import dev.jellystructure.db.closeLastDatabaseForTests
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.Scanner
import dev.jellystructure.tmdb.TmdbClient
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * DEB-2477 (DEB-2474, FR-8.1, FR-8.4) — the defect this slice closes: the metadata re-pull overwrote an episode's title and overview
 * with whatever TMDB returned, blank included. TMDB wins where it has text; a field it leaves blank keeps the page's text when the
 * queue's marker says the text is the page's; an episode TMDB had all along is ordinary and is not given the page's words.
 */
class QueueScanTest {
    private val dir = "/tmp/autofetch-queue-scan-${platform.posix.getpid()}"
    private val lib = "$dir/tv"
    private val series = "$lib/Royndin"
    private val season = "$series/Season 2026"
    private val queuedFile = "$season/Royndin - S2026E03 - Royndin 2026 · 3. partur.mkv"
    private val ordinaryFile = "$season/Royndin - S2026E01 - Royndin 2026 · 1. partur.mkv"

    /** What TMDB says of season 2026, episodes 1 and 3: (name, overview). */
    private val tmdbSays = AtomicReference(mapOf(1 to ("" to ""), 3 to ("" to "")))

    private val tmdb = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
        routing {
            get("/search/tv") { call.respondText("""{"results":[{"id":777,"name":"Royndin","first_air_date":"2026-01-01"}]}""", ContentType.Application.Json) }
            get("/tv/{id}") { call.respondText("""{"id":777,"name":"Royndin","overview":"The series."}""", ContentType.Application.Json) }
            get("/tv/{id}/season/{s}/episode/{e}") {
                val e = call.parameters["e"]!!.toInt()
                val (name, overview) = tmdbSays.value[e] ?: return@get call.respondText("{}", ContentType.Application.Json, io.ktor.http.HttpStatusCode.NotFound)
                call.respondText("""{"id":${50 + e},"name":"$name","overview":"$overview","season_number":2026,"episode_number":$e}""", ContentType.Application.Json)
            }
            get("/{...}") { call.respondText("{}", ContentType.Application.Json) }
        }
    }.also { it.start(wait = false) }

    private lateinit var store: AutofetchStore
    private lateinit var scanner: Scanner

    @BeforeTest
    fun setUp() {
        platform.posix.system("rm -rf '$dir'; mkdir -p '$season'")
        val config = ConfigStore("$dir/config.toml")
        val port = runBlocking { tmdb.engine.resolvedConnectors().first().port }
        runBlocking {
            config.update(config.current.copy(
                apiKeys = config.current.apiKeys.copy(tmdbV3Key = "key"),
                libraries = listOf(LibraryMapping(jellyfinId = "tv", name = "TV", collectionType = "tvshows", localPath = lib, jellyfinPath = "/jf/tv")),
            ))
        }
        store = AutofetchStore(createDatabase("$dir/test.db"))
        scanner = Scanner(config, TmdbClient(config, "http://127.0.0.1:$port"), JellyfinClient(), JsTagStore("$dir/tags.json"), autofetch = StoreScanHooks(store))
        for (f in listOf(queuedFile, ordinaryFile)) platform.posix.system("printf x > '$f'")
        store.insert(Fetcher("f1", "Royndin", "https://varpid.example.fo/sendingar/royndin", "varpid.example.fo", 777, false, lib, series, "{}", true, null, null, RetentionSpec(), "daily@04:00", true, "ok", "", 1, null, null))
        store.upsertListed("f1", "i3", ListedItem(title = "Royndin 2026 · 3. partur", description = "Lýsing 3", link = "https://varpid.example.fo/sendingar/royndin/3", duration = "1800"), SeasonEpisode(2026, 3), "2026-03-10", 2)
        store.upsertListed("f1", "i1", ListedItem(title = "Royndin 2026 · 1. partur", description = "Lýsing 1", link = "https://varpid.example.fo/sendingar/royndin/1", duration = "1800"), SeasonEpisode(2026, 1), "2026-01-10", 2)
        store.setPlaced("f1", "i3", queuedFile, 3); store.setPlaced("f1", "i1", ordinaryFile, 3)
        store.fileQueue("f1", "i3", 2026, 3, 3, titleFromPage = true, overviewFromPage = true, stills = emptyList())
    }

    @AfterTest
    fun tearDown() { tmdb.stop(0, 0); closeLastDatabaseForTests(); platform.posix.system("rm -rf '$dir'") }

    private val jfItem = JellyfinItem(id = "jf1", name = "Royndin", type = "Series", path = "/jf/tv/Royndin", providerIds = JellyfinProviderIds(tmdb = "777"))

    private fun tmdbNow(vararg eps: Pair<Int, Pair<String, String>>) { tmdbSays.value = mapOf(*eps) }

    @Test
    fun `a re-pull keeps the page's words for a field TMDB leaves blank and takes TMDB's where it has text`() = runBlocking<Unit> {
        // TMDB has the numbers and no words yet.
        tmdbNow(1 to ("" to ""), 3 to ("" to ""))
        val scanned = assertNotNull(scanner.scanItem(jfItem))
        val first = scanned.episodes.single { it.path == queuedFile }
        assertEquals("Royndin 2026 · 3. partur", first.title); assertEquals("Lýsing 3", first.overview)
        assertNotNull(first.tmdbEpisodeId, "TMDB does have the episode now")

        val repulled = assertNotNull(scanner.rescanMetadata(scanned))
        val ep = repulled.episodes.single { it.path == queuedFile }
        assertEquals("Royndin 2026 · 3. partur", ep.title, "a blank TMDB name does not overwrite the page's heading (FR-8.4)")
        assertEquals("Lýsing 3", ep.overview)

        // TMDB now has a name but still no overview: the name is TMDB's, the overview stays the page's.
        tmdbNow(1 to ("" to ""), 3 to ("Útfall" to ""))
        val second = assertNotNull(scanner.rescanMetadata(repulled)).episodes.single { it.path == queuedFile }
        assertEquals("Útfall", second.title); assertEquals("Lýsing 3", second.overview)

        // …and when it has both, both are TMDB's.
        tmdbNow(1 to ("" to ""), 3 to ("Útfall" to "Tvey bátar við sama sund."))
        val third = assertNotNull(scanner.rescanMetadata(repulled)).episodes.single { it.path == queuedFile }
        assertEquals("Útfall", third.title); assertEquals("Tvey bátar við sama sund.", third.overview)
    }

    @Test
    fun `an episode TMDB had all along is ordinary and a blank TMDB field is not filled with the page's words`() = runBlocking<Unit> {
        tmdbNow(1 to ("" to ""), 3 to ("" to ""))
        val scanned = assertNotNull(scanner.scanItem(jfItem))
        val ordinary = scanned.episodes.single { it.path == ordinaryFile }
        assertNull(ordinary.title, "FR-8.1 — no queue row, so the page's heading is not used"); assertNull(ordinary.overview)
        val repulled = assertNotNull(scanner.rescanMetadata(scanned))
        assertNull(repulled.episodes.single { it.path == ordinaryFile }.title)
    }

    @Test
    fun `the marker is field by field`() = runBlocking<Unit> {
        store.queueMarkers(store.queueRow("f1", "i3")!!.id, title = false, overview = true)
        tmdbNow(1 to ("" to ""), 3 to ("" to ""))
        val scanned = assertNotNull(scanner.scanItem(jfItem))
        val ep = scanned.episodes.single { it.path == queuedFile }
        assertNull(ep.title, "the title is no longer the page's, so a blank TMDB title stays blank"); assertEquals("Lýsing 3", ep.overview)
        val repulled = assertNotNull(scanner.rescanMetadata(scanned)).episodes.single { it.path == queuedFile }
        assertNull(repulled.title); assertEquals("Lýsing 3", repulled.overview)
    }
}
