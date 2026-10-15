package dev.jellystructure.support

import dev.jellystructure.config.AiConfig
import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.SupportConfig
import dev.jellystructure.tv.TvEventBus
import dev.jellystructure.tv.openDescriptorCount
import dev.jellystructure.tv.parseEventFeatures
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Phase 320 (FR-320-1, FR-320-10, FR-320-19) — over real sockets and the real [TvEventBus] and [wireSupport]:
 *  - `support_changed` reaches only the owner's sockets that opened with `features=support`;
 *  - saving or removing the AI key, and changing `[support] enabled`, each emit `config_changed` to every device, and a
 *    key replaced by another key (the lane unchanged) emits nothing.
 */
class SupportEventsTest {
    private class Sock(val label: String, private val scope: CoroutineScope, private val client: HttpClient, port: Int, user: String, device: String, features: String?) {
        private val lock = Mutex()
        private val got = mutableListOf<String>()
        val job = scope.launch {
            val q = "user=$user&device=$device" + (features?.let { "&features=$it" } ?: "")
            runCatching {
                client.webSocket("ws://127.0.0.1:$port/events?$q") {
                    for (fr in incoming) if (fr is Frame.Text) lock.withLock { got += fr.readText() }
                }
            }
        }
        suspend fun frames(): List<String> = lock.withLock { got.toList() }
        suspend fun clear() = lock.withLock { got.clear() }
    }

    private suspend fun waitFor(what: String, check: suspend () -> Boolean) {
        val ok = runCatching { withTimeout(10_000) { while (!check()) delay(25) } }.isSuccess
        if (!ok) fail("timed out waiting for: $what")
    }

    @Test fun supportChangedGoesOnlyToTheOwnersOptedInSocketsAndConfigChangedFollowsTheLane() = runBlocking {
        if (openDescriptorCount() > 700) { println("SupportEventsTest: too many descriptors already open — skipped in this run"); return@runBlocking }
        val run = getpid()
        val dbPath = "/tmp/jellystructure-test-support-events-$run.db"
        val tomlPath = "/tmp/jellystructure-test-support-events-$run.toml"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bus = TvEventBus(scope)
        val db = dev.jellystructure.db.createDatabase(dbPath)
        val store = ConfigStore(tomlPath).also { it.load() }
        val service = SupportService(db, { store.current })
        val adminSeen = mutableListOf<Any>()
        wireSupport(service, store, bus) { adminSeen += it }
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket("/events") {
                    val user = call.request.queryParameters["user"]!!
                    val id = call.request.queryParameters["device"]!!
                    bus.tryRegister(user, id, this, parseEventFeatures(call.request.queryParameters["features"]))
                    try { for (f in incoming) { /* the server ignores what a device sends here */ } } finally { bus.unregister(user, id, this) }
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(Curl) { install(io.ktor.client.plugins.websocket.WebSockets) }
        try {
            val optedIn = Sock("anna-phone", scope, client, port, "u-anna", "phone-1", "support")
            val notOptedIn = Sock("anna-tv", scope, client, port, "u-anna", "tv-1", null)
            val other = Sock("ben-phone", scope, client, port, "u-ben", "phone-2", "support")
            waitFor("three sockets registered") { bus.isConnected("phone-1") && bus.isConnected("tv-1") && bus.isConnected("phone-2") }

            // The lane is off (no key, switch off): turning it on is `config_changed` to every device.
            store.update(AppConfig(support = SupportConfig(enabled = true)))
            delay(300)
            assertTrue(optedIn.frames().isEmpty(), "enabled but no key: still unavailable, nothing to push: ${optedIn.frames()}")
            store.update(AppConfig(ai = AiConfig(apiKey = "sk-one"), support = SupportConfig(enabled = true)))
            for (s in listOf(optedIn, notOptedIn, other)) {
                waitFor("${s.label}: config_changed on key saved") { s.frames().any { "\"type\":\"config_changed\"" in it } }
            }

            // A support_changed goes only to the owner's opted-in socket.
            for (s in listOf(optedIn, notOptedIn, other)) s.clear()
            val n = (service.file("u-anna", "phone", "phone-1", "It stops after ten minutes") as SupportService.Outcome.Ok).value.ticket.number
            waitFor("opted-in socket got support_changed") { optedIn.frames().any { it == """{"type":"support_changed","ticket":$n}""" } }
            delay(300)
            assertEquals(emptyList(), notOptedIn.frames(), "an installed app reads an unknown event as a config change")
            assertEquals(emptyList(), other.frames(), "another viewer's socket hears nothing about this ticket")
            assertEquals(1, adminSeen.size, "the admin's /ws event is sent too")

            // A key replaced by another key leaves the lane as it was: nothing is emitted.
            for (s in listOf(optedIn, notOptedIn, other)) s.clear()
            store.update(store.current.copy(ai = AiConfig(apiKey = "sk-two")))
            delay(300)
            assertEquals(emptyList(), notOptedIn.frames())

            // The key removed, then put back: one config_changed each, and the ticket is exactly as it was.
            store.update(store.current.copy(ai = AiConfig(apiKey = "")))
            waitFor("config_changed on key removed") { notOptedIn.frames().count { "config_changed" in it } == 1 }
            assertTrue(!service.available)
            store.update(store.current.copy(ai = AiConfig(apiKey = "sk-three")))
            waitFor("config_changed on key put back") { notOptedIn.frames().count { "config_changed" in it } == 2 }
            assertTrue(service.available)
            assertEquals(1, service.list("u-anna").tickets.size)
            assertEquals("It stops after ten minutes", service.thread("u-anna", n)!!.messages.first().body)

            // `[support] enabled` flipped: the same event.
            notOptedIn.clear()
            store.update(store.current.copy(support = SupportConfig(enabled = false)))
            waitFor("config_changed on switch off") { notOptedIn.frames().count { "config_changed" in it } == 1 }
        } finally {
            runCatching { client.close() }
            runCatching { server.stop(100, 500) }
            scope.cancel()
            dev.jellystructure.db.closeLastDatabaseForTests()
            for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
            listOf(tomlPath, "$tomlPath.tmp").forEach { runCatching { platform.posix.remove(it) } }
        }
    }
}
