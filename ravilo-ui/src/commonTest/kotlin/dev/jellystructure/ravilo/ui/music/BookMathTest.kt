package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.shared.tv.AudiobookChapterItem
import dev.jellystructure.shared.tv.AudiobookDetail
import dev.jellystructure.shared.tv.AudiobookPartItem
import kotlin.test.Test
import kotlin.test.assertEquals

/** R323 — book time is the parts end to end; a place in the book maps to one part and one offset, and back. */
class BookMathTest {
    private val min = 60_000L
    private val d = AudiobookDetail(
        id = "b", title = "Bogen",
        parts = listOf(AudiobookPartItem("p0", 0, 20 * min), AudiobookPartItem("p1", 1, 4 * min), AudiobookPartItem("p2", 2, 30 * min)),
        chapters = listOf(AudiobookChapterItem("", 0, 20 * min, 0, 0), AudiobookChapterItem("", 20 * min, 4 * min, 1, 0), AudiobookChapterItem("Slutningen", 24 * min, 30 * min, 2, 0)),
    )

    @Test fun book_time_is_the_parts_before_plus_the_place_in_this_one() {
        assertEquals(54 * min, BookMath.length(d))
        assertEquals(22 * min, BookMath.bookPosition(d, 1, 2 * min))
        assertEquals(24 * min, BookMath.partStart(d, 2))
    }

    @Test fun a_place_in_the_book_is_one_part_and_an_offset_clamped_to_the_book() {
        assertEquals(1 to 2 * min, BookMath.locate(d, 22 * min))
        assertEquals(2 to 0L, BookMath.locate(d, 24 * min), "a boundary belongs to the next part")
        assertEquals(0 to 0L, BookMath.locate(d, -5_000L))
        assertEquals(2 to 30 * min, BookMath.locate(d, 99 * min))
    }

    @Test fun chapters_are_found_by_book_time() {
        assertEquals(0, BookMath.chapterAt(d, 0))
        assertEquals(1, BookMath.chapterAt(d, 23 * min))
        assertEquals(2, BookMath.chapterAt(d, 50 * min))
        assertEquals(24 * min, BookMath.chapterEnd(d, 1))
        assertEquals(54 * min, BookMath.chapterEnd(d, 2))
    }

    @Test fun the_speed_chip_says_one_or_two_decimals() {
        assertEquals("1.0", speedText(1.0)); assertEquals("1.75", speedText(1.75)); assertEquals("0.8", speedText(0.8)); assertEquals("2.0", speedText(2.0))
    }
}
