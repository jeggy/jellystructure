package dev.jellystructure.ravilo.ui.desktop

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * R328 — the desktop's SharedPreferences: one small JSON object of strings per [name], held in memory and written
 * through on every change (a temporary file, then an atomic rename, so a crash mid-write leaves the old file).
 * [private] files are created `0600` — the Linux fallback for tokens (FR-R328-4). Unreadable content reads as empty:
 * a corrupt preference must never stop the app from starting.
 */
class PrefsFile(name: String, private val private: Boolean = false, dir: File = DesktopPaths.dataDir) {
    private val file = File(dir, "$name.json")
    private val lock = Any()
    private var cache: MutableMap<String, String>? = null

    fun get(key: String): String? = synchronized(lock) { map()[key] }

    /** Null removes. */
    fun put(key: String, value: String?) = synchronized(lock) {
        val m = map()
        val changed = if (value == null) m.remove(key) != null else m.put(key, value) != value
        if (changed) save(m)
    }

    fun remove(vararg keys: String) = synchronized(lock) {
        val m = map()
        if (keys.fold(false) { any, k -> (m.remove(k) != null) || any }) save(m)
    }

    fun keys(): Set<String> = synchronized(lock) { map().keys.toSet() }

    fun clear() = synchronized(lock) {
        cache = mutableMapOf()
        file.delete()
    }

    private fun map(): MutableMap<String, String> = cache ?: load().also { cache = it }

    private fun load(): MutableMap<String, String> = runCatching {
        if (!file.isFile) mutableMapOf() else json.decodeFromString(serializer, file.readText()).toMutableMap()
    }.getOrElse { mutableMapOf() }

    private fun save(m: Map<String, String>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            if (private) restrict(tmp)
            tmp.writeText(json.encodeToString(serializer, m))
            if (private) restrict(tmp)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { println("Ravilo: could not write ${file.name}: ${it.message}") }
    }

    private fun restrict(f: File) {
        runCatching {
            if (!f.exists()) f.createNewFile()
            Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val serializer = MapSerializer(String.serializer(), String.serializer())
    }
}
