package dev.jellystructure.ravilo.ui.seams

import kotlin.test.Test
import kotlin.test.assertEquals

/** R300 (FR-R300-1) — captions are sized from the picture's height, not the window's. Pixel 9 numbers. */
class CaptionSizingTest {
    private val dar16x9 = 16f / 9f

    @Test fun portrait_uses_the_letterboxed_pictures_height() =
        // 960×2142 window, 16:9 picture → the picture is 960 wide and 540 tall; captions size from 540.
        assertEquals(540f, captionBaseHeight(960f, 2142f, dar16x9, fill = false))

    @Test fun landscape_is_unchanged_from_the_window() =
        // 2142×960 window, 16:9 picture → the picture fills the height.
        assertEquals(960f, captionBaseHeight(2142f, 960f, dar16x9, fill = false))

    @Test fun fill_mode_covers_the_window_height() = assertEquals(2142f, captionBaseHeight(960f, 2142f, dar16x9, fill = true))
    @Test fun an_unknown_aspect_falls_back_to_the_window() = assertEquals(2142f, captionBaseHeight(960f, 2142f, 0f, fill = false))
    @Test fun a_tv_is_unchanged() = assertEquals(1080f, captionBaseHeight(1920f, 1080f, dar16x9, fill = false))
    @Test fun cinemascope_on_a_tv_sizes_from_the_narrower_picture() =
        assertEquals(1920f / 2.35f, captionBaseHeight(1920f, 1080f, 2.35f, fill = false), 0.01f)
}
