package dev.jellystructure.tv

import dev.jellystructure.shared.tv.RaviloConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 202 (FR-202-2/FR-202-3) — the two pure resolution rules a client is never allowed to
 * re-implement: which of the two directions wins, and what an out-of-range delay reads back as.
 */
class FocusDetailConfigTest {

    @Test
    fun `defaults resolve to rowOpen since the 2026-09-13 reflow measurement closed invariant 11`() {
        assertEquals("rowOpen", RaviloConfig().resolvedFocusDetail())
    }

    @Test
    fun `row-open wins over line regardless of the line switch`() {
        assertEquals(
            "rowOpen",
            RaviloConfig(focusDetailLine = true, focusDetailRowOpen = true).resolvedFocusDetail(),
        )
        assertEquals(
            "rowOpen",
            RaviloConfig(focusDetailLine = false, focusDetailRowOpen = true).resolvedFocusDetail(),
        )
    }

    @Test
    fun `both off resolves to none`() {
        assertEquals(
            "none",
            RaviloConfig(focusDetailLine = false, focusDetailRowOpen = false).resolvedFocusDetail(),
        )
    }

    @Test
    fun `delay accepts any non-negative value untouched incl 0 and past the old 600 ceiling`() {
        assertEquals(0, clampFocusDetailDelayMs(0))
        assertEquals(137, clampFocusDetailDelayMs(137))
        assertEquals(600, clampFocusDetailDelayMs(600))
        assertEquals(10000, clampFocusDetailDelayMs(10000))
    }

    @Test
    fun `a negative delay reads back as the 170 default`() {
        assertEquals(170, clampFocusDetailDelayMs(-1))
    }
}
