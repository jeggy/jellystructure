package dev.jellystructure.ravilo.ui.desktop

import dev.jellystructure.ravilo.castv2.CastDevice
import dev.jellystructure.ravilo.castv2.CastSession
import dev.jellystructure.ravilo.castv2.JmdnsCastBrowser
import dev.jellystructure.ravilo.castv2.openCastTransport
import dev.jellystructure.ravilo.ui.seams.CastPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * R330 (FR-R330-2) — the household's Cast devices, while someone needs them: the sheet (only while the app is on
 * screen, R293) and the start-up reconnect. On a Mac with its library, macOS's own Bonjour (dev review 1); elsewhere
 * JmDNS. Reference-counted: the last [release] stops browsing.
 */
internal object CastDiscovery {
    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var users = 0
    private var job: Job? = null
    private val jmdns by lazy { JmdnsCastBrowser() }

    fun acquire() = synchronized(this) { if (users++ == 0) begin() }

    fun release() = synchronized(this) {
        if (users == 0) return
        if (--users == 0) end()
    }

    private fun begin() {
        val lib = MacNative.lib
        job = if (lib != null) {
            lib.ravilo_bonjour_start()
            scope.launch {
                while (isActive) {
                    val (state, found) = parseSnapshot(MacNative.take(lib.ravilo_bonjour_snapshot()).orEmpty())
                    CastPlatform.reportLocalNetworkDenied(state == 1)
                    _devices.value = found
                    delay(1_000)
                }
            }
        } else {
            jmdns.start()
            scope.launch { jmdns.devices.collect { _devices.value = it } }
        }
    }

    private fun end() {
        job?.cancel(); job = null
        val lib = MacNative.lib
        if (lib != null) lib.ravilo_bonjour_stop() else jmdns.stop()
        _devices.value = emptyList()
    }

    /** Bonjour.swift's snapshot: `state=N`, then `id · fn · md · ca · rs · host · port` per line, tab-separated. */
    fun parseSnapshot(text: String): Pair<Int, List<CastDevice>> {
        val lines = text.lines().filter { it.isNotBlank() }
        val state = lines.firstOrNull()?.removePrefix("state=")?.toIntOrNull() ?: 0
        val devices = lines.drop(1).mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 7) return@mapNotNull null
            val txt = mapOf("id" to f[0], "fn" to f[1], "md" to f[2], "ca" to f[3], "rs" to f[4])
            CastDevice.fromTxt(txt, f[5], f[6].toIntOrNull() ?: return@mapNotNull null)
        }
        return state to devices.distinctBy { it.id }
    }
}

/**
 * R330 D2 — a device is listed only once `GET_APP_AVAILABILITY` says our app can run on it, asked once per device
 * per app session. Only a definite answer is remembered: a device that could not be asked (asleep, a timeout) is
 * asked again the next time it is seen. Also remembers the name our app runs under (from any status that shows it),
 * so a device already running Ravilo is not called busy.
 */
internal object CastAvailability {
    private val answers = ConcurrentHashMap<String, Boolean>()
    private val asking = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _changed = MutableStateFlow(0)
    val changed: StateFlow<Int> = _changed.asStateFlow()
    @Volatile var ourDisplayName: String? = null

    fun known(device: CastDevice, appId: String): Boolean? = answers["$appId/${device.id}"]

    fun ask(device: CastDevice, appId: String) {
        val key = "$appId/${device.id}"
        if (answers.containsKey(key) || !asking.add(key)) return
        scope.launch {
            var session: CastSession? = null
            val answer = try {
                withTimeoutOrNull(8_000) {
                    runCatching {
                        coroutineScope {
                            val s = CastSession(openCastTransport(device.host, device.port), appId, this).also { session = it }
                            s.start()
                            val available = s.appAvailable()
                            s.requestReceiverStatus()?.apps?.firstOrNull { it.appId == appId }?.displayName?.let { ourDisplayName = it }
                            s.close("asked")
                            available
                        }
                    }.getOrNull()
                }
            } finally {
                session?.close("asked")
            }
            if (answer != null) { answers[key] = answer; _changed.value++ }
            asking.remove(key)
        }
    }
}
