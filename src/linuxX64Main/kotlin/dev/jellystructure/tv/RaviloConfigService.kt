package dev.jellystructure.tv

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.shared.tv.ChannelConfig
import dev.jellystructure.shared.tv.HeroConfig
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.RowConfig
import dev.jellystructure.shared.tv.RowKind
import dev.jellystructure.shared.tv.Skin
import dev.jellystructure.shared.tv.TileShape
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.timespec
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr

private val json = Json { ignoreUnknownKeys = true }

private val DEFAULT_ROWS = listOf(
    RowConfig(id = "continue",     kind = RowKind.CONTINUE,    title = "Continue Watching",  enabled = true, order = 0),
    RowConfig(id = "newly-movies", kind = RowKind.NEWLY_ADDED, title = "Newly Added Movies", enabled = true, order = 1, mediaKind = "MOVIE"),
    RowConfig(id = "newly-series", kind = RowKind.NEWLY_ADDED, title = "Newly Added Series", enabled = true, order = 2, mediaKind = "SERIES"),
    RowConfig(id = "genre-drama",  kind = RowKind.GENRE,       title = "Drama",              enabled = true, order = 3),
    RowConfig(id = "genre-action", kind = RowKind.GENRE,       title = "Action & Adventure", enabled = true, order = 4),
    RowConfig(id = "genre-scifi",  kind = RowKind.GENRE,       title = "Sci-Fi & Fantasy",   enabled = true, order = 5),
    RowConfig(id = "genre-comedy", kind = RowKind.GENRE,       title = "Comedy",             enabled = true, order = 6),
)

private val DEFAULT_CONFIG = RaviloConfig(
    heroes = emptyList(),
    channels = emptyList(),
    rows = DEFAULT_ROWS,
    mergeNewlyAdded = false,
    defaultSkin = Skin.AURORA,
    allowSkinOverride = true,
    showContinueProgress = true,
    tileShape = TileShape.POSTER,
)

class RaviloConfigService(
    private val db: JellystructureDb,
    private val eventBus: TvEventBus? = null,
) {

    fun getConfig(userId: String): RaviloConfig {
        val stored = db.raviloConfigQueries.getByUser(userId).executeAsOneOrNull()
        if (stored != null) {
            return runCatching { json.decodeFromString<RaviloConfig>(stored) }.getOrDefault(DEFAULT_CONFIG)
        }
        // First access: persist defaults so any surface can read/update them.
        save(userId, DEFAULT_CONFIG)
        return DEFAULT_CONFIG
    }

    fun save(userId: String, config: RaviloConfig) {
        db.raviloConfigQueries.upsert(
            user_id = userId,
            json = json.encodeToString(normalize(config)),
            updated_at = nowMs(),
        )
        // R33: push a live signal to this user's connected TVs so they re-pull immediately.
        eventBus?.notifyConfigChanged(userId)
    }

    /**
     * Clamp ranges and assign contiguous ordering before persisting, so the stored layout is always
     * well-formed regardless of which surface wrote it. Idempotent. Drops heroes with a blank itemId.
     */
    fun normalize(config: RaviloConfig): RaviloConfig = config.copy(
        heroes = config.heroes
            .filter { it.itemId.isNotBlank() }
            .mapIndexed { i, h -> h.copy(order = i) },
        channels = config.channels.mapIndexed { i, c -> c.copy(order = i) },
        rows = config.rows.mapIndexed { i, r -> r.copy(order = i) },
        heroHeightPct = config.heroHeightPct.coerceIn(30, 100),
        autoAdvanceSeconds = config.autoAdvanceSeconds.coerceIn(0, 120),
    )

    /**
     * Validate an incoming admin-edited config before persisting. Returns an error message, or null
     * if the config is sound. Channels and rows must carry unique, non-blank ids (the TV and the
     * editor both key off them); an empty id would silently collide on the next edit.
     */
    fun validate(config: RaviloConfig): String? {
        val channelIds = config.channels.map { it.id }
        if (channelIds.any { it.isBlank() }) return "Every channel must have an id."
        if (channelIds.size != channelIds.toSet().size) return "Channel ids must be unique."
        val rowIds = config.rows.map { it.id }
        if (rowIds.any { it.isBlank() }) return "Every row must have an id."
        if (rowIds.size != rowIds.toSet().size) return "Row ids must be unique."
        return null
    }

    /**
     * Apply only the viewer-tweakable fields; layout (heroes/channels/rows) is operator-only.
     * A viewer's skin choice is stored in [RaviloConfig.viewerSkinOverride], never in defaultSkin,
     * so a later operator change to the default still surfaces for viewers who never picked one.
     */
    fun applyViewerSettings(userId: String, skin: Skin?, showContinueProgress: Boolean?, autoplayNext: Boolean?, tileShape: TileShape?) {
        val current = getConfig(userId)
        save(
            userId = userId,
            config = current.copy(
                viewerSkinOverride = skin ?: current.viewerSkinOverride,
                showContinueProgress = showContinueProgress ?: current.showContinueProgress,
                autoplayNext = autoplayNext ?: current.autoplayNext,
                tileShape = tileShape ?: current.tileShape,
            ),
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun nowMs(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, ts.ptr)
    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}
