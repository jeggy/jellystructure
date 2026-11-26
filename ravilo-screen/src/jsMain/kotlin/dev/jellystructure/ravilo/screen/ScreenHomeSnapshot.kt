package dev.jellystructure.ravilo.screen

import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.RaviloWireJson
import dev.jellystructure.shared.tv.exceedsSnapshotSizeCap
import dev.jellystructure.shared.tv.isSnapshotFresh
import kotlinx.browser.localStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * DEB-5697 (DEB-5685, FR-61) — Android TV's R212 (`HomeSnapshotCache`) on the Samsung: the last Home feed that drew from the network, one per
 * profile in `localStorage` under the profile's user id, so a cold start draws it at once instead of a skeleton. An exact copy of a server answer
 * (the router never merges or derives it), fresh for 7 days and 2 MB at most — the rules are `shared`'s, Android TV's too. A channel's feed is not saved.
 */
internal object ScreenHomeSnapshot {
    private const val KEY_PREFIX = "ravilo.screen.homeSnapshot."

    /** What `HomeFeed` needs of Android TV's `HomeSnapshot`: the feed and when it was saved. */
    @Serializable
    private data class Saved(val feed: HomeFeed, val savedAtEpochMs: Long)

    /** The profile's feed, or null when none is saved, it is older than 7 days, or it no longer decodes (a key a build changed). */
    fun load(userId: String, nowMs: Long): HomeFeed? {
        val raw = runCatching { localStorage.getItem(KEY_PREFIX + userId) }.getOrNull() ?: return null
        val saved = runCatching { RaviloWireJson.decodeFromString<Saved>(raw) }.getOrNull() ?: return null
        return saved.feed.takeIf { isSnapshotFresh(saved.savedAtEpochMs, nowMs) }
    }

    /** A full overwrite. A feed over 2 MB encoded, or a store that refuses the write, leaves what was there; a save never disturbs the page. */
    fun save(userId: String, feed: HomeFeed, nowMs: Long) {
        val json = runCatching { RaviloWireJson.encodeToString(Saved(feed, nowMs)) }.getOrNull() ?: return
        if (exceedsSnapshotSizeCap(json)) return
        runCatching { localStorage.setItem(KEY_PREFIX + userId, json) }
    }

    /** Signing a profile out (FR-3, FR-60). */
    fun delete(userId: String) {
        runCatching { localStorage.removeItem(KEY_PREFIX + userId) }
    }

    /** *Change server* (FR-11): the saved feeds belong to the server being left. */
    fun deleteAll() {
        runCatching {
            val keys = (0 until localStorage.length).mapNotNull { localStorage.key(it) }.filter { it.startsWith(KEY_PREFIX) }
            keys.forEach { localStorage.removeItem(it) }
        }
    }
}
