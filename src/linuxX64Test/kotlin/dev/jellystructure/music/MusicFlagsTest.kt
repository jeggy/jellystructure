package dev.jellystructure.music

import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicCredit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 283 — the two flags on the shapes a real library showed, and on the look-alikes that must stay quiet. */
class MusicFlagsTest {
    private val root = "/music"
    private val roots = setOf(root)

    private fun album(id: String, folder: String, title: String, artist: String, rg: String? = null) = MusicAlbum(
        id = id, title = title, path = "$root/$folder", albumArtists = listOf(MusicCredit("ar-" + MusicFlags.key(artist), artist)),
        releaseGroupMbid = rg,
    )

    // Shape 1: a band's single folders whose files all name one B-sides compilation, matched to it.
    private val singles = listOf(
        album("s1", "The Owls/First Light (Single)", "Odds & Ends: B-Sides (1998-2007)", "The Owls", rg = "rg-odds"),
        album("s2", "The Owls/Night Swim (EP)", "Odds & Ends: B-Sides (1998-2007)", "THE OWLS", rg = "rg-odds"),
        album("s3", "The Owls/Harbour (Single)", "Odds & Ends: B-Sides (1998-2007)", "The Owls", rg = "rg-odds"),
    )
    // Shape 2: the folder names the single, the tag names an EP MusicBrainz doesn't know by that name.
    private val renamed = album("r", "The Owls/Lantern - Paper Boats (Single)", "Lantern EP", "The Owls")
    // Shape 3: a band's song in a various-artists compilation folder, tagged as the band's own album.
    private val stray = album("v", "Various Artists/Summer Hits (2002)", "Misc Songs", "The Owls")

    // Look-alikes that agree.
    private val quiet = listOf(
        album("q1", "The Owls/Grey Coast (2004)", "Grey Coast", "The Owls", rg = "rg-grey"),
        album("q2", "The Owls/Grey Coast (2004)/CD2", "Grey Coast", "The Owls", rg = "rg-grey2"),
        album("q3", "Various Artists/Road Songs (2002)", "Road Songs", "Various Artists", rg = "rg-road"),
        album("q4", "Tavi/Tavi - Blue Hour [FLAC]", "Blue Hour", "Tavi"),
        album("q5", "Other Band/Greatest Hits (2003)", "Greatest Hits", "Other Band"),
        album("q6", "Third Band/Greatest Hits (1999)", "Greatest Hits", "Third Band"),
        album("q7", "Loose Album", "Loose Album", "Someone"),
    )

    private val all = singles + renamed + stray + quiet

    @Test fun several_folders_one_album_flags_every_folder_and_names_the_others() {
        val flags = MusicFlags.of(all, roots)
        for (s in singles) {
            val f = flags[s.id].orEmpty().first { it.kind == MusicFlags.SHARED }
            assertEquals("3 folders say they are this album", f.sentence)
            assertEquals(singles.map { it.id }.filter { it != s.id }.toSet(), f.others.map { it.id }.toSet())
        }
        assertTrue(flags.getValue("s1").any { it.kind == MusicFlags.FOLDER }, "a single folder named after a single, tagged as the compilation")
        assertEquals("First Light", flags.getValue("s1").first { it.kind == MusicFlags.FOLDER }.search)
    }

    @Test fun a_folder_named_after_what_the_tags_are_not_is_flagged_with_its_own_name_to_search() {
        val f = MusicFlags.of(all, roots).getValue("r").single()
        assertEquals(MusicFlags.FOLDER, f.kind)
        assertEquals("Lantern - Paper Boats", f.search)
    }

    @Test fun a_song_in_another_artists_folder_is_flagged() {
        val f = MusicFlags.of(all, roots).getValue("v").single()
        assertEquals(MusicFlags.FOLDER, f.kind)
        assertEquals("Various Artists", f.folderArtist)
        assertEquals("The Owls", f.filesArtist)
    }

    @Test fun folders_that_agree_are_quiet() {
        val flags = MusicFlags.of(all, roots)
        for (q in quiet) assertNull(flags[q.id], "${q.path} should not be flagged")
        assertEquals(5, flags.size, "three singles, the renamed single, the stray song — and nothing else")
    }

    @Test fun cleaning_a_folder_name_removes_what_only_folders_carry() {
        assertEquals("First Light", MusicFlags.cleanFolder("First Light (Single)", "The Owls"))
        assertEquals("Blue Hour", MusicFlags.cleanFolder("Tavi - Blue Hour [FLAC]", "Tavi"))
        assertEquals("Grey Coast", MusicFlags.cleanFolder("2004 - Grey Coast", "The Owls"))
        assertEquals(MusicFlags.Folders("Grey Coast (2004)", "The Owls"), MusicFlags.folders("$root/The Owls/Grey Coast (2004)/CD2", roots))
        assertEquals(MusicFlags.Folders("Loose Album", null), MusicFlags.folders("$root/Loose Album", roots))
    }

    @Test fun this_is_right_holds_until_what_it_was_said_for_changes() {
        val fp = MusicFlags.fingerprint(MusicFlags.FOLDER, renamed, emptyList(), roots)
        val dismissed = renamed.copy(flagsDismissed = mapOf(MusicFlags.FOLDER to fp))
        assertNull(MusicFlags.of(all - renamed + dismissed, roots)["r"])
        val renamedAgain = dismissed.copy(path = "$root/The Owls/Lantern (Single)", title = "Paper Boats")
        assertEquals(MusicFlags.FOLDER, MusicFlags.of(all - renamed + renamedAgain, roots).getValue("r").single().kind)

        val s1 = singles[0]
        val sharedFp = MusicFlags.fingerprint(MusicFlags.SHARED, s1, singles.drop(1), roots)
        val quietS1 = s1.copy(flagsDismissed = mapOf(MusicFlags.SHARED to sharedFp))
        assertTrue(MusicFlags.of(all - s1 + quietS1, roots)["s1"].orEmpty().none { it.kind == MusicFlags.SHARED })
        val fourth = album("s4", "The Owls/Tide (Single)", "Odds & Ends: B-Sides (1998-2007)", "The Owls", rg = "rg-odds")
        assertTrue(MusicFlags.of(all - s1 + quietS1 + fourth, roots).getValue("s1").any { it.kind == MusicFlags.SHARED }, "a new folder brings it back")
    }
}
