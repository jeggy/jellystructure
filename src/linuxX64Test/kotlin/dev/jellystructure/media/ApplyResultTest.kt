package dev.jellystructure.media

import kotlin.test.Test
import kotlin.test.assertEquals

/** DEB-2275 (FR-11 amendment) — the message produced by an ApplyResult after applyChanges(). */
class ApplyResultTest {

    private fun result(
        failed: Boolean = false,
        refused: Boolean = false,
        othersApplied: Boolean = false,
        reorderQueued: Boolean = false,
    ) = ApplyResult(failed, refused, othersApplied, reorderQueued)

    @Test fun `refusal only produces no message`() {
        assertEquals(null, result(refused = true).messageOf())
    }

    @Test fun `refusal with other applied changes shows applied`() {
        assertEquals("Changes applied", result(refused = true, othersApplied = true).messageOf())
    }

    @Test fun `refusal with reorder queued shows reorder message`() {
        assertEquals("Changes applied — reorder queued, see Activity ▸ Jobs", result(refused = true, othersApplied = true, reorderQueued = true).messageOf())
    }

    @Test fun `refusal with failure shows failure`() {
        assertEquals("Some changes failed — see details", result(failed = true, refused = true).messageOf())
    }

    @Test fun `normal path with no refusal shows applied`() {
        assertEquals("Changes applied", result().messageOf())
        assertEquals("Changes applied", result(othersApplied = true).messageOf())
    }

    @Test fun `normal path with reorder queued shows reorder message`() {
        assertEquals("Changes applied — reorder queued, see Activity ▸ Jobs", result(reorderQueued = true).messageOf())
    }

    @Test fun `failure without refusal shows failure`() {
        assertEquals("Some changes failed — see details", result(failed = true).messageOf())
    }

    @Test fun `nothing applied and nothing refused produces no message`() {
        assertEquals(null, result().messageOf())
    }
}
