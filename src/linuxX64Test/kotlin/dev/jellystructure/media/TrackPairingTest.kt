package dev.jellystructure.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Phase 263 — which source track each library track is, decided by the packets the tracks carry.
 *  The layouts are the measured ones (2026-09-26, the damaged episodes of *Hoppe Hares Byggebande*). */
class TrackPairingTest {

    private fun shape(index: Int, type: String, codec: String, lang: String = "und", filename: String = "") =
        StreamShape(StreamFlags(index, lang, "", emptyList()), type, codec, filename)

    /** Library: the operator put Danish first. Source: release order, English first. */
    private val library = listOf(
        shape(0, "video", "hevc"), shape(1, "audio", "eac3", "dan"), shape(2, "audio", "eac3", "swe"), shape(3, "audio", "eac3", "nor"),
        shape(4, "audio", "eac3", "eng"), shape(5, "audio", "eac3", "fin"), shape(6, "subtitle", "subrip", "swe"),
    )
    private val source = listOf(
        shape(0, "video", "hevc"), shape(1, "audio", "eac3", "eng"), shape(2, "audio", "eac3", "dan"), shape(3, "audio", "eac3", "fin"),
        shape(4, "audio", "eac3", "nor"), shape(5, "audio", "eac3", "swe"), shape(6, "subtitle", "subrip", "swe"),
    )

    /** A track's packets: [n] distinct ones named after [track], plus any [shared] ones. */
    private fun track(track: String, n: Int, shared: List<String> = emptyList(), drop: Int = 0): Map<String, Int> =
        ((drop until n).map { "$track-$it" } + shared).groupingBy { it }.eachCount()

    // Three byte-identical frames between two dubs — what S01E19 measured between English and Finnish.
    private val quiet = listOf("quiet-0", "quiet-1", "quiet-2")
    private val sourcePackets = mapOf(
        0 to track("video", 2880), 1 to track("eng", 3750, quiet), 2 to track("dan", 3750), 3 to track("fin", 3750, quiet),
        4 to track("nor", 3750), 5 to track("swe", 3750), 6 to track("sub", 22),
    )
    // The damaged library copy: the same tracks in its own order, a few packets lost where the damage is.
    private val libraryPackets = mapOf(
        0 to track("video", 2880, drop = 1), 1 to track("dan", 3750), 2 to track("swe", 3750), 3 to track("nor", 3750, drop = 45),
        4 to track("eng", 3750, quiet), 5 to track("fin", 3750, quiet), 6 to track("sub", 22),
    )

    private fun paired(r: TrackPairing): List<TrackPair> = assertIs<TrackPairing.Paired>(r, (r as? TrackPairing.Refused)?.reason).pairs
    private fun refused(r: TrackPairing): String = assertIs<TrackPairing.Refused>(r).reason

    // Acceptance 1
    @Test
    fun `the measured layout pairs every track with its own - out of order - by content`() {
        val pairs = paired(TrackPairer.pair(library, source, libraryPackets, sourcePackets))
        assertEquals(listOf(0 to 0, 1 to 2, 2 to 5, 3 to 4, 4 to 1, 5 to 3, 6 to 6), pairs.map { it.libraryIndex to it.sourceIndex })
        assertTrue(pairs.all { it.by == PairedBy.CONTENT })
        assertEquals(3750 - 45, pairs[3].matched)
        assertEquals(3750 - 45, pairs[3].window)
    }

    @Test
    fun `a relabelled track is found by what it carries - not by what it is called`() {
        // The release called its Danish track English; the operator fixed the label in the library.
        val mislabelledSource = source.map { if (it.index == 2) shape(2, "audio", "eac3", "eng") else it }
        val pairs = paired(TrackPairer.pair(library, mislabelledSource, libraryPackets, sourcePackets))
        assertEquals(2, pairs[1].sourceIndex)
    }

    @Test
    fun `a codec never pairs with a different codec whatever its packets look like`() {
        val otherCodec = source.map { if (it.index == 2) shape(2, "audio", "ac3", "dan") else it }
        assertTrue("Track 1 (audio, dan)" in refused(TrackPairer.pair(library, otherCodec, libraryPackets, sourcePackets)))
    }

    // Acceptance 2 — every refusal names the track
    @Test
    fun `a track whose packets match no source track refuses`() {
        val lost = libraryPackets + (1 to track("dan", 3750, drop = 2000) + track("garbled", 2000))   // most of its window damaged
        val strange = libraryPackets + (1 to track("elsewhere", 3750))
        assertTrue(refused(TrackPairer.pair(library, source, lost, sourcePackets)).startsWith("Track 1 (audio, dan) matches no track of the source"))
        assertTrue(refused(TrackPairer.pair(library, source, strange, sourcePackets)).startsWith("Track 1 (audio, dan) matches no track of the source"))
    }

    @Test
    fun `two library tracks claiming one source track refuse`() {
        val twice = libraryPackets + (2 to track("dan", 3750))
        assertEquals("Track 2 (audio, swe) and track 1 both match source track 2", refused(TrackPairer.pair(library, source, twice, sourcePackets)))
    }

    @Test
    fun `a close runner-up refuses rather than guesses`() {
        // What the library copy kept of its Danish track is mostly a stretch two different source tracks share.
        val shared = (0 until 3000).map { "shared-$it" }
        val src = sourcePackets + (1 to track("eng", 750, shared)) + (2 to track("dan", 700, shared))
        val lib = libraryPackets + (1 to track("shared", 3000) + track("garbled", 50))
        assertEquals(
            "Track 1 (audio, dan) can't be told apart: source tracks 2 and 1 both hold its first two minutes",
            refused(TrackPairer.pair(library, source, lib, src)),
        )
    }

    // Measured 2026-09-26 on a film with four Portuguese subtitle tracks: a forced track's cues are a
    // subset of the full track's, so a plain count finds the forced track's packets in both.
    @Test
    fun `a forced subtitle pairs with the forced track - not the full one that contains its cues`() {
        val lib = listOf(shape(0, "subtitle", "subrip", "por"), shape(1, "subtitle", "subrip", "por"))
        val src = listOf(shape(0, "subtitle", "subrip", "por"), shape(1, "subtitle", "subrip", "por"))
        val forced = (0 until 17).map { "cue-$it" }
        val full = forced + (17 until 27).map { "cue-$it" }
        val pairs = paired(TrackPairer.pair(lib, src, mapOf(0 to track("x", 0, full), 1 to track("x", 0, forced)), mapOf(0 to track("x", 0, forced), 1 to track("x", 0, full))))
        assertEquals(listOf(0 to 1, 1 to 0), pairs.map { it.libraryIndex to it.sourceIndex })
    }

    // Measured 2026-09-26: one title's release muxes one audio track three times, labelled swe, dan and
    // nor — identical `streamhash`es over the whole file, 28 damaged episodes.
    @Test
    fun `tracks identical in the window are one group - the label chooses within it`() {
        val lib = listOf(shape(0, "video", "h264"), shape(1, "audio", "aac", "dan"), shape(2, "audio", "aac", "swe"), shape(3, "audio", "aac", "nor"), shape(4, "audio", "aac", "fin"))
        val src = listOf(shape(0, "video", "h264"), shape(1, "audio", "aac", "swe"), shape(2, "audio", "aac", "dan"), shape(3, "audio", "aac", "nor"), shape(4, "audio", "aac", "fin"))
        val same = track("nordic", 5626)
        val srcP = mapOf(0 to track("video", 3002), 1 to same, 2 to same, 3 to same, 4 to track("fin", 5626))
        val libP = mapOf(0 to track("video", 3002), 1 to same, 2 to same, 3 to same, 4 to track("fin", 5626))
        val pairs = paired(TrackPairer.pair(lib, src, libP, srcP))
        assertEquals(listOf(0 to 0, 1 to 2, 2 to 1, 3 to 3, 4 to 4), pairs.map { it.libraryIndex to it.sourceIndex })
        assertEquals(listOf(PairedBy.CONTENT, PairedBy.IDENTICAL, PairedBy.IDENTICAL, PairedBy.IDENTICAL, PairedBy.CONTENT), pairs.map { it.by })
        assertEquals(listOf(1, 3), pairs[1].identicalTo)
        assertEquals(listOf(listOf(1, 2, 3)), TrackPairer.identicalGroups(pairs))
        // …and the goal check does not call a position wrong for holding a byte-identical twin.
        assertEquals(emptyList(), TrackPairer.positionsNotHolding(libP, srcP))
    }

    @Test
    fun `more of our tracks than identical source tracks refuses`() {
        val lib = listOf(shape(0, "audio", "aac", "dan"), shape(1, "audio", "aac", "swe"), shape(2, "audio", "aac", "nor"))
        val src = listOf(shape(0, "audio", "aac", "swe"), shape(1, "audio", "aac", "dan"), shape(2, "audio", "aac", "nor"))
        val same = track("nordic", 100)
        val reason = refused(TrackPairer.pair(lib, src, mapOf(0 to same, 1 to same, 2 to same), mapOf(0 to same, 1 to same, 2 to track("nor", 100))))
        assertTrue(reason.endsWith("all match source tracks 0, 1, which are identical in the first two minutes, and there are more of ours than theirs"), reason)
    }

    @Test
    fun `a different number of tracks refuses`() = assertEquals(
        "The source has a different number of tracks (6 vs 7)",
        refused(TrackPairer.pair(library, source.dropLast(1), libraryPackets, sourcePackets)),
    )

    // FR-263-2
    @Test
    fun `a track with nothing in the window falls back to its label - when exactly one source track fits`() {
        val noCueYet = libraryPackets - 6
        val pairs = paired(TrackPairer.pair(library, source, noCueYet, sourcePackets - 6))
        assertEquals(TrackPair(6, 6, PairedBy.LABEL), pairs[6])
    }

    @Test
    fun `an ambiguous label fallback refuses`() {
        val twoSwedish = library + shape(7, "subtitle", "subrip", "swe")
        val sourceTwo = source + shape(7, "subtitle", "subrip", "swe")
        assertEquals(
            "Track 6 (subtitle, swe) has nothing in the first two minutes to compare, and 2 source tracks (6, 7) carry its label",
            refused(TrackPairer.pair(twoSwedish, sourceTwo, libraryPackets - 6, sourcePackets - 6)),
        )
    }

    @Test
    fun `an attachment pairs by its file name`() {
        val lib = library + shape(7, "attachment", "ttf", filename = "b.ttf") + shape(8, "attachment", "ttf", filename = "a.ttf")
        val src = source + shape(7, "attachment", "ttf", filename = "a.ttf") + shape(8, "attachment", "ttf", filename = "b.ttf")
        val pairs = paired(TrackPairer.pair(lib, src, libraryPackets, sourcePackets))
        assertEquals(listOf(7 to 8, 8 to 7), pairs.drop(7).map { it.libraryIndex to it.sourceIndex })
    }

    // FR-263-5 check 2
    @Test
    fun `the goal check reads no pairing - a new file in the source's order fails at every moved position`() {
        val inSourceOrder = sourcePackets   // what 254's `-map 0` wrote
        assertEquals(listOf(1, 2, 3, 4, 5), TrackPairer.positionsNotHolding(libraryPackets, inSourceOrder))
        val inLibraryOrder = mapOf(0 to 0, 1 to 2, 2 to 5, 3 to 4, 4 to 1, 5 to 3, 6 to 6).mapValues { (_, src) -> sourcePackets.getValue(src) }
        assertEquals(emptyList(), TrackPairer.positionsNotHolding(libraryPackets, inLibraryOrder))
    }

    @Test
    fun `framemd5 output parses to per-stream packet counts - headers skipped`() {
        val out = """
            #format: frame checksums
            #version: 2
            #hash: MD5
            #extradata 0,                             114, c82847f6868587147800a4d2c59bf542
            #tb 1: 1/1000
            #media_type 1: audio
            #stream#, dts,        pts, duration,     size, hash
            1,          0,          0,       32,      896, 74e439634ec917607516ec9361273a64
            1,         32,         32,       32,      896, ce67cdffb4a2dd45903d854b75f6cac1
            1,         64,         64,       32,      896, ce67cdffb4a2dd45903d854b75f6cac1
            6,      26526,      26526,     3504,       23, 898e3b176deaa1802b2df2f086df0944
        """.trimIndent()
        val p = TrackPairer.packets(out)
        assertEquals(setOf(1, 6), p.keys)
        assertEquals(2, p.getValue(1)["896:ce67cdffb4a2dd45903d854b75f6cac1"])
        assertEquals(1, p.getValue(6)["23:898e3b176deaa1802b2df2f086df0944"])
    }

    @Test
    fun `streamhash output parses - and the pass's own error lines are not hashes`() {
        val out = """
            [matroska,webm @ 0x5615141da040] Element at 0xc0b7c0 ending at 0x2a9881f9d exceeds containing master element ending at 0xd77244
            0,v,MD5=f305d9f033d7be2008eab4798b34b80f
            1,a,MD5=4c4114c8d22c419cba1ac493bd0233d8
            6,s,MD5=462e305d833188d6ccde3ec22b5490dd
            7,t,MD5=d41d8cd98f00b204e9800998ecf8427e
        """.trimIndent()
        assertEquals(
            mapOf(0 to "MD5=f305d9f033d7be2008eab4798b34b80f", 1 to "MD5=4c4114c8d22c419cba1ac493bd0233d8", 6 to "MD5=462e305d833188d6ccde3ec22b5490dd", 7 to "MD5=d41d8cd98f00b204e9800998ecf8427e"),
            TrackPairer.streamHashes(out),
        )
        assertEquals(1, FileIntegrity.damageLines(out).size)
    }
}
