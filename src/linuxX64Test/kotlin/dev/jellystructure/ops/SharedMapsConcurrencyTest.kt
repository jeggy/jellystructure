package dev.jellystructure.ops

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.DeviceIdentityRegistry
import dev.jellystructure.auth.JellyfinDeviceIdentity
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.io.FileIo
import dev.jellystructure.media.LOGO_INK_DARK
import dev.jellystructure.media.LOGO_INK_LIGHT
import dev.jellystructure.media.LogoDownloader
import dev.jellystructure.tmdb.TmdbClient
import dev.jellystructure.tv.RaviloDeviceService
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
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
    private var logoDir: String? = null

    @AfterTest
    fun tearDown() {
        DeviceIdentityRegistry.clear()
        dbPath?.let { p -> for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$p$suffix") } }
        logoDir?.let { d ->
            runCatching { SystemFileSystem.list(Path("$d/artwork/studios")).forEach { SystemFileSystem.delete(it, mustExist = false) } }
            for (p in listOf("$d/artwork/studios", "$d/artwork", d)) runCatching { SystemFileSystem.delete(Path(p), mustExist = false) }
        }
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

    /**
     * DEB-1498 (FR-294-6) — the two sidecar caches in [LogoDownloader]. `BrowseService.toFacets` calls `logoInk` and
     * `logoReink` once per studio and once per network on the request thread, so one cold facets build inserts
     * hundreds of keys into both maps; several TVs asking for their Discover walls in the seconds after a restart do
     * that at the same time, with the background ink sweep removing from the same maps. That is the shape phase 294
     * proved corrupts a Kotlin/Native `HashMap`, and it is how `/api/tv/facets/summary` threw a message-less NPE on
     * production 4 s into the DEB-1479 deploy. Every thread inserts the same key set, exactly as the real builds do.
     */
    @Test
    fun `concurrent facets builds never corrupt the logo ink caches`() {
        val dir = "/tmp/jellystructure-test-logoink-${getpid()}".also { logoDir = it }
        SystemFileSystem.createDirectories(Path("$dir/artwork/studios"))
        val names = (0 until 400).map { "Studio $it" }
        for ((i, name) in names.withIndex()) {
            val slug = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            FileIo.writeBytes(Path("$dir/artwork/studios/$slug.png"), byteArrayOf(0))
            FileIo.writeBytes(Path("$dir/artwork/studios/$slug.ink"), (if (i % 2 == 0) LOGO_INK_LIGHT else LOGO_INK_DARK).encodeToByteArray())
            FileIo.writeBytes(Path("$dir/artwork/studios/$slug.reink"), (if (i % 3 == 0) "1" else "0").encodeToByteArray())
        }
        val logos = LogoDownloader(dir, TmdbClient(ConfigStore("$dir/config.toml")))
        onThreads {
            for (name in names) {
                assertNotNull(logos.logoInk("studios", name))
                logos.logoReink("studios", name)
            }
        }
        // Read back from the warm caches: every sidecar still answers its own value, and nothing threw above.
        for ((i, name) in names.withIndex()) {
            assertEquals(if (i % 2 == 0) LOGO_INK_LIGHT else LOGO_INK_DARK, logos.logoInk("studios", name))
            assertEquals(if (i % 3 == 0) true else null, logos.logoReink("studios", name))
        }
    }
}
