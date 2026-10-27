package dev.jellystructure.ravilo.ui.components

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

/** DEB-2596 FR-1. The ramp indices were computed by running the design's JS loop in Node, not this Kotlin. */
class ProfileFaceTest {
    @Test
    fun `one word gives one letter`() {
        assertEquals("J", profileInitials("jeggy"))
        assertEquals("E", profileInitials("Eyð"))
        assertEquals("2", profileInitials("2pac"))
        assertEquals("S", profileInitials("straße"))
    }

    @Test
    fun `two or more words give first of first and first of last`() {
        assertEquals("AH", profileInitials("Anna Hansen"))
        assertEquals("JH", profileInitials("Jens Peter Hansen"))
        assertEquals("AH", profileInitials("  Anna   Hansen  "))
        assertEquals("RD", profileInitials("R2 D2"))
        assertEquals("ÁB", profileInitials("ánna berg"))
    }

    @Test
    fun `a word with nothing left is dropped`() {
        assertEquals("A", profileInitials("Anna 🎬"))
    }

    @Test
    fun `letters are uppercased per character`() {
        assertEquals("Ø", profileInitials("øli"))
    }

    @Test
    fun `no letter or digit at all gives a question mark`() {
        assertEquals("?", profileInitials(""))
        assertEquals("?", profileInitials("🎬🍿"))
        assertEquals("?", profileInitials("--- !!!"))
    }

    @Test
    fun `ramp index matches the design's hash`() {
        assertEquals(0, profileRampIndex(""))
        assertEquals(1, profileRampIndex("a"))
        assertEquals(2, profileRampIndex("b"))
        assertEquals(3, profileRampIndex("c"))
        assertEquals(4, profileRampIndex("d"))
        assertEquals(5, profileRampIndex("e"))
    }

    @Test
    fun `ramp index survives hashes past 2 to the 31`() {
        assertEquals(4, profileRampIndex("0123456789abcdef0123456789abcdef"))
        assertEquals(3, profileRampIndex("4f1c2a9be7d34c0f8a6b5e2d1c3f9a7b"))
    }

    @Test
    fun `the ramp has six pairs starting with the design's first`() {
        assertEquals(6, ProfileRamp.size)
        assertEquals(Color(0xFF7B6EF0) to Color(0xFF3FB6F5), ProfileRamp[0])
    }
}
