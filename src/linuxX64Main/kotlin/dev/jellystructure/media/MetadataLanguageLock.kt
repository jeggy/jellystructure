package dev.jellystructure.media

import dev.jellystructure.model.MediaItem

/**
 * Phase 184 (FR-184-3) — the [MediaItem.metadataLanguage] equivalent of [preserveLockedArtwork]/
 * [preserveTmdbMatchLock], applied at both store write choke points. A fresh `MediaItem` built by a
 * scan carries the field at its `null` default (Scanner never sets it — it's an operator-only field
 * consulted, not written, by [Scanner.rescanMetadata]), so without this guard an operator's chosen
 * language would be silently forgotten on the very next scheduled scan/sync/re-pull.
 *
 * Simpler than the artwork/match-lock guards: there's no residue to strip on the way back to
 * automatic (that's an explicit action of its own, not something a store write ever discovers), so
 * this only ever carries the value forward — never clears it.
 */
internal fun preserveMetadataLanguage(fresh: MediaItem, existing: MediaItem?): MediaItem {
    val locked = existing?.metadataLanguage ?: return fresh
    return fresh.copy(metadataLanguage = locked, metadataLanguageSetAt = existing.metadataLanguageSetAt)
}
