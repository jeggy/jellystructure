package dev.jellystructure.nfo

import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Person
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** DEB-5534 (FR-1, FR-2, FR-3) — an actor's `<thumb>` is TMDB's own image URL, absent without a `profilePath`,
 *  and no NFO names `/api/people/` or depends on a configured address. */
class NfoWriterActorThumbTest {
    private val dir = "/tmp/jellystructure-test-nfo-actor-thumb-${getpid()}"
    private val thumb = "<thumb>https://image.tmdb.org/t/p/h632/abc.jpg</thumb>"

    @BeforeTest fun setUp() { SystemFileSystem.createDirectories(Path(dir)) }
    @AfterTest fun tearDown() { runCatching { SystemFileSystem.list(Path(dir)).forEach { SystemFileSystem.delete(it) }; SystemFileSystem.delete(Path(dir)) } }

    private val withPhoto = Person(tmdbId = 11, name = "Has Photo", profilePath = "/abc.jpg", order = 0)
    private val noPhoto = Person(tmdbId = 12, name = "No Photo", profilePath = null, order = 1)
    private val blankPhoto = Person(tmdbId = 13, name = "Blank Photo", profilePath = "  ", order = 2)
    private val noId = Person(tmdbId = 0, name = "No Id", profilePath = null, order = 3)

    private fun item(kind: MediaKind) = MediaItem(
        id = "x", title = "T", year = 2022, kind = kind, path = "$dir/T.mkv", tmdbId = 1, originalLanguage = null,
        posterPath = null, overview = null, director = null, tracks = emptyList(), issueCount = 0, scannedAt = 0,
        cast = listOf(withPhoto, noPhoto, blankPhoto, noId),
    )

    private fun episode(file: String, n: Int) = Episode(
        filename = file, path = "$dir/$file", seasonNumber = 1, episodeNumber = n, tracks = emptyList(), issueCount = 0,
        title = "E$n", guestStars = listOf(withPhoto, noPhoto),
    )

    private fun assertThumbs(xml: String) {
        assertEquals(1, Regex(Regex.escape(thumb)).findAll(xml).count(), xml)
        assertEquals(1, Regex("<thumb>").findAll(xml).count(), "only the actor with a profilePath gets a thumb:\n$xml")
        assertFalse(xml.contains("/api/people/"), xml)
    }

    @Test
    fun `a film series and music video actor with a profilePath carries the TMDB thumb and nobody else does`() {
        for (kind in MediaKind.values()) assertThumbs(NfoWriter.buildXml(item(kind)))
    }

    @Test
    fun `an episode guest star carries the TMDB thumb`() = runBlocking {
        val ep = episode("T - S01E01.mkv", 1)
        NfoWriter.writeEpisode(ep).getOrThrow()
        assertThumbs(assertNotNull(NfoWriter.readRawEpisode(ep)))
    }

    @Test
    fun `an episode inheriting the series cast carries the TMDB thumb`() = runBlocking {
        val ep = episode("T - S01E01.mkv", 1).copy(guestStars = emptyList())
        NfoWriter.writeEpisode(ep, listOf(withPhoto, noPhoto)).getOrThrow()
        assertThumbs(assertNotNull(NfoWriter.readRawEpisode(ep)))
    }

    @Test
    fun `each block of a multi-episode file carries the thumb`() = runBlocking {
        val a = episode("T - S01E01-E02.mkv", 1)
        val b = episode("T - S01E01-E02.mkv", 2)
        NfoWriter.writeEpisodeNfos(listOf(a, b)).forEach { it.second.getOrThrow() }
        val xml = assertNotNull(NfoWriter.readRawEpisode(a))
        assertEquals(2, Regex(Regex.escape(thumb)).findAll(xml).count(), xml)
        assertFalse(xml.contains("/api/people/"), xml)
    }

    @Test
    fun `a profilePath is escaped like every other value`() {
        val xml = NfoWriter.buildXml(item(MediaKind.MOVIE).copy(cast = listOf(withPhoto.copy(profilePath = "/a&b.jpg"))))
        assertTrue(xml.contains("<thumb>https://image.tmdb.org/t/p/h632/a&amp;b.jpg</thumb>"), xml)
    }

    @Test
    fun `the content hash is a function of the item and the cascade alone`() {
        for (kind in MediaKind.values()) {
            val it = item(kind)
            assertEquals(NfoWriter.contentHash(it, emptyList()), NfoWriter.contentHash(it.copy(), emptyList()))
            assertEquals(NfoWriter.buildXml(it, emptyList()).hashCode().toString(), NfoWriter.contentHash(it, emptyList()))
        }
    }
}
