package dev.jellystructure.tv

import dev.jellystructure.shared.tv.ClientCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * R291 (FR-R291-13, amendment 2026-10-12) — a cast receiver is offered audio renditions on our own encoder's stream
 * (video-only variants) and never on Jellyfin's; every other device keeps what it declared, on both masters.
 *
 * [withReceiverRenditions] is the our-encoder side of the gate; the Jellyfin side is the device's own
 * `hlsAudioRenditions`, which the tests below read straight off the capabilities.
 */
class ReceiverRenditionsTest {
    private val caps = ClientCapabilities(hlsOnly = true)
    private val declares = ClientCapabilities(hlsOnly = true, hlsAudioRenditions = true)

    @Test fun ourEncoderOffersARenditionToACast() = assertTrue(withReceiverRenditions("cast", caps).hlsAudioRenditions)

    /** The Jellyfin-path master reads the declaration itself: a cast declares nothing, so it is offered nothing. */
    @Test fun theJellyfinMasterOffersACastNothing() = assertFalse(caps.hlsAudioRenditions)

    /** …and a cast that did declare it (an older or a future receiver build) is still only served ours. */
    @Test fun aCastThatDeclaresItChangesNothing() = assertEquals(declares, withReceiverRenditions("cast", declares))

    @Test fun everyOtherDeviceKeepsWhatItDeclared() {
        assertEquals(caps, withReceiverRenditions("tv", caps))
        assertEquals(caps, withReceiverRenditions("phone", caps))
        assertEquals(caps, withReceiverRenditions("screen", caps))
        assertEquals(caps, withReceiverRenditions(null, caps))
        assertEquals(declares, withReceiverRenditions("tv", declares))
    }

    /** The kind comes off a device row; `Cast` and `cast` are the same device. */
    @Test fun theKindIsReadWithoutCase() = assertTrue(withReceiverRenditions("Cast", caps).hlsAudioRenditions)

    /** Nothing is copied when nothing changes: the same object back, for a device the rule does not touch. */
    @Test fun anUntouchedDeviceGetsTheSameObject() = assertTrue(withReceiverRenditions("tv", caps) === caps)
}
