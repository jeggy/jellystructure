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

/** R51 sentinel key for the global (all-users) layout. Not a real Jellyfin user id. */
const val GLOBAL_USER_ID = "__global__"

private val DEFAULT_ROWS = listOf(
    RowConfig(id = "continue",  kind = RowKind.CONTINUE,    title = "Continue Watching", enabled = true, order = 0),
    RowConfig(id = "newly-all", kind = RowKind.NEWLY_ADDED, title = "Newly Added",       enabled = true, order = 1),
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

    /**
     * R51: resolve config for a viewer.
     * - If the user has their own record → use it (full override).
     * - Otherwise fall through to the global record (`__global__`).
     * - If neither exists, seed and return the global default.
     */
    fun getConfig(userId: String): RaviloConfig {
        if (userId != GLOBAL_USER_ID) {
            val userStored = db.raviloConfigQueries.getByUser(userId).executeAsOneOrNull()
            if (userStored != null) {
                return runCatching { json.decodeFromString<RaviloConfig>(userStored) }.getOrDefault(getGlobalConfig())
            }
        }
        return getGlobalConfig()
    }

    fun getGlobalConfig(): RaviloConfig {
        val stored = db.raviloConfigQueries.getByUser(GLOBAL_USER_ID).executeAsOneOrNull()
        if (stored != null) {
            return runCatching { json.decodeFromString<RaviloConfig>(stored) }.getOrDefault(DEFAULT_CONFIG)
        }
        save(GLOBAL_USER_ID, DEFAULT_CONFIG)
        return DEFAULT_CONFIG
    }

    fun hasCustomConfig(userId: String): Boolean =
        userId != GLOBAL_USER_ID && db.raviloConfigQueries.getByUser(userId).executeAsOneOrNull() != null

    fun removeCustomConfig(userId: String) {
        if (userId == GLOBAL_USER_ID) return
        db.raviloConfigQueries.deleteByUser(userId)
        eventBus?.notifyConfigChanged(userId)
    }

    fun save(userId: String, config: RaviloConfig) {
        db.raviloConfigQueries.upsert(
            user_id = userId,
            json = json.encodeToString(normalize(config)),
            updated_at = nowMs(),
        )
        // R33/R51: a global write notifies all users on the global layout; a per-user write notifies just them.
        if (userId == GLOBAL_USER_ID) {
            eventBus?.notifyGlobalConfigChanged()
        } else {
            eventBus?.notifyConfigChanged(userId)
        }
    }

    /**
     * Clamp ranges and assign contiguous ordering before persisting, so the stored layout is always
     * well-formed regardless of which surface wrote it. Idempotent. Drops heroes with a blank itemId.
     */
    fun normalize(config: RaviloConfig): RaviloConfig = config.copy(
        heroes = config.heroes
            .filter { it.itemId.isNotBlank() }
            .mapIndexed { i, h -> h.copy(order = i) },
        channels = config.channels.mapIndexed { i, c ->
            c.copy(
                order = i,
                brandColor = sanitizeBrandColor(c.brandColor),
                pageHero = c.pageHero?.let { h ->
                    h.copy(items = h.items.filter { it.itemId.isNotBlank() }.mapIndexed { j, item -> item.copy(order = j) })
                },
                paddingLogo = c.paddingLogo?.let { p -> p.copy(top = p.top.coerceIn(0,40), right = p.right.coerceIn(0,40), bottom = p.bottom.coerceIn(0,40), left = p.left.coerceIn(0,40)) },
                paddingText = c.paddingText?.let { p -> p.copy(top = p.top.coerceIn(0,40), right = p.right.coerceIn(0,40), bottom = p.bottom.coerceIn(0,40), left = p.left.coerceIn(0,40)) },
            )
        },
        rows = config.rows.mapIndexed { i, r -> r.copy(order = i) },
        heroHeightPct = config.heroHeightPct.coerceIn(40, 100),
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

/** First channel-fill preset; the fallback when an authored brandColor is malformed. */
private const val DEFAULT_BRAND_COLOR = "linear-gradient(135deg,#3b2a78,#15102e)"

/**
 * R36/R26: a channel `brandColor` is a CSS fill — a solid hex color OR a single
 * `linear-gradient(<deg>, <c1>, <c2>)`. Anything else is clamped to the default preset so the TV
 * always has a renderable fill; a null/blank value stays null (the card falls back to the skin accent).
 */
private fun sanitizeBrandColor(value: String?): String? {
    val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (isValidBrandColor(v)) v else DEFAULT_BRAND_COLOR
}

private fun isValidBrandColor(v: String): Boolean {
    val s = v.trim()
    if (s.startsWith("linear-gradient(", ignoreCase = true) && s.endsWith(")")) {
        val inner = s.substringAfter('(').substringBeforeLast(')')
        val colorParts = inner.split(',').map { it.trim() }
            .filter { it.isNotEmpty() && !it.endsWith("deg") && !it.startsWith("to ", ignoreCase = true) }
        return colorParts.size >= 2 && colorParts.all { isColorToken(it) }
    }
    return isColorToken(s)
}

private fun isColorToken(t: String): Boolean {
    val s = t.trim()
    if (s.startsWith("#")) {
        val h = s.removePrefix("#")
        return (h.length == 3 || h.length == 6 || h.length == 8) &&
            h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }
    return s.startsWith("rgb(") || s.startsWith("rgba(") // lenient; the editor emits hex
}

@OptIn(ExperimentalForeignApi::class)
private fun nowMs(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, ts.ptr)
    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}
