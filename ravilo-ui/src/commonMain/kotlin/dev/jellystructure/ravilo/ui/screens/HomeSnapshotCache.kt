package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.HomeFeed
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
)

/** A snapshot older than this is treated as absent (phase-R212 FR-RV-R212-4) — a starting point,
 *  not load-bearing; easy to tune without any structural change. */
const val HOME_SNAPSHOT_STALE_AFTER_MS: Long = 7L * 24 * 60 * 60 * 1000

/** Defensive backstop only — a realistic Home feed serializes to well under this (phase-R212
 *  "Storage guardrails"). `save()` silently skips (keeping whatever was previously cached) rather
 *  than ever writing something this large. */
const val HOME_SNAPSHOT_MAX_BYTES: Long = 2L * 1024 * 1024

internal val homeSnapshotJson = Json { ignoreUnknownKeys = true }

/** Pure staleness check (FR-RV-R212-4), factored out of the platform actuals so it's unit-testable
 *  from commonTest without needing an actual `load()`/file system. `nowEpochMs` is a parameter
 *  (never `Clock.System.now()` internally) purely so tests can pin "now" — the actuals always pass
 *  the real current time. A negative age (clock skew) is treated as stale, not fresh. */
internal fun isSnapshotFresh(snapshot: HomeSnapshot, nowEpochMs: Long): Boolean {
    val ageMs = nowEpochMs - snapshot.savedAtEpochMs
    return ageMs in 0..HOME_SNAPSHOT_STALE_AFTER_MS
}

/** Pure size-guard check (part of the "Storage guardrails" in the phase spec), also factored out
 *  for the same testability reason as [isSnapshotFresh]. */
internal fun exceedsSnapshotSizeCap(json: String): Boolean = json.length.toLong() > HOME_SNAPSHOT_MAX_BYTES

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
