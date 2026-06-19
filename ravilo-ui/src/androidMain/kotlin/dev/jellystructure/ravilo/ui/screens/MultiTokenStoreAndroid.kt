package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.RaviloAppContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class StoredSession(
    val userId: String,
    val displayName: String,
    val deviceToken: String,
    val isAdmin: Boolean,
)

private val json = Json { ignoreUnknownKeys = true }

actual object MultiTokenStore {
    private val prefs get() = RaviloAppContext.get()
        .getSharedPreferences("ravilo_sessions", android.content.Context.MODE_PRIVATE)

    private fun loadAll(): List<StoredSession> {
        val raw = prefs.getString("sessions", null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<StoredSession>>(raw) }.getOrDefault(emptyList())
    }

    private fun saveAll(list: List<StoredSession>) {
        prefs.edit().putString("sessions", json.encodeToString(list)).apply()
    }

    actual fun getAll(): List<LocalSession> = loadAll().map { it.toLocal() }

    actual fun add(session: LocalSession) {
        val list = loadAll().filter { it.userId != session.userId }.toMutableList()
        list.add(StoredSession(session.userId, session.displayName, session.deviceToken, session.isAdmin))
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

    private fun StoredSession.toLocal() = LocalSession(userId, displayName, deviceToken, isAdmin)
}
