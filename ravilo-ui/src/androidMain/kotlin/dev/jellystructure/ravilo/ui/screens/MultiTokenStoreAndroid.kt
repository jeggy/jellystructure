package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.RaviloAppContext
import org.json.JSONArray
import org.json.JSONObject

private data class StoredSession(
    val userId: String,
    val displayName: String,
    val deviceToken: String,
    val isAdmin: Boolean,
    val isKids: Boolean = false,
    val avatarUrl: String? = null,
)

actual object MultiTokenStore {
    private val prefs get() = RaviloAppContext.get()
        .getSharedPreferences("ravilo_sessions", android.content.Context.MODE_PRIVATE)

    // R211 — in-memory cache of the parsed session list. Every getAll()/getActive() call used to
    // re-read + re-parse the whole SharedPreferences JSON blob from scratch, hit redundantly during
    // just the first composition of RaviloApp plus once per API call for the device token. The list
    // only ever changes through this object's own add/remove/setActive/clear, so a simple
    // invalidate-on-write cache is correct with no merge logic needed.
    private var cache: List<StoredSession>? = null

    private fun loadAll(): List<StoredSession> {
        cache?.let { return it }
        val raw = prefs.getString("sessions", null)
        val parsed = if (raw == null) emptyList() else runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                StoredSession(
                    userId       = o.getString("userId"),
                    displayName  = o.getString("displayName"),
                    deviceToken  = o.getString("deviceToken"),
                    isAdmin      = o.optBoolean("isAdmin", false),
                    isKids       = o.optBoolean("isKids", false),
                    avatarUrl    = o.optString("avatarUrl").ifEmpty { null },
                )
            }
        }.getOrDefault(emptyList())
        cache = parsed
        return parsed
    }

    private fun saveAll(list: List<StoredSession>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("userId",      s.userId)
                put("displayName", s.displayName)
                put("deviceToken", s.deviceToken)
                put("isAdmin",     s.isAdmin)
                put("isKids",      s.isKids)
                if (s.avatarUrl != null) put("avatarUrl", s.avatarUrl)
            })
        }
        prefs.edit().putString("sessions", arr.toString()).apply()
        cache = null // invalidate; the next loadAll() re-reads and re-populates it
    }

    actual fun getAll(): List<LocalSession> = loadAll().map { it.toLocal() }

    actual fun add(session: LocalSession) {
        val list = loadAll().filter { it.userId != session.userId }.toMutableList()
        list.add(StoredSession(session.userId, session.displayName, session.deviceToken, session.isAdmin, session.isKids, session.avatarUrl))
        saveAll(list)
        setActive(session.userId)
    }

    actual fun remove(userId: String) {
        val list = loadAll().filter { it.userId != userId }
        saveAll(list)
        if (prefs.getString("active_user", null) == userId) {
            prefs.edit().putString("active_user", list.firstOrNull()?.userId).apply()
        }
    }

    actual fun getActive(): LocalSession? {
        val activeId = prefs.getString("active_user", null)
        return loadAll().firstOrNull { it.userId == activeId }?.toLocal()
    }

    actual fun setActive(userId: String) {
        prefs.edit().putString("active_user", userId).apply()
    }

    actual fun clear() {
        prefs.edit().remove("sessions").remove("active_user").apply()
        cache = null
    }

    private fun StoredSession.toLocal() = LocalSession(userId, displayName, deviceToken, isAdmin, isKids, avatarUrl)
}
