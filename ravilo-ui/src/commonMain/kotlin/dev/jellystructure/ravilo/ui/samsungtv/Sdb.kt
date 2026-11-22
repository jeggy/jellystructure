package dev.jellystructure.ravilo.ui.samsungtv

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * DEB-4843 (FR-5) — the Samsung debug bridge, as much of it as an install needs: ADB-framed packets over TCP 26101, a `shell`
 * service and the sync `push`. Framing and sequencing are plain Kotlin over [SdbTransport], so a fake TV tests them; the socket
 * is the platform's.
 *
 * The rules the real 2019 TV taught (DEB-4783): ONE command per fresh connection — a second command on a connection hangs
 * `sdbd` — so [SdbClient] dials anew for every command; push and `vd_appinstall` are separate connections with a wait between;
 * every command runs under a timeout; the answer's text is checked, never an exit status; the raw answer goes to the log.
 */
interface SdbTransport {
    suspend fun write(bytes: ByteArray)
    /** Exactly [n] bytes, or throws [SdbException] when the peer closes first. */
    suspend fun readFully(n: Int): ByteArray
    fun close()
}

fun interface SdbDialer {
    suspend fun dial(host: String, port: Int): SdbTransport
}

class SdbException(message: String) : Exception(message)

internal class SdbPacket(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray = ByteArray(0))

internal object SdbWire {
    const val CNXN = 0x4e584e43
    const val OPEN = 0x4e45504f
    const val OKAY = 0x59414b4f
    const val CLSE = 0x45534c43
    const val WRTE = 0x45545257
    const val VERSION = 0x01000000
    const val MAX_DATA = 4096
    const val HEADER = 24
    private const val MAX_PAYLOAD = 1 shl 20

    fun le32(v: Int): ByteArray = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    fun readLe32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 255) or ((b[at + 1].toInt() and 255) shl 8) or ((b[at + 2].toInt() and 255) shl 16) or ((b[at + 3].toInt() and 255) shl 24)

    fun checksum(data: ByteArray): Int { var s = 0; for (x in data) s += x.toInt() and 255; return s }

    fun encode(p: SdbPacket): ByteArray =
        le32(p.command) + le32(p.arg0) + le32(p.arg1) + le32(p.data.size) + le32(checksum(p.data)) + le32(p.command.inv()) + p.data

    suspend fun decode(t: SdbTransport): SdbPacket {
        val h = t.readFully(HEADER)
        val command = readLe32(h, 0)
        if (readLe32(h, 20) != command.inv()) throw SdbException("bad packet magic")
        val len = readLe32(h, 12)
        if (len < 0 || len > MAX_PAYLOAD) throw SdbException("bad packet length $len")
        val data = if (len == 0) ByteArray(0) else t.readFully(len)
        return SdbPacket(command, readLe32(h, 4), readLe32(h, 8), data)
    }
}

/** One connection: handshake, then exactly one service. */
internal class SdbSession(private val t: SdbTransport) {
    private var maxData = SdbWire.MAX_DATA
    private val localId = 1
    private var remoteId = 0

    suspend fun handshake(): String {
        t.write(SdbWire.encode(SdbPacket(SdbWire.CNXN, SdbWire.VERSION, SdbWire.MAX_DATA, "host::\u0000".encodeToByteArray())))
        while (true) {
            val p = SdbWire.decode(t)
            if (p.command == SdbWire.CNXN) {
                if (p.arg1 in 256..SdbWire.MAX_DATA) maxData = p.arg1
                return p.data.decodeToString()
            }
            if (p.command != SdbWire.OKAY) throw SdbException("unexpected reply to connect")
        }
    }

    suspend fun open(service: String) {
        t.write(SdbWire.encode(SdbPacket(SdbWire.OPEN, localId, 0, (service + "\u0000").encodeToByteArray())))
        val p = SdbWire.decode(t)
        if (p.command != SdbWire.OKAY) throw SdbException("the TV refused $service")
        remoteId = p.arg0
    }

    /** Sends [data] in packets of at most the negotiated size, each acknowledged before the next. */
    suspend fun write(data: ByteArray) {
        var at = 0
        while (at < data.size) {
            val n = minOf(maxData, data.size - at)
            t.write(SdbWire.encode(SdbPacket(SdbWire.WRTE, localId, remoteId, data.copyOfRange(at, at + n))))
            at += n
            while (true) {
                val p = SdbWire.decode(t)
                if (p.command == SdbWire.OKAY) break
                if (p.command == SdbWire.CLSE) throw SdbException("the TV closed the connection")
            }
        }
    }

    /** Reads stream bytes until the TV closes (or, when [atLeast] > 0, until that many have arrived). */
    suspend fun read(atLeast: Int = 0): ByteArray {
        var out = ByteArray(0)
        while (atLeast == 0 || out.size < atLeast) {
            val p = SdbWire.decode(t)
            when (p.command) {
                SdbWire.WRTE -> {
                    out += p.data
                    t.write(SdbWire.encode(SdbPacket(SdbWire.OKAY, localId, remoteId)))
                }
                SdbWire.CLSE -> return out
                else -> {}
            }
        }
        return out
    }

    suspend fun close() {
        try { t.write(SdbWire.encode(SdbPacket(SdbWire.CLSE, localId, remoteId))) } catch (_: Exception) {}
    }
}

class SdbClient(
    private val dialer: SdbDialer,
    private val log: (String) -> Unit = {},
    /** The wait between two connections to the same TV. */
    private val pause: suspend () -> Unit = { delay(2500) },
    private val port: Int = 26101,
) {
    private suspend fun <T> connection(host: String, timeoutMs: Long, what: String, block: suspend (SdbSession) -> T): T {
        val t = dialer.dial(host, port)
        try {
            return withTimeout(timeoutMs) {
                val s = SdbSession(t)
                log("sdb $what: connected, ${s.handshake().take(200)}")
                try { block(s) } finally { s.close() }
            }
        } finally {
            t.close()
        }
    }

    /** One `shell` command on a fresh connection; the raw answer is logged and returned. */
    suspend fun shell(host: String, command: String, timeoutMs: Long = 30_000): String =
        connection(host, timeoutMs, "shell $command") { s ->
            s.open("shell:$command")
            s.read().decodeToString().also { log("sdb shell $command -> ${it.trim().take(500)}") }
        }

    /** One sync `push` on a fresh connection. [onProgress] gets bytes sent so far. */
    suspend fun push(host: String, remotePath: String, bytes: ByteArray, onProgress: (Long) -> Unit = {}, timeoutMs: Long = 300_000) =
        connection(host, timeoutMs, "push $remotePath") { s ->
            s.open("sync:")
            val spec = "$remotePath,33206".encodeToByteArray()
            s.write("SEND".encodeToByteArray() + SdbWire.le32(spec.size) + spec)
            var at = 0
            while (at < bytes.size) {
                val n = minOf(SYNC_CHUNK, bytes.size - at)
                s.write("DATA".encodeToByteArray() + SdbWire.le32(n) + bytes.copyOfRange(at, at + n))
                at += n
                onProgress(at.toLong())
            }
            s.write("DONE".encodeToByteArray() + SdbWire.le32(0))
            val reply = s.read(atLeast = 8)
            val tag = reply.copyOfRange(0, 4).decodeToString()
            log("sdb push $remotePath -> $tag (${bytes.size} bytes)")
            if (tag != "OKAY") throw SdbException("the TV refused the file")
        }

    /** The TV's device id: the first 8–32 character run of capitals and digits in the answer. */
    suspend fun readDuid(host: String): String {
        val answer = shell(host, "0 getduid")
        return DuidParse.first(answer) ?: throw SdbException("this TV did not give its ID")
    }

    /** Push, wait, `vd_appinstall`, wait, launch — each on its own connection. Each step's answer text is checked. */
    suspend fun install(host: String, wgt: ByteArray, packageId: String, appId: String, onStage: (Stage, Long) -> Unit = { _, _ -> }) {
        val remote = "$TV_TMP/$packageId.wgt"
        onStage(Stage.SENDING, 0)
        push(host, remote, wgt, { onStage(Stage.SENDING, it) })
        pause()
        onStage(Stage.INSTALLING, 0)
        val installed = shell(host, "0 vd_appinstall $packageId $remote", timeoutMs = 240_000)
        if (!installed.contains("install completed")) throw SdbException("the TV did not finish installing")
        pause()
        val launched = shell(host, "0 was_execute $appId", timeoutMs = 60_000)
        if (!launched.contains("launched")) throw SdbException("the TV did not open the app")
    }

    enum class Stage { SENDING, INSTALLING }

    private companion object {
        const val SYNC_CHUNK = 64 * 1024
        const val TV_TMP = "/home/owner/share/tmp/sdk_tools"
    }
}

internal object DuidParse {
    private val run = Regex("(?<![A-Za-z0-9])[A-Z0-9]{8,32}(?![A-Za-z0-9])")
    fun first(answer: String): String? = run.find(answer)?.value
}
