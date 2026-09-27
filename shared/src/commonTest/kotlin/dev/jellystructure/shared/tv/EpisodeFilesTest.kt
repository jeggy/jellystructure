package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R309 (FR-R309-6) — production's exact shape: the parts of `S01E01E02E03.mkv` share the file's one
 * Jellyfin id, and every one of them must survive. The auto-play-next loop fix's own case (two files,
 * one id) must still collapse to the first file.
 */
class EpisodeFilesTest {

    private fun ep(id: String, number: Int, file: String, partIndex: Int = 0, partCount: Int = 1) = Episode(
        id = id, episodeNumber = number, title = "E$number", runtime = 7, overview = null, stillUrl = null,
        file = file, partIndex = partIndex, partCount = partCount,
    )

    private val threeInOne = listOf(
        ep("jf1", 1, "/tv/Show/S01E01E02E03.mkv", 0, 3),
        ep("jf1", 2, "/tv/Show/S01E01E02E03.mkv", 1, 3),
        ep("jf1", 3, "/tv/Show/S01E01E02E03.mkv", 2, 3),
    )

    @Test
    fun `every part of a multi-episode file is kept although all share one id`() {
        assertEquals(listOf(1, 2, 3), threeInOne.oneIdPerFile().map { it.episodeNumber })
    }

    @Test
    fun `two files claiming one id keep only the first file`() {
        val eps = listOf(
            ep("jf1", 1, "/tv/Show/Season 1/S01E01.mkv"),
            ep("jf1", 1, "/tv/Show/Season 2/S01E01.mkv"),
            ep("jf2", 2, "/tv/Show/Season 1/S01E02.mkv"),
        )
        assertEquals(listOf("/tv/Show/Season 1/S01E01.mkv", "/tv/Show/Season 1/S01E02.mkv"), eps.oneIdPerFile().map { it.file })
    }

    @Test
    fun `a season mixing both keeps the whole file and drops the second claimant`() {
        val eps = threeInOne + listOf(
            ep("jf4", 4, "/tv/Show/S01E04E05.mkv", 0, 2),
            ep("jf4", 5, "/tv/Show/S01E04E05.mkv", 1, 2),
            ep("jf1", 1, "/tv/Show/copy/S01E01.mkv"),
            ep("jf6", 6, "/tv/Show/S01E06.mkv"),
        )
        assertEquals(listOf(1, 2, 3, 4, 5, 6), eps.oneIdPerFile().map { it.episodeNumber })
    }

    @Test
    fun `two entries with no file and one id still collapse`() {
        val eps = listOf(ep("jf1", 1, ""), ep("jf1", 1, ""), ep("jf2", 2, ""))
        assertEquals(listOf(1, 2), eps.oneIdPerFile().map { it.episodeNumber })
    }

    @Test
    fun `a multi-episode file is one group of three`() {
        val groups = (threeInOne + ep("jf4", 4, "/tv/Show/S01E04.mkv")).groupedByFile()
        assertEquals(listOf(listOf(1, 2, 3), listOf(4)), groups.map { g -> g.map { it.episodeNumber } })
    }

    @Test
    fun `two files claiming one id are still one group`() {
        val eps = listOf(ep("jf1", 1, "/a/S01E01.mkv"), ep("jf1", 1, "/b/S01E01.mkv"), ep("jf2", 2, "/a/S01E02.mkv"))
        val groups = eps.groupedByFile()
        assertEquals(listOf("/a/S01E01.mkv", "/a/S01E02.mkv"), groups.map { it.single().file })
        assertEquals(groups.size, groups.map { it.first().id }.toSet().size)
    }
}
