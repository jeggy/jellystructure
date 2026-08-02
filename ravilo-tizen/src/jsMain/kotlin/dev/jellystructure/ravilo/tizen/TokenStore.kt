package dev.jellystructure.ravilo.tizen

import kotlinx.browser.localStorage
import org.w3c.dom.get
import org.w3c.dom.set

/** R189 milestone 2 — a locally-cached user session: the device token + display info, mirroring
 *  `ravilo-ui`'s `LocalSession`/`MultiTokenStore` shape exactly (same field names/semantics) so the
 *  two clients' persisted-session concept stays in sync even though this store is hand-written, not
 *  shared code — `ravilo-ui`'s own `MultiTokenStore` is itself hand-written per-platform (wasm/Android),
 *  not part of the `shared` module, so there was nothing to literally share here either. */
data class LocalSession(
    val userId: String,
    val displayName: String,
    val deviceToken: String,
    val isAdmin: Boolean,
    val isKids: Boolean = false,
    val avatarUrl: String? = null,
)

private const val SESSIONS_KEY = "ravilo_sessions"
private const val ACTIVE_KEY = "ravilo_active_user"
private const val DEVICE_ID_KEY = "ravilo_device_id"

/** Hand-rolled encode/decode (same wire format as `ravilo-ui`'s wasm actual) — avoids pulling
 *  kotlinx.serialization into a tiny, purely-local persistence blob. */
object MultiTokenStore {
    fun getAll(): List<LocalSession> {
        val raw = localStorage[SESSIONS_KEY] ?: return emptyList()
        return runCatching { parseAll(raw) }.getOrDefault(emptyList())
    }

    fun add(session: LocalSession) {
        val list = getAll().filter { it.userId != session.userId }.toMutableList()
        list.add(session)
        localStorage[SESSIONS_KEY] = encodeAll(list)
        setActive(session.userId)
    }

    fun remove(userId: String) {
        val list = getAll().filter { it.userId != userId }
        localStorage[SESSIONS_KEY] = encodeAll(list)
        if (localStorage[ACTIVE_KEY] == userId) {
            list.firstOrNull()?.let { localStorage[ACTIVE_KEY] = it.userId } ?: localStorage.removeItem(ACTIVE_KEY)
        }
    }

    fun getActive(): LocalSession? {
        val activeId = localStorage[ACTIVE_KEY] ?: return null
        return getAll().firstOrNull { it.userId == activeId }
    }

    fun setActive(userId: String) { localStorage[ACTIVE_KEY] = userId }

    fun clear() {
        localStorage.removeItem(SESSIONS_KEY)
        localStorage.removeItem(ACTIVE_KEY)
    }

    private fun parseAll(raw: String): List<LocalSession> =
        raw.removePrefix("[").removeSuffix("]")
            .split("},{")
            .filter { it.isNotBlank() }
            .mapNotNull { chunk ->
                val s = chunk.trim().removePrefix("{").removeSuffix("}")
                val map = s.split(",").associate { kv ->
                    val (k, v) = kv.split(":", limit = 2)
                    k.trim().removeSurrounding("\"") to v.trim().removeSurrounding("\"")
                }
                val u = map["u"] ?: return@mapNotNull null
                val n = map["n"] ?: return@mapNotNull null
                val t = map["t"] ?: return@mapNotNull null
                LocalSession(u, n, t, map["a"] == "true", map["k"] == "true", map["v"]?.takeIf { it.isNotBlank() })
            }

    private fun encodeAll(list: List<LocalSession>): String =
        list.joinToString(",", "[", "]") { s ->
            """{"u":"${s.userId}","n":"${s.displayName}","t":"${s.deviceToken}","a":${s.isAdmin},"k":${s.isKids},"v":"${s.avatarUrl.orEmpty()}"}"""
        }
}

object DeviceIdStore {
    fun get(): String {
        localStorage[DEVICE_ID_KEY]?.let { return it }
        val id = "tizen-" + randomId()
        localStorage[DEVICE_ID_KEY] = id
        return id
    }

    private fun randomId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..16).map { chars.random() }.joinToString("")
    }
}
