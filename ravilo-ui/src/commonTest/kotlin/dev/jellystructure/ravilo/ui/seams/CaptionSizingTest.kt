package dev.jellystructure.ravilo.ui.seams

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R300 (FR-R300-1, amended 2026-10-25) — captions are sized from a 16:9 frame fitted inside the window,
 * so the film's shape never changes them. The film's aspect is not an input, which is the point.
 */
class CaptionSizingTest {
    @Test fun a_tv_sizes_from_the_full_screen_height_whatever_the_film() =
        // 1920×1080: the same 1080 for 16:9, 2.40:1 and 4:3 alike, since the film is not an input.
        assertEquals(1080f, captionBaseHeight(1920f, 1080f))

    @Test fun landscape_phone_sizes_from_the_window_height() =
        // 2142×960 window: a 16:9 frame fills the height, and a 2.40:1 film gets the same 960.
        assertEquals(960f, captionBaseHeight(2142f, 960f))

    @Test fun portrait_phone_sizes_from_the_fitted_16x9_frame() =
        // 960×2142 window: the 16:9 frame is 960 wide and 540 tall, for any film, fill or no frame yet.
        assertEquals(540f, captionBaseHeight(960f, 2142f))
}
