package dev.jellystructure.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 254 — every fixture line is a real one, from the 2026-09-21 demux pass over the 93 files of
 *  *Hoppe Hares Byggebande* (20 damaged, 72 clean, 1 clean-but-noisy). */
class FileIntegrityTest {

    @Test
    fun `a matroska demuxer line is damage - whichever shape it takes`() {
        val real = listOf(
            "[matroska,webm @ 0x55727b7f9280] Element at 0xc0b7c0 ending at 0x2a9881f9d exceeds containing master element ending at 0xd77244",   // S01E06
            "[matroska,webm @ 0x55fe3014d280] Unknown-sized element at 0x16c81 inside parent with finite size",                                   // S01E04
            "[matroska,webm @ 0x55b770616281] Invalid length 0x61 > 0x8 for element with ID 0xE7 at 0x120cc7d",                                   // S01E32
            "[matroska,webm @ 0x55b770616282] 0x00 at pos 56420409 (0x35ce839) invalid as first byte of an EBML number",                          // S01E28
            "[matroska,webm @ 0x55b770616283] Length 6 indicated by an EBML number's first byte 0x05 at pos 101242963 (0x608d853) exceeds max length 4.", // S01E21
            "Truncating packet of size 1656599 to 4847",                                                                                          // S02E03
        )
        for (line in real) assertEquals(listOf(line), FileIntegrity.damageLines(line), line)
        assertEquals(real.size, FileIntegrity.damageLines(real.joinToString("\n")).size)
    }

    @Test
    fun `S01E11 is clean - decoder noise that clean sources also print is not damage`() {
        val s01e11 = "[eac3 @ 0x557e3eaa0100] exponent -2 is out-of-range\n[eac3 @ 0x557e3eaa0100] exponent -2 is out-of-range\n"
        assertTrue(FileIntegrity.damageLines(s01e11).isEmpty())
    }

    @Test
    fun `the null muxer's timestamp complaint and ffmpeg's repeat counter are not damage`() {
        val out = """
            [null @ 0x5581] Application provided invalid, non monotonically increasing dts to muxer in stream 5: 1000 >= 1000
            [matroska,webm @ 0x5582] non monotonically increasing dts to muxer in stream 5
                Last message repeated 3 times
        """.trimIndent()
        assertTrue(FileIntegrity.damageLines(out).isEmpty())
    }

    @Test
    fun `empty output is clean`() = assertTrue(FileIntegrity.damageLines("\n  \n").isEmpty())

    @Test
    fun `the stored line drops ffmpeg's own address`() = assertEquals(
        "[matroska,webm] Unknown-sized element at 0x16c81 inside parent with finite size",
        FileIntegrity.displayLine("[matroska,webm @ 0x55fe3014d280] Unknown-sized element at 0x16c81 inside parent with finite size"),
    )

    // FR-254-3
    @Test
    fun `a stored result stops describing a file when its size or mtime changes`() {
        val stamp = FileStamp(size = 282_449_498, mtime = 1_757_770_000, device = 1, inode = 2)
        assertTrue(FileIntegrityService.isCurrent(282_449_498, 1_757_770_000, stamp))
        assertFalse(FileIntegrityService.isCurrent(282_449_497, 1_757_770_000, stamp))
        assertFalse(FileIntegrityService.isCurrent(282_449_498, 1_757_770_001, stamp))
    }

    // FR-254-10/12
    private val plan = FileRepairPlan(
        libraryPath = "/mnt/series/jellyfin/Bob's Show/Season 1/Bob.S01E01.mkv",
        sourcePath = "/mnt/series/cross-seed-links/X/Bob.S01/Bob.S01E01.mkv",
        quarantinePath = "/mnt/series/.js-quarantine/jellyfin/Bob's Show/Season 1/Bob.S01E01.mkv",
        libraryFlags = listOf(
            StreamFlags(0, "und", "", emptyList()),
            StreamFlags(1, "dan", "Dansk", listOf("default")),
            StreamFlags(2, "eng", "SDH", listOf("forced", "hearing_impaired")),
        ),
        // Phase 263 — the source holds the library's tracks 1 and 2 the other way round.
        pairs = listOf(TrackPair(0, 0, PairedBy.CONTENT), TrackPair(1, 2, PairedBy.CONTENT), TrackPair(2, 1, PairedBy.LABEL)),
    )

    @Test
    fun `a path containing a quote is quoted - never left to the shell`() {
        assertTrue("'/mnt/series/jellyfin/Bob'\\''s Show/Season 1/.jsreplace_Bob.S01E01.mkv'" in plan.copyCommand)
        assertFalse("Bob's Show" in plan.snippet)
    }

    @Test
    fun `every library flag is carried over - and a stream with none is cleared explicitly`() {
        assertTrue("-disposition:0 0 " in plan.copyCommand)
        assertTrue("-disposition:1 default " in plan.copyCommand)
        assertTrue("-disposition:2 forced+hearing_impaired " in plan.copyCommand)
        assertTrue("-metadata:s:1 'language=dan' -metadata:s:1 'title=Dansk'" in plan.copyCommand)
    }

    @Test
    fun `the source is only ever read`() {
        val src = "'${plan.sourcePath}'"
        val uses = Regex(Regex.escape(src)).findAll(plan.snippet).toList()
        assertEquals(2, uses.size)   // the copy, and the hash pass the new file is compared against
        assertTrue(uses.all { plan.snippet.substring(0, it.range.first).endsWith("-i ") })
        assertFalse(plan.sourcePath in plan.swapCommand)
    }

    // FR-263-4
    @Test
    fun `the source is mapped in the library's order - never -map 0 - and flags go by output position`() {
        assertTrue("-i '${plan.sourcePath}' -map 0:0 -map 0:2 -map 0:1 -c copy" in plan.copyCommand)
        assertFalse(Regex("-map 0 ").containsMatchIn(plan.copyCommand))
        assertTrue("-disposition:1 default -metadata:s:1 'language=dan'" in plan.copyCommand)
        assertTrue("-disposition:2 forced+hearing_impaired -metadata:s:2 'language=eng'" in plan.copyCommand)
    }

    // FR-263-5/7
    @Test
    fun `the new file is hashed against the source under the same maps - before the swap`() {
        assertTrue("-map 0:0 -map 0:2 -map 0:1 -c copy -f streamhash" in plan.sourceHashCommand)
        assertTrue("'${plan.tmpPath.replace("'", "'\\''")}' -map 0 -c copy -f streamhash" in plan.newHashCommand)
        val compare = plan.snippet.indexOf("= \"\$(${plan.newHashCommand}")
        assertTrue(compare > plan.snippet.indexOf(plan.sourceHashCommand))
        assertTrue(compare in 0 until plan.snippet.indexOf(plan.swapCommand))
    }

    // FR-263-8
    @Test
    fun `an identical group is proved identical across the whole file before the swap`() {
        val grouped = FileRepairPlan(
            "/l/a.mkv", "/s/a.mkv", "/q/a.mkv",
            listOf(StreamFlags(0, "und", "", emptyList()), StreamFlags(1, "dan", "", emptyList()), StreamFlags(2, "swe", "", emptyList())),
            listOf(TrackPair(0, 0, PairedBy.CONTENT), TrackPair(1, 2, PairedBy.IDENTICAL, identicalTo = listOf(1)), TrackPair(2, 1, PairedBy.IDENTICAL, identicalTo = listOf(2))),
        )
        assertEquals(listOf(listOf(1, 2)), grouped.identicalGroups)
        assertEquals(listOf(2, 1), grouped.positionsOf(listOf(1, 2)))
        val proof = grouped.snippet.indexOf("-i '/s/a.mkv' -map 0:1 -map 0:2 -c copy -f streamhash")
        assertTrue(proof in 0 until grouped.snippet.indexOf(grouped.swapCommand))
        assertTrue("| sort -u | wc -l)\" -eq 1 ]" in grouped.snippet)
        assertTrue(plan.identicalGroups.isEmpty())
    }

    @Test
    fun `a plan without one pair per library track in library order cannot be built`() {
        assertFailsWith<IllegalArgumentException> {
            FileRepairPlan("/l.mkv", "/s.mkv", "/q.mkv", listOf(StreamFlags(0, "und", "", emptyList()), StreamFlags(1, "dan", "", emptyList())), listOf(TrackPair(0, 0, PairedBy.CONTENT)))
        }
        assertFailsWith<IllegalArgumentException> {
            FileRepairPlan("/l.mkv", "/s.mkv", "/q.mkv", listOf(StreamFlags(0, "und", "", emptyList()), StreamFlags(1, "dan", "", emptyList())), listOf(TrackPair(1, 1, PairedBy.CONTENT), TrackPair(0, 0, PairedBy.CONTENT)))
        }
    }

    @Test
    fun `the damaged file is moved aside before the new one takes its name - and never over an existing quarantine file`() {
        val swap = plan.swapCommand
        val toQuarantine = swap.indexOf("mv '/mnt/series/jellyfin/Bob'\\''s Show/Season 1/Bob.S01E01.mkv'")
        val intoPlace = swap.indexOf("mv '/mnt/series/jellyfin/Bob'\\''s Show/Season 1/.jsreplace_")
        assertTrue(toQuarantine in 0 until intoPlace)
        assertTrue("[ ! -e '/mnt/series/.js-quarantine/" in swap)
    }
}
