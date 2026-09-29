package dev.jellystructure.ravilo.castv2

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * R330 (FR-R330-2, dev review 1) — `_googlecast._tcp.local.` on the Linux development build, where JmDNS is the
 * only Bonjour there is. The Mac browses with macOS's own (`NWBrowser`, in the Swift library), which is what its
 * Local Network permission is built around. Browsing runs only between [start] and [stop] (R293: on screen only).
 */
class JmdnsCastBrowser {
    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()
    private var jmdns: JmDNS? = null
    private val found = LinkedHashMap<String, CastDevice>()

    private val listener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) { event.dns.requestServiceInfo(event.type, event.name, 3_000) }
        override fun serviceRemoved(event: ServiceEvent) {
            synchronized(found) { found.remove(event.name); publish() }
        }
        override fun serviceResolved(event: ServiceEvent) {
            val device = event.info.toDevice() ?: return
            synchronized(found) { found[event.name] = device; publish() }
        }
    }

    private fun publish() { _devices.value = found.values.toList() }

    fun start() {
        if (jmdns != null) return
        val dns = runCatching { JmDNS.create(InetAddress.getLocalHost()) }.getOrNull() ?: return
        jmdns = dns
        dns.addServiceListener(TYPE, listener)
    }

    fun stop() {
        val dns = jmdns ?: return
        jmdns = null
        runCatching { dns.removeServiceListener(TYPE, listener); dns.close() }
        synchronized(found) { found.clear(); publish() }
    }

    private fun ServiceInfo.toDevice(): CastDevice? {
        val host = inet4Addresses.firstOrNull()?.hostAddress ?: inetAddresses.firstOrNull()?.hostAddress ?: return null
        val txt = propertyNames.toList().associateWith { getPropertyString(it).orEmpty() }
        return CastDevice.fromTxt(txt, host, port)
    }

    private companion object {
        const val TYPE = "_googlecast._tcp.local."
    }
}
