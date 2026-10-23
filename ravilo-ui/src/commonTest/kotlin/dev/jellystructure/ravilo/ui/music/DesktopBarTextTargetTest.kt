package dev.jellystructure.ravilo.ui.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** DEB-2236 (FR-R337-6) — what the desktop bar's title and artist open. */
class DesktopBarTextTargetTest {
    @Test fun a_song_with_an_album_opens_that_album() = assertEquals("alb1", barTextAlbumId("alb1", isBook = false))
    @Test fun a_song_with_no_album_id_falls_back_to_playing() {
        assertNull(barTextAlbumId(null, isBook = false))
        assertNull(barTextAlbumId("", isBook = false))
    }
    @Test fun a_book_keeps_today_s_tap() = assertNull(barTextAlbumId("alb1", isBook = true))
}
