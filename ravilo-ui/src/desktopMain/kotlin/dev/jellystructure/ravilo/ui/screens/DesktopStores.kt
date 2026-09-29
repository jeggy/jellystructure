package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.i18n.LastLanguageStore
import dev.jellystructure.ravilo.ui.DesktopApp
import dev.jellystructure.ravilo.ui.desktop.DesktopPaths
import dev.jellystructure.ravilo.ui.desktop.PrefsFile
import dev.jellystructure.ravilo.ui.desktop.SecretStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/*
 * R328 (FR-R328-3) — the desktop's stores: files in the data directory (`~/Library/Application Support/Ravilo/` on a
 * Mac), tokens in the Keychain through [SecretStore]. Each mirrors its Android actual's contract; none may throw.
 */

private val storeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** A Jellyfin user id is hex, but a file name built from one must never escape the directory. */
private fun safeName(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

actual object HomeSnapshotCache {
    private fun file(userId: String) = File(DesktopPaths.dataDir, "home_snapshot_${safeName(userId)}.json")

    actual fun load(userId: String): HomeSnapshot? = runCatching {
        val f = file(userId)
        if (!f.isFile) return null
        homeSnapshotJson.decodeFromString(HomeSnapshot.serializer(), f.readText())
    }.getOrNull()?.takeIf { isSnapshotFresh(it, System.currentTimeMillis()) }

    actual fun save(userId: String, snapshot: HomeSnapshot) {
        runCatching {
            val json = homeSnapshotJson.encodeToString(HomeSnapshot.serializer(), snapshot)
            if (exceedsSnapshotSizeCap(json)) return
            val f = file(userId)
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(json)
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    actual fun clear(userId: String) {
        runCatching { file(userId).delete() }
    }
}

/** R279 — survives sign-out and unpair: it is the device's, not a viewer's. */
actual object DeviceLanguageStore : LastLanguageStore {
    actual override fun read(): String? = runCatching { DesktopApp.prefs.get("ui_language") }.getOrNull()
    actual override fun write(code: String) {
        runCatching { DesktopApp.prefs.put("ui_language", code) }
    }
}

@Serializable
private data class StoredChoice(
    val seriesKey: String = "",
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
    val subtitlesOff: Boolean = false,
    val audioVariant: String? = null,
    val subtitleVariant: String? = null,
) {
    fun toChoice() = RememberedChoice(audioLanguage, subtitleLanguage, subtitlesOff, audioVariant, subtitleVariant)
}

private fun RememberedChoice.stored(seriesKey: String = "") =
    StoredChoice(seriesKey, audioLanguage, subtitleLanguage, subtitlesOff, audioVariant, subtitleVariant)

actual object PlaybackPrefsStore {
    private val file by lazy { PrefsFile("ravilo_playback_prefs") }
    private val listSerializer = ListSerializer(StoredChoice.serializer())

    private fun series(profileId: String): List<StoredChoice> =
        file.get("series_$profileId")?.let { runCatching { storeJson.decodeFromString(listSerializer, it) }.getOrNull() }.orEmpty()

    actual fun getSeriesChoice(profileId: String, seriesKey: String): RememberedChoice? =
        series(profileId).lastOrNull { it.seriesKey == seriesKey }?.toChoice()

    actual fun setSeriesChoice(profileId: String, seriesKey: String, choice: RememberedChoice) {
        val next = series(profileId).filter { it.seriesKey != seriesKey } + choice.stored(seriesKey)
        file.put("series_$profileId", storeJson.encodeToString(listSerializer, next))
    }

    actual fun getGlobalChoice(profileId: String): RememberedChoice? =
        file.get("global_$profileId")?.let { runCatching { storeJson.decodeFromString(StoredChoice.serializer(), it) }.getOrNull() }?.toChoice()

    actual fun setGlobalChoice(profileId: String, choice: RememberedChoice) =
        file.put("global_$profileId", storeJson.encodeToString(StoredChoice.serializer(), choice.stored()))

    actual fun clearProfile(profileId: String) = file.remove("series_$profileId", "global_$profileId")
}

@Serializable
private data class StoredSession(
    val userId: String,
    val displayName: String,
    val isAdmin: Boolean,
    val isKids: Boolean = false,
    val avatarUrl: String? = null,
)

/**
 * The sessions on this device: who they are in `ravilo_sessions.json`, each one's token in [SecretStore] under
 * `session.<userId>` — never in the plain file.
 */
actual object MultiTokenStore {
    private val file by lazy { PrefsFile("ravilo_sessions") }
    private val listSerializer = ListSerializer(StoredSession.serializer())
    private var cache: List<LocalSession>? = null

    private fun account(userId: String) = "session.$userId"

    actual fun getAll(): List<LocalSession> = synchronized(this) {
        cache ?: run {
            val stored = file.get("sessions")?.let { runCatching { storeJson.decodeFromString(listSerializer, it) }.getOrNull() }.orEmpty()
            // A session whose token is gone (a Keychain reset) is not a session: it is dropped, not shown.
            stored.mapNotNull { s ->
                val token = SecretStore.get(account(s.userId)) ?: return@mapNotNull null
                LocalSession(s.userId, s.displayName, token, s.isAdmin, s.isKids, s.avatarUrl)
            }
        }.also { cache = it }
    }

    private fun saveAll(sessions: List<LocalSession>) {
        cache = sessions
        val stored = sessions.map { StoredSession(it.userId, it.displayName, it.isAdmin, it.isKids, it.avatarUrl) }
        file.put("sessions", storeJson.encodeToString(listSerializer, stored))
    }

    actual fun add(session: LocalSession) = synchronized(this) {
        SecretStore.set(account(session.userId), session.deviceToken)
        saveAll(getAll().filter { it.userId != session.userId } + session)
        setActive(session.userId)
    }

    actual fun remove(userId: String) = synchronized(this) {
        SecretStore.delete(account(userId))
        val remaining = getAll().filter { it.userId != userId }
        saveAll(remaining)
        if (file.get("active_user") == userId) file.put("active_user", remaining.firstOrNull()?.userId)
    }

    actual fun getActive(): LocalSession? = synchronized(this) {
        val id = file.get("active_user") ?: return null
        getAll().firstOrNull { it.userId == id }
    }

    actual fun setActive(userId: String) = synchronized(this) { file.put("active_user", userId) }

    actual fun clear() = synchronized(this) {
        getAll().forEach { SecretStore.delete(account(it.userId)) }
        cache = null
        file.remove("sessions", "active_user")
    }
}
