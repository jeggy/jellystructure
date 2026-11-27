package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-5843 (FR-7) — `still_watching` is additive on the receiver's message: absent when unset, ignored by a phone that does not know it. */
class CastStillWatchingWireTest {
    private val json = RaviloWireJsonWithDefaults

    @Test
    fun `the field travels as still_watching and is set only on the ask`() {
        val wire = json.encodeToString(CastReceiverMessage.serializer(), CastReceiverMessage(type = "status", itemId = "e3", stillWatching = true))
        assertTrue("\"still_watching\":true" in wire, wire)
    }

    @Test
    fun `an unset field is null and a receiver that never sends it decodes to null`() {
        assertNull(CastReceiverMessage(type = "status").stillWatching)
        assertNull(json.decodeFromString(CastReceiverMessage.serializer(), """{"type":"status","item_id":"e3"}""").stillWatching)
        assertEquals(true, json.decodeFromString(CastReceiverMessage.serializer(), """{"type":"status","still_watching":true}""").stillWatching)
    }

    @Test
    fun `a message without the field is not longer on the wire than before`() {
        val wire = RaviloWireJson.encodeToString(CastReceiverMessage.serializer(), CastReceiverMessage(type = "status", itemId = "e3"))
        assertFalse("still_watching" in wire, wire)
    }
}
