package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.shared.tv.Channel
import dev.jellystructure.shared.tv.ChannelConfig
import dev.jellystructure.shared.tv.ChannelStyle
import dev.jellystructure.shared.tv.Hero
import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.Row
import dev.jellystructure.shared.tv.RowConfig
import dev.jellystructure.shared.tv.RowKind

private const val ROW_ITEM_LIMIT = 30
private const val HERO_AUTO_COUNT = 5

class HomeFeedService(
    private val mediaStore: MediaStore,
    private val configService: RaviloConfigService,
    private val jellyfinClient: JellyfinClient,
    private val configStore: ConfigStore,
) {
    suspend fun getHomeFeed(device: DeviceData): HomeFeed {
        val config = configService.getConfig(device.jellyfinUserId)
        val all = mediaStore.allItems()
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken)
        return HomeFeed(
            heroes = buildHeroes(config, all, jellyfinBase, token),
            channels = buildChannels(config),
            rows = buildRows(config, device, all, jellyfinBase, token, channelFilter = null),
            heroHeightPct = config.heroHeightPct,
            autoAdvanceSeconds = config.autoAdvanceSeconds,
            tileShape = config.tileShape,
        )
    }

    suspend fun getChannelFeed(device: DeviceData, channelId: String): HomeFeed {
        val config = configService.getConfig(device.jellyfinUserId)
        val channelCfg = config.channels.find { it.id == channelId }
            ?: return HomeFeed(emptyList(), emptyList(), emptyList())
        val heroIds = config.heroes.map { it.itemId }.toSet()
        val all = mediaStore.allItems().filter { it.matchesChannel(channelCfg, heroIds) }
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken)
        return HomeFeed(
            heroes = buildHeroes(config, all, jellyfinBase, token),
            channels = buildChannels(config),
            rows = buildRows(config, device, all, jellyfinBase, token, channelFilter = channelCfg),
            heroHeightPct = config.heroHeightPct,
            autoAdvanceSeconds = config.autoAdvanceSeconds,
            tileShape = config.tileShape,
        )
    }

    // ─── Heroes ───────────────────────────────────────────────────────────────

    private fun buildHeroes(
        config: RaviloConfig,
        all: List<MediaItem>,
        jellyfinBase: String,
        token: String,
    ): List<Hero> {
        val items: List<MediaItem> = if (config.heroes.isEmpty()) {
            all.sortedByDescending { it.scannedAt }.take(HERO_AUTO_COUNT)
        } else {
            config.heroes
                .filter { it.enabled }
                .sortedBy { it.order }
                .mapNotNull { hc ->
                    all.firstOrNull { it.jellyfinId == hc.itemId } ?: all.firstOrNull { it.id == hc.itemId }
                }
        }
        return items.mapNotNull { item ->
            val jellyfinId = item.jellyfinId ?: return@mapNotNull null
            Hero(
                item = item.toMediaCard(jellyfinBase, token),
                taglineKicker = null,
                backdropUrl = "$jellyfinBase/Items/$jellyfinId/Images/Backdrop/0?api_key=$token",
                logoUrl = null,
                badge = null,
                synopsis = item.overview,
            )
        }
    }

    // ─── Channels ─────────────────────────────────────────────────────────────

    private fun buildChannels(config: RaviloConfig): List<Channel> =
        config.channels
            .filter { it.enabled }
            .sortedBy { it.order }
            .map { ch ->
                Channel(
                    id = ch.id,
                    name = ch.name,
                    logoUrl = ch.logoUrl,
                    style = ch.style,
                    brandColor = ch.brandColor,
                )
            }

    // ─── Rows ─────────────────────────────────────────────────────────────────

    private suspend fun buildRows(
        config: RaviloConfig,
        device: DeviceData,
        all: List<MediaItem>,
        jellyfinBase: String,
        token: String,
        channelFilter: ChannelConfig?,
    ): List<Row> {
        val enabledRows = config.rows.filter { it.enabled }.sortedBy { it.order }
        val result = mutableListOf<Row>()

        for (rowCfg in enabledRows) {
            when (rowCfg.kind) {
                RowKind.CONTINUE -> {
                    if (channelFilter != null) continue // skip resume row inside channel view
                    val cards = buildContinueRow(device, all, jellyfinBase, token)
                    if (cards.isNotEmpty()) result.add(Row(rowCfg.id, rowCfg.title ?: "Continue Watching", RowKind.CONTINUE, cards))
                }

                RowKind.NEWLY_ADDED -> {
                    if (config.mergeNewlyAdded && rowCfg.mediaKind != null) {
                        // When merged, skip the individual movie/series rows; a merged row will be added once
                        continue
                    }
                    val filtered = when (rowCfg.mediaKind) {
                        "MOVIE"  -> all.filter { it.kind == MediaKind.MOVIE }
                        "SERIES" -> all.filter { it.kind == MediaKind.TV_SHOW }
                        else     -> all
                    }
                    val cards = filtered
                        .sortedByDescending { it.scannedAt }
                        .take(ROW_ITEM_LIMIT)
                        .mapNotNull { it.toMediaCardOrNull(jellyfinBase, token) }
                    if (cards.isNotEmpty()) result.add(Row(rowCfg.id, rowCfg.title ?: "Newly Added", RowKind.NEWLY_ADDED, cards))
                }

                RowKind.GENRE -> {
                    val genreTerms = (rowCfg.title ?: "").split("&", ",")
                        .map { it.trim().lowercase() }
                        .filter { it.isNotBlank() }
                    val cards = all
                        .filter { item -> genreTerms.isEmpty() || item.genres.any { g -> genreTerms.any { t -> g.lowercase().contains(t) } } }
                        .sortedByDescending { it.scannedAt }
                        .take(ROW_ITEM_LIMIT)
                        .mapNotNull { it.toMediaCardOrNull(jellyfinBase, token) }
                    if (cards.isNotEmpty()) result.add(Row(rowCfg.id, rowCfg.title ?: "Genre", RowKind.GENRE, cards))
                }

                RowKind.CUSTOM -> {
                    // R32: a custom row is a saved condition stack.
                    val heroIds = config.heroes.map { it.itemId }.toSet()
                    val matched = all.filter { ConditionEvaluator.matches(it, rowCfg.match, rowCfg.conditions, heroIds) }
                    val filtered = when (rowCfg.mediaKind) {
                        "MOVIE"  -> matched.filter { it.kind == MediaKind.MOVIE }
                        "SERIES" -> matched.filter { it.kind == MediaKind.TV_SHOW }
                        else     -> matched
                    }
                    val cards = filtered
                        .sortedByDescending { it.scannedAt }
                        .take(ROW_ITEM_LIMIT)
                        .mapNotNull { it.toMediaCardOrNull(jellyfinBase, token) }
                    if (cards.isNotEmpty()) result.add(Row(rowCfg.id, rowCfg.title ?: "Custom", RowKind.CUSTOM, cards))
                }
            }
        }

        // If mergeNewlyAdded is on and no merged row was emitted, inject one
        if (config.mergeNewlyAdded) {
            val mergedCards = all
                .sortedByDescending { it.scannedAt }
                .take(ROW_ITEM_LIMIT)
                .mapNotNull { it.toMediaCardOrNull(jellyfinBase, token) }
            if (mergedCards.isNotEmpty()) {
                val insertAt = enabledRows.indexOfFirst { it.kind == RowKind.NEWLY_ADDED }
                    .takeIf { it >= 0 }?.let { idx ->
                        // insert after continue row if it exists, at the newly-added position
                        result.indexOfFirst { r -> enabledRows.getOrNull(idx - 1)?.id == r.id } + 1
                    } ?: result.size
                result.add(insertAt.coerceIn(0, result.size), Row("newly-added", "Newly Added", RowKind.NEWLY_ADDED, mergedCards))
            }
        }

        return result
    }

    private suspend fun buildContinueRow(
        device: DeviceData,
        all: List<MediaItem>,
        jellyfinBase: String,
        token: String,
    ): List<MediaCard> {
        val jellyfinUrl = configStore.current.apiKeys.jellyfinUrl.takeIf { it.isNotBlank() }
            ?: return emptyList()

        val resumeItems = jellyfinClient.getResumeItems(jellyfinUrl, token, device.jellyfinUserId)
        val nextUpItems = jellyfinClient.getNextUp(jellyfinUrl, token, device.jellyfinUserId)

        val cards = mutableListOf<MediaCard>()
        val seen = mutableSetOf<String>()

        for (play in resumeItems) {
            val itemId = play.seriesId ?: play.id
            if (!seen.add(itemId)) continue
            val mediaItem = all.firstOrNull { it.jellyfinId == itemId } ?: continue
            val pct = play.userData?.playedPercentage?.toFloat()?.div(100f)
            cards.add(mediaItem.toMediaCard(jellyfinBase, token, progressPct = pct))
        }

        for (play in nextUpItems) {
            val itemId = play.seriesId ?: play.id
            if (!seen.add(itemId)) continue
            val mediaItem = all.firstOrNull { it.jellyfinId == itemId } ?: continue
            val s = play.seasonNumber; val e = play.episodeNumber
            val label = if (s != null && e != null) "S${s}E${e} · ${play.name}" else play.name
            cards.add(mediaItem.toMediaCard(jellyfinBase, token, nextUpLabel = label))
        }

        return cards.take(ROW_ITEM_LIMIT)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun MediaItem.toMediaCardOrNull(jellyfinBase: String, token: String): MediaCard? {
        val jId = jellyfinId ?: return null
        return toMediaCard(jellyfinBase, token)
    }

    private fun MediaItem.toMediaCard(
        jellyfinBase: String,
        token: String,
        progressPct: Float? = null,
        nextUpLabel: String? = null,
        badge: String? = null,
    ): MediaCard {
        val jId = jellyfinId
        val posterUrl = if (jId != null) "$jellyfinBase/Items/$jId/Images/Primary?api_key=$token" else null
        val backdropUrl = if (jId != null) "$jellyfinBase/Items/$jId/Images/Backdrop/0?api_key=$token" else null
        return MediaCard(
            id = jId ?: id,
            kind = if (kind == MediaKind.TV_SHOW) dev.jellystructure.shared.tv.MediaKind.SERIES
                   else dev.jellystructure.shared.tv.MediaKind.MOVIE,
            title = title,
            year = year,
            genre = genres.firstOrNull(),
            rating = null,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            progressPct = progressPct,
            nextUpLabel = nextUpLabel,
            badge = badge,
        )
    }

    private fun MediaItem.matchesChannel(ch: ChannelConfig, heroIds: Set<String>): Boolean {
        // R32: a condition stack supersedes the legacy single typed filters.
        if (ch.conditions.isNotEmpty()) return ConditionEvaluator.matches(this, ch.match, ch.conditions, heroIds)
        if (ch.filterNetwork != null && network.equals(ch.filterNetwork, ignoreCase = true)) return true
        if (ch.filterStudio  != null && studio.equals(ch.filterStudio,  ignoreCase = true)) return true
        if (ch.filterGenre   != null && genres.any { it.equals(ch.filterGenre, ignoreCase = true) }) return true
        if (ch.filterTag     != null && tags.any { it.equals(ch.filterTag, ignoreCase = true) }) return true
        return false
    }
}
