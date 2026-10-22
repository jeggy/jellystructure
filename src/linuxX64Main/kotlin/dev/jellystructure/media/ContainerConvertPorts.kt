package dev.jellystructure.media

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.model.MediaItem
import dev.jellystructure.torrent.SeedingCheckResult
import dev.jellystructure.torrent.SeedingGuard

/**
 * DEB-2051 — the production [ConversionPorts]. The seeding question is asked with `inPlace = false`: the original keeps
 * its inode (it is renamed aside, never rewritten), so a hard link elsewhere is unaffected and only the library path
 * itself blocks (FR-5.2, the lean in [SeedingGuard]'s own documentation). A preview that only asks is not a refusal.
 */
class ContainerConvertPorts(
    private val configStore: ConfigStore,
    private val seedingGuard: SeedingGuard,
    private val looseFilms: LooseFilmsService,
) : ConversionPorts {
    override suspend fun seeding(path: String): SeedingCheckResult =
        seedingGuard.check(path, configStore.current, inPlace = false, countRefusal = false)

    override suspend fun jellyfinPath(item: MediaItem): String? = looseFilms.jellyfinPathOf(item)

    override fun freeBytes(dir: String): Long? =
        captureShell("df -B1 --output=avail '${dir.replace("'", "'\\''")}' 2>/dev/null | tail -n1")?.trim()?.toLongOrNull()

    override suspend fun moveIdentity(item: MediaItem, oldPath: String, newPath: String): ConversionIdentityResult =
        looseFilms.moveIdentityAfterConversion(item, oldPath, newPath)
}
