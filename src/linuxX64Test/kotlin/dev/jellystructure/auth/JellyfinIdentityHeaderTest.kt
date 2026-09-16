package dev.jellystructure.auth

import dev.jellystructure.ServerVersion
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 224 (FR-224-1, FR-224-3, FR-224-4) — the one header builder, and the registry it falls back to. */
class JellyfinIdentityHeaderTest {
    @AfterTest
    fun tearDown() = DeviceIdentityRegistry.clear()

    @Test
    fun `a device identity with a version says it and without one says nothing`() {
        val with = jellyfinIdentityHeader(JellyfinDeviceIdentity("ravilo-bravia-u1", "Stue TV", "1.18"))
        assertTrue(with.contains("""Client="Ravilo""""), with)
        assertTrue(with.contains("""Device="Stue TV""""), with)
        assertTrue(with.contains("""DeviceId="ravilo-bravia-u1""""), with)
        assertTrue(with.endsWith("""Version="1.18""""), with)

        val without = jellyfinIdentityHeader(JellyfinDeviceIdentity("ravilo-bravia-u1", "Stue TV", null))
        assertFalse(without.contains("Version="), without)
        assertFalse(jellyfinIdentityHeader(JellyfinDeviceIdentity("x", "y", "   ")).contains("Version="))
        assertFalse(with.contains("0.1.0"))
    }

    @Test
    fun `the server identity carries the real version and never 0 1 0`() {
        val server = jellyfinIdentityHeader(null)
        assertTrue(server.contains("""Client="Jellystructure""""), server)
        assertTrue(server.contains("""Device="Server""""), server)
        assertTrue(server.contains("""Version="${ServerVersion.current}""""), server)
        assertFalse(ServerVersion.current.isBlank())
        assertFalse(server.contains("0.1.0"))
    }

    @Test
    fun `the registry maps a token to its identity and forgets it`() {
        val device = DeviceData(
            deviceId = "bravia", deviceToken = "dt", jellyfinUserId = "u1", jellyfinUsername = "jeggy",
            jellyfinUserToken = "jf-1", isAdmin = false, displayName = "Stue TV", appVersion = "1.18",
        )
        DeviceIdentityRegistry.remember(device)
        val id = DeviceIdentityRegistry.identityFor("jf-1")
        assertEquals(JellyfinDeviceIdentity("ravilo-bravia-u1", "Stue TV", "1.18"), id)
        DeviceIdentityRegistry.forget("jf-1")
        assertNull(DeviceIdentityRegistry.identityFor("jf-1"))
        DeviceIdentityRegistry.remember(device.copy(jellyfinUserToken = ""))
        assertNull(DeviceIdentityRegistry.identityFor(""), "a blank token is never a key")
    }
}
