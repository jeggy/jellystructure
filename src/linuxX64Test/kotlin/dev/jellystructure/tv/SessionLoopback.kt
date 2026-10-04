package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.DeviceKey
import dev.jellystructure.auth.SessionData
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.server.routes.playbackSessionRoutes
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.test.fail

/**
 * R369's loopback harness (specs: `SessionCommandIntegrationTest`, `PlaybackStartIntegrationTest`,
 * `SessionRoomsIntegrationTest`, `SessionMoveIntegrationTest`): an embedded CIO server on `127.0.0.1:0` with the real
 * [TvEventBus], [PlaybackSessions], [SessionPublisher], [SessionControl] and [SessionStarter] on a temporary database,
 * the real `playbackSessionRoutes(…)`, and an events socket that does what `/api/tv/events` does (features, the list on
 * connect, attach/detach and `cast_devices_seen`). A request names its device with `X-Test-Device`/`X-Test-User` (the
 * real auth plugin is not under test); `X-Test-Admin` stands for the admin's cookie. Fake devices are plain sockets.
 */
internal class SessionLoopback(tag: String) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val run = getpid().toString()
    private val dbPath = "/tmp/jellystructure-test-loop-$tag-$run.db"
    private val tomlPath = "/tmp/jellystructure-test-loop-$tag-$run.toml"
    val db = createDatabase(dbPath)
    val devices = RaviloDeviceService(db)
    val bus = TvEventBus(scope)
    val sessions = PlaybackSessions(db)
    var household = false
    val publisher = SessionPublisher(sessions, devices, bus, canSee = { _, _, _, _ -> true }, scope = scope)
    val control = SessionControl(db, sessions, devices, bus)
    val cast = CastService(db, ConfigStore(tomlPath), devices)
    val starter = SessionStarter(sessions, control, devices, bus, cast, CastReach(), serverUrl = { "https://media.example.test" })
    /** What an item is: film unless set here. */
    val kinds = mutableMapOf<String, String>()

    init {
        sessions.describe = { id, _ -> (kinds[id] ?: SessionKind.FILM) to SessionItem(id, "Title $id", null, null, 3_600_000L) }
        control.householdControl = { household }
        publisher.control = control
        publisher.controlWidened = { true }
        publisher.householdControl = { household }
        publisher.opsOf = { s -> control.opsOf(s) }
        publisher.starter = starter
        sessions.stopPlace = { s, from -> if (bus.isConnected(from)) bus.notifyPlaystateCommand(s.ownerUserId, from, "Stop", null) }
        control.relayAvailable = { s -> starter.relayAvailable(s) }
        control.relayLoad = { s -> starter.relayResume(s) }
        control.relayAppFor = { s -> starter.relayAppFor(s) }
        control.rememberRoomLevel = { id, room, level -> sessions.rememberRoomLevel(id, room, level) }
        sessions.notify = { change -> publisher.publish(change); if (change is SessionChange.List) starter.targetsChanged() }
    }

    fun device(id: String, user: String, kind: String, platform: String? = null, name: String = id): DeviceData = devices.loginDevice(
        deviceId = id, deviceName = name, jellyfinUserId = user, jellyfinUsername = user.removePrefix("u-"), jellyfinUserToken = "jf",
        isAdmin = false, isKids = false, platform = platform, kind = kind,
    ).first.also { devices.recordAddress(id, user, "198.51.100.7") }

    fun fresh(d: DeviceData): DeviceData = devices.listSessions(d.deviceId).first { it.jellyfinUserId == d.jellyfinUserId }

    private val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(WebSockets)
        intercept(ApplicationCallPipeline.Plugins) {
            val d = call.request.headers["X-Test-Device"]
            val u = call.request.headers["X-Test-User"]
            if (d != null) devices.listSessions(d).firstOrNull { it.jellyfinUserId == u }?.let { call.attributes.put(DeviceKey, it) }
            if (call.request.headers["X-Test-Admin"] != null) call.attributes.put(SessionKey, SessionData("t", "u-admin", "admin", "jf", Long.MAX_VALUE))
        }
        routing {
            route("/api") {
                playbackSessionRoutes(publisher, sessions, control, { on -> household = on; publisher.publish(SessionChange.List) }, starter)
            }
            webSocket("/events") {
                val user = call.request.queryParameters["user"]!!
                val id = call.request.queryParameters["device"]!!
                val features = parseEventFeatures(call.request.queryParameters["features"]) + parsePlays(call.request.queryParameters["plays"])
                bus.tryRegister(user, id, this, features)
                val device = devices.listSessions(id).first { it.jellyfinUserId == user }
                if (dev.jellystructure.shared.tv.EVENTS_FEATURE_SESSIONS in features) send(Frame.Text(publisher.listFrameFor(device)))
                try {
                    for (f in incoming) if (f is Frame.Text) runCatching { publisher.onSocketMessage(f.readText(), device) }
                } finally {
                    if (!bus.unregister(user, id, this)) runCatching { publisher.onSocketClosed(id) }
                }
            }
        }
    }.start(wait = false)
    private val port = server.engine.let { runBlocking { it.resolvedConnectors().first().port } }
    private val client = HttpClient(Curl) { install(io.ktor.client.plugins.websocket.WebSockets) }

    /** One fake device's events socket: what it received, and a way to send. */
    inner class Sock(val device: DeviceData, private val features: String?, private val plays: String?) {
        private val lock = Mutex()
        private val got = mutableListOf<String>()
        val out = Channel<String>(Channel.UNLIMITED)
        val job = scope.launch {
            val q = "user=${device.jellyfinUserId}&device=${device.deviceId}" + (features?.let { "&features=$it" } ?: "") + (plays?.let { "&plays=$it" } ?: "")
            runCatching {
                client.webSocket("ws://127.0.0.1:$port/events?$q") {
                    launch { for (t in out) send(Frame.Text(t)) }
                    for (fr in incoming) if (fr is Frame.Text) lock.withLock { got += fr.readText() }
                }
            }
        }
        suspend fun frames(): List<String> = lock.withLock { got.toList() }
        suspend fun frames(type: String): List<String> = frames().filter { "\"type\":\"$type\"" in it }
        suspend fun clear() = lock.withLock { got.clear() }
        fun close() = job.cancel()
    }

    suspend fun open(d: DeviceData, features: String?, plays: String? = null): Sock {
        val s = Sock(d, features, plays)
        waitFor("${d.deviceId} registered") { bus.isConnected(d.deviceId) }
        return s
    }

    data class Resp(val status: Int, val body: String)

    suspend fun post(path: String, body: String, as_: DeviceData? = null, admin: Boolean = false): Resp {
        val r = client.post("http://127.0.0.1:$port/api$path") {
            as_?.let { header("X-Test-Device", it.deviceId); header("X-Test-User", it.jellyfinUserId) }
            if (admin) header("X-Test-Admin", "1")
            setBody(TextContent(body, ContentType.Application.Json))
        }
        return Resp(r.status.value, r.bodyAsText())
    }

    suspend fun get(path: String, as_: DeviceData? = null, admin: Boolean = false): Resp {
        val r = client.get("http://127.0.0.1:$port/api$path") {
            as_?.let { header("X-Test-Device", it.deviceId); header("X-Test-User", it.jellyfinUserId) }
            if (admin) header("X-Test-Admin", "1")
        }
        return Resp(r.status.value, r.bodyAsText())
    }

    fun close() {
        runCatching { client.close() }
        runCatching { server.stop(100, 500) }
        scope.cancel()
        dev.jellystructure.db.closeLastDatabaseForTests()
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
        runCatching { platform.posix.remove(tomlPath) }
    }

    companion object {
        suspend fun waitFor(what: String, timeoutMs: Long = 10_000, check: suspend () -> Boolean) {
            val ok = runCatching { withTimeout(timeoutMs) { while (!check()) delay(25) } }.isSuccess
            if (!ok) fail("timed out waiting for: $what")
        }

        /** Runs [body] on a fresh harness, unless the process is already near Kotlin/Native's FD_SETSIZE. */
        fun loopback(tag: String, body: suspend SessionLoopback.() -> Unit) = runBlocking {
            val open = openDescriptorCount()
            if (open > 700) { println("SessionLoopback($tag): $open descriptors already open (${fdCensus()}) — skipped in this run"); return@runBlocking }
            val h = SessionLoopback(tag)
            try { h.body() } finally { h.close() }
        }
    }
}

/** How many file descriptors this process holds (/proc/self/fd). */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal fun openDescriptorCount(): Int {
    val dir = platform.posix.opendir("/proc/self/fd") ?: return 0
    var n = 0
    while (platform.posix.readdir(dir) != null) n++
    platform.posix.closedir(dir)
    return n
}
