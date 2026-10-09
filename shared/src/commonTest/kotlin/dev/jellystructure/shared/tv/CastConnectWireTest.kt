package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R266 — the two wire additions: the casting viewer on a LOAD, and the launch observation on the status route. */
class CastConnectWireTest {
    private val json = RaviloWireJsonWithDefaults

    @Test
    fun `the casting viewer rides the LOAD as user_id and an older LOAD reads as no viewer`() {
        val load = CastLoadData(serverUrl = "https://media.example.test", code = "ABC123", itemId = "i1", title = "A film", userId = "u-anna")
        val text = json.encodeToString(CastLoadData.serializer(), load)
        assertTrue(""""user_id":"u-anna"""" in text, text)
        assertEquals("u-anna", json.decodeFromString(CastLoadData.serializer(), text).userId)
        val older = """{"server_url":"https://media.example.test","code":"ABC123","item_id":"i1","title":"A film"}"""
        assertNull(json.decodeFromString(CastLoadData.serializer(), older).userId)
    }

    @Test
    fun `the sender's device name rides the LOAD as sender_name, beside the receiver's device_name, and an older LOAD has none`() {
        val load = CastLoadData(serverUrl = "https://media.example.test", code = "ABC123", itemId = "i1", title = "A song",
            deviceName = "Living Room TV", senderName = "Pixel 9 Pro")
        val text = json.encodeToString(CastLoadData.serializer(), load)
        assertTrue(""""sender_name":"Pixel 9 Pro"""" in text, text)
        assertTrue(""""device_name":"Living Room TV"""" in text, "the receiver's own device_name is untouched: $text")
        val back = json.decodeFromString(CastLoadData.serializer(), text)
        assertEquals("Pixel 9 Pro", back.senderName)
        assertEquals("Living Room TV", back.deviceName)
        val older = """{"server_url":"https://media.example.test","code":"ABC123","item_id":"i1","title":"A song"}"""
        assertNull(json.decodeFromString(CastLoadData.serializer(), older).senderName)
    }

    @Test
    fun `a status that only reports a launch is an observation and never a now-playing report`() {
        assertTrue(isLaunchObservationOnly(ScreenStatus(castConnectLaunch = true)))
        assertFalse(isLaunchObservationOnly(ScreenStatus()), "an ordinary idle status is fanned out as before")
        assertFalse(isLaunchObservationOnly(ScreenStatus(castConnectLaunch = true, loaded = true, itemId = "i1")),
            "a real now-playing report that also carries the flag still reaches the remote")
        val text = json.encodeToString(ScreenStatus.serializer(), ScreenStatus(castConnectLaunch = true))
        assertTrue(""""cast_connect_launch":true""" in text, text)
        assertFalse(json.decodeFromString(ScreenStatus.serializer(), "{}").castConnectLaunch)
    }
}
