package dev.jellystructure.ravilo.ui.samsungtv

/** A fake TV `sdbd`: answers the handshake, shell commands from [answers], and a sync push; wedges on a second OPEN per connection. */
class FakeSdbTv(private val answers: Map<String, String>) : SdbDialer {
    val commands = mutableListOf<String>()
    var connections = 0
    var wedged = false
    val pushed = mutableMapOf<String, ByteArray>()
    var refusePush = false

    override suspend fun dial(host: String, port: Int): SdbTransport {
        connections++
        return Conn()
    }

    private inner class Conn : SdbTransport {
        private var inbox = ByteArray(0)
        private var outbox = ByteArray(0)
        private var opens = 0
        private var sync = false
        private var stream = ByteArray(0)
        private var pushPath: String? = null
        private var pushData = ByteArray(0)

        override suspend fun write(bytes: ByteArray) {
            inbox += bytes
            while (inbox.size >= SdbWire.HEADER) {
                val len = SdbWire.readLe32(inbox, 12)
                if (inbox.size < SdbWire.HEADER + len) return
                val cmd = SdbWire.readLe32(inbox, 0)
                val a0 = SdbWire.readLe32(inbox, 4)
                val data = inbox.copyOfRange(SdbWire.HEADER, SdbWire.HEADER + len)
                check(SdbWire.readLe32(inbox, 16) == SdbWire.checksum(data)) { "bad checksum" }
                check(SdbWire.readLe32(inbox, 20) == cmd.inv()) { "bad magic" }
                inbox = inbox.copyOfRange(SdbWire.HEADER + len, inbox.size)
                handle(cmd, a0, data)
            }
        }

        private fun reply(cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) { outbox += SdbWire.encode(SdbPacket(cmd, a0, a1, data)) }

        private fun handle(cmd: Int, a0: Int, data: ByteArray) {
            when (cmd) {
                SdbWire.CNXN -> reply(SdbWire.CNXN, SdbWire.VERSION, SdbWire.MAX_DATA, "device::ro.product=fake".encodeToByteArray())
                SdbWire.OPEN -> {
                    if (++opens > 1) { wedged = true; return }   // the real TV answers nothing to a second command
                    val service = data.decodeToString().trimEnd('\u0000')
                    reply(SdbWire.OKAY, 7, a0)
                    if (service == "sync:") { sync = true; return }
                    val command = service.removePrefix("shell:")
                    commands += command
                    reply(SdbWire.WRTE, 7, a0, (answers[command] ?: "").encodeToByteArray())
                    reply(SdbWire.CLSE, 7, a0)
                }
                SdbWire.WRTE -> {
                    reply(SdbWire.OKAY, 7, a0)
                    if (sync) syncBytes(data, a0)
                }
            }
        }

        private fun syncBytes(data: ByteArray, local: Int) {
            stream += data
            while (stream.size >= 8) {
                val tag = stream.copyOfRange(0, 4).decodeToString()
                val n = SdbWire.readLe32(stream, 4)
                if (tag == "DONE") {
                    stream = stream.copyOfRange(8, stream.size)
                    pushPath?.let { pushed[it] = pushData }
                    reply(SdbWire.WRTE, 7, local, (if (refusePush) "FAIL" else "OKAY").encodeToByteArray() + SdbWire.le32(0))
                    return
                }
                if (stream.size < 8 + n) return
                val body = stream.copyOfRange(8, 8 + n)
                stream = stream.copyOfRange(8 + n, stream.size)
                if (tag == "SEND") pushPath = body.decodeToString().substringBefore(',') else if (tag == "DATA") pushData += body
            }
        }

        override suspend fun readFully(n: Int): ByteArray {
            if (outbox.size < n) throw SdbException("the TV closed the connection")
            return outbox.copyOfRange(0, n).also { outbox = outbox.copyOfRange(n, outbox.size) }
        }

        override fun close() {}
    }
}
