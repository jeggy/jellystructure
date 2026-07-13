package dev.jellystructure.tv

import dev.jellystructure.io.FileIo
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Phase 147 — jellystructure's presentation overrides for Jellyfin's Live TV channels, keyed by the
 * Jellyfin channel id (never a positional index — dev-review addendum B: Jellyfin gives no channel
 * `Number`, so [number] is entirely jellystructure-owned, seeded from the order index on first sync).
 */
@Serializable
data class LiveTvChannelOverride(
    @SerialName("channel_id") val channelId: String,
    val shown: Boolean = false,          // §C1 — new channels default hidden
    val number: Int = 0,
    val order: Int = 0,
    @SerialName("logo_override_url") val logoOverrideUrl: String? = null,
    // Admin-owned rename, distinct from Jellyfin's own channel name — null/blank means "use Jellyfin's
    // name" (see LiveTvService.lineup()'s resolution order), so clearing the admin input reverts it.
    @SerialName("display_name") val displayName: String? = null,
    val category: String = "",           // 100% jellystructure-owned (addendum B — Jellyfin has none)
    val unavailable: Boolean = false,     // present in the store but gone from Jellyfin's live lineup
    @SerialName("is_new") val isNew: Boolean = false, // badge: appeared since the operator last acted
)

@Serializable
data class LiveTvSettings(
    val enabled: Boolean = false,
    @SerialName("epg_cadence_minutes") val epgCadenceMinutes: Int = 60,
    @SerialName("last_synced_at") val lastSyncedAt: Long = 0L,
)

@Serializable
private data class LiveTvStoreData(
    val settings: LiveTvSettings = LiveTvSettings(),
    val overrides: List<LiveTvChannelOverride> = emptyList(),
)

/** JSON-file store (the [dev.jellystructure.media.JsTagStore] pattern) — a small, admin-owned config
 *  blob, not a catalog table; atomic tmp-then-rename writes, loaded once at startup. */
class LiveTvStore(private val filePath: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private var settings = LiveTvSettings()
    private var overrides: MutableMap<String, LiveTvChannelOverride> = linkedMapOf()

    fun load() {
        val path = Path(filePath)
        if (!SystemFileSystem.exists(path)) return
        runCatching {
            val data = json.decodeFromString<LiveTvStoreData>(FileIo.readText(path))
            settings = data.settings
            overrides = data.overrides.associateBy { it.channelId }.toMutableMap()
        }
    }

    fun settings(): LiveTvSettings = settings
    fun overridesByChannelId(): Map<String, LiveTvChannelOverride> = overrides.toMap()
    fun override(channelId: String): LiveTvChannelOverride? = overrides[channelId]

    fun updateSettings(mutate: (LiveTvSettings) -> LiveTvSettings) {
        settings = mutate(settings)
        persist()
    }

    fun upsertOverride(channelId: String, mutate: (LiveTvChannelOverride) -> LiveTvChannelOverride) {
        val cur = overrides[channelId] ?: LiveTvChannelOverride(channelId = channelId)
        overrides[channelId] = mutate(cur)
        persist()
    }

    /** Replace the whole override set in one write — used by [LiveTvService.sync]'s new/unavailable diff. */
    fun replaceAll(next: List<LiveTvChannelOverride>) {
        overrides = next.associateBy { it.channelId }.toMutableMap()
        persist()
    }

    fun removeOverride(channelId: String): Boolean {
        val removed = overrides.remove(channelId) != null
        if (removed) persist()
        return removed
    }

    private fun persist() {
        val tmp = Path("$filePath.tmp")
        val target = Path(filePath)
        FileIo.writeText(tmp, json.encodeToString(LiveTvStoreData(settings, overrides.values.toList())))
        SystemFileSystem.atomicMove(tmp, target)
    }
}
