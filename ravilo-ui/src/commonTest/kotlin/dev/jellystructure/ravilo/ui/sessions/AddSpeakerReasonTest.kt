package dev.jellystructure.ravilo.ui.sessions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** R330 (found live 2026-10-09, evening, Mac) — why *Add a speaker…* is greyed says what is true on this device. */
class AddSpeakerReasonTest {
    @Test fun `offered means no line`() {
        assertNull(addSpeakerBlockedReason(canAdd = true, groupsHere = false))
        assertNull(addSpeakerBlockedReason(canAdd = true, groupsHere = true))
    }

    @Test fun `a device that cannot group says a phone nearby would — never that nothing reaches the speakers`() {
        assertEquals("group.needs_phone", addSpeakerBlockedReason(canAdd = false, groupsHere = false))
    }

    @Test fun `a device that can group but finds no road keeps the reach line`() {
        assertEquals("group.no_reach", addSpeakerBlockedReason(canAdd = false, groupsHere = true))
    }
}
