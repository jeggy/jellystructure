package dev.jellystructure.ravilo.ui.apps

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-4912 (FR-14) — a hold on Select is the way to the pin menu, since the Streamer's remote has no Menu key. */
class EnterPressTest {
    @Test fun aShortPressSelectsOnKeyUp() {
        var t = 0L
        val p = EnterPress { t }
        assertFalse(p.down())
        t += 90
        assertTrue(p.up())
    }

    @Test fun aHoldIsLongOnTheFirstRepeatAndNeverSelects() {
        var t = 0L
        val p = EnterPress { t }
        assertFalse(p.down())
        t += 500
        assertTrue(p.down())
        t += 50
        assertFalse(p.down())
        t += 50
        assertFalse(p.up())
    }

    @Test fun aLostKeyUpDoesNotTurnTheNextClickIntoALongPress() {
        var t = 0L
        val p = EnterPress { t }
        p.down()
        t += 5_000
        assertFalse(p.down())
        t += 80
        assertTrue(p.up())
    }

    @Test fun theOpeningPressesRepeatsAndKeyUpNeverReachTheMenu() {
        var t = 0L
        val g = OpeningPressGuard({ t }, armed = true)
        t += 50; assertTrue(g.swallow(isDown = true))
        t += 50; assertTrue(g.swallow(isDown = true))
        t += 60; assertTrue(g.swallow(isDown = false))
        t += 300; assertFalse(g.swallow(isDown = true))
    }

    @Test fun aFreshPressAfterAQuickReleaseIsNotSwallowed() {
        var t = 0L
        val g = OpeningPressGuard({ t }, armed = true)
        t += 2_000
        assertFalse(g.swallow(isDown = true))
    }

    @Test fun aMenuOpenedByTheMenuKeyIsNeverGuarded() {
        val g = OpeningPressGuard({ 0L }, armed = false)
        assertFalse(g.swallow(isDown = true))
        assertFalse(g.swallow(isDown = false))
    }
}

/** DEB-4912 — Down on a short last row. */
class TileBelowTest {
    @Test fun straightDownWhenThereIsATileThere() = kotlin.test.assertEquals(8, tileBelow(2, 20))
    @Test fun theLastTileWhenTheRowBelowIsShort() = kotlin.test.assertEquals(7, tileBelow(5, 8))
    @Test fun noMoveOnTheLastRow() = kotlin.test.assertEquals(7, tileBelow(7, 8))
}
