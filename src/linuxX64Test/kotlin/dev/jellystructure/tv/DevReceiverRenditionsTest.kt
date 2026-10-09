package dev.jellystructure.tv

import dev.jellystructure.shared.tv.ClientCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/** R291 (2026-10-09) — the dev-only `receiver_renditions` switch touches only a cast receiver, and only when on. */
class DevReceiverRenditionsTest {
    private val caps = ClientCapabilities(hlsOnly = true)

    @Test fun offLeavesTheReceiverAlone() = assertFalse(withDevReceiverRenditions("cast", caps, enabled = false).hlsAudioRenditions)

    @Test fun onDeclaresRenditionsForAReceiver() = assertTrue(withDevReceiverRenditions("cast", caps, enabled = true).hlsAudioRenditions)

    @Test fun onNeverTouchesOtherDevices() {
        assertEquals(caps, withDevReceiverRenditions("tv", caps, enabled = true))
        assertEquals(caps, withDevReceiverRenditions("phone", caps, enabled = true))
        assertEquals(caps, withDevReceiverRenditions(null, caps, enabled = true))
    }
}
