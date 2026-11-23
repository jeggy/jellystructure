package dev.jellystructure.nfo

import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
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

/** DEB-5440 (FR-1, FR-2) — the release date reaches the NFO as `<aired>`/`<premiered>` (episode), `<premiered>` (series) and
 *  `<premiered>`/`<releasedate>` (movie), and is omitted — never empty — when unknown. */
class NfoWriterReleaseDateTest {
    private val dir = "/tmp/jellystructure-test-nfo-release-date-${getpid()}"

    @BeforeTest fun setUp() { SystemFileSystem.createDirectories(Path(dir)) }
    @AfterTest fun tearDown() { runCatching { SystemFileSystem.list(Path(dir)).forEach { SystemFileSystem.delete(it) }; SystemFileSystem.delete(Path(dir)) } }

    private fun item(kind: MediaKind, releaseDate: String?) = MediaItem(
        id = "x", title = "T", year = 2022, kind = kind, path = "$dir/T.mkv", tmdbId = 1, originalLanguage = null,
        posterPath = null, overview = null, director = null, tracks = emptyList(), issueCount = 0, scannedAt = 0,
        releaseDate = releaseDate,
    )

    private fun episode(n: Int, airDate: String?, file: String = "T - S01E0$n.mkv") = Episode(
        filename = file, path = "$dir/$file", seasonNumber = 1, episodeNumber = n, tracks = emptyList(), issueCount = 0,
        title = "E$n", airDate = airDate,
    )

    @Test
    fun `an episode with an air date writes aired and premiered with the same value`() = runBlocking {
        val ep = episode(1, "2022-06-03")
        NfoWriter.writeEpisode(ep).getOrThrow()
        val xml = assertNotNull(NfoWriter.readRawEpisode(ep))
        assertTrue(xml.contains("<aired>2022-06-03</aired>"))
        assertTrue(xml.contains("<premiered>2022-06-03</premiered>"))
    }

    @Test
    fun `an episode without a usable air date writes neither tag`() = runBlocking {
        for ((i, raw) in listOf(null, "", "  ", "June 3rd").withIndex()) {
            val ep = episode(1, raw, "T - $i.mkv")
            NfoWriter.writeEpisode(ep).getOrThrow()
            val xml = assertNotNull(NfoWriter.readRawEpisode(ep))
            assertFalse(xml.contains("<aired"), "raw=$raw")
            assertFalse(xml.contains("<premiered"), "raw=$raw")
        }
    }

    @Test
    fun `each block of a multi-episode file carries its own air date`() = runBlocking {
        val a = episode(1, "2022-06-03", "T - S01E01-E02.mkv")
        val b = episode(2, "2022-06-10", "T - S01E01-E02.mkv")
        NfoWriter.writeEpisodeNfos(listOf(a, b)).forEach { it.second.getOrThrow() }
        val xml = assertNotNull(NfoWriter.readRawEpisode(a))
        assertTrue(xml.contains("<aired>2022-06-03</aired>"))
        assertTrue(xml.contains("<aired>2022-06-10</aired>"))
    }

    @Test
    fun `a series writes premiered and keeps year`() {
        val xml = NfoWriter.buildXml(item(MediaKind.TV_SHOW, "2022-06-03"))
        assertTrue(xml.contains("<premiered>2022-06-03</premiered>"))
        assertFalse(xml.contains("<releasedate>"))
        assertTrue(xml.contains("<year>2022</year>"))
    }

    @Test
    fun `a movie writes premiered and releasedate and keeps year`() {
        val xml = NfoWriter.buildXml(item(MediaKind.MOVIE, "2022-06-03"))
        assertTrue(xml.contains("<premiered>2022-06-03</premiered>"))
        assertTrue(xml.contains("<releasedate>2022-06-03</releasedate>"))
        assertTrue(xml.contains("<year>2022</year>"))
    }

    @Test
    fun `no release date omits the tags and a date change moves the content hash`() {
        for (kind in listOf(MediaKind.TV_SHOW, MediaKind.MOVIE)) {
            val xml = NfoWriter.buildXml(item(kind, null))
            assertFalse(xml.contains("<premiered"))
            assertFalse(xml.contains("<releasedate"))
            assertTrue(NfoWriter.contentHash(item(kind, null)) != NfoWriter.contentHash(item(kind, "2022-06-03")))
            assertEquals(NfoWriter.contentHash(item(kind, "2022-06-03")), NfoWriter.contentHash(item(kind, "2022-06-03")))
        }
    }
}
