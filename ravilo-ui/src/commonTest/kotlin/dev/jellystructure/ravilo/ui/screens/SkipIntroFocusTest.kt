package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.SkipMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R363 — the decisions behind *Skip Intro you can reach with the remote* (the walks are TV checks). */
class SkipIntroFocusTest {

    @Test fun `FR-1 — Up from the seek bar, Audio & Subs and Next reaches the visible pill`() {
        for (f in listOf(PlFocus.SEEK_BAR, PlFocus.TRACKS, PlFocus.NEXT_EP)) assertEquals(PlFocus.SKIP_INTRO, skipIntroUpTarget(f, pillVisible = true), "$f")
        for (f in listOf(PlFocus.SKIP_BACK, PlFocus.PLAY, PlFocus.SKIP_FWD)) assertEquals(PlFocus.SEEK_BAR, skipIntroUpTarget(f, pillVisible = true), "$f")
    }

    @Test fun `FR-1 — with the pill hidden, Up is today's target`() {
        for (f in listOf(PlFocus.SKIP_BACK, PlFocus.PLAY, PlFocus.SKIP_FWD, PlFocus.TRACKS, PlFocus.NEXT_EP)) assertEquals(PlFocus.SEEK_BAR, skipIntroUpTarget(f, pillVisible = false))
        assertNull(skipIntroUpTarget(PlFocus.SEEK_BAR, pillVisible = false), "Up on the seek bar does nothing")
        assertNull(skipIntroUpTarget(PlFocus.SKIP_INTRO, pillVisible = true), "Up on the pill does nothing")
    }

    @Test fun `FR-1 — Down from the pill goes back where it came from, Play when it took focus itself`() {
        assertEquals(PlFocus.TRACKS, skipIntroDownTarget(PlFocus.TRACKS))
        assertEquals(PlFocus.SEEK_BAR, skipIntroDownTarget(PlFocus.SEEK_BAR))
        assertEquals(PlFocus.PLAY, skipIntroDownTarget(null))
    }

    @Test fun `FR-1 — the pill is never in the Left-Right order`() {
        for (next in listOf(false, true)) assertFalse(PlFocus.SKIP_INTRO in transportOrder(next))
        assertEquals(listOf(PlFocus.SEEK_BAR, PlFocus.SKIP_BACK, PlFocus.PLAY, PlFocus.SKIP_FWD, PlFocus.TRACKS, PlFocus.NEXT_EP), transportOrder(true))
    }

    @Test fun `FR-2 — the pill grabs focus only when it appears on its own`() {
        assertTrue(skipIntroGrabsFocus(armingEdge = true, chromeVisible = false))
        assertFalse(skipIntroGrabsFocus(armingEdge = true, chromeVisible = true), "a rewind into the intro wakes the controls: no grab")
        assertFalse(skipIntroGrabsFocus(armingEdge = false, chromeVisible = false))
        assertFalse(skipIntroGrabsFocus(armingEdge = false, chromeVisible = true))
    }

    @Test fun `armed means inside the intro, a skip mode, and no other modal`() {
        assertTrue(skipIntroArmed(true, SkipMode.PROMPT, false, false, false))
        assertTrue(skipIntroArmed(true, SkipMode.AUTO, false, false, false))
        assertFalse(skipIntroArmed(false, SkipMode.PROMPT, false, false, false))
        assertFalse(skipIntroArmed(true, SkipMode.OFF, false, false, false))
        assertFalse(skipIntroArmed(true, SkipMode.PROMPT, true, false, false))
        assertFalse(skipIntroArmed(true, SkipMode.PROMPT, false, true, false))
        assertFalse(skipIntroArmed(true, SkipMode.PROMPT, false, false, true))
    }

    @Test fun `FR-3 — Up or OK waking hidden controls goes to the pill when it had focus or nothing else was touched`() {
        for (key in listOf(PlayerDpadKey.UP, PlayerDpadKey.SELECT)) {
            assertEquals(PlFocus.SKIP_INTRO, skipIntroWakeFocus(key, true, pillHadFocusWhenHidden = true, touchedOther = true))
            assertEquals(PlFocus.SKIP_INTRO, skipIntroWakeFocus(key, true, pillHadFocusWhenHidden = false, touchedOther = false))
            assertNull(skipIntroWakeFocus(key, true, pillHadFocusWhenHidden = false, touchedOther = true), "another control was used: the remembered control")
            assertNull(skipIntroWakeFocus(key, false, pillHadFocusWhenHidden = true, touchedOther = false), "outside the armed window")
        }
        for (key in listOf(PlayerDpadKey.LEFT, PlayerDpadKey.RIGHT, PlayerDpadKey.DOWN)) {
            assertNull(skipIntroWakeFocus(key, true, pillHadFocusWhenHidden = true, touchedOther = false), "$key keeps its own rule")
        }
    }

    @Test fun `FR-5 — the ring is drawn only in Auto`() {
        assertTrue(skipIntroShowsRing(SkipMode.AUTO))
        assertFalse(skipIntroShowsRing(SkipMode.PROMPT))
    }

    @Test fun `FR-6 — a credits card the viewer skipped or scrubbed into starts on Watch credits`() {
        assertEquals(NuFocus.STAY, nextUpStartsOn(broughtByViewer = true))
        assertEquals(NuFocus.PLAY, nextUpStartsOn(broughtByViewer = false))
    }
}
