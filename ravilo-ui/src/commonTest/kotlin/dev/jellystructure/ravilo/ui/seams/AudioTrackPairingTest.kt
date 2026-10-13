package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.AudioTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * R382, amendment 2026-10-13 (FR-R382-9) — a row is its own ticket entry's track, whatever order the engine
 * reports its audio groups in. Both shapes here are the ones QA measured on the Pixel 9 (DEB-740), written as the
 * engine order Media3 produces for them: an audio group that fell to the FFmpeg extension renderer is listed after
 * every group a platform decoder took, because `Tracks.groups` is in renderer order.
 */
class AudioTrackPairingTest {

    private fun ticket(index: Int, lang: String?, label: String, codec: String?, channels: Int?, default: Boolean = false, copyOf: Int? = null) =
        AudioTrack(index = index, language = lang, label = label, codec = codec, channels = channels, isDefault = default, copyOf = copyOf)

    /** The rows' ticket positions, in row order — the list the picker's `flatIndex` indexes into. */
    private fun rows(engine: List<EngineAudioTrack>, ticket: List<AudioTrack>): List<Int?> =
        pairAudioToTicket(engine, ticket).map { it.ticketPosition }

    // ── FR-R382-9.1 — title A: six tracks, the English one only FFmpeg decodes ────────────────────────

    /** Jellyfin lists the embedded audio after seven external SRTs, so the stream numbers are 7..12; the ticket is
     *  still the container's own order (`ticketAudioTracks`), which is what the picker's rows must follow. */
    private val titleATicket = listOf(
        ticket(7, "eng", "English - DTS-HD MA - 5.1", "dts", 6, default = true),
        ticket(8, "dan", "Dansk - Dolby Digital - 5.1", "ac3", 6),
        ticket(9, "fin", "Suomi - Dolby Digital - 5.1", "ac3", 6),
        ticket(10, "nor", "Norsk - Dolby Digital - 5.1", "ac3", 6),
        ticket(11, "swe", "Svenska - Dolby Digital - 5.1", "ac3", 6),
        ticket(12, "eng", "English - Dolby Digital - 2.0", "ac3", 2),
    )

    /** Media3's order for it: the five AC-3 groups (MediaCodec) first, then the DTS group (FFmpeg, R379 puts that
     *  renderer last) — and the second English track is AC-3, so it sits among them, not beside its own language. */
    private val titleAEngine = listOf(
        EngineAudioTrack("da", "Dansk", 6, "audio/ac3"),
        EngineAudioTrack("fi", "Suomi", 6, "audio/ac3"),
        EngineAudioTrack("no", "Norsk", 6, "audio/ac3"),
        EngineAudioTrack("sv", "Svenska", 6, "audio/ac3"),
        EngineAudioTrack("en", "English", 2, "audio/ac3"),
        EngineAudioTrack("en", "English", 6, "audio/vnd.dts"),
    )

    @Test
    fun `every row of the reported six-track title is its own ticket entry`() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5), rows(titleAEngine, titleATicket))
    }

    @Test
    fun `the Norwegian row plays the Norwegian group and not the Swedish one`() {
        val paired = pairAudioToTicket(titleAEngine, titleATicket)
        val norsk = paired.first { it.ticketPosition == 3 }
        // Engine position 2 is the group whose own language is `no`. Before the fix this row was engine position 3.
        assertEquals(2, norsk.enginePosition)
        assertEquals("no", titleAEngine[norsk.enginePosition].language)
    }

    @Test
    fun `the row order the old positional zip produced is the off-by-one that was measured`() {
        // What the defect did, kept as the thing this must never do again: row i took engine group i, so the row
        // labelled `Dansk` (ticket 1) played the Finnish group and `Norsk` (ticket 3) played the Swedish one.
        assertEquals("fi", titleAEngine[1].language)
        assertEquals("sv", titleAEngine[3].language)
        val paired = pairAudioToTicket(titleAEngine, titleATicket)
        assertEquals(1, paired.first { it.enginePosition == 0 }.ticketPosition)   // the `da` group is row 1, Dansk
        assertEquals(2, paired.first { it.enginePosition == 1 }.ticketPosition)   // the `fi` group is row 2, Suomi
        assertEquals(3, paired.first { it.enginePosition == 2 }.ticketPosition)   // the `no` group is row 3, Norsk
        assertEquals(4, paired.first { it.enginePosition == 3 }.ticketPosition)   // the `sv` group is row 4, Svenska
    }

    // ── FR-R382-9.2 — title B: two `eng` tracks, TrueHD and AC-3 ─────────────────────────────────────

    @Test
    fun `two same-language tracks pair by shape when the engine reports them the other way round`() {
        val ticket = listOf(
            ticket(4, "eng", "English - TrueHD - 7.1", "truehd", 8, default = true),
            ticket(5, "eng", "English - Dolby Digital - 5.1", "ac3", 6),
        )
        // AC-3 goes to MediaCodec, TrueHD to the FFmpeg renderer, which media3 lists after it.
        val engine = listOf(
            EngineAudioTrack("en", "Surround 5.1", 6, "audio/ac3"),
            EngineAudioTrack("en", "TrueHD Atmos 7.1", 8, "audio/true-hd"),
        )
        val paired = pairAudioToTicket(engine, ticket)
        assertEquals(listOf(0, 1), paired.map { it.ticketPosition })
        assertEquals(1, paired[0].enginePosition)   // row 0 — the TrueHD ticket entry — is the TrueHD group
        assertEquals(0, paired[1].enginePosition)   // row 1 — the AC-3 ticket entry — is the AC-3 group
    }

    @Test
    fun `an e-ac3 and an ac3 track of the same language and layout are not swapped by the loose codec match`() {
        // `eac3`.contains(`ac3`), which is why the exact pass runs first; this is the case that pass protects.
        val ticket = listOf(
            ticket(1, "eng", "English - Dolby Digital - 5.1", "ac3", 6),
            ticket(2, "eng", "English - Dolby Digital Plus - 5.1", "eac3", 6),
        )
        val engine = listOf(
            EngineAudioTrack("en", null, 6, "audio/eac3"),
            EngineAudioTrack("en", null, 6, "audio/ac3"),
        )
        val paired = pairAudioToTicket(engine, ticket)
        assertEquals(1, paired[0].enginePosition)   // row 0 is `ac3`: engine group 1
        assertEquals(0, paired[1].enginePosition)   // row 1 is `eac3`: engine group 0
    }

    // ── FR-R382-9.3 — a composed master: R291's `a{n}` name wins ─────────────────────────────────────

    @Test
    fun `a rendition name decides the row even when every rendition shares a language`() {
        val ticket = listOf(
            ticket(1, "eng", "English - TrueHD - 7.1", "truehd", 8, default = true),
            ticket(2, "eng", "English - Dolby Digital - 5.1", "ac3", 6),
            ticket(3, "dan", "Dansk - AAC - 2.0", "aac", 2),
        )
        // What a player reports for `composeMaster`'s master: every rendition is AAC/unknown shape, the muxed one
        // first and in no particular ticket order, so the name is the only identity there is.
        val engine = listOf(
            EngineAudioTrack("en", "a1 English - Dolby Digital - 5.1", null, null),
            EngineAudioTrack("da", "a2 Dansk - AAC - 2.0", null, null),
            EngineAudioTrack("en", "a0 English - TrueHD - 7.1", null, null),
        )
        val paired = pairAudioToTicket(engine, ticket)
        assertEquals(listOf(0, 1, 2), paired.map { it.ticketPosition })
        assertEquals(2, paired[0].enginePosition)   // row 0 is the group named `a0`
        assertEquals(0, paired[1].enginePosition)
        assertEquals(1, paired[2].enginePosition)
    }

    // ── FR-R382-9.4 — the common case is unchanged ───────────────────────────────────────────────────

    @Test
    fun `an engine list that already agrees with the ticket pairs row i to group i`() {
        val ticket = listOf(
            ticket(1, "eng", "English", "ac3", 6, default = true),
            ticket(2, "dan", "Dansk", "ac3", 6),
            ticket(3, "fao", "Føroyskt", "aac", 2),
        )
        val engine = listOf(
            EngineAudioTrack("en", "English", 6, "audio/ac3"),
            EngineAudioTrack("da", "Dansk", 6, "audio/ac3"),
            EngineAudioTrack("fo", "Føroyskt", 2, "audio/mp4a-latm"),
        )
        val paired = pairAudioToTicket(engine, ticket)
        assertEquals(listOf(0, 1, 2), paired.map { it.ticketPosition })
        assertEquals(listOf(0, 1, 2), paired.map { it.enginePosition })
    }

    @Test
    fun `a 639-2 B code and a 639-2 T code are the same language`() {
        // R382's own 2026-10-09 fix, from the other side of the wire: `fre` (B) vs `fra` (T).
        val paired = pairAudioToTicket(
            listOf(EngineAudioTrack("fr", "Français", 6, "audio/ac3"), EngineAudioTrack("en", "English", 6, "audio/ac3")),
            listOf(ticket(1, "eng", "English", "ac3", 6), ticket(2, "fre", "Français", "ac3", 6)),
        )
        assertEquals(1, paired[0].enginePosition)   // row 0 — `eng` — is the English group
        assertEquals(0, paired[1].enginePosition)   // row 1 — `fre` — is the `fr` group
    }

    // ── FR-R382-9.5 — degenerate shapes ─────────────────────────────────────────────────────────────

    @Test
    fun `fewer engine groups than ticket entries invents no row`() {
        // R284's single-audio session: the stream carries one track and the ticket lists three. The rows stay the
        // engine's, so `sessionAudioTracks` still decides what the picker shows.
        val ticket = listOf(
            ticket(1, "eng", "English", "ac3", 6, default = true),
            ticket(2, "dan", "Dansk", "ac3", 6),
            ticket(3, "nor", "Norsk", "ac3", 6),
        )
        val paired = pairAudioToTicket(listOf(EngineAudioTrack("no", "Norsk", 6, "audio/aac")), ticket)
        assertEquals(1, paired.size)
        assertEquals(2, paired[0].ticketPosition)
    }

    @Test
    fun `a group no ticket entry claims keeps its own metadata and sorts last`() {
        val ticket = listOf(ticket(1, "eng", "English", "ac3", 6, default = true))
        val engine = listOf(
            EngineAudioTrack("de", "Kommentar", 2, "audio/ac3"),
            EngineAudioTrack("en", "English", 6, "audio/ac3"),
        )
        val paired = pairAudioToTicket(engine, ticket)
        assertEquals(listOf(0, null), paired.map { it.ticketPosition })
        assertEquals(1, paired[0].enginePosition)
        assertEquals(0, paired[1].enginePosition)
    }

    @Test
    fun `an empty ticket leaves the engine's own list as it is`() {
        val engine = listOf(EngineAudioTrack("en", "English", 6, "audio/ac3"), EngineAudioTrack("da", "Dansk", 6, "audio/ac3"))
        val paired = pairAudioToTicket(engine, emptyList())
        assertEquals(listOf(0, 1), paired.map { it.enginePosition })
        assertNull(paired[0].ticketPosition)
        assertNull(paired[1].ticketPosition)
    }

    @Test
    fun `a 314b copy is paired by its own shape so the fold finds its source`() {
        // Phase 314b: jellystructure added an E-AC-3 Surround copy of the TrueHD track. The copy is last in the
        // ticket; Media3 lists it FIRST, because the TrueHD original is the one that fell to FFmpeg.
        val ticket = listOf(
            ticket(1, "eng", "English - TrueHD - 7.1", "truehd", 8, default = true),
            ticket(2, "dan", "Dansk - Dolby Digital - 5.1", "ac3", 6),
            ticket(3, "eng", "English - Dolby Digital Plus - 5.1", "eac3", 6, copyOf = 1),
        )
        val engine = listOf(
            EngineAudioTrack("en", "English - Dolby Digital Plus - 5.1", 6, "audio/eac3"),
            EngineAudioTrack("da", "Dansk - Dolby Digital - 5.1", 6, "audio/ac3"),
            EngineAudioTrack("en", "English - TrueHD - 7.1", 8, "audio/true-hd"),
        )
        val paired = pairAudioToTicket(engine, ticket)
        assertEquals(listOf(0, 1, 2), paired.map { it.ticketPosition })
        assertEquals(2, paired[0].enginePosition)   // row 0 — the TrueHD source
        assertEquals(1, paired[1].enginePosition)
        assertEquals(0, paired[2].enginePosition)   // row 2 — its copy, where 314b folds it onto row 0
    }
}
