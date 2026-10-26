package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DEB-2518 FR-3 (amends R188 FR-RV-UP1-2) — a not-yet-held item has no tags to test, so a device whose allowed or
 * blocked tag list is non-empty is restricted for that half of Coming Soon, as a library grant or kids already was.
 */
class UpcomingTagVisibilityTest {
    private fun device(
        allowedLibraries: Set<String>? = null,
        isKids: Boolean = false,
        allowedTags: Set<String> = emptySet(),
        blockedTags: Set<String> = emptySet(),
    ) = DeviceData(
        deviceId = "d", deviceToken = "t", jellyfinUserId = "u", jellyfinUsername = "u", jellyfinUserToken = "j",
        isAdmin = false, isKids = isKids, allowedLibraries = allowedLibraries, allowedTags = allowedTags, blockedTags = blockedTags,
    )

    // "mv-1" maps to null = resolved to no library item yet.
    private val notYetHeld: Map<String, dev.jellystructure.model.MediaItem?> = mapOf("mv-1" to null)

    private fun sees(d: DeviceData) = visibleToDevice("mv-1", notYetHeld, d)

    @Test fun `an unrestricted device sees a not-yet-held item`() = assertTrue(sees(device()))

    @Test fun `an allowed-tags-only device does not see a not-yet-held item`() =
        assertFalse(sees(device(allowedTags = setOf("family"))))

    @Test fun `a blocked-tags-only device does not see a not-yet-held item`() =
        assertFalse(sees(device(blockedTags = setOf("adult"))))

    @Test fun `a library-restricted device does not see a not-yet-held item`() =
        assertFalse(sees(device(allowedLibraries = setOf("lib"))))

    @Test fun `a kids device does not see a not-yet-held item`() = assertFalse(sees(device(isKids = true)))
}
