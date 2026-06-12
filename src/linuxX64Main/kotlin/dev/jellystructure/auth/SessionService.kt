package dev.jellystructure.auth

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.posix.CLOCK_REALTIME
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.clock_gettime
import platform.posix.open
import platform.posix.read
import platform.posix.rename
import platform.posix.timespec

private const val SESSION_TTL_MS = 7L * 24 * 60 * 60 * 1000

class SessionService(private val storeFile: String) {
    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, SessionData>()
    private val json = Json { ignoreUnknownKeys = true }

    init { load() }

    suspend fun create(
        jellyfinUserId: String,
        jellyfinUsername: String,
        jellyfinUserToken: String,
    ): String {
        val token = generateSecureToken()
        val session = SessionData(
            token = token,
            jellyfinUserId = jellyfinUserId,
            jellyfinUsername = jellyfinUsername,
            jellyfinUserToken = jellyfinUserToken,
            expiresAt = nowMs() + SESSION_TTL_MS,
        )
        mutex.withLock {
            sessions[token] = session
            persist()
        }
        return token
    }

    fun validate(token: String): SessionData? {
        val session = sessions[token] ?: return null
        if (session.expiresAt < nowMs()) return null
        return session
    }

    suspend fun revoke(token: String) = mutex.withLock {
        sessions.remove(token)
        persist()
    }

    private fun load() {
        val path = Path(storeFile)
        if (!SystemFileSystem.exists(path)) return
        runCatching {
            val content = SystemFileSystem.source(path).buffered().readString()
            val list = json.decodeFromString<List<SessionData>>(content)
            val now = nowMs()
            list.filter { it.expiresAt > now }.forEach { sessions[it.token] = it }
        }.onFailure { println("[WARN] Could not load sessions: ${it.message}") }
    }

    private fun persist() {
        val tmp = "$storeFile.tmp"
        runCatching {
            val content = json.encodeToString(sessions.values.toList())
            val sink = SystemFileSystem.sink(Path(tmp)).buffered()
            sink.writeString(content)
            sink.flush()
            sink.close()
            rename(tmp, storeFile)
        }.onFailure { println("[ERROR] Could not persist sessions: ${it.message}") }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun nowMs(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, ts.ptr)
    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}

@OptIn(ExperimentalForeignApi::class)
fun generateSecureToken(): String {
    val bytes = ByteArray(32)
    bytes.usePinned { pinned ->
        val fd = open("/dev/urandom", O_RDONLY)
        read(fd, pinned.addressOf(0), 32.convert())
        close(fd)
    }
    return bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
