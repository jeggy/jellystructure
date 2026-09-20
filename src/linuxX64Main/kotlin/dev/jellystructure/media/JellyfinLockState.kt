package dev.jellystructure.media

import dev.jellystructure.model.MediaItem

/**
 * Phase 251 (FR-251-2) — a scan must not clear a lock it cannot see.
 *
 * ## The measurement
 *
 * Jellyfin **12.1.0** stopped sending `LockData` and `LockedFields` on the list shape
 * `GET /Items?Ids=…&Fields=…` — the shape every scanner path and `JellyfinClient.getItem` use — even
 * though the request asks for them by name. `GET /Items/{id}?userId=…` still returns both,
 * unconditionally, with or without `Fields=`. Measured 2026-09-20 against a fresh 12.1.0 container
 * with the lock deliberately **set** first (`LockData: true`, `LockedFields: ["Name"]`, accepted with
 * 204):
 *
 * | shape | `LockData` | `LockedFields` |
 * |---|---|---|
 * | `GET /Items/{id}?userId=…` | `true` | `["Name"]` |
 * | `GET /Items?Ids=…&Fields=…LockData,LockedFields…` | absent | absent |
 *
 * ## Why it was silent, and why it was worse than silent
 *
 * `JellyfinItem` declared both with defaults (`false` / `emptyList()`), so an absent field was
 * indistinguishable from Jellyfin saying "nothing is locked". Nothing threw, nothing logged, and the
 * product simply believed no title had ever been locked — and then **wrote that belief into its own
 * database** on every scan and on every visit to a media detail page.
 *
 * ## The rule
 *
 * The Scanner builds its `MediaItem` from the list shape, which cannot carry this state, so it may
 * never write it — exactly the reasoning phase 153 applied to `nfoWrittenAt`/`nfoHash`/`jfSyncedAt`
 * one field over. Lock state is carried forward from the stored item on the scan path, and only a
 * caller that has genuinely read the detail shape (`/api/media/{id}/jellyfin-locks`) is allowed to
 * overwrite it, by opting out of this guard.
 */
internal fun preserveJellyfinLockState(fresh: MediaItem, existing: MediaItem?): MediaItem {
    val old = existing ?: return fresh
    return fresh.copy(
        jellyfinLockData = old.jellyfinLockData,
        jellyfinLockedFields = old.jellyfinLockedFields,
    )
}
