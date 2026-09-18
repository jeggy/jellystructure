package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.sha256Hex
import dev.jellystructure.db.createDatabase
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 236 (FR-236-2, dev review item 1) — the receiver-shows-a-code pairing flow: the secret is
 * required (the code alone, visible to the whole room, must never retrieve a token), the code is
 * single-use, and an expired code is refused exactly like an unknown one.
 */
class ScreenPairingServiceTest {
    private lateinit var dbPath: String
    private lateinit var devices: RaviloDeviceService
    private lateinit var pairing: ScreenPairingService
    private lateinit var db: dev.jellystructure.db.JellystructureDb

    @BeforeTest
    fun setUp() {
        val suffix = getpid()
        dbPath = "/tmp/jellystructure-test-screenpairing-$suffix.db"
        db = createDatabase(dbPath)
        devices = RaviloDeviceService(db)
        pairing = ScreenPairingService(db, devices)
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    private fun phone(userId: String = "u1", username: String = "jeggy"): DeviceData = devices.loginDevice(
        deviceId = "pixel9-$userId", deviceName = "Pixel 9", jellyfinUserId = userId, jellyfinUsername = username,
        jellyfinUserToken = "jf-token-$userId", isAdmin = false, isKids = false, kind = "phone",
    ).first

    @Test
    fun aClaimedCodeIsCollectedOnceByTheReceiver() {
        val (code, expiresAt, claimSecret) = pairing.mintCode("screen-1", "Living room TV", "tizen-screen")
        assertEquals(6, code.length)
        assertTrue(expiresAt > 0)

        // Not claimed yet: the receiver's poll waits.
        assertEquals(ScreenPairingService.PollResult.Waiting, pairing.poll(code, claimSecret))

        val p = phone()
        val (name, deviceId) = assertNotNull(pairing.claim(code, p))
        assertEquals("Living room TV", name)
        assertEquals("screen-1", deviceId)

        val result = assertNotNull(pairing.poll(code, claimSecret)) as ScreenPairingService.PollResult.Claimed
        assertEquals("screen-1", result.result.session.deviceId)
        assertEquals("u1", result.result.session.userId)
        assertNotNull(devices.validateDeviceToken(result.result.deviceToken), "the receiver's own device token is real")

        // Single-use: the row is gone after the receiver has collected its token once.
        assertNull(pairing.poll(code, claimSecret), "a second poll for the same code sees unknown, not waiting")
    }

    @Test
    fun theSecretIsRequiredNeverTheCodeAlone() {
        val (code, _, claimSecret) = pairing.mintCode("screen-2", null, null)
        pairing.claim(code, phone())
        assertNull(pairing.poll(code, "wrong-secret"), "the code alone (as shown on screen) must not retrieve a token")
        assertNotNull(pairing.poll(code, claimSecret), "the real secret does")
    }

    @Test
    fun anUnknownCodeIsRefused() {
        assertNull(pairing.claim("ZZZZZZ", phone()))
        assertNull(pairing.poll("ZZZZZZ", "anything"))
    }

    @Test
    fun anAlreadyClaimedCodeCannotBeClaimedTwice() {
        val (code, _, claimSecret) = pairing.mintCode("screen-3", null, null)
        assertNotNull(pairing.claim(code, phone(userId = "u1")))
        assertNull(pairing.claim(code, phone(userId = "u2")), "one code, one claim")
        assertNotNull(pairing.poll(code, claimSecret), "the first claim's token is still collectable")
    }

    @Test
    fun anExpiredCodeIsRefusedLikeAnUnknownOne() {
        // Insert a pre-expired row directly — waiting out the real 10-minute TTL isn't practical here.
        val code = "EXPIRD"
        val claimSecret = "secret123"
        db.screenPairingQueries.insert(code, "screen-4", null, null, sha256Hex(claimSecret), 1L, 2L)
        assertNull(pairing.claim(code, phone()), "expired ⇒ refused exactly like unknown")
        assertNull(pairing.poll(code, claimSecret))
    }

    @Test
    fun twoDifferentUsersCanClaimTheSameReceiverOverTime() {
        // FR-236-2's "one identity across every user who has claimed it" — the multi-session binding
        // FR-236-4/4a and the e2e suite build on: two claims, two ravilo_device sessions, one device id.
        val (codeA, _, secretA) = pairing.mintCode("screen-5", "Living room TV", null)
        pairing.claim(codeA, phone(userId = "u1", username = "alice"))
        pairing.poll(codeA, secretA)

        val (codeB, _, secretB) = pairing.mintCode("screen-5", "Living room TV", null)
        pairing.claim(codeB, phone(userId = "u2", username = "bob"))
        pairing.poll(codeB, secretB)

        val sessions = devices.listSessions("screen-5")
        assertEquals(setOf("alice", "bob"), sessions.map { it.jellyfinUsername }.toSet())
        assertEquals(setOf("u1", "u2"), sessions.map { it.jellyfinUserId }.toSet())
    }
}
