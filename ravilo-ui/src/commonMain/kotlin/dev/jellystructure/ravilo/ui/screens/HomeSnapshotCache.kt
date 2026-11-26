package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.SNAPSHOT_MAX_BYTES
import dev.jellystructure.shared.tv.SNAPSHOT_STALE_AFTER_MS
import dev.jellystructure.shared.tv.isSnapshotFresh
import dev.jellystructure.shared.tv.Skin
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * R212 — the last successfully loaded Home feed + display settings, persisted per user so a
 * returning viewer sees real content instantly on cold start instead of a shimmer skeleton and a
 * default-skin flash. Always an exact copy of a prior server response — [HomeStore]/`RaviloApp`
 * never merge or derive it — and always superseded the instant a fresh fetch succeeds (see the
 * phase spec's Invariants).
 */
@Serializable
data class HomeSnapshot(
    val feed: HomeFeed,
    val uiLanguage: String,
    val skin: Skin,
    val tileScale: Float,
    val gridColumns: Int,
    val portraitGridColumns: Int,
    val savedAtEpochMs: Long,
    // R338 — the viewer's theme settings as last drawn, so a cold start resolves the right theme before the config
    // arrives (a snapshot from an older build has none and draws [skin], as before).
    val theme: String? = null,
    val themeFollow: Boolean? = null,
    val themeLight: String? = null,
    val themeDark: String? = null,
    // DEB-3966 (FR-6) — Screen scale as last drawn, so a cold start draws its first frame at the saved scale (a snapshot
    // from an older build has none and draws at 100).
    val uiScalePct: Int = 100,
)

/** R338 — the theme settings a snapshot carries, or null from a build before R338. */
fun HomeSnapshot.themeSettings(): dev.jellystructure.ravilo.ui.theme.ThemeSettings? = themeFollow?.let { follow ->
    val skinId = dev.jellystructure.shared.tv.RaviloThemes.fromSkin(skin)
    dev.jellystructure.ravilo.ui.theme.ThemeSettings(follow, themeLight ?: dev.jellystructure.shared.tv.RaviloThemes.DAYLIGHT, themeDark ?: skinId, theme ?: skinId)
}

/** A snapshot older than this is treated as absent (phase-R212 FR-RV-R212-4). DEB-5697 (FR-61): the rule lives in `shared`, the Samsung's too. */
const val HOME_SNAPSHOT_STALE_AFTER_MS: Long = SNAPSHOT_STALE_AFTER_MS

/** Defensive backstop only — see [SNAPSHOT_MAX_BYTES]. `save()` silently skips (keeping whatever was previously cached) rather than
 *  ever writing something this large. */
const val HOME_SNAPSHOT_MAX_BYTES: Long = SNAPSHOT_MAX_BYTES

// R318 — a stored feed is server data too: a snapshot written by a newer build must not fail to load.
internal val homeSnapshotJson = dev.jellystructure.shared.tv.RaviloWireJson

/** The staleness check (FR-RV-R212-4) on a snapshot; the rule itself is `shared`'s [isSnapshotFresh]. */
internal fun isSnapshotFresh(snapshot: HomeSnapshot, nowEpochMs: Long): Boolean = isSnapshotFresh(snapshot.savedAtEpochMs, nowEpochMs)

/** The size guard (part of the "Storage guardrails" in the phase spec); the rule itself is `shared`'s [exceedsSnapshotSizeCap]. */
internal fun exceedsSnapshotSizeCap(json: String): Boolean = dev.jellystructure.shared.tv.exceedsSnapshotSizeCap(json)

/**
 * One overwritten file/entry per user — never a growing history. Images are never part of this
 * cache; `HomeFeed`'s cards carry poster/backdrop *URLs* only, the pixels stay exclusively in
 * Coil's own separately-budgeted disk cache.
 */
expect object HomeSnapshotCache {
    /** Null if nothing is cached for [userId], or the cached snapshot is older than
     *  [HOME_SNAPSHOT_STALE_AFTER_MS]. */
    fun load(userId: String): HomeSnapshot?

    /** Full overwrite, never an append. Silently skips (see [HOME_SNAPSHOT_MAX_BYTES]) rather than
     *  throwing — a failed cache write must never disrupt the Home screen it's a pure side-channel to. */
    fun save(userId: String, snapshot: HomeSnapshot)

    /** Called wherever a session is removed (`MultiTokenStore.remove()`'s call sites) so a
     *  signed-out profile doesn't leave its snapshot behind indefinitely. */
    fun clear(userId: String)
}
