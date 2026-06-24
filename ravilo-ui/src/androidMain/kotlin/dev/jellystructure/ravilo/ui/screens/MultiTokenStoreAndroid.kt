package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.RaviloAppContext
import org.json.JSONArray
import org.json.JSONObject

private data class StoredSession(
    val userId: String,
    val displayName: String,
    val deviceToken: String,
    val isAdmin: Boolean,
    val avatarUrl: String? = null,
)

actual object MultiTokenStore {
    private val prefs get() = RaviloAppContext.get()
        .getSharedPreferences("ravilo_sessions", android.content.Context.MODE_PRIVATE)

    private fun loadAll(): List<StoredSession> {
        val raw = prefs.getString("sessions", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                StoredSession(
                    userId       = o.getString("userId"),
                    displayName  = o.getString("displayName"),
                    deviceToken  = o.getString("deviceToken"),
                    isAdmin      = o.optBoolean("isAdmin", false),
                    avatarUrl    = o.optString("avatarUrl").ifEmpty { null },
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveAll(list: List<StoredSession>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("userId",      s.userId)
                put("displayName", s.displayName)
                put("deviceToken", s.deviceToken)
                put("isAdmin",     s.isAdmin)
                if (s.avatarUrl != null) put("avatarUrl", s.avatarUrl)
            })
        }
        prefs.edit().putString("sessions", arr.toString()).apply()
    }

    actual fun getAll(): List<LocalSession> = loadAll().map { it.toLocal() }

    actual fun add(session: LocalSession) {
        val list = loadAll().filter { it.userId != session.userId }.toMutableList()
        list.add(StoredSession(session.userId, session.displayName, session.deviceToken, session.isAdmin, session.avatarUrl))
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
    }

    private fun StoredSession.toLocal() = LocalSession(userId, displayName, deviceToken, isAdmin, avatarUrl)
}
