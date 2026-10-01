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

    // ── R352 (FR-R352-1) — the title takes its space first ──

    /** Three named + nothing, two + "+1", one + "+2", "+3": widths a desktop row measures. */
    private val groups = listOf(3 to 150, 2 to 110, 1 to 70, 0 to 24)

    @Test
    fun a_short_title_keeps_its_width_and_every_chip_that_fits() {
        assertEquals(VersionFit(3, 80), fitVersions(80, 8, groups, 300))
        assertEquals(VersionFit(2, 80), fitVersions(80, 8, groups, 200), "three do not fit beside it: two and +1")
        assertEquals(VersionFit(0, 80), fitVersions(80, 8, groups, 120), "only +3 fits beside the whole title")
    }

    @Test
    fun a_long_title_truncates_and_the_chips_keep_at_most_their_share() {
        // The 300 dp queue panel: about 180 dp for title + chips, the title alone wider than that.
        val fit = fitVersions(400, 8, groups, 180)
        assertEquals(0, fit.shown, "40 % of 180 is 72: +3 alone (24 + 8) fits, one chip (70 + 8) does not")
        assertEquals(180 - 8 - 24, fit.titleWidth)
        assertEquals(VersionFit(2, 300 - 8 - 110), fitVersions(600, 8, groups, 300), "a wide row keeps two named chips and +1")
    }

    @Test
    fun the_title_is_never_left_with_nothing_while_it_fits_beside_plus_n() {
        // The bug: the chips were measured first and the title got 0 px.
        val fit = fitVersions(90, 8, groups, 130)
        assertTrue(fit.titleWidth == 90)
    }
}
