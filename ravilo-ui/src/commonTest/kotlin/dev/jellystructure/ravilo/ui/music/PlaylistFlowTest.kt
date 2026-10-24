package dev.jellystructure.ravilo.ui.music

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import dev.jellystructure.shared.tv.MusicPlaylist
import dev.jellystructure.shared.tv.MusicPlaylistAdded
import dev.jellystructure.shared.tv.MusicTrackItem
import dev.jellystructure.shared.tv.TvApiError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-2254 (FR-3, FR-4, FR-6, FR-7, FR-13) — what the picker offers and what the toast says. Stand-in names only. */
class PlaylistFlowTest {
    private fun pl(id: String, name: String, mine: Boolean, n: Int = 3) = MusicPlaylist(id, name, n, emptyList(), mine = mine)

    @Test fun the_picker_offers_the_viewers_own_playlists_only_by_name() {
        val all = listOf(pl("a", "road trip", true), pl("b", "Shared Mix", false), pl("c", "Late Night", true), pl("d", "Running", true))
        assertEquals(listOf("Late Night", "road trip", "Running"), pickerPlaylists(all).map { it.name })
    }

    @Test fun with_none_of_their_own_the_picker_has_nothing_after_new_playlist() {
        assertEquals(emptyList(), pickerPlaylists(listOf(pl("b", "Shared Mix", false))))
        assertEquals(emptyList(), pickerPlaylists(emptyList()))
    }

    @Test fun a_song_added_says_added_to() {
        assertEquals(ToastText("music.pl_added", mapOf("name" to "Stand-in Mix")), addedToast(PlaylistSource.SONG, "Stand-in Mix", MusicPlaylistAdded(1, 0)))
    }

    @Test fun nothing_added_says_already_in_whatever_the_source() {
        for (s in listOf(PlaylistSource.SONG, PlaylistSource.ALBUM, PlaylistSource.QUEUE)) {
            assertEquals(ToastText("music.pl_already", mapOf("name" to "Stand-in Mix")), addedToast(s, "Stand-in Mix", MusicPlaylistAdded(0, 10)))
        }
    }

    @Test fun an_album_says_how_many_songs_went_in_and_one_song_says_added_to() {
        assertEquals(ToastText("music.pl_added_n", mapOf("n" to "10", "name" to "Stand-in Mix")), addedToast(PlaylistSource.ALBUM, "Stand-in Mix", MusicPlaylistAdded(10, 0)))
        // 4 of 5 held already: the toast counts what went in, not what was asked.
        assertEquals(ToastText("music.pl_added", mapOf("name" to "Stand-in Mix")), addedToast(PlaylistSource.ALBUM, "Stand-in Mix", MusicPlaylistAdded(1, 4)))
    }

    @Test fun a_new_playlist_says_what_it_was_made_for() {
        val made = pl("n", "Stand-in Mix", true, n = 7)
        assertEquals(ToastText("music.pl_created", mapOf("name" to "Stand-in Mix")), createdToast(PlaylistSource.NOTHING, made, 0))
        assertEquals(ToastText("music.pl_saved_n", mapOf("name" to "Stand-in Mix", "n" to "7")), createdToast(PlaylistSource.QUEUE, made, 9))
        assertEquals(ToastText("music.pl_added_n", mapOf("name" to "Stand-in Mix", "n" to "7")), createdToast(PlaylistSource.ALBUM, made, 10))
        assertEquals(ToastText("music.pl_added", mapOf("name" to "Stand-in Mix")), createdToast(PlaylistSource.SONG, made.copy(trackCount = 1), 1))
        // A server that answers no count falls back to what was sent.
        assertEquals(ToastText("music.pl_saved_n", mapOf("name" to "Stand-in Mix", "n" to "9")), createdToast(PlaylistSource.QUEUE, made.copy(trackCount = 0), 9))
    }

    @Test fun save_queue_takes_the_playing_song_then_up_next_in_order() {
        fun t(id: String) = MusicTrackItem(id, id)
        assertEquals(listOf("p", "u1", "u2"), queueToSave(t("p"), listOf(t("u1"), t("u2"))).map { it.id })
        assertEquals(listOf("p"), queueToSave(t("p"), emptyList()).map { it.id })
    }

    @Test fun a_blank_name_is_not_offered_and_only_a_400_is_a_name_problem() {
        assertFalse(playlistNameReady("")); assertFalse(playlistNameReady("   "))
        assertTrue(playlistNameReady(" a "))
        assertTrue(isNameRefusal(TvApiError.Http(400, "bad")))
        assertFalse(isNameRefusal(TvApiError.Http(403, "no")))
        assertFalse(isNameRefusal(TvApiError.Http(502, "down")))
        assertFalse(isNameRefusal(IllegalStateException("offline")))
    }

    // The desktop's popover (FR-4, round direction 2).
    private val window = IntSize(1120, 720)
    private val popover = IntSize(312, 300)

    @Test fun the_popover_opens_under_its_dots() {
        val dots = IntRect(660, 100, 690, 130)
        assertEquals(IntOffset(648, 136), popoverPosition(dots, window, popover, gap = 6, edge = 12))
    }

    @Test fun a_dots_low_in_the_window_open_it_upward() {
        val dots = IntRect(660, 560, 690, 590)
        val at = popoverPosition(dots, window, popover, gap = 6, edge = 12)
        assertEquals(560 - 6 - 300, at.y)
    }

    @Test fun the_popover_stays_inside_the_window_sideways() {
        val dots = IntRect(1090, 100, 1112, 130)
        assertEquals(1120 - 312 - 12, popoverPosition(dots, window, popover, gap = 6, edge = 12).x)
        assertEquals(12, popoverPosition(IntRect(0, 100, 20, 130), window, popover, gap = 6, edge = 12).x)
    }

    @Test fun without_an_anchor_it_is_centred() {
        assertEquals(IntOffset((1120 - 312) / 2, (720 - 300) / 2), popoverPosition(null, window, popover, gap = 6, edge = 12))
    }
}
