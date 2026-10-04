package dev.jellystructure.music

import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicArtist
import dev.jellystructure.model.MusicCredit
import dev.jellystructure.model.MusicExtraOrigin
import dev.jellystructure.model.MusicMatch
import dev.jellystructure.model.MusicMbCredit
import dev.jellystructure.model.MusicOfficialList
import dev.jellystructure.model.MusicOfficialSong
import dev.jellystructure.model.MusicRecording
import dev.jellystructure.model.MusicRecordingFacts
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.model.MusicVersionTarget

/**
 * Phase 305 — the design's stand-ins as a library: Harbour Lights' *Kite Weather* (11 + *Lantern Swing*, the Japanese
 * CD), its five singles with 13 B-sides, *Signal Found* (14 + 12 extras from the 20th Anniversary), the box set
 * *Tide Tables 1999–2012*, the compilation *Nordic Nights Vol. 2*, two singles on no album, three EPs, and a
 * *Lighthouse Keepers* soundtrack single. Nothing real.
 */
object EditionsFixture {
    const val LIB = "lib"
    val HL = MusicCredit("hl", "Harbour Lights")
    val LK = MusicCredit("lk", "Lighthouse Keepers")
    val VA = MusicCredit("va", "Various Artists")
    val MB_HL = MusicMbCredit("mb-hl", "Harbour Lights")
    val MB_LK = MusicMbCredit("mb-lk", "Lighthouse Keepers")

    val KW_TITLES = listOf("Kite Weather", "Northern Line", "Fog Bank", "Salt on the Window", "Low Tide", "Tidewater",
        "Harbour Song", "Grey Gulls", "Paper Boats", "Slow Ferry", "Last Light")

    fun kwOfficial() = MusicOfficialList(
        songs = KW_TITLES.mapIndexed { i, t -> MusicOfficialSong("kw-r${i + 1}", t, 260_000L + i * 1000, 1, i + 1) },
        releaseMbid = "kw-rel-gb", releaseTitle = "Kite Weather", k = 7, m = 9, groupMbid = "rg-kw", groupTitle = "Kite Weather", media = 1,
    )

    fun album(id: String, title: String, year: Int, primary: String = "Album", secondary: List<String> = emptyList(), rg: String? = "rg-$id",
              official: MusicOfficialList? = null, singleFrom: String? = null, credit: MusicCredit = HL, mb: MusicMbCredit? = MB_HL,
              origins: Map<String, MusicExtraOrigin> = emptyMap()) = MusicAlbum(
        id = id, libraryId = LIB, title = title, year = year, albumArtists = listOf(credit),
        matchState = if (rg != null) MusicMatch.MATCHED else MusicMatch.UNMATCHED, releaseGroupMbid = rg, releaseMbid = rg?.let { "$it-rel" },
        primaryType = if (rg != null) primary else null, secondaryTypes = secondary, firstReleaseDate = "$year",
        mbArtists = listOfNotNull(mb), official = official, singleFrom = singleFrom, extraOrigins = origins, relsReadAt = 1,
    )

    fun track(id: String, albumId: String, pos: Int, title: String, rec: String?, state: String? = MusicRecording.AGREES,
              bitrate: Int = 320_000, codec: String = "mp3", credit: MusicCredit = HL, added: Long = 100, disc: Int = 1) = MusicTrack(
        id = id, albumId = albumId, libraryId = LIB, title = title, disc = disc, position = pos, durationMs = 240_000L + pos * 1000,
        path = "/music/$id.$codec", container = codec, codec = codec, bitrate = bitrate, artists = listOf(credit),
        recordingMbid = rec, recordingState = rec?.let { state }, mbArtists = if (credit == HL) listOf(MB_HL) else emptyList(), addedAt = added,
    )

    data class Lib(val artists: List<MusicArtist>, val albums: List<MusicAlbum>, val tracks: List<MusicTrack>, val facts: List<MusicRecordingFacts>) {
        fun snapshot(
            sameSong: Map<Pair<String, String>, String> = emptyMap(), sound: List<MusicSoundPair> = emptyList(),
            homes: Map<String, MusicSingleHome.Override> = emptyMap(),
        ) = MusicStore.Snapshot(
            artists.associateBy { it.id }, albums.associateBy { it.id }, tracks.associateBy { it.id }, facts.associateBy { it.recordingMbid },
            homeOverrides = homes, sameSong = sameSong, soundPairs = sound,
        )
        fun rows() = MusicLibraryRows(LIB, artists, albums, tracks)
    }

    /** The whole stand-in library. */
    fun library(): Lib {
        val albums = ArrayList<MusicAlbum>()
        val tracks = ArrayList<MusicTrack>()
        // Kite Weather (2006): 11 official + Lantern Swing, first on the Japanese CD.
        albums += album("kw", "Kite Weather", 2006, rg = "rg-kw", official = kwOfficial(),
            origins = mapOf("kw-r12" to MusicExtraOrigin("kw-rel-jp", "Kite Weather", "2006-03-08", "JP", null, "CD")))
        KW_TITLES.forEachIndexed { i, t -> tracks += track("kw-${i + 1}", "kw", i + 1, t, "kw-r${i + 1}") }
        tracks += track("kw-12", "kw", 12, "Lantern Swing", "kw-r12")

        // Its five singles: three by MusicBrainz, one by title, one via a remix — 3 + 3 + 3 + 2 + 2 = 13 B-sides.
        fun single(id: String, title: String, year: Int, singleFrom: String?, aSide: Pair<String, String?>, bsides: List<String>) {
            albums += album(id, title, year, primary = "Single", singleFrom = singleFrom)
            tracks += track("$id-1", id, 1, aSide.first, aSide.second, bitrate = 256_000)
            bsides.forEachIndexed { i, b -> tracks += track("$id-${i + 2}", id, i + 2, b, "$id-b${i + 2}", bitrate = 256_000) }
        }
        single("s-nl", "Northern Line", 2005, "rg-kw", "Northern Line" to "kw-r2", listOf("Night Bus", "Ticket Hall", "Platform Nine"))
        single("s-fb", "Fog Bank", 2006, "rg-kw", "Fog Bank" to "kw-r3", listOf("Foghorn", "Mist Line", "Coastguard"))
        // A fourth Fog Bank track is Lantern Swing again: it folds into the extra, so it is no B-side.
        tracks += track("s-fb-5", "s-fb", 5, "Lantern Swing", "kw-r12", bitrate = 256_000)
        single("s-salt", "Salt on the Window", 2006, "rg-kw", "Salt on the Window" to "kw-r4", listOf("Brine", "Window Seat", "Sea Glass"))
        single("s-lt", "Low Tide", 2007, null, "Low Tide (radio edit)" to "lt-edit", listOf("Ebb", "Mudflats"))
        single("s-tw", "Tidewater", 2007, null, "Tidewater (Harbour Remix)" to "tw-remix", listOf("Undertow", "Breakwater"))
        val facts = listOf(MusicRecordingFacts("tw-remix", remix = true, remixOf = MusicVersionTarget("kw-r6", "Tidewater")))

        // Signal Found (2003): 14 official + 12 extras from the 20th Anniversary edition.
        albums += album("sf", "Signal Found", 2003, official = MusicOfficialList(
            (1..14).map { MusicOfficialSong("sf-r$it", "Signal $it", 200_000, 1, it) }, "sf-rel", "Signal Found", 2, 4, "rg-sf", "Signal Found"),
            origins = (15..26).associate { "sf-r$it" to MusicExtraOrigin("sf-rel-20", "Signal Found 20th Anniversary", "2003-05-01", "GB") })
        (1..26).forEach { tracks += track("sf-$it", "sf", it, "Signal $it", "sf-r$it") }

        // The box set and the compilation holding Northern Line again.
        albums += album("tt", "Tide Tables 1999–2012", 2012, secondary = listOf("Compilation"))
        tracks += track("tt-1", "tt", 1, "Northern Line", "kw-r2", bitrate = 256_000)
        albums += album("nn", "Nordic Nights Vol. 2", 2008, secondary = listOf("Compilation"), credit = VA, mb = null)
        tracks += track("nn-1", "nn", 1, "Northern Line", null, bitrate = 192_000)

        // On no album: two singles, three EPs, and a single from another artist's soundtrack.
        single("s-hr", "Small Hours Radio", 2010, null, "Small Hours Radio" to "hr-1", emptyList())
        single("s-bc", "Beacon", 2011, null, "Beacon" to "bc-1", emptyList())
        for ((i, e) in listOf("Shoreline EP", "Harbour Sessions", "Winter Ferry").withIndex()) {
            albums += album("ep-$i", e, 2008 + i, primary = "EP")
            tracks += track("ep-$i-1", "ep-$i", 1, if (i == 0) "Low Tide" else "$e Song", "ep-$i-r1")
        }
        albums += album("lk-st", "Lighthouse Keepers OST", 2009, secondary = listOf("Soundtrack"), credit = LK, mb = MB_LK,
            official = MusicOfficialList(listOf(MusicOfficialSong("st-r1", "Kite Weather Theme", 100_000, 1, 1)), "st-rel", "Lighthouse Keepers OST", 1, 1, "rg-lk-st", "Lighthouse Keepers OST"))
        single("s-st", "Kite Weather Theme", 2009, "rg-lk-st", "Kite Weather Theme" to "st-r1", emptyList())

        val artists = listOf(
            MusicArtist(id = "hl", libraryId = LIB, name = "Harbour Lights", path = "/music/Harbour Lights", mbid = "mb-hl"),
            MusicArtist(id = "lk", libraryId = LIB, name = "Lighthouse Keepers", path = "/music/Lighthouse Keepers", mbid = "mb-lk"),
            MusicArtist(id = "va", libraryId = LIB, name = "Various Artists"),
        )
        return Lib(artists, albums, tracks, facts)
    }
}
