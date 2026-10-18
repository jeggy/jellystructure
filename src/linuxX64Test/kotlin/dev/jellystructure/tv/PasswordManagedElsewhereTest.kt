package dev.jellystructure.tv

import dev.jellystructure.auth.DEFAULT_AUTHENTICATION_PROVIDER_ID
import dev.jellystructure.auth.DeviceIdentityRegistry
import dev.jellystructure.auth.JellyfinPolicy
import dev.jellystructure.auth.JellyfinUser
import dev.jellystructure.auth.PasswordChangeOutcome
import dev.jellystructure.db.createDatabase
import dev.jellystructure.shared.tv.AccountPasswordResult
import dev.jellystructure.shared.tv.RaviloConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
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
 * DEB-1854 (FR-2, FR-3, FR-5, FR-8, FR-9, FR-11) — an account whose password Jellyfin does not keep: the rule, the
 * reconciler's refresh of the provider and the name from one `/Users` answer, the viewer config's two fields, and the
 * password route that answers instead of relaying.
 */
class PasswordManagedElsewhereTest {
    private lateinit var dbPath: String
    private lateinit var db: dev.jellystructure.db.JellystructureDb
    private lateinit var devices: RaviloDeviceService
    private var answer: List<JellyfinUser>? = emptyList()
    private var fetches = 0
    private val configPushes = ArrayList<String>()
    private val wireJson = Json { ignoreUnknownKeys = true }

    @BeforeTest fun setUp() {
        dbPath = "/tmp/jellystructure-test-pwelsewhere-${getpid()}.db"
        db = createDatabase(dbPath)
        devices = RaviloDeviceService(db)
        DeviceIdentityRegistry.clear()
        fetches = 0; configPushes.clear()
    }

    @AfterTest fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    private fun policy(provider: String?) = JellyfinPolicy(isAdministrator = false, authenticationProviderId = provider)
    private fun user(id: String, name: String, provider: String?) = JellyfinUser(id = id, name = name, policy = policy(provider))
    private fun login(user: String, name: String = "old-$user") = devices.loginDevice(
        deviceId = "tv-$user", deviceName = "TV $user", jellyfinUserId = user, jellyfinUsername = name,
        jellyfinUserToken = "jf-$user", isAdmin = false, isKids = false, allowedLibraries = null,
    )

    private fun reconciler() = DevicePolicyReconciler(
        deviceService = devices, fetchUsers = { fetches++; answer }, onPolicyChanged = {}, onConfigChanged = { configPushes += it },
    )

    // ── FR-2 — the rule ─────────────────────────────────────────────────────────────────────────────

    @Test fun jellyfinsOwnProviderIsLocalAndAnyOtherIdIsManagedElsewhere() {
        assertFalse(policy(DEFAULT_AUTHENTICATION_PROVIDER_ID).passwordManagedElsewhere)
        assertTrue(policy("Some.Other.Plugin.AuthenticationProvider").passwordManagedElsewhere)
    }

    @Test fun anAbsentOrBlankProviderReadsAsLocal() {
        assertFalse(policy(null).passwordManagedElsewhere)
        assertFalse(policy("").passwordManagedElsewhere)
        assertFalse(policy("   ").passwordManagedElsewhere)
    }

    @Test fun theProviderIdIsParsedFromJellyfinsPolicyAndItsAbsenceStillDecodes() {
        val withId = wireJson.decodeFromString<JellyfinPolicy>("""{"IsAdministrator":false,"AuthenticationProviderId":"Some.Plugin"}""")
        assertEquals("Some.Plugin", withId.authenticationProviderId); assertTrue(withId.passwordManagedElsewhere)
        val without = wireJson.decodeFromString<JellyfinPolicy>("""{"IsAdministrator":false}""")
        assertNull(without.authenticationProviderId); assertFalse(without.passwordManagedElsewhere)
    }

    // ── FR-3 / FR-9 — the reconciler ────────────────────────────────────────────────────────────────

    @Test fun onePassRefreshesTheProviderAndTheNameFromOneUsersAnswerAndPushesConfigChanged() = runBlocking {
        val (_, token) = login("u1")
        login("u2", "same")
        answer = listOf(user("u1", "new-u1", "Some.Plugin"), user("u2", "same", DEFAULT_AUTHENTICATION_PROVIDER_ID))
        val pass = assertNotNull(reconciler().reconcileAll("start"))
        assertEquals(1, fetches, "no extra Jellyfin call")
        assertTrue(devices.passwordManagement.isManagedElsewhere("u1"))
        assertFalse(devices.passwordManagement.isManagedElsewhere("u2"))
        assertEquals("new-u1", assertNotNull(devices.validateDeviceToken(token)).jellyfinUsername, "the cached DeviceData follows the rename")
        assertEquals(listOf("u1"), pass.providerChanges); assertEquals(listOf("u1"), pass.renames)
        assertEquals(listOf("u1"), configPushes, "one push for the account that moved, none for the one that did not")
    }

    @Test fun aSwitchBackAndAQuietPassPushNothingMoreThanTheMove() = runBlocking {
        login("u1", "n")
        val r = reconciler()
        answer = listOf(user("u1", "n", "Some.Plugin"))
        r.reconcileAll("start"); r.reconcileAll("interval")
        assertEquals(listOf("u1"), configPushes, "the second, unchanged pass is silent")
        answer = listOf(user("u1", "n", DEFAULT_AUTHENTICATION_PROVIDER_ID))
        r.reconcileAll("interval")
        assertEquals(listOf("u1", "u1"), configPushes)
        assertFalse(devices.passwordManagement.isManagedElsewhere("u1"))
    }

    @Test fun aFailedFetchLeavesBothAnswersAlone() = runBlocking {
        login("u1", "n")
        devices.passwordManagement.record("u1", true)
        answer = null
        assertNull(reconciler().reconcileAll("interval"))
        assertTrue(devices.passwordManagement.isManagedElsewhere("u1"))
        assertEquals(emptyList(), configPushes)
    }

    // ── FR-5 — the viewer's config ──────────────────────────────────────────────────────────────────

    private fun configFor(managed: Boolean, url: String?) =
        RaviloConfigService(db, passwordManagedFor = { managed }, passwordPage = { url }).getConfig("u1")

    @Test fun aLocalViewerGetsNeitherFieldNorTheUrl() {
        val c = configFor(managed = false, url = "https://account.example.org/p")
        assertFalse(c.passwordManagedElsewhere); assertNull(c.passwordChangeUrl)
        val json = wireJson.encodeToString(c)
        assertFalse("password_managed_elsewhere" in json); assertFalse("password_change_url" in json)
    }

    @Test fun aViewerManagedElsewhereGetsTheFlagAndThePageWhenOneIsSet() {
        val c = configFor(managed = true, url = "https://account.example.org/p")
        assertTrue(c.passwordManagedElsewhere); assertEquals("https://account.example.org/p", c.passwordChangeUrl)
        val json = wireJson.encodeToString(c)
        assertTrue(""""password_managed_elsewhere":true""" in json); assertTrue("password_change_url" in json)
    }

    @Test fun withNoPageSetOnlyTheFlagTravels() {
        val c = configFor(managed = true, url = null)
        assertTrue(c.passwordManagedElsewhere); assertNull(c.passwordChangeUrl)
        assertFalse("password_change_url" in wireJson.encodeToString(c))
    }

    @Test fun aStoredConfigNeverResurrectsTheFields() {
        val svc = RaviloConfigService(db)
        svc.save("u1", svc.getConfig("u1").copy(passwordManagedElsewhere = true, passwordChangeUrl = "https://stale.example.org"))
        val back = RaviloConfigService(db).getConfig("u1")
        assertFalse(back.passwordManagedElsewhere); assertNull(back.passwordChangeUrl)
    }

    // ── wire — old payloads without the keys still decode ───────────────────────────────────────────

    @Test fun aRaviloConfigAndAnAccountPasswordResultWithoutTheNewKeysStillDecode() {
        val stripped = wireJson.encodeToString(RaviloConfigService(db).getConfig("u1"))
        val c = wireJson.decodeFromString<RaviloConfig>(stripped)
        assertFalse(c.passwordManagedElsewhere); assertNull(c.passwordChangeUrl)
        val r = wireJson.decodeFromString<AccountPasswordResult>("""{"ok":true,"token_survived":true}""")
        assertFalse(r.managedElsewhere)
        assertTrue(""""managed_elsewhere":true""" in wireJson.encodeToString(AccountPasswordResult(ok = false, managedElsewhere = true)))
    }

    // ── FR-8 — the route ────────────────────────────────────────────────────────────────────────────

    private fun noRelay(): suspend () -> PasswordChangeOutcome = { fail("the password must never be sent to Jellyfin") }

    @Test fun anAccountManagedElsewhereAnswersWithoutRelaying() = runBlocking {
        var heard: Boolean? = null
        val r = AccountPassword.change(
            readUser = { user("u1", "n", "Some.Plugin") }, onProvider = { heard = it }, relay = noRelay(),
            tokenSurvived = { fail("no token check without a change") },
        )
        assertEquals(AccountPasswordResult(ok = false, managedElsewhere = true), r)
        assertEquals(true, heard)
    }

    @Test fun aLocalAccountRelaysAsBefore() = runBlocking {
        var relayed = 0
        val r = AccountPassword.change(
            readUser = { user("u1", "n", DEFAULT_AUTHENTICATION_PROVIDER_ID) }, onProvider = {},
            relay = { relayed++; PasswordChangeOutcome.OK }, tokenSurvived = { true },
        )
        assertEquals(1, relayed); assertEquals(AccountPasswordResult(ok = true, tokenSurvived = true), r)
    }

    @Test fun aFailedLiveReadFallsThroughToTodaysBehaviour() = runBlocking {
        var relayed = 0
        val wrong = AccountPassword.change(readUser = { null }, onProvider = { fail("nothing was read") },
            relay = { relayed++; PasswordChangeOutcome.WRONG_CURRENT }, tokenSurvived = { true })
        assertEquals(AccountPasswordResult(ok = false, wrongCurrentPassword = true), wrong)
        assertNull(AccountPassword.change(readUser = { null }, onProvider = {}, relay = { relayed++; PasswordChangeOutcome.FAILED }, tokenSurvived = { true }))
        assertEquals(2, relayed)
    }

    // ── FR-9 — the name, and No thanks ──────────────────────────────────────────────────────────────

    @Test fun refreshUsernameWritesEveryRowOfTheUserAndIsQuietWhenNothingMoved() {
        val (_, a) = login("u1", "old")
        devices.loginDevice(deviceId = "tv-b", deviceName = "TV b", jellyfinUserId = "u1", jellyfinUsername = "old",
            jellyfinUserToken = "jf-u1b", isAdmin = false, isKids = false, allowedLibraries = null)
        assertTrue(devices.refreshUsername("u1", "Renamed"))
        assertFalse(devices.refreshUsername("u1", "Renamed"))
        assertFalse(devices.refreshUsername("u1", "  "), "a blank name never overwrites")
        assertEquals(setOf("Renamed"), devices.listByUser("u1").map { it.jellyfinUsername }.toSet())
        assertEquals("Renamed", assertNotNull(devices.validateDeviceToken(a)).jellyfinUsername)
    }

    @Test fun noThanksResolvesByUserIdWhereOneIsStoredAndByNameBefore() {
        val viewers = listOf("u1" to "Renamed", "u2" to "Other")
        val byId = dev.jellystructure.model.SuggestionBecause("Old name", "finished", viewerId = "u1")
        assertEquals("u1", dev.jellystructure.suggestions.SuggestionService.viewerIdFor(byId, viewers))
        val legacy = dev.jellystructure.model.SuggestionBecause("Other", "finished")
        assertEquals("u2", dev.jellystructure.suggestions.SuggestionService.viewerIdFor(legacy, viewers))
        assertNull(dev.jellystructure.suggestions.SuggestionService.viewerIdFor(dev.jellystructure.model.SuggestionBecause("Gone", "finished"), viewers))
    }
}
