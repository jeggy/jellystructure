package dev.jellystructure.ravilo.ui.focus

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R350 (FR-R350-17) — when a computer draws its focus ring. */
class KeyboardModeTest {
    @Test fun navigationKeysTurnItOn() {
        for (k in listOf(Key.Tab, Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.Enter, Key.NumPadEnter, Key.Escape)) {
            val m = KeyboardMode()
            m.onKey(k)
            assertTrue(m.on, "$k")
        }
        val typing = KeyboardMode()
        typing.onKey(Key.A); typing.onKey(Key.Spacebar)
        assertFalse(typing.on, "typing a letter is not navigating")
    }

    @Test fun aMoveThatDoesNotMoveKeepsIt() {
        val m = KeyboardMode()
        m.onPointer(PointerEventType.Move, Offset(100f, 100f))
        m.onKey(Key.Tab)
        // The layout under the resting pointer changed (the player, then the page): Compose Desktop's synthetic moves.
        m.onPointer(PointerEventType.Move, Offset(100f, 100f))
        m.onPointer(PointerEventType.Move, Offset(100.5f, 100f))
        assertTrue(m.on)
    }

    @Test fun aRealMoveOrAPressTurnsItOff() {
        val moved = KeyboardMode()
        moved.onPointer(PointerEventType.Move, Offset(100f, 100f))
        moved.onKey(Key.Tab)
        moved.onPointer(PointerEventType.Move, Offset(110f, 100f))
        assertFalse(moved.on)

        val pressed = KeyboardMode()
        pressed.onPointer(PointerEventType.Move, Offset(100f, 100f))
        pressed.onKey(Key.Tab)
        pressed.onPointer(PointerEventType.Press, Offset(100f, 100f))
        assertFalse(pressed.on, "a click is pointer use wherever it lands")
    }

    @Test fun theFirstPointerEventOnlyRecordsWhereThePointerIs() {
        val m = KeyboardMode()
        m.onKey(Key.DirectionDown)
        m.onPointer(PointerEventType.Move, Offset(500f, 300f))
        assertTrue(m.on, "nothing to compare with yet")
        m.onPointer(PointerEventType.Enter, Offset(10f, 10f))
        assertTrue(m.on, "entering the window is not using it")
        m.onPointer(PointerEventType.Move, Offset(60f, 10f))
        assertFalse(m.on)
    }
}
