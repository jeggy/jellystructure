package dev.jellystructure.server.routes

import dev.jellystructure.auth.DeviceData
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R327 (FR-R327-2) — a Chromecast receiver's record is never listed or driven by `/api/remote`. */
class RemoteListingTest {
    private fun device(kind: String) = DeviceData(
        deviceId = "d-$kind", jellyfinUserId = "u", jellyfinUsername = "u", jellyfinUserToken = "t",
        isAdmin = false, isKids = false, deviceToken = "dt-$kind", displayName = kind, lastSeen = 0L, kind = kind,
    )

    @Test
    fun screensAndTvsAreListed() {
        assertTrue(device("screen").listedToRemote())
        assertTrue(device("tv").listedToRemote())
        assertTrue(device("phone").listedToRemote())
    }

    @Test
    fun aChromecastReceiverRecordIsNot() {
        assertFalse(device("cast").listedToRemote())
    }
}
