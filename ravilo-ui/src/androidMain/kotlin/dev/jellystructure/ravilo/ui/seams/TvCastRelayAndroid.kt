package dev.jellystructure.ravilo.ui.seams

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import dev.jellystructure.ravilo.castv2.CastDevice
import dev.jellystructure.ravilo.castv2.CastSession
import dev.jellystructure.ravilo.castv2.castTxtStrings
import dev.jellystructure.ravilo.castv2.isOwnCastDevice
import dev.jellystructure.ravilo.castv2.openCastTransport
import dev.jellystructure.ravilo.castv2.preferredCastHost
import dev.jellystructure.ravilo.ui.RaviloAppContext
import dev.jellystructure.shared.tv.CastLoadData
import dev.jellystructure.shared.tv.CastSeenDevice
import dev.jellystructure.shared.tv.newCastQueueId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** R378 — only an Android TV relays this way; a phone keeps its Cast SDK (R370). */
actual fun platformTvCastRelay(): TvCastRelay? =
    if (runCatching { RaviloAppContext.isTelevision }.getOrDefault(false)) AndroidTvCastRelay else null

/**
 * R378 (FR-R378-2) — the reach report from what a TV found: never its own receiver, never a group, and only a device that
 * said our receiver app can run there ([available], asked once per device as the Mac does, R330 D2). Same ids and kinds
 * as a phone's report: the TXT `id` is the Cast SDK's `CastDevice.deviceId` (R370 review item 3).
 */
internal fun tvCastSeenOf(
    devices: List<CastDevice>, ownName: String?, ownAddresses: Set<String>, available: (CastDevice) -> Boolean?,
): List<CastSeenDevice> = devices
    .filter { it.kind != "group" && !isOwnCastDevice(it, ownName, ownAddresses) && available(it) == true }
    .distinctBy { it.id }
    .map { CastSeenDevice(castDeviceId = it.id, name = it.name, kind = if (it.kind == "speaker") "speaker" else "display") }
    .sortedBy { it.castDeviceId }

/**
 * R378 (FR-R378-1..-4) — the Android TV's relay: `_googlecast._tcp` through Android's own `NsdManager` while the app is
 * on screen, and a relay launch over `:ravilo-castv2` (TLS to port 8009, LAUNCH, LOAD, leave). Logged under
 * `RaviloSessions` like every other relay; nothing is drawn.
 */
internal object AndroidTvCastRelay : TvCastRelay {
    private const val SERVICE_TYPE = "_googlecast._tcp"
    private const val RESOLVE_TIMEOUT_MS = 5_000L
    private const val ASK_TIMEOUT_MS = 8_000L
    private const val RELAY_TIMEOUT_MS = 45_000L
    private const val LOADED_WAIT_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _seen = MutableStateFlow<List<CastSeenDevice>>(emptyList())
    override val seen: StateFlow<List<CastSeenDevice>> = _seen.asStateFlow()

    private val lock = Any()
    /** By service name (what `onServiceLost` names); guarded by [lock]. */
    private val found = LinkedHashMap<String, CastDevice>()
    private var listener: NsdManager.DiscoveryListener? = null
    /** Bumped by every start and stop, so a resolve that finishes after a stop adds nothing. */
    private var generation = 0
    @Volatile private var appId: String? = null
    /** Below Android 14 `NsdManager` resolves one service at a time (a second is refused as already active). */
    private val resolving = Mutex()
    /** `appId/deviceId` → whether our receiver can run there; only a definite answer is kept (R330 D2). */
    private val availability = ConcurrentHashMap<String, Boolean>()
    private val asking = ConcurrentHashMap.newKeySet<String>()
    /** FR-R378-4 — one relay at a time. */
    private val relaying = AtomicBoolean(false)

    private fun nsd(): NsdManager? = runCatching { RaviloAppContext.get().getSystemService(Context.NSD_SERVICE) as? NsdManager }.getOrNull()

    override fun discover(appId: String?, on: Boolean) {
        if (appId != null) this.appId = appId
        synchronized(lock) {
            val want = on && appId != null
            if (want && listener == null) start() else if (!want && listener != null) stop()
        }
    }

    private fun start() {
        val mgr = nsd() ?: return
        val gen = ++generation
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { sessionLog("R378: looking for Cast devices") }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                sessionLog("R378: could not look for Cast devices (NsdManager error $errorCode)")
                synchronized(lock) { if (listener === this) listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { scope.launch { resolve(info, gen) } }
            override fun onServiceLost(info: NsdServiceInfo) {
                val gone = synchronized(lock) { found.remove(info.serviceName) }
                if (gone != null) { sessionLog("R378: ${gone.name} is gone"); publish() }
            }
        }
        listener = l
        runCatching { mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { listener = null; sessionLog("R378: could not look for Cast devices: ${it.message}") }
    }

    private fun stop() {
        val l = listener ?: return
        listener = null
        generation++
        runCatching { nsd()?.stopServiceDiscovery(l) }
        found.clear()
        _seen.value = emptyList()
    }

    private suspend fun resolve(info: NsdServiceInfo, gen: Int) {
        val (addresses, resolved) = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) resolveNow(info) else resolving.withLock { resolveLegacy(info) })
            ?: return sessionLog("R378: ${info.serviceName} did not resolve")
        // FR-R378-1 — IPv4 first: a link-local IPv6 address without its scope cannot be reached (the Mac's lesson).
        val host = preferredCastHost(addresses) ?: return sessionLog("R378: ${info.serviceName} has no address to reach (${addresses.joinToString()})")
        val device = CastDevice.fromTxt(castTxtStrings(resolved.attributes.orEmpty()), host, resolved.port) ?: return
        val added = synchronized(lock) {
            if (gen != generation) false else { found[info.serviceName] = device; true }
        }
        if (!added) return
        sessionLog("R378: found ${device.name} [${device.model}] at $host (${device.kind})")
        ask(device)
        publish()
    }

    /** Android 14+: the service's every address (`getHostAddresses`), waiting a moment for an IPv4 one to arrive. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun resolveNow(info: NsdServiceInfo): Pair<List<String>, NsdServiceInfo>? {
        val mgr = nsd() ?: return null
        val updates = Channel<NsdServiceInfo>(Channel.CONFLATED)
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) { updates.close() }
            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) { updates.trySend(serviceInfo) }
            override fun onServiceLost() {}
            override fun onServiceInfoCallbackUnregistered() { updates.close() }
        }
        val direct = Executor { it.run() }
        if (runCatching { mgr.registerServiceInfoCallback(info, direct, callback) }.isFailure) return null
        var last: NsdServiceInfo? = null
        try {
            withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                for (u in updates) {
                    last = u
                    if (u.hostAddresses.any { it is java.net.Inet4Address }) break
                }
            }
        } finally {
            runCatching { mgr.unregisterServiceInfoCallback(callback) }
        }
        val r = last ?: return null
        return r.hostAddresses.mapNotNull { it.hostAddress } to r
    }

    /** Below Android 14: `resolveService`, one at a time; a refusal as already active is tried again once. */
    @Suppress("DEPRECATION")
    private suspend fun resolveLegacy(info: NsdServiceInfo): Pair<List<String>, NsdServiceInfo>? {
        val mgr = nsd() ?: return null
        repeat(2) { attempt ->
            val outcome = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                suspendCancellableCoroutine<Pair<NsdServiceInfo?, Int>> { cont ->
                    val l = object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { if (cont.isActive) cont.resume(null to errorCode) }
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) { if (cont.isActive) cont.resume(serviceInfo to 0) }
                    }
                    runCatching { mgr.resolveService(info, l) }.onFailure { if (cont.isActive) cont.resume(null to -1) }
                }
            } ?: return null
            val r = outcome.first
            if (r != null) return listOfNotNull(r.host?.hostAddress) to r
            if (outcome.second != NsdManager.FAILURE_ALREADY_ACTIVE || attempt == 1) return null
            delay(500)
        }
        return null
    }

    /** R330 D2 — asked once per device: can our receiver run there? A device that gave no answer is asked again when seen again. */
    private fun ask(device: CastDevice) {
        val app = appId ?: return
        if (device.kind == "group" || isOwnCastDevice(device, ownName(), ownAddresses())) return
        val key = "$app/${device.id}"
        if (availability.containsKey(key) || !asking.add(key)) return
        scope.launch {
            var session: CastSession? = null
            val answer = try {
                withTimeoutOrNull(ASK_TIMEOUT_MS) {
                    runCatching {
                        coroutineScope {
                            val s = CastSession(openCastTransport(device.host, device.port), app, this).also { session = it }
                            s.start()
                            s.appAvailableOrNull().also { s.close("asked") }
                        }
                    }.getOrNull()
                }
            } finally {
                session?.close("asked")
            }
            asking.remove(key)
            if (answer != null) {
                availability[key] = answer
                if (!answer) sessionLog("R378: ${device.name} cannot run the Ravilo receiver; not reported")
                publish()
            }
        }
    }

    private fun publish() {
        val app = appId
        val list = synchronized(lock) { found.values.toList() }
        _seen.value = tvCastSeenOf(list, ownName(), ownAddresses()) { d -> app?.let { availability["$it/${d.id}"] } }
    }

    /** The TV's name, as its built-in receiver advertises it (`fn`). `DEVICE_NAME` is Android 7.1+. */
    private fun ownName(): String? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) null
        else runCatching { Settings.Global.getString(RaviloAppContext.get().contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()

    private fun ownAddresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .flatMap { it.inetAddresses.toList() }
            .mapNotNull { it.hostAddress }
            .toSet()
    }.getOrDefault(emptySet())

    override fun relay(castDeviceId: String, appId: String?, load: CastLoadData): Boolean {
        val app = appId ?: this.appId ?: return false
        val device = synchronized(lock) { found.values.firstOrNull { it.id == castDeviceId } } ?: return false
        if (!relaying.compareAndSet(false, true)) {
            sessionLog("R378: a relay is already in flight; not relaying to ${device.name}")
            return false
        }
        sessionLog("R378: relay launch on ${device.name} ($castDeviceId) for ${load.sessionId} at ${load.positionMs} ms")
        scope.launch {
            try { relayNow(device, app, load) } finally { relaying.set(false) }
        }
        return true
    }

    /**
     * FR-R378-3 — connect, LAUNCH (or join) our receiver, LOAD the hand-off as the Mac's sender does, wait for the
     * receiver to take it, then close the connection: the receiver plays on, joined to the server with its own hand-off,
     * and nothing is mirrored or handed back (FR-R378-4: the TV never holds a lasting link).
     */
    private suspend fun relayNow(d: CastDevice, app: String, load: CastLoadData) {
        var session: CastSession? = null
        val outcome = try {
            withTimeoutOrNull(RELAY_TIMEOUT_MS) {
                runCatching {
                    coroutineScope {
                        val s = CastSession(openCastTransport(d.host, d.port), app, this).also { session = it }
                        s.start()
                        if (!s.launchOrJoin()) {
                            s.close("not launched")
                            return@coroutineScope "the receiver did not start (${s.closedReason ?: "no app"})"
                        }
                        val frames = castRelayFrames(load, newCastQueueId())
                        val bytes = s.load(frames.media, frames.startSec, autoplay = true, customData = null)
                        frames.parts.forEach { s.sendCustom(it) }
                        val media = withTimeoutOrNull(LOADED_WAIT_MS) { s.media.first { it != null && it.playerState != "IDLE" } }
                        s.close("relayed")
                        "LOAD sent (${(bytes + 512) / 1024} KB, ${frames.parts.size} parts); ${media?.playerState?.lowercase() ?: "no media status in ${LOADED_WAIT_MS / 1000} s"}; left it playing"
                    }
                }.getOrElse { "failed: ${it.message ?: it::class.simpleName}" }
            } ?: "gave up after ${RELAY_TIMEOUT_MS / 1000} s"
        } finally {
            session?.close("relayed")
        }
        sessionLog("R378: relay on ${d.name}: $outcome")
    }
}
