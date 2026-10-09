package dev.jellystructure.ravilo.ui.components

import dev.jellystructure.shared.tv.CastLoadData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** R266 / R380 — every LOAD says who casts (the viewer) and from which device (*Playing from {device}* on the TV). */
class CastLoadSenderTest {
    private val base = CastLoadData(serverUrl = "https://media.example.test", code = "ABC123", itemId = "i1", title = "A song",
        deviceName = "Living Room TV")

    @Test fun `a LOAD gets the viewer and this device's name, and the receiver's device_name is untouched`() {
        val out = castLoadFromSender(base, "u-anna", "Pixel 9 Pro")
        assertEquals("u-anna", out.userId)
        assertEquals("Pixel 9 Pro", out.senderName)
        assertEquals("Living Room TV", out.deviceName)
    }

    @Test fun `a LOAD that already names them keeps its own`() {
        val out = castLoadFromSender(base.copy(userId = "u-bo", senderName = "MacBook"), "u-anna", "Pixel 9 Pro")
        assertEquals("u-bo", out.userId)
        assertEquals("MacBook", out.senderName)
    }

    @Test fun `no name or a blank one sends none`() {
        assertNull(castLoadFromSender(base, "u-anna", null).senderName)
        assertNull(castLoadFromSender(base, "u-anna", "  ").senderName)
    }
}
