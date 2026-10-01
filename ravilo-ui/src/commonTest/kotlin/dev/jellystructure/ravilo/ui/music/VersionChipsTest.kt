package dev.jellystructure.ravilo.ui.music

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import dev.jellystructure.ravilo.ui.theme.AuroraColors
import dev.jellystructure.ravilo.ui.theme.DaylightColors
import dev.jellystructure.ravilo.ui.theme.GraphiteColors
import dev.jellystructure.ravilo.ui.theme.MidnightColors
import dev.jellystructure.ravilo.ui.theme.NoirColors
import dev.jellystructure.ravilo.ui.theme.RaviloColors
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R344 — the chip group's rules: the fold, unknown keys, the colours, and contrast in every theme. */
class VersionChipsTest {
    @Test
    fun a_phone_folds_at_two_and_the_desktop_at_three() {
        // Acceptance 2 — *Northbound (acoustic, alternate take, radio session)*.
        val keys = listOf("live", "acoustic", "alternate", "session")
        assertEquals(VersionFold(listOf("live", "acoustic"), 2, keys), foldVersions(keys, VERSION_FOLD_PHONE))
        assertEquals(VersionFold(listOf("live", "acoustic", "alternate"), 1, keys), foldVersions(keys, VERSION_FOLD_DESKTOP))
        assertEquals(VersionFold(keys, 0, keys), foldVersions(keys, Int.MAX_VALUE), "Now playing shows them all")
    }

    @Test
    fun two_fit_without_a_more_chip() {
        val f = foldVersions(listOf("remix", "edit"), VERSION_FOLD_PHONE)
        assertEquals(listOf("remix", "edit"), f.shown)
        assertEquals(0, f.more)
    }

    @Test
    fun no_version_draws_nothing() {
        assertEquals(VersionFold(emptyList(), 0, emptyList()), foldVersions(emptyList(), VERSION_FOLD_PHONE))
    }

    @Test
    fun an_unknown_key_draws_nothing_and_is_not_counted() {
        val f = foldVersions(listOf("live", "karaoke", "remix", "edit"), VERSION_FOLD_PHONE)
        assertEquals(listOf("live", "remix"), f.shown)
        assertEquals(1, f.more, "karaoke is a key from a newer server: no chip and no +1")
        assertEquals(listOf("live", "remix", "edit"), f.all)
        assertEquals(VersionFold(emptyList(), 0, emptyList()), foldVersions(listOf("karaoke"), VERSION_FOLD_PHONE))
    }

    @Test
    fun session_alone_stays_session() {
        // Owner decision 1 — a Session whose Live the owner removed arrives as `session` alone; the phone adds nothing.
        assertEquals(listOf("session"), foldVersions(listOf("session"), Int.MAX_VALUE).shown)
    }

    @Test
    fun colours_parse_or_fall_back() {
        assertEquals(Color(0xFFF0795B), parseVersionColor("#f0795b"))
        assertEquals(Color(0xFFF0795B), parseVersionColor("F0795B"))
        assertEquals(Color(0xFFFF0000), parseVersionColor("#f00"))
        assertNull(parseVersionColor("red"))
        assertNull(parseVersionColor(""))
        assertNull(parseVersionColor(null))
        assertEquals(9, MusicVersions.DEFAULTS.size)
        assertEquals(Color(0xFF2DD49A), MusicVersions.colorOf("cover", emptyMap()), "no household colour: the default")
        assertEquals(Color.Red, MusicVersions.colorOf("cover", mapOf("cover" to Color.Red)))
        assertNull(MusicVersions.colorOf("karaoke", emptyMap()))
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble() + 0.05
        val lb = b.luminance().toDouble() + 0.05
        return max(la, lb) / min(la, lb)
    }

    private fun worstContrast(theme: RaviloColors): Double = MusicVersions.DEFAULTS.values.minOf { c ->
        val ink = versionInk(c, theme.text, theme.isLight)
        listOf(theme.background, theme.card, theme.surface).minOf { ground ->
            contrast(ink, versionTint(c).compositeOver(ground))
        }
    }

    @Test
    fun every_chip_reads_at_4_5_to_1_in_daylight() {
        // Acceptance 5 — the 58 % mix left Cover at ~4.3 : 1 on the page; 45 % clears it.
        val worst = worstContrast(DaylightColors)
        assertTrue(worst >= 4.5, "Daylight worst chip contrast $worst")
    }

    @Test
    fun every_chip_reads_in_the_dark_themes() {
        for (t in listOf(AuroraColors, MidnightColors, NoirColors, GraphiteColors)) {
            val worst = worstContrast(t)
            assertTrue(worst >= 4.5, "worst chip contrast $worst")
        }
    }
}
