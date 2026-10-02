package dev.jellystructure.config

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinLibrary
import dev.jellystructure.log.Logger
import dev.jellystructure.media.MediaStore

/**
 * Phase 298 — the saved library mappings follow Jellyfin's libraries, renames included.
 *
 * A Jellyfin library's `ItemId` is a hash of its name, so a rename gives it a new id and a new library created
 * under an old name inherits the old one. Matching saved mappings by id alone (what Settings did) therefore kept
 * the old name and attributed titles to whichever library holds that name today. `Locations` survive a rename, so
 * they are the first key.
 */
object LibraryReconciler {

    /** One change worth a log line. */
    data class Change(val message: String, val oldId: String? = null, val newId: String? = null, val mapping: LibraryMapping? = null)

    data class Result(val mappings: List<LibraryMapping>, val changes: List<Change>)

    /** FR-298-1/2 — pure. Jellyfin's list is the list; an empty answer changes nothing. */
    fun reconcile(saved: List<LibraryMapping>, jellyfin: List<JellyfinLibrary>): Result {
        if (jellyfin.isEmpty()) return Result(saved, emptyList())
        val claimed = mutableSetOf<Int>()
        val changes = mutableListOf<Change>()
        val out = jellyfin.map { lib ->
            val locations = lib.locations.map { norm(it) }.filter { it.isNotEmpty() }.toSet()
            val byPath = saved.indices.firstOrNull { i ->
                i !in claimed && norm(saved[i].jellyfinPath).let { it.isNotEmpty() && it in locations }
            }
            val idx = byPath ?: saved.indices.firstOrNull { i ->
                i !in claimed && saved[i].jellyfinPath.isBlank() && saved[i].jellyfinId == lib.id
            }
            val type = lib.collectionType.orEmpty()
            if (idx == null) {
                changes += Change("New library in Jellyfin: ${lib.name} (${lib.id}) — added as skipped until it is mapped")
                LibraryMapping(jellyfinId = lib.id, name = lib.name, collectionType = type, jellyfinPath = lib.locations.firstOrNull().orEmpty(), localPath = "", skip = true)
            } else {
                claimed += idx
                val m = saved[idx]
                val updated = m.copy(jellyfinId = lib.id, name = lib.name, collectionType = type)
                if (m.jellyfinId != lib.id || m.name != lib.name) {
                    changes += Change(
                        if (m.name != lib.name) "Library renamed in Jellyfin: ${m.name} → ${lib.name} (${m.jellyfinId} → ${lib.id})"
                        else "Library ${lib.name} has a new Jellyfin id (${m.jellyfinId} → ${lib.id})",
                        oldId = m.jellyfinId.ifBlank { null }, newId = lib.id, mapping = updated,
                    )
                } else if (m.collectionType != type) {
                    changes += Change("Library ${lib.name} changed type in Jellyfin: ${m.collectionType.ifBlank { "(none)" }} → ${type.ifBlank { "(none)" }}")
                }
                updated
            }
        }
        for (i in saved.indices) if (i !in claimed) changes += Change("Library ${saved[i].name} (${saved[i].jellyfinId}) is no longer in Jellyfin — removed")
        return Result(out, changes)
    }

    private fun norm(p: String): String = p.trim().trimEnd('/')

    /** FR-298-3/5 — reads Jellyfin, saves the result when it differs, and re-stamps titles whose library's id
     *  changed. Returns the mappings now in force. Never throws: a failure leaves the config as it was. */
    suspend fun reconcileNow(jellyfinClient: JellyfinClient, configStore: ConfigStore, mediaStore: MediaStore?): List<LibraryMapping> {
        val cfg = configStore.current
        if (cfg.apiKeys.jellyfinUrl.isBlank() || cfg.apiKeys.jellyfinToken.isBlank()) return cfg.libraries
        val libs = runCatching { jellyfinClient.getLibraries(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken) }.getOrDefault(emptyList())
        val result = reconcile(cfg.libraries, libs)
        if (result.mappings == cfg.libraries) return cfg.libraries
        // Re-read immediately before writing so a Save that landed while Jellyfin answered is not overwritten.
        val latest = configStore.current
        val again = if (latest.libraries == cfg.libraries) result else reconcile(latest.libraries, libs)
        if (again.mappings == latest.libraries) return latest.libraries
        if (!configStore.update(latest.copy(libraries = again.mappings))) {
            Logger.warn("Library reconcile: could not save the config; leaving it as it was", "config")
            return latest.libraries
        }
        for (c in again.changes) Logger.info(c.message, "config")
        if (mediaStore != null) {
            for (c in again.changes) {
                val oldId = c.oldId ?: continue
                val newId = c.newId ?: continue
                val m = c.mapping ?: continue
                val prefix = m.localPath.ifBlank { m.jellyfinPath }
                if (prefix.isBlank()) continue
                runCatching { mediaStore.restampLibraryId(oldId, newId, prefix) }
                    .onFailure { Logger.warn("Library reconcile: re-stamping titles of ${m.name} failed: ${it.message}", "config") }
            }
        }
        return again.mappings
    }
}
