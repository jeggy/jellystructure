package dev.jellystructure.tmdb

import dev.jellystructure.config.ConfigStore
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.concurrent.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * DEB-1681 (FR-184-5, FR-192-4) — `getTvImages` / `getMovieImages` over a fake TMDB whose unfiltered
 * `/images` answer is empty for one title (id 111) and complete for another (id 222), while a request
 * scoped with `include_image_language` always answers. The result is the deduplicated union.
 */
class TmdbImagesScopedUnionTest {
    private val dir = "/tmp/jellystructure-test-tmdb-images-${getpid()}"
    private val queries = AtomicReference(listOf<String>())
    private fun record(entry: String) {
        while (true) {
            val before = queries.value
            if (queries.compareAndSet(before, before + entry)) return
        }
    }

    private fun img(path: String, lang: String?) =
        """{"file_path":"$path","iso_639_1":${if (lang == null) "null" else "\"$lang\""},"vote_average":1.0,"width":1,"height":1}"""

    private val tmdb = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
        routing {
            get("/tv/{id}/images") {
                val scope = call.request.queryParameters["include_image_language"]
                record("${call.parameters["id"]} ${scope ?: "-"}")
                val empty = """{"posters":[],"backdrops":[],"logos":[]}"""
                val body = when (call.parameters["id"] to scope) {
                    "111" to null -> empty
                    "111" to "fo,null" -> """{"posters":[${img("/p-fo.jpg", "fo")},${img("/p-nul.jpg", null)}],
                        "backdrops":[${img("/b-nul.jpg", null)}],
                        "logos":[${img("/l-fo.png", "fo")},${img("/l-nul.png", null)}]}"""
                    "222" to null -> """{"posters":[${img("/p-fo.jpg", "fo")},${img("/p-en.jpg", "en")}],
                        "backdrops":[${img("/b-nul.jpg", null)}],"logos":[${img("/l-en.png", "en")}]}"""
                    "222" to "fo,null" -> """{"posters":[${img("/p-fo.jpg", "fo")}],"backdrops":[${img("/b-nul.jpg", null)}],"logos":[]}"""
                    "222" to "null" -> """{"posters":[],"backdrops":[${img("/b-nul.jpg", null)}],"logos":[]}"""
                    else -> empty
                }
                call.respondText(body, ContentType.Application.Json)
            }
        }
    }.also { it.start(wait = false) }

    private lateinit var client: TmdbClient

    @BeforeTest
    fun setUp() {
        platform.posix.system("rm -rf '$dir'; mkdir -p '$dir'")
        val configStore = ConfigStore("$dir/config.toml")
        runBlocking {
            configStore.update(configStore.current.copy(apiKeys = configStore.current.apiKeys.copy(tmdbV3Key = "key")))
        }
        val port = runBlocking { tmdb.engine.resolvedConnectors().first().port }
        client = TmdbClient(configStore, "http://127.0.0.1:$port")
    }

    @AfterTest
    fun tearDown() {
        tmdb.stop(0, 0)
        platform.posix.system("rm -rf '$dir'")
    }

    @Test
    fun `an empty unfiltered answer is filled from the language scoped one and the logo pick reaches the title language tier`() {
        val images = runBlocking { client.getTvImages(111, "fo") }!!
        assertEquals(listOf("/p-fo.jpg", "/p-nul.jpg"), images.posters.map { it.filePath })
        assertEquals(listOf("/b-nul.jpg"), images.backdrops.map { it.filePath })
        assertEquals(listOf("/l-fo.png", "/l-nul.png"), images.logos.map { it.filePath })
        // The pick ArtworkDownloader.fetch makes (FR-192-4): the resolved-language tier first.
        val tier = images.logos.filter { it.languageCode == "fo" }.ifEmpty { images.logos.filter { it.languageCode == null } }
        assertEquals(listOf("/l-fo.png"), tier.map { it.filePath })
        assertEquals(listOf("111 -", "111 fo,null"), queries.value)
    }

    @Test
    fun `a complete unfiltered answer returns what it did before and the scoped request adds no duplicates`() {
        val images = runBlocking { client.getTvImages(222, "fo") }!!
        assertEquals(listOf("/p-fo.jpg", "/p-en.jpg"), images.posters.map { it.filePath })
        assertEquals(listOf("/b-nul.jpg"), images.backdrops.map { it.filePath })
        assertEquals(listOf("/l-en.png"), images.logos.map { it.filePath })
    }

    @Test
    fun `with no language the scoped request asks for textless alone`() {
        val images = runBlocking { client.getTvImages(222) }!!
        assertEquals(listOf("/p-fo.jpg", "/p-en.jpg"), images.posters.map { it.filePath })
        assertEquals(listOf("222 -", "222 null"), queries.value)
    }

    @Test
    fun `an item language reduces to its two letter form and the choice outranks the resolved one`() {
        assertEquals("pt", imageLanguageFor(null, "pt-BR"))
        assertEquals("fo", imageLanguageFor("fo", "en"))
        assertEquals("en", imageLanguageFor("  ", "EN"))
        assertNull(imageLanguageFor(null, null))
    }
}
