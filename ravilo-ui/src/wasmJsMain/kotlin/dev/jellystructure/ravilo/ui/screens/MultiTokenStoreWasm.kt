@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui.screens

// Wasm-safe helpers: all js() calls must be top-level functions
private fun jsGet(key: String): String? = js("localStorage.getItem(key)")
private fun jsSet(key: String, value: String): Unit = js("localStorage.setItem(key, value)")
private fun jsRemove(key: String): Unit = js("localStorage.removeItem(key)")

// Avatar URLs are stored separately per-user to avoid embedding URLs in the session JSON.
private fun loadAvatar(userId: String): String? = jsGet("ravilo_avatar_$userId")
private fun saveAvatar(userId: String, url: String?) {
    if (url != null) jsSet("ravilo_avatar_$userId", url) else jsRemove("ravilo_avatar_$userId")
}

private fun loadRaw(): String? = jsGet("ravilo_sessions")
private fun saveRaw(v: String) = jsSet("ravilo_sessions", v)
private fun loadActiveId(): String? = jsGet("ravilo_active_user")
private fun saveActiveId(id: String?) { if (id != null) jsSet("ravilo_active_user", id) else jsRemove("ravilo_active_user") }

// Simple JSON serialization without kotlinx.serialization in wasm actual
// Format: JSON array of {"u":userId,"n":displayName,"t":token,"a":isAdmin}
private fun parseAll(raw: String): List<LocalSession> {
    return raw.removePrefix("[").removeSuffix("]")
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
            val a = map["a"] == "true"
            LocalSession(u, n, t, a)
        }
}

private fun encodeAll(list: List<LocalSession>): String =
    list.joinToString(",", "[", "]") { s ->
        """{"u":"${s.userId}","n":"${s.displayName}","t":"${s.deviceToken}","a":${s.isAdmin}}"""
    }

actual object MultiTokenStore {
    actual fun getAll(): List<LocalSession> {
        val raw = loadRaw() ?: return emptyList()
        return runCatching { parseAll(raw) }.getOrDefault(emptyList())
            .map { it.copy(avatarUrl = loadAvatar(it.userId)) }
    }

    actual fun add(session: LocalSession) {
        val list = getAll().filter { it.userId != session.userId }.toMutableList()
        list.add(session)
        saveRaw(encodeAll(list.map { it.copy(avatarUrl = null) }))
        saveAvatar(session.userId, session.avatarUrl)
        setActive(session.userId)
    }

    actual fun remove(userId: String) {
        val list = getAll().filter { it.userId != userId }
        saveRaw(encodeAll(list.map { it.copy(avatarUrl = null) }))
        saveAvatar(userId, null)
        if (loadActiveId() == userId) saveActiveId(list.firstOrNull()?.userId)
    }

    actual fun getActive(): LocalSession? {
        val activeId = loadActiveId()
        return getAll().firstOrNull { it.userId == activeId }
    }

    actual fun setActive(userId: String) { saveActiveId(userId) }

    actual fun clear() { jsRemove("ravilo_sessions"); jsRemove("ravilo_active_user") }
}
