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
import io.ktor.server.request.uri
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
import kotlin.test.assertTrue

/**
 * DEB-2476 (FR-15.4, FR-15.5, FR-8.2; Acceptance 9) — a series a page-only fetcher feeds is never searched on TMDB, even though the
 * test's TMDB answers a title search for `Dagbók` with a series of that name and Jellyfin offers a TMDB id for it; and each
 * episode's title, overview and air date are the fetcher's item row, verbatim, matched by the placed path.
 */
class PageOnlyScanTest {
    private val dir = "/tmp/autofetch-scan-${platform.posix.getpid()}"
    private val lib = "$dir/tv"
    private val seen = AtomicReference(listOf<String>())
    private fun record(uri: String) { while (true) { val b = seen.value; if (seen.compareAndSet(b, b + uri)) return } }

    private val tmdb = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
        routing {
            get("/search/tv") { record(call.request.uri); call.respondText("""{"results":[{"id":777,"name":"Dagbók","first_air_date":"1999-01-01","overview":"a stranger's series"}]}""", ContentType.Application.Json) }
            get("/tv/{id}") { record(call.request.uri); call.respondText("""{"id":777,"name":"Dagbók","overview":"a stranger's series"}""", ContentType.Application.Json) }
            get("/{...}") { record(call.request.uri); call.respondText("{}", ContentType.Application.Json) }
        }
    }.also { it.start(wait = false) }

    private lateinit var store: AutofetchStore
    private lateinit var config: ConfigStore
    private lateinit var scanner: Scanner

    @BeforeTest
    fun setUp() {
        platform.posix.system("rm -rf '$dir'; mkdir -p '$lib/Dagbók/Season 2026'")
        config = ConfigStore("$dir/config.toml")
        val port = runBlocking { tmdb.engine.resolvedConnectors().first().port }
        runBlocking {
            config.update(config.current.copy(
                apiKeys = config.current.apiKeys.copy(tmdbV3Key = "key"),
                libraries = listOf(LibraryMapping(jellyfinId = "tv", name = "TV", collectionType = "tvshows", localPath = lib, jellyfinPath = "/jf/tv")),
            ))
        }
        store = AutofetchStore(createDatabase("$dir/test.db"))
        scanner = Scanner(config, TmdbClient(config, "http://127.0.0.1:$port"), JellyfinClient(), JsTagStore("$dir/tags.json"), autofetch = StoreScanHooks(store))
    }

    @AfterTest
    fun tearDown() {
        tmdb.stop(0, 0); closeLastDatabaseForTests(); platform.posix.system("rm -rf '$dir'")
    }

    private fun seed(pageOnly: Boolean): String {
        val series = "$lib/Dagbók"
        val file = "$series/Season 2026/Dagbók - S2026E300 - 2026-10-27 - Dagbók · 27. oktober.mkv"
        platform.posix.system("printf x > '$file'")
        store.insert(Fetcher("f1", "Dagbók", "https://varpid.example.fo/sendingar/dagbok", "varpid.example.fo", null, pageOnly, lib, series, "{}", false, "Dagligur tátturin.", null, RetentionSpec(), "daily@04:00", true, "ok", "", 1, null, null))
        if (pageOnly) store.mark(series, "f1")
        store.upsertListed("f1", "i27", ListedItem(title = "Dagbók · 27. oktober", description = "Lýsing 27", link = "https://varpid.example.fo/sendingar/dagbok/27", duration = "1800"), SeasonEpisode(2026, 300), "2026-10-27", 2)
        store.setPlaced("f1", "i27", file, 3)
        return file
    }

    private val jfItem = JellyfinItem(id = "jf1", name = "Dagbók", type = "Series", path = "/jf/tv/Dagbók", providerIds = JellyfinProviderIds(tmdb = "777"))

    @Test
    fun `a page only series is never searched on TMDB and takes the page's words`() = runBlocking<Unit> {
        val file = seed(pageOnly = true)
        val item = assertNotNull(scanner.scanItem(jfItem))
        // A scan, then a re-scan and a re-pull: TMDB receives nothing about the series.
        scanner.scanItem(jfItem)
        scanner.rescanMetadata(item)
        assertEquals(emptyList(), seen.value, "TMDB must receive no request: ${seen.value}")
        assertNull(item.tmdbId)
        assertEquals("Dagligur tátturin.", item.overview)
        val ep = item.episodes.single { it.path == file }
        assertEquals(2026, ep.seasonNumber); assertEquals(300, ep.episodeNumber)
        assertEquals("Dagbók · 27. oktober", ep.title); assertEquals("Lýsing 27", ep.overview); assertEquals("2026-10-27", ep.airDate)
        assertEquals(30, ep.runtime)
    }

    @Test
    fun `without the mark the same series is searched on TMDB as before`() = runBlocking<Unit> {
        seed(pageOnly = false)
        scanner.scanItem(jfItem.copy(providerIds = JellyfinProviderIds()))
        assertTrue(seen.value.any { it.startsWith("/search/tv") }, seen.value.toString())
    }
}
