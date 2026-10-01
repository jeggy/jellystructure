package dev.jellystructure.ops

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.DeviceIdentityRegistry
import dev.jellystructure.auth.JellyfinDeviceIdentity
import dev.jellystructure.db.createDatabase
import dev.jellystructure.tv.RaviloDeviceService
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Phase 294 — the maps the TV sign-in path shares across request threads. Kotlin/Native's `HashMap` corrupts itself
 * when two threads insert at once (the next insert throws *"…grow-only hash array. Have object hashCodes
 * changed?"*), which is what `/api/tv/login` hit in production on 2026-10-02. Eight threads hammer each map with
 * disjoint keys; the final contents are exact, and nothing throws.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class SharedMapsConcurrencyTest {
    private val threads = 8
    private var dbPath: String? = null

    @AfterTest
    fun tearDown() {
        DeviceIdentityRegistry.clear()
        dbPath?.let { p -> for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$p$suffix") } }
    }

    private fun onThreads(body: suspend (Int) -> Unit) = runBlocking {
        val pool = newFixedThreadPoolContext(threads, "shared-maps-test")
        try {
            (0 until threads).map { t -> launch(pool) { body(t) } }.joinAll()
        } finally {
            pool.close()
        }
    }

    @Test
    fun `LockedMap keeps exact contents under concurrent inserts and removes`() {
        val map = LockedMap<String, Int>()
        val perThread = 20_000
        onThreads { t ->
            for (i in 0 until perThread) {
                map["k-$t-$i"] = i
                if (i % 2 == 1) map.remove("k-$t-${i - 1}")
                map["k-$t-$i"]
            }
        }
        assertEquals(threads * perThread / 2, map.size)
        val snap = map.snapshot()
        for (t in 0 until threads) assertEquals(perThread - 1, snap["k-$t-${perThread - 1}"])
        assertEquals(threads * perThread / 2, map.removeIf { _, v -> v % 2 == 1 }.size)
        assertEquals(0, map.size)
    }

    @Test
    fun `DeviceIdentityRegistry survives every request thread remembering devices at once`() {
        DeviceIdentityRegistry.clear()
        val perThread = 5_000
        onThreads { t ->
            for (i in 0 until perThread) {
                val d = DeviceData(deviceId = "dev-$t-$i", deviceToken = "dt-$t-$i", jellyfinUserId = "u$t", jellyfinUsername = "user$t",
                    jellyfinUserToken = "jt-$t-$i", isAdmin = false, displayName = "TV $t")
                DeviceIdentityRegistry.remember(d)
                DeviceIdentityRegistry.remember(d)   // every request remembers again
                if (i % 4 == 0) DeviceIdentityRegistry.forget("jt-$t-$i")
            }
        }
        assertEquals(threads * (perThread - perThread / 4), DeviceIdentityRegistry.size)
        assertEquals(JellyfinDeviceIdentity("ravilo-dev-3-7-u3", "TV 3", null), DeviceIdentityRegistry.identityFor("jt-3-7"))
    }

    @Test
    fun `token validation and sign-ins on many threads at once`() {
        val path = "/tmp/jellystructure-test-sharedmaps-${getpid()}.db".also { dbPath = it }
        val service = RaviloDeviceService(createDatabase(path))
        val tokens = (0 until 16).map { n ->
            service.loginDevice(deviceId = "device-$n", deviceName = "TV $n", jellyfinUserId = "user-$n", jellyfinUsername = "viewer$n",
                jellyfinUserToken = "jf-$n", isAdmin = false, isKids = false).second
        }
        onThreads { t ->
            repeat(300) { i ->
                val n = (t * 7 + i) % tokens.size
                assertNotNull(service.validateDeviceToken(tokens[n], appVersion = "1.${i % 3}", platform = "tv"))
                if (i % 50 == 0) {
                    // A sign-in on this thread while the others validate: the path that threw in production.
                    service.loginDevice(deviceId = "device-$n", deviceName = "TV $n", jellyfinUserId = "user-$n", jellyfinUsername = "viewer$n",
                        jellyfinUserToken = "jf-$n", isAdmin = false, isKids = false)
                }
            }
        }
        for (token in tokens) assertNotNull(service.validateDeviceToken(token))
    }
}
