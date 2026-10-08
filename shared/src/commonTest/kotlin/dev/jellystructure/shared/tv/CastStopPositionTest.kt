package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals

class CastStopPositionTest {
    @Test fun `an update with no media time keeps the real position`() {
        assertEquals(1_117_014L, castPositionAfterUpdate(1_117_014L, null))
    }

    @Test fun `an idle player's zero after a real position is not the viewer's place`() {
        assertEquals(1_117_014L, castPositionAfterUpdate(1_117_014L, 0L))
    }

    @Test fun `real times move it both ways`() {
        assertEquals(1_120_000L, castPositionAfterUpdate(1_117_014L, 1_120_000L))
        assertEquals(250L, castPositionAfterUpdate(1_117_014L, 250L))   // a seek to the start: the next update is real
    }

    @Test fun `before anything played a zero is the truth`() {
        assertEquals(0L, castPositionAfterUpdate(0L, 0L))
    }
}
