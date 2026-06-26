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
import dev.jellystructure.shared.tv.HeroConfig
import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.Row
import dev.jellystructure.shared.tv.RowConfig
import dev.jellystructure.shared.tv.RowKind
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private const val ROW_ITEM_LIMIT = 30
private const val HERO_AUTO_COUNT = 5

class HomeFeedService(
    private val mediaStore: MediaStore,
    private val configService: RaviloConfigService,
    private val jellyfinClient: JellyfinClient,
    private val configStore: ConfigStore,
) {
    suspend fun getHomeFeed(device: DeviceData): HomeFeed = coroutineScope {
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val config        = configService.getConfig(device.jellyfinUserId) // sync disk read — no suspend needed
        val allDeferred   = async { mediaStore.allItems() }
        // R85: token no longer needed for image URLs; still needed for buildContinueRow.
        val tokenDeferred = async { jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken) }
        val all   = allDeferred.await()
        val token = tokenDeferred.await()
        HomeFeed(
            heroes = buildHeroes(config, all),
            channels = buildChannels(config),
            rows = buildRows(config, device, all, jellyfinBase, token, channelFilter = null),
            heroHeightPct = config.heroHeightPct,
            autoAdvanceSeconds = config.autoAdvanceSeconds,
            tileShape = config.tileShape,
        )
    }

    suspend fun getChannelFeed(device: DeviceData, channelId: String): HomeFeed = coroutineScope {
        val config     = configService.getConfig(device.jellyfinUserId)
        val channelCfg = config.channels.find { it.id == channelId }
            ?: return@coroutineScope HomeFeed(emptyList(), emptyList(), emptyList())
        val jellyfinBase  = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val allDeferred   = async { mediaStore.allItems() }
        // R85: token no longer needed for image URLs; still needed for buildContinueRow.
        val tokenDeferred = async { jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken) }
        val allItems = allDeferred.await()
        val token    = tokenDeferred.await()
        val heroIds  = config.heroes.map { it.itemId }.toSet()
        val filtered = allItems.filter { it.matchesChannel(channelCfg, heroIds) }
        val pageHero = channelCfg.pageHero
        val heroes   = if (pageHero?.enabled == true && pageHero.items.isNotEmpty())
            buildHeroesFromList(pageHero.items, allItems)
        else
            emptyList()
        HomeFeed(
            heroes = heroes,
            channels = buildChannels(config),
            rows = buildRows(config, device, filtered, jellyfinBase, token, channelFilter = channelCfg),
            heroHeightPct = config.heroHeightPct,
            autoAdvanceSeconds = config.autoAdvanceSeconds,
            tileShape = config.tileShape,
        )
    }

    // ─── Heroes ───────────────────────────────────────────────────────────────

    private fun buildHeroes(config: RaviloConfig, all: List<MediaItem>): List<Hero> {
        // Auto mode: pick the most-recently-scanned items with no dressing.
        if (config.heroes.isEmpty()) {
            return all.sortedByDescending { it.scannedAt }.take(HERO_AUTO_COUNT).mapNotNull { item ->
                val jellyfinId = item.jellyfinId ?: return@mapNotNull null
                Hero(
                    item = item.toMediaCard(),
                    taglineKicker = null,
                    backdropUrl = JellyfinImageUrl.heroBackdrop(jellyfinId),
                    logoUrl = JellyfinImageUrl.logo(jellyfinId),
                    badge = null,
                    synopsis = item.overview,
                )
            }
        }
        // Curated mode: keep the HeroConfig alongside the resolved MediaItem so badge/tagline survive.
        return config.heroes
            .filter { it.enabled }
            .sortedBy { it.order }
            .mapNotNull { hc ->
                val item = all.firstOrNull { it.jellyfinId == hc.itemId } ?: all.firstOrNull { it.id == hc.itemId }
                    ?: return@mapNotNull null
                val jellyfinId = item.jellyfinId ?: return@mapNotNull null
                Hero(
                    item = item.toMediaCard(),
                    taglineKicker = hc.tagline,
                    backdropUrl = JellyfinImageUrl.heroBackdrop(jellyfinId),
                    logoUrl = if (hc.clearlogoOverlay) JellyfinImageUrl.logo(jellyfinId) else null,
                    badge = hc.badge,
                    synopsis = item.overview,
                )
            }
    }

    private fun buildHeroesFromList(heroConfigs: List<HeroConfig>, all: List<MediaItem>): List<Hero> =
        heroConfigs
            .filter { it.enabled }
            .sortedBy { it.order }
            .mapNotNull { hc ->
                val item = all.firstOrNull { it.jellyfinId == hc.itemId } ?: all.firstOrNull { it.id == hc.itemId }
                    ?: return@mapNotNull null
                val jellyfinId = item.jellyfinId ?: return@mapNotNull null
                Hero(
                    item = item.toMediaCard(),
                    taglineKicker = hc.tagline,
                    backdropUrl = JellyfinImageUrl.heroBackdrop(jellyfinId),
                    logoUrl = if (hc.clearlogoOverlay) JellyfinImageUrl.logo(jellyfinId) else null,
                    badge = hc.badge,
                    synopsis = item.overview,
                )
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
                    paddingLogo = ch.paddingLogo,
                    paddingText = ch.paddingText,
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
        // R59: channel custom rows override the global Home rows when mode == "custom".
        val channelRows = channelFilter?.rows
        val rowSource = if (channelRows?.mode == "custom") channelRows.items else config.rows
        val enabledRows = rowSource.filter { it.enabled }.sortedBy { it.order }
        val result = mutableListOf<Row>()

        for (rowCfg in enabledRows) {
            when (rowCfg.kind) {
                RowKind.CONTINUE -> {
                    if (channelFilter != null) continue // skip resume row inside channel view
                    val cards = buildContinueRow(device, all, jellyfinBase, token)
                    if (cards.isNotEmpty()) result.add(Row(rowCfg.id, rowCfg.title ?: "Continue Watching", RowKind.CONTINUE, cards))
                }

                RowKind.NEWLY_ADDED -> {
                    if (config.mergeNewlyAdded) {
                        // When merged, ALL NEWLY_ADDED rows are skipped; a single merged row is injected below
                        continue
                    }
                    // When not merged and mediaKind=null (the single "newly-all" system row), emit
                    // two typed rows: Movies then Series. This is what the "Merge newly added" toggle
                    // controls: false → split, true → one combined (R71).
                    if (rowCfg.mediaKind == null) {
                        val movies = all.filter { it.kind == MediaKind.MOVIE }
                            .sortedByDescending { it.scannedAt }.take(ROW_ITEM_LIMIT)
                            .mapNotNull { it.toMediaCardOrNull() }
                        val series = all.filter { it.kind == MediaKind.TV_SHOW }
                            .sortedByDescending { it.scannedAt }.take(ROW_ITEM_LIMIT)
                            .mapNotNull { it.toMediaCardOrNull() }
                        if (movies.isNotEmpty()) result.add(Row("${rowCfg.id}-movies", rowCfg.title?.let { "$it — Movies" } ?: "Movies — Newly Added", RowKind.NEWLY_ADDED, movies))
                        if (series.isNotEmpty()) result.add(Row("${rowCfg.id}-series", rowCfg.title?.let { "$it — Series" } ?: "Series — Newly Added", RowKind.NEWLY_ADDED, series))
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
                        .mapNotNull { it.toMediaCardOrNull() }
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
                        .mapNotNull { it.toMediaCardOrNull() }
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
                        .mapNotNull { it.toMediaCardOrNull() }
                    if (cards.isNotEmpty()) result.add(Row(rowCfg.id, rowCfg.title ?: "Custom", RowKind.CUSTOM, cards))
                }
            }
        }

        // If mergeNewlyAdded is on, inject one merged NEWLY_ADDED row at the config position
        if (config.mergeNewlyAdded) {
            val mergedCards = all
                .sortedByDescending { it.scannedAt }
                .take(ROW_ITEM_LIMIT)
                .mapNotNull { it.toMediaCardOrNull() }
            if (mergedCards.isNotEmpty()) {
                // Find where in the config the first NEWLY_ADDED row was and insert the merged
                // row at that logical position — after whichever result row came just before it.
                val firstIdx = enabledRows.indexOfFirst { it.kind == RowKind.NEWLY_ADDED }
                val insertAt = if (firstIdx <= 0) {
                    0 // no predecessor; place before all rows (or at 0 if firstIdx == -1 = no NEWLY_ADDED configured)
                } else {
                    val predecessorId = enabledRows[firstIdx - 1].id
                    val pos = result.indexOfFirst { it.id == predecessorId }
                    if (pos < 0) result.size else pos + 1
                }
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
    ): List<MediaCard> = coroutineScope {
        val jellyfinUrl = configStore.current.apiKeys.jellyfinUrl.takeIf { it.isNotBlank() }
            ?: return@coroutineScope emptyList()

        // Both calls are independent — fetch in parallel to halve the Jellyfin round-trips.
        val resumeDeferred = async { jellyfinClient.getResumeItems(jellyfinUrl, token, device.jellyfinUserId) }
        val nextUpDeferred = async { jellyfinClient.getNextUp(jellyfinUrl, token, device.jellyfinUserId) }
        val resumeItems = resumeDeferred.await()
        val nextUpItems = nextUpDeferred.await()

        val cards = mutableListOf<MediaCard>()
        val seen = mutableSetOf<String>()

        for (play in resumeItems) {
            val itemId = play.seriesId ?: play.id
            if (!seen.add(itemId)) continue
            val mediaItem = all.firstOrNull { it.jellyfinId == itemId } ?: continue
            val pct = play.userData?.playedPercentage?.toFloat()?.div(100f)
            cards.add(mediaItem.toMediaCard(progressPct = pct))
        }

        for (play in nextUpItems) {
            val itemId = play.seriesId ?: play.id
            if (!seen.add(itemId)) continue
            val mediaItem = all.firstOrNull { it.jellyfinId == itemId } ?: continue
            val s = play.seasonNumber; val e = play.episodeNumber
            val label = if (s != null && e != null) "S${s}E${e} · ${play.name}" else play.name
            cards.add(mediaItem.toMediaCard(nextUpLabel = label))
        }

        cards.take(ROW_ITEM_LIMIT)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun MediaItem.toMediaCardOrNull(): MediaCard? {
        jellyfinId ?: return null
        return toMediaCard()
    }

    private fun MediaItem.toMediaCard(
        progressPct: Float? = null,
        nextUpLabel: String? = null,
        badge: String? = null,
    ): MediaCard {
        val jId = jellyfinId
        return MediaCard(
            id = jId ?: id,
            kind = if (kind == MediaKind.TV_SHOW) dev.jellystructure.shared.tv.MediaKind.SERIES
                   else dev.jellystructure.shared.tv.MediaKind.MOVIE,
            title = title,
            year = year,
            genre = genres.firstOrNull(),
            rating = null,
            posterUrl = if (jId != null) JellyfinImageUrl.poster(jId) else null,
            backdropUrl = if (jId != null) JellyfinImageUrl.backdrop(jId) else null,
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
