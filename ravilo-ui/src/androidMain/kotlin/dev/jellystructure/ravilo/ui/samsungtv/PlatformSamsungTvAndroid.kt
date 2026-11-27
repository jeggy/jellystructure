package dev.jellystructure.ravilo.ui.samsungtv

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import dev.jellystructure.ravilo.ui.RaviloAppContext
import dev.jellystructure.ravilo.ui.isTvPlatform
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

private const val TAG = "RaviloSamsungTv"

/** DEB-4843 (FR-1) — only the phone build; a TV never offers this. */
actual fun platformSamsungTv(): SamsungTvPlatform? = if (isTvPlatform) null else AndroidSamsungTv

private object AndroidSamsungTv : SamsungTvPlatform {
    // A short client for the subnet scan: a neighbour that does not answer in a second is not a TV.
    private val probeClient by lazy {
        RaviloAppContext.okHttp.newBuilder().connectTimeout(1, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).callTimeout(3, TimeUnit.SECONDS).build()
    }

    override fun log(line: String) { Log.i(TAG, line) }

    override val sdb = SdbClient(dialer = { host, port -> SocketTransport.dial(host, port) }, log = ::log)

    /** The first IPv4 address of the active network when it is Wi-Fi; null on mobile data or no network. */
    override fun phoneAddress(): String? {
        val cm = RaviloAppContext.get().getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        return cm.getLinkProperties(net)?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull { !it.isLoopbackAddress }?.hostAddress
    }

    override suspend fun probe(ip: String): String? = withContext(Dispatchers.IO) {
        try {
            probeClient.newCall(Request.Builder().url("http://$ip:8001/api/v2/").build()).execute().use { r ->
                if (r.isSuccessful) r.body?.source()?.let { it.request(MAX_PROBE); it.buffer.snapshot().utf8() } else null
            }
        } catch (_: Exception) { null }
    }

    /** FR-11 — the status and body of the TV's own answer about Ravilo, a 404 included; null when nothing answered. */
    override suspend fun appReply(ip: String): TvAppReply? = withContext(Dispatchers.IO) {
        try {
            probeClient.newCall(Request.Builder().url("http://$ip:8001/api/v2/applications/${SamsungGuide.APP_ID}").build()).execute().use { r ->
                TvAppReply(r.code, r.body?.source()?.let { it.request(MAX_PROBE); it.buffer.snapshot().utf8() }.orEmpty())
            }
        } catch (_: Exception) { null }
    }

    private val prefs get() = RaviloAppContext.get().getSharedPreferences("ravilo_samsung_tv", android.content.Context.MODE_PRIVATE)
    override fun lastInstalledTag(duid: String): String? = prefs.getString("tag.$duid", null)
    override fun rememberInstalledTag(duid: String, tag: String) { prefs.edit().putString("tag.$duid", tag).apply() }

    override suspend fun download(url: String, onProgress: (Long, Long?) -> Unit): ByteArray = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "refusing a non-HTTPS package" }
        RaviloAppContext.okHttp.newCall(Request.Builder().url(url).build()).execute().use { r ->
            check(r.isSuccessful) { "download answered ${r.code}" }
            val total = r.body?.contentLength()?.takeIf { it > 0 }
            check(total == null || total <= MAX_WGT) { "package too large" }
            val out = java.io.ByteArrayOutputStream()
            val src = r.body!!.byteStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                check(out.size() <= MAX_WGT) { "package too large" }
                onProgress(out.size().toLong(), total)
            }
            out.toByteArray()
        }
    }

    override fun packageDeviceIds(wgt: ByteArray): Set<String>? {
        ZipInputStream(ByteArrayInputStream(wgt)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: return null
                if (e.name == "signature1.xml") return WgtSignature.deviceIds(zip.readNBytes(MAX_SIGNATURE).decodeToString())
            }
        }
    }

    private const val MAX_PROBE = 64L * 1024
    private const val MAX_WGT = 40L * 1024 * 1024
    private const val MAX_SIGNATURE = 256 * 1024
}

private class SocketTransport(private val socket: Socket) : SdbTransport {
    private val input = DataInputStream(socket.getInputStream())
    private val output = socket.getOutputStream()

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        try { output.write(bytes); output.flush() } catch (e: java.io.IOException) { throw SdbException("the TV closed the connection") }
    }

    override suspend fun readFully(n: Int): ByteArray = withContext(Dispatchers.IO) {
        try { ByteArray(n).also { input.readFully(it) } } catch (e: java.io.IOException) { throw SdbException("the TV closed the connection") }
    }

    override fun close() { runCatching { socket.close() } }

    companion object {
        suspend fun dial(host: String, port: Int): SdbTransport = withContext(Dispatchers.IO) {
            val s = Socket()
            try { s.connect(InetSocketAddress(host, port), 5_000); s.soTimeout = 60_000; s.tcpNoDelay = true } catch (e: java.io.IOException) {
                runCatching { s.close() }; throw SdbException("couldn't connect to the TV")
            }
            SocketTransport(s)
        }
    }
}
