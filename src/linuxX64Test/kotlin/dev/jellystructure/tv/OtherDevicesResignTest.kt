package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.DeviceIdentityRegistry
import dev.jellystructure.auth.JellyfinDeviceIdentity
import dev.jellystructure.auth.JellyfinPolicy
import dev.jellystructure.auth.JellyfinUser
import dev.jellystructure.auth.PasswordChangeOutcome
import dev.jellystructure.db.createDatabase
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.log.Logger
import dev.jellystructure.media.ActivityLog
import dev.jellystructure.shared.tv.AccountPasswordResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * DEB-1932 (FR-187-8, 2026-10-19 amendment, rules 1-5) — after Jellyfin's `204` the password route signs the account's
 * other own-sign-in Ravilo devices back in with the new password, and signs out at once any it cannot.
 */
class OtherDevicesResignTest {
    private lateinit var dbPath: String
    private lateinit var db: dev.jellystructure.db.JellystructureDb
    private lateinit var devices: RaviloDeviceService

    private val newPassword = "n3w-s3cret-Pw-7731"
    private val signIns = ArrayList<Pair<String, JellyfinDeviceIdentity>>()
    private val pushed = ArrayList<String>()
    private val forgotten = ArrayList<String>()
    private val heard = ArrayList<Pair<String, String>>()   // tokenListener: device id, the token it replaced

    @BeforeTest fun setUp() {
        dbPath = "/tmp/jellystructure-test-resign-${getpid()}.db"
        db = createDatabase(dbPath)
        devices = RaviloDeviceService(db)
        devices.tokenListener = { d, old -> heard += d.deviceId to old }
        DeviceIdentityRegistry.clear()
        signIns.clear(); pushed.clear(); forgotten.clear(); heard.clear()
    }

    @AfterTest fun tearDown() {
        Logger.activityLog = null
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    private val deviceTokens = HashMap<String, String>()

    private fun login(deviceId: String, user: String = "u1", kind: String? = null, token: String = "jf-$deviceId"): DeviceData =
        devices.loginDevice(
            deviceId = deviceId, deviceName = "Device $deviceId", jellyfinUserId = user, jellyfinUsername = "alice",
            jellyfinUserToken = token, isAdmin = false, isKids = false, allowedLibraries = null, kind = kind,
        ).let { (d, deviceToken) -> deviceTokens[deviceId] = deviceToken; d }

    private fun row(deviceId: String, user: String = "u1") = devices.listByUser(user).single { it.deviceId == deviceId }

    private suspend fun resign(
        caller: DeviceData,
        liveUsername: String? = "alice",
        signIn: suspend (String, JellyfinDeviceIdentity) -> String?,
    ): Boolean = OtherDevicesResign.run(
        caller = caller, rows = devices.listByUser(caller.jellyfinUserId), liveUsername = liveUsername,
        signIn = { name, identity -> signIns += name to identity; signIn(name, identity) },
        replaceToken = { r, t -> devices.replaceJellyfinToken(r, t) },
        signOut = { r -> devices.unpair(r.deviceToken) },
        pushSignedOut = { r -> pushed += r.deviceId },
        forget = { forgotten += it },
    )

    @Test fun twoOtherDevicesAreSignedBackInAndNothingIsSignedOut() = runBlocking {
        val caller = login("phone"); login("tv-a"); login("tv-b")
        val out = resign(caller) { _, identity -> "new-" + identity.deviceId }
        assertFalse(out)
        assertEquals("new-ravilo-tv-a-alice", row("tv-a").jellyfinUserToken)
        assertEquals("new-ravilo-tv-b-alice", row("tv-b").jellyfinUserToken)
        assertEquals("jf-phone", row("phone").jellyfinUserToken, "the caller's own token is the one Jellyfin kept")
        assertTrue(pushed.isEmpty(), "no signed_out event when every device was re-signed")
        assertEquals(setOf("tv-a", "tv-b"), devices.listByUser("u1").map { it.deviceId }.filter { it != "phone" }.toSet(), "no row was removed")
    }

    @Test fun theSignInPresentsTheLoginShapedDeviceIdWithTheLiveUsernameAndTheDevicesOwnName() = runBlocking {
        val caller = login("phone"); login("tv-a")
        resign(caller, liveUsername = "Alice-Renamed") { _, _ -> "tok" }
        val (name, identity) = signIns.single()
        assertEquals("Alice-Renamed", name)
        // POST /tv/login's `ravilo-<deviceId>-<username>`, not JellyfinDeviceIdentity.forDevice's `-<jellyfinUserId>`.
        assertEquals("ravilo-tv-a-Alice-Renamed", identity.deviceId)
        assertEquals("Device tv-a", identity.deviceName)
        assertEquals("ravilo-tv-a-u1", JellyfinDeviceIdentity.forDevice(row("tv-a")).deviceId)
    }

    @Test fun aFailedLiveReadFallsBackToTheRowsStoredName() = runBlocking {
        val caller = login("phone"); login("tv-a")
        resign(caller, liveUsername = null) { _, _ -> "tok" }
        assertEquals("alice", signIns.single().first)
    }

    @Test fun oneFailedReSignRevokesThatRowPushesToThatDeviceOnlyAndReportsIt() = runBlocking {
        val caller = login("phone"); login("tv-a"); login("tv-b")
        val out = resign(caller) { _, identity -> if (identity.deviceId.contains("tv-a")) null else "ok-token" }
        assertTrue(out)
        assertEquals(listOf("tv-a"), pushed, "the event goes to the device that was signed out, and to no other")
        assertTrue(devices.listByUser("u1").none { it.deviceId == "tv-a" }, "its device token is revoked, as Sign out does")
        assertNull(devices.validateDeviceToken(loginToken("tv-a")))
        assertEquals("ok-token", row("tv-b").jellyfinUserToken)
        assertEquals("phone", row("phone").deviceId)
    }

    private fun loginToken(deviceId: String): String = deviceTokens.getValue(deviceId)

    @Test fun aSignInThatThrowsOrComesBackBlankIsTreatedAsNoToken() = runBlocking {
        val caller = login("phone"); login("tv-a"); login("tv-b")
        val out = resign(caller) { _, identity -> if (identity.deviceId.contains("tv-a")) throw IllegalArgumentException("Invalid Jellyfin credentials") else "  " }
        assertTrue(out)
        assertEquals(setOf("tv-a", "tv-b"), pushed.toSet())
        assertEquals(listOf("phone"), devices.listByUser("u1").map { it.deviceId })
    }

    @Test fun aSignInThatNeverAnswersIsBoundedAndCountsAsFailed() = runBlocking {
        val caller = login("phone"); login("tv-a")
        val out = resign(caller) { _, _ -> delay(60_000); "too-late" }
        assertTrue(out)
        assertEquals(listOf("tv-a"), pushed)
    }

    @Test fun noOtherDeviceMeansNoSignInAndTheFieldStaysFalse() = runBlocking {
        val caller = login("phone")
        login("tv-of-someone-else", user = "u2")
        val out = resign(caller) { _, _ -> fail("nothing to re-sign") }
        assertFalse(out)
        assertTrue(signIns.isEmpty())
        assertTrue(forgotten.isEmpty())
        assertEquals("jf-tv-of-someone-else", row("tv-of-someone-else", "u2").jellyfinUserToken)
    }

    @Test fun aBorrowingRowIsNeverReSignedAndItsFollowerMovesToTheNewToken() = runBlocking {
        val caller = login("phone"); login("tv-a", token = "jf-tv-a")
        login("cast-1", kind = "cast", token = "jf-tv-a")     // borrowed tv-a's token
        login("screen-1", kind = "screen", token = "jf-tv-a")
        login("cast-2", kind = "cast", token = "jf-phone")    // borrowed the caller's: its token survived
        val out = resign(caller) { _, _ -> "fresh" }
        assertFalse(out)
        assertEquals(listOf("tv-a"), signIns.map { it.second.deviceId.removePrefix("ravilo-").removeSuffix("-alice") }, "only the own-sign-in row is signed in again")
        assertEquals("fresh", row("tv-a").jellyfinUserToken)
        assertEquals("fresh", row("cast-1").jellyfinUserToken)
        assertEquals("fresh", row("screen-1").jellyfinUserToken)
        assertEquals("jf-phone", row("cast-2").jellyfinUserToken)
        assertEquals(setOf("tv-a" to "jf-tv-a", "cast-1" to "jf-tv-a", "screen-1" to "jf-tv-a"), heard.toSet(), "the session bridge hears every row that moved")
    }

    @Test fun everyRevokedTokenIsForgottenWhetherOrNotItsDeviceWasSignedBackIn() = runBlocking {
        val caller = login("phone"); login("tv-a"); login("tv-b")
        resign(caller) { _, identity -> if (identity.deviceId.contains("tv-a")) null else "ok-token" }
        assertEquals(setOf("jf-tv-a", "jf-tv-b"), forgotten.toSet())
        assertFalse("jf-phone" in forgotten, "the caller's token was not revoked")
    }

    @Test fun theIdentityRegistryFollowsTheNewToken() = runBlocking {
        val caller = login("phone"); login("tv-a")
        assertNotNull(DeviceIdentityRegistry.identityFor("jf-tv-a"))
        resign(caller) { _, _ -> "fresh" }
        assertNull(DeviceIdentityRegistry.identityFor("jf-tv-a"))
        assertTrue(DeviceIdentityRegistry.identityFor("fresh") != null)
    }

    @Test fun theSignedOutEventIsOneTypeAndNothingElse() {
        assertEquals("""{"type":"signed_out"}""", OtherDevicesResign.SIGNED_OUT_EVENT)
    }

    // ── the route's composition: AccountPassword.change ─────────────────────────────────────────────────────────

    private fun localUser() = JellyfinUser(id = "u1", name = "alice", policy = JellyfinPolicy(isAdministrator = false, authenticationProviderId = null))

    @Test fun theResultCarriesTheFieldOnlyWhenADeviceWasSignedOut() = runBlocking {
        val ok = AccountPassword.change(readUser = { localUser() }, onProvider = {}, relay = { PasswordChangeOutcome.OK }, tokenSurvived = { true }, otherDevices = { false })
        assertEquals(AccountPasswordResult(ok = true, tokenSurvived = true, otherDevicesSignedOut = false), ok)
        val out = AccountPassword.change(readUser = { localUser() }, onProvider = {}, relay = { PasswordChangeOutcome.OK }, tokenSurvived = { true }, otherDevices = { true })
        assertEquals(AccountPasswordResult(ok = true, tokenSurvived = true, otherDevicesSignedOut = true), out)
        assertTrue(Json { encodeDefaults = true }.encodeToString(out).contains(""""other_devices_signed_out":true"""))
        assertTrue(Json { encodeDefaults = true }.encodeToString(ok).contains(""""other_devices_signed_out":false"""))
    }

    @Test fun aWrongCurrentPasswordOrAFailedChangeTriggersNoReSign() = runBlocking {
        val noResign: suspend () -> Boolean = { fail("no re-sign without a 204 from Jellyfin") }
        val wrong = AccountPassword.change(readUser = { localUser() }, onProvider = {}, relay = { PasswordChangeOutcome.WRONG_CURRENT }, tokenSurvived = { true }, otherDevices = noResign)
        assertEquals(AccountPasswordResult(ok = false, wrongCurrentPassword = true), wrong)
        assertNull(AccountPassword.change(readUser = { localUser() }, onProvider = {}, relay = { PasswordChangeOutcome.FAILED }, tokenSurvived = { true }, otherDevices = noResign))
    }

    @Test fun anAccountManagedElsewhereTriggersNoReSign() = runBlocking {
        val managed = JellyfinUser(id = "u1", name = "alice", policy = JellyfinPolicy(isAdministrator = false, authenticationProviderId = "Some.Other.Plugin"))
        val r = AccountPassword.change(
            readUser = { managed }, onProvider = {}, relay = { fail("not relayed") }, tokenSurvived = { fail("no check") },
            otherDevices = { fail("no re-sign for an account Jellyfin does not own") },
        )
        assertEquals(AccountPasswordResult(ok = false, managedElsewhere = true), r)
    }

    // ── rule 2 ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun theNewPasswordAppearsInNoLogLineNoResultAndNoStoredRow() = runBlocking {
        val dir = "/tmp/jellystructure-test-resign-log-${getpid()}"
        platform.posix.system("rm -rf '$dir'; mkdir -p '$dir'")
        val activity = ActivityLog("$dir/activity.json", WsBroadcaster(), CoroutineScope(Dispatchers.Default))
        Logger.activityLog = activity
        try {
            val caller = login("phone"); login("tv-a"); login("tv-b")
            // A transport error that carries the request body in its message — the worst case for a log line.
            val out = resign(caller) { _, identity ->
                if (identity.deviceId.contains("tv-a")) throw IllegalStateException("POST failed: {\"Pw\":\"$newPassword\"}") else "ok-token"
            }
            assertTrue(out)
            val lines = activity.list(1, 1000, null, null).entries.map { it.message }
            assertTrue(lines.isNotEmpty(), "the failure was logged")
            assertTrue(lines.none { newPassword in it }, "no log line carries the password: $lines")
            assertFalse(newPassword in Json.encodeToString(AccountPasswordResult(ok = true, otherDevicesSignedOut = out)))
            assertTrue(devices.allDevices().none { newPassword in it.toString() }, "no stored row carries it")
        } finally {
            Logger.activityLog = null
            platform.posix.system("rm -rf '$dir'")
        }
    }
}
