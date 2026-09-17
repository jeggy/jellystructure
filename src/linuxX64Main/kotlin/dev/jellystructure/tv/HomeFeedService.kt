package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.MediaStore
import dev.jellystructure.ops.GateClass
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.recencyKey
import dev.jellystructure.resolver.CertificationResolver
import dev.jellystructure.shared.tv.Channel
import dev.jellystructure.shared.tv.ChannelConfig
import dev.jellystructure.shared.tv.Condition
import dev.jellystructure.shared.tv.ConditionGroup
import dev.jellystructure.shared.tv.FocusDetailFacts
import dev.jellystructure.shared.tv.RatingBadge
import dev.jellystructure.shared.tv.Hero
import dev.jellystructure.shared.tv.HeroConfig
import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.QueryJoin
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.Row
import dev.jellystructure.shared.tv.RowConfig
import dev.jellystructure.shared.tv.RowKind
import dev.jellystructure.shared.tv.RowOrder
import dev.jellystructure.shared.tv.effectiveQuery
import dev.jellystructure.shared.tv.isLive
import dev.jellystructure.shared.tv.CardPlayState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.timespec

private const val ROW_ITEM_LIMIT = 30
private const val HERO_AUTO_COUNT = 5
private const val FEED_TTL_MS = 5 * 60_000L  // Continue row freshness window
private const val CONTINUE_TIMEOUT_MS = 6_000L  // R102: cap the live Jellyfin resume/next-up wait
// R219 (FR-R219-5) — Continue Watching's own per-view cap (Home row, channel row). Down from the old
// ROW_ITEM_LIMIT=30: with a genuinely time-sorted canonical list the row's head is always the most
// recent entries, so a shorter row costs nothing and scans faster on a remote. See-all stays uncapped.
private const val CONTINUE_ROW_LIMIT = 20
// R219 (FR-R219-3) — membership rule §2(c): an item touched (played, no position, no finish) within this
// many days still counts as "genuinely started". 7 days, not the 30 first proposed — owner's call
// (2026-08-30): the household's newest such item was 3 days old, so a shorter window still keeps it
// while shedding the stale tail (6 weeks–6 months for the rest).
private const val CONTINUE_TOUCHED_WINDOW_DAYS = 7L
// Phase 205 (FR-205-2/FR-205-6) — the background loop's own refresh cadence, now that the reader never
// triggers a build. Coarser than PlaystateCache's 20s: getRecentlyTouched (one of the four fetches
// buildCanonicalContinueList runs) is a whole-library DatePlayed sort (measured: 1.26-2.01s, up to
// 8.56s during a pipeline run) — the single most expensive of the four, so this cadence is chosen for
// that fetch's cost, not the cheaper three it happens to run alongside.
private const val CONTINUE_REFRESH_INTERVAL_MS = 60_000L
// Phase 230 (FR-230-4) — a user with NO Continue list (boot, or every build so far failed) shows no row at
// all: build patiently, and come back soon rather than in a minute.
private const val CONTINUE_COLD_TIMEOUT_MS = 20_000L
private const val CONTINUE_COLD_RETRY_MS = 5_000L
// Same reasoning as PlaystateCache's own window — a household's historical pairings shouldn't grow
// background Jellyfin traffic forever.
private const val RECENTLY_SEEN_WINDOW_MS = 30L * 24 * 3_600_000L
private const val REFRESH_STAGGER_MS = 500L

class HomeFeedService(
    private val mediaStore: MediaStore,
    private val configService: RaviloConfigService,
    private val jellyfinClient: JellyfinClient,
    private val configStore: ConfigStore,
    private val tvEventBus: TvEventBus,
    private val artwork: ArtworkDownloader,
) {
    /** Phase 232 (FR-232-5) — set by Main; null in tests (= every logo's ink unknown). */
    var clearlogoInk: dev.jellystructure.media.ClearlogoInk? = null
    private val json = Json { encodeDefaults = true }

    // Phase R86-A: stale-while-revalidate home feed cache per Jellyfin user.
    // Key = jellyfinUserId; invalidated on a card-relevant library write (feedVersion — Phase 204;
    // was libraryVersion, which bumps on every write regardless of relevance and thrashed this cache on
    // background scan noise), config change (cfgHash), a Phase 142 (+ tag follow-up) policy change
    // (allowedHash), or TTL (Continue stays fresh within FEED_TTL_MS).
    // Phase 229 (FR-229-3) — [continueStamp] is the [ContinueListEntry.stamp] this feed read (0 = the
    // user had no list). A feed can never outlive the Continue list it was built from: a differing
    // stamp is a miss, which is what makes the background loop's work visible on Home at all.
    private data class FeedEntry(val feed: HomeFeed, val builtAt: Long, val feedVer: Long, val cfgHash: Int, val allowedHash: Int, val continueStamp: Long)
    private val feedCache = HashMap<String, FeedEntry>()

    // Phase 205 (FR-205-2) — playstate moved to PlaystateCache, a background-refreshed, cross-service
    // cache (also read by BrowseService and DetailService, neither of which had ANY cache before this
    // phase). The per-user whole-catalog fetch this comment used to describe now happens off the
    // request path entirely; see that object's own doc.

    // R219 (FR-R219-1) — the ONE canonical Continue Watching list, keyed by (user, visibility scope),
    // reused by every view (Home row, every channel row, See-all) instead of each re-deriving it. Same
    // staleness/invalidation shape as [feedCache]: TTL = FEED_TTL_MS, dropped immediately on a reported
    // playback stop (see [invalidatePlaystate]). Never keyed by channel — channel membership is a filter
    // *over* this list (FR-R219-6), not a reason to rebuild it.
    // Phase 229 — NOT dropped on a stop any more (see [invalidatePlaystate]): it is rebuilt in place, and
    // [stamp] moves only when the list's content actually changed (FR-229-4) — [builtAt] moves on every
    // successful build and is what /api/health reports.
    private data class ContinueListEntry(val list: List<ContinueEntry>, val builtAt: Long, val feedVer: Long, val allowedHash: Int, val stamp: Long)
    private var continueStampSeq = 0L
    /** Phase 229 (FR-229-5) — test seam only: stands in for [buildCanonicalContinueList]'s four Jellyfin
     *  fetches as (item, progress %, last activity). `null` result = a failed build, exactly as R231 defines it. */
    internal var continueSourceForTest: (suspend (DeviceData) -> List<Triple<MediaItem, Float, Long>>?)? = null
    private fun continueStamp(userId: String): Long = continueListCache[userId]?.stamp ?: 0L
    private val continueListCache = HashMap<String, ContinueListEntry>()
    /** Phase 219 (FR-219-4) — the age of each user's last successful Continue Watching build, for /api/health. */
    fun continueRefreshAges(): Map<String, Long> { val now = nowMs(); return continueListCache.mapValues { now - it.value.builtAt } }

    // Phase 206 (FR-206-3) — the channel rail cached once per user, same shape/signal as [feedCache],
    // shared by every entry point that needs it ([buildHomeFeed], [getChannels], [getChannelFeed]) so
    // it is computed once per (user, feedVersion, config, scope) rather than once per caller — resolves
    // the phase's own open question 2 in favour of "yes, one lookup."
    private data class RailEntry(val channels: List<Channel>, val builtAt: Long, val feedVer: Long, val cfgHash: Int, val allowedHash: Int)
    private val channelRailCache = HashMap<String, RailEntry>()

    // Phase 206 (FR-206-4) — [buildChannelContent]'s own cache, per (user, channelId), same TTL and
    // invalidation signal as [feedCache]. A viewer moving between channels pays for one build per
    // channel, not one per navigation.
    private data class ChannelContentEntry(val heroes: List<Hero>, val rows: List<Row>, val builtAt: Long, val feedVer: Long, val cfgHash: Int, val allowedHash: Int, val continueStamp: Long)
    private val channelContentCache = HashMap<Pair<String, String>, ChannelContentEntry>()

    /**
     * R187 fix — just the channel id→name list, for callers that need Ravilo channel display names
     * (e.g. the seeded-browse page's Channel facet) without a top-level Home/Channel screen having
     * already loaded a full [HomeFeed] first.
     * R228: no longer config-only — [buildChannels] now needs this device's own accessible library
     * to decide which channels are non-empty for it, so this makes the same MediaStore/Jellyfin calls
     * [getHomeFeed] does.
     */
    // Phase 205 (FR-205-1) — no longer fetches a tvToken: the channel rail's only Jellyfin-touching
    // dependency was Continue Watching, which channelHasAnyMatch now reads from continueListCache
    // (background-refreshed) instead of building live. Listing channels makes no Jellyfin call at all.
    suspend fun getChannels(device: DeviceData): List<Channel> {
        val config = configService.getConfig(device.jellyfinUserId)
        val allItems = mediaStore.liveItems(device)
        val heroIds = config.heroes.map { it.itemId }.toSet()
        return channelRail(device, config, allItems, heroIds)
    }

    /** Phase 206 (FR-206-3) — see the cache field's own doc. Same shape as [feedCache]'s own gate. */
    private fun channelRail(
        device: DeviceData,
        config: RaviloConfig,
        allItems: List<MediaItem>,
        heroIds: Set<String>,
    ): List<Channel> {
        val userId = device.jellyfinUserId
        val feedVer = mediaStore.feedVersion
        val cfgHash = config.hashCode()
        val allowedHash = (device.allowedLibraries.hashCode() * 31 + device.allowedTags.hashCode()) * 31 + device.blockedTags.hashCode()
        val now = nowMs()
        channelRailCache[userId]?.takeIf {
            it.feedVer == feedVer && it.cfgHash == cfgHash && it.allowedHash == allowedHash && (now - it.builtAt) < FEED_TTL_MS
        }?.let { return it.channels }
        val built = buildChannels(config, device, allItems, heroIds)
        channelRailCache[userId] = RailEntry(built, now, feedVer, cfgHash, allowedHash)
        return built
    }

    suspend fun getHomeFeed(device: DeviceData): HomeFeed = coroutineScope {
        val userId = device.jellyfinUserId
        val feedVer = mediaStore.feedVersion
        val config = configService.getConfig(userId)
        val cfgHash = config.hashCode()
        val allowedHash = (device.allowedLibraries.hashCode() * 31 + device.allowedTags.hashCode()) * 31 + device.blockedTags.hashCode()
        val now = nowMs()

        // Phase 229 — read BEFORE the build: a list that lands mid-build leaves this entry already stale.
        val contStamp = continueStamp(userId)
        val cachedStructural = feedCache[userId]?.takeIf {
            it.feedVer == feedVer && it.cfgHash == cfgHash && it.allowedHash == allowedHash && it.continueStamp == contStamp && (now - it.builtAt) < FEED_TTL_MS
        }

        // Bug fix: these two used to run sequentially (build the whole feed — including its own live
        // Continue-row Jellyfin calls — THEN fetch playstate afterward), stacking their worst-case
        // latencies. Neither depends on the other's result, so run them concurrently; a cache hit on
        // either side just returns immediately without touching Jellyfin. Phase 205 — playstate is now
        // a PlaystateCache map read (no Jellyfin call at all), but kept as its own async so a future
        // change to it can't accidentally re-serialize onto feedDeferred's path.
        val feedDeferred = async {
            cachedStructural?.feed ?: buildHomeFeed(device, config).also {
                feedCache[userId] = FeedEntry(it, now, feedVer, cfgHash, allowedHash, contStamp)
            }
        }
        val playstateDeferred = async { PlaystateCache.get(userId) }
        applyPlaystate(feedDeferred.await(), playstateDeferred.await())
    }

    /**
     * Bug fix: a playback stop (and the played write-through) used to only forward to Jellyfin — nothing
     * touched [feedCache] or [continueListCache]. The Continue row is built INSIDE the cached feed from
     * Jellyfin's Resume + NextUp, so even a perfectly correct stop stayed invisible on Home for up to
     * five minutes: the row still showed the episode at its old position, or still showed one the viewer
     * had just finished. Drop the structural caches for this user so the next load rebuilds the row.
     *
     * Phase 205 (FR-205-9) — playstate itself no longer has a per-request cache to drop: under FR-205-2
     * it's [PlaystateCache], background-refreshed. "Correct it at once" now means triggering an
     * immediate, best-effort refresh for this one user rather than waiting out that cache's next cycle —
     * still fire-and-forget (a failure here must never turn a successful stop into an error response),
     * and it still fires the same R176 `playstate_changed` push on success so an already-open Home/
     * Browse screen elsewhere patches instantly.
     */
    suspend fun invalidatePlaystate(device: DeviceData, stoppedJellyfinId: String? = null) {
        val userId = device.jellyfinUserId
        // Phase 229 (FR-229-1/2) — REBUILD FIRST, drop caches after. This used to remove
        // continueListCache and feedCache up front and then spend seconds at Jellyfin; R248's
        // refresh-on-return asked for Home inside that window, got a feed built from "no list" (= no
        // Continue row), and that feed was cached for FEED_TTL_MS — the row left Home for five minutes
        // after every stop. The list is now replaced in place (R231: a failed rebuild leaves the previous
        // value standing, which the old order made impossible), and a request arriving mid-rebuild is
        // served the pre-stop feed, corrected by the push below.
        // Phase 230 (FR-230-2) — only what the stop touched (titles + that title's episodes), not the
        // whole 9 000-id catalog: this used to put 94 Jellyfin requests (3.4 s) ahead of the rebuild below.
        runCatching { PlaystateCache.refreshOne(device, mediaStore, jellyfinClient, configStore, tvEventBus, scope = PlaystateCache.RefreshScope.Touched(stoppedJellyfinId)) }
        runCatching { refreshContinueListFor(device) }
        feedCache.remove(userId)
        channelRailCache.remove(userId)
        // Phase 206 (FR-206-4) — same reasoning as the caches above: a channel whose Continue row just
        // changed must not keep serving a pre-stop build for the rest of FEED_TTL_MS.
        channelContentCache.keys.filter { it.first == userId }.forEach { channelContentCache.remove(it) }
        // R248 (FR-R248-2) — only now, with the caches dropped and the Continue list rebuilt (or its
        // rebuild failed and the previous value standing), is a client re-pull guaranteed to see the
        // post-stop answer. Sent whether the refreshes above succeeded or not: the client shows whatever
        // the server honestly has (FR-R248-5).
        tvEventBus.notifyHomeChanged(userId)
    }

    /**
     * R142 — overlay each tile's Jellyfin played / in-progress state. Continue Watching rows keep their
     * episode-level progress (carried in the card already); all other rows get a ✓ when played or a resume
     * sliver when in-progress.
     */
    private fun applyPlaystate(feed: HomeFeed, ps: Map<String, CardPlayState>): HomeFeed {
        if (ps.isEmpty()) return feed
        return feed.copy(rows = feed.rows.map { row ->
            if (row.kind == RowKind.CONTINUE) row else row.copy(items = row.items.map { it.withPlaystate(ps) })
        })
    }

    // Phase 205 (FR-205-1) — no longer fetches a tvToken. R85 already removed the image-URL need for
    // it; Continue Watching (the "still needed for buildContinueRow" reason this comment gave) is now a
    // background-refreshed cache read (see canonicalContinueList's doc), so buildHomeFeed makes no
    // Jellyfin call of its own at all.
    private suspend fun buildHomeFeed(device: DeviceData, config: RaviloConfig): HomeFeed {
        val all = mediaStore.liveItems(device)
        val heroIds = config.heroes.map { it.itemId }.toSet()
        val rows = buildRows(config, device, all, channelFilter = null)
        // Phase 202/R240 — Home content rows only (not heroes, not the channel rail, not channel
        // pages): see FocusDetailFacts' doc and the R240 spec's non-goals.
        return HomeFeed(
            heroes = buildHeroes(config, all),
            channels = channelRail(device, config, all, heroIds),
            rows = if (config.focusDetail == "none") rows else attachFocusDetail(rows, all),
            heroHeightPct = config.heroHeightPct,
            autoAdvanceSeconds = config.autoAdvanceSeconds,
            tileShape = config.tileShape,
            portraitHeroHeightPct = config.portrait?.heroHeightPct,
            liveTvHome = config.liveTvHome,
            focusDetail = config.focusDetail,
            focusDetailDelayMs = config.focusDetailDelayMs,
        )
    }

    /** Phase 202 (FR-202-5) — attach the per-title fact set to every Home content-row item, resolved
     *  from the same [MediaItem] list the rows were built from (no extra fetch). A card whose id can't
     *  be matched back to a MediaItem (shouldn't happen — rows are built from this exact list) is left
     *  as-is rather than failing the whole feed. */
    private fun attachFocusDetail(rows: List<Row>, all: List<MediaItem>): List<Row> {
        val byId = all.associateBy { it.jellyfinId ?: it.id }
        return rows.map { row ->
            row.copy(items = row.items.map { card ->
                byId[card.id]?.let { card.copy(focusDetail = it.toFocusDetailFacts()) } ?: card
            })
        }
    }

    /** Phase 202 (FR-202-5) — the fact set itself. Mirrors the same resolution DetailService/BrowseService
     *  already use for the equivalent detail-page fields (ratingBadge/imdbRating/qualityLabel), duplicated
     *  here rather than shared — same precedent as `toMediaCard()` being its own copy per service. */
    private fun MediaItem.toFocusDetailFacts(): FocusDetailFacts {
        val isSeries = kind == MediaKind.TV_SHOW
        val allTracks = if (isSeries) episodes.flatMap { it.tracks } else tracks
        val bestVideo = allTracks.filter { it.kind == dev.jellystructure.model.TrackKind.VIDEO }
            .maxByOrNull { (it.width ?: 0) * (it.height ?: 0) }
        val badge = bestVideo?.let { v ->
            val tier = when {
                (v.width ?: 0) >= 3840 || (v.height ?: 0) >= 2160 -> "4K"
                (v.width ?: 0) >= 1920 || (v.height ?: 0) >= 1080 -> "1080p"
                (v.width ?: 0) >= 1280 || (v.height ?: 0) >= 720  -> "720p"
                v.width != null || v.height != null -> "SD"
                else -> null
            } ?: return@let null
            if (v.videoRange == "HDR") "$tier HDR" else tier
        }
        val cert = CertificationResolver.resolve(configStore.current.metadata.ageRatingCascade, certifications)
        return FocusDetailFacts(
            year = year,
            badge = badge,
            seasons = if (isSeries) episodes.mapNotNull { it.seasonNumber }.distinct().size.takeIf { it > 0 } else null,
            episodes = if (isSeries) episodes.distinctBy { (it.seasonNumber ?: 0) to (it.episodeNumber ?: 0) }.size.takeIf { it > 0 } else null,
            runtimeMinutes = if (!isSeries) runtime else null,
            ratingBadge = cert?.let { RatingBadge(region = it.region, code = it.code, tier = it.tier, fallback = it.fallback) },
            imdbRating = imdbRating?.let { dev.jellystructure.shared.tv.TvImdbRating(aggregateRating = it.aggregateRating, voteCount = it.voteCount) },
            genres = genres,
            audioLanguages = allTracks.filter { it.kind == dev.jellystructure.model.TrackKind.AUDIO }
                .mapNotNull { it.language?.lowercase()?.takeIf { l -> l.isNotBlank() } }.distinct(),
            subtitleLanguages = allTracks.filter { it.kind == dev.jellystructure.model.TrackKind.SUBTITLE }
                .mapNotNull { it.language?.lowercase()?.takeIf { l -> l.isNotBlank() } }.distinct(),
            overview = overview,
        )
    }

    // Phase 205 (FR-205-1) — no longer fetches a tvToken (same reasoning as buildHomeFeed above); reads
    // PlaystateCache instead of the deleted playstateFor.
    suspend fun getChannelFeed(device: DeviceData, channelId: String): HomeFeed {
        val config     = configService.getConfig(device.jellyfinUserId)
        val channelCfg = config.channels.find { it.id == channelId }
            ?: return HomeFeed(emptyList(), emptyList(), emptyList())
        val allItems = mediaStore.liveItems(device)
        val heroIds  = config.heroes.map { it.itemId }.toSet()
        // Phase 206 (FR-206-2/FR-206-4) — both cached; channelContent builds this channel's heroes/rows
        // at most once per (user, channel, feedVersion, config, scope) instead of buildChannels (below,
        // via channelRail) building it again as a side effect of assembling the rail.
        val (heroes, rows) = channelContent(device, config, channelCfg, allItems, heroIds)
        return applyPlaystate(HomeFeed(
            heroes = heroes,
            channels = channelRail(device, config, allItems, heroIds),
            rows = rows,
            heroHeightPct = config.heroHeightPct,
            autoAdvanceSeconds = config.autoAdvanceSeconds,
            tileShape = config.tileShape,
            portraitHeroHeightPct = config.portrait?.heroHeightPct,
        ), PlaystateCache.get(device.jellyfinUserId))
    }

    /** Phase 206 (FR-206-4) — see [channelContentCache]'s own doc. Same shape as [channelRail]. */
    private fun channelContent(
        device: DeviceData,
        config: RaviloConfig,
        channelCfg: ChannelConfig,
        allItems: List<MediaItem>,
        heroIds: Set<String>,
    ): Pair<List<Hero>, List<Row>> {
        val userId = device.jellyfinUserId
        val feedVer = mediaStore.feedVersion
        val cfgHash = config.hashCode()
        val allowedHash = (device.allowedLibraries.hashCode() * 31 + device.allowedTags.hashCode()) * 31 + device.blockedTags.hashCode()
        val now = nowMs()
        val cacheKey = userId to channelCfg.id
        val contStamp = continueStamp(userId)  // Phase 229 (FR-229-3)
        channelContentCache[cacheKey]?.takeIf {
            it.feedVer == feedVer && it.cfgHash == cfgHash && it.allowedHash == allowedHash && it.continueStamp == contStamp && (now - it.builtAt) < FEED_TTL_MS
        }?.let { return it.heroes to it.rows }
        val (heroes, rows) = buildChannelContent(device, config, channelCfg, allItems, heroIds)
        channelContentCache[cacheKey] = ChannelContentEntry(heroes, rows, now, feedVer, cfgHash, allowedHash, contStamp)
        return heroes to rows
    }

    // ─── Heroes ───────────────────────────────────────────────────────────────

    private fun buildHeroes(config: RaviloConfig, all: List<MediaItem>): List<Hero> {
        // Auto mode: pick the most-recently-scanned items with no dressing.
        if (config.heroes.isEmpty()) {
            return all.sortedWith(compareByDescending<MediaItem> { it.recencyKey() }.thenBy { it.title }).take(HERO_AUTO_COUNT).mapNotNull { item ->
                item.jellyfinId ?: return@mapNotNull null   // hero must be navigable (detail opens by jellyfinId)
                Hero(
                    item = item.toMediaCard(),
                    taglineKicker = null,
                    backdropUrl = RaviloImageUrl.heroBackdrop(item.id, artwork.assetVersion(item, "backdrop")),
                    logoUrl = RaviloImageUrl.logo(item.id, artwork.assetVersion(item, "clearlogo")),
                    logoInk = clearlogoInk?.inkFor(item),   // Phase 232 (FR-232-5/6)
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
                item.jellyfinId ?: return@mapNotNull null   // hero must be navigable (detail opens by jellyfinId)
                Hero(
                    item = item.toMediaCard(),
                    taglineKicker = hc.tagline,
                    backdropUrl = RaviloImageUrl.heroBackdrop(item.id, artwork.assetVersion(item, "backdrop")),
                    logoUrl = if (hc.clearlogoOverlay) RaviloImageUrl.logo(item.id, artwork.assetVersion(item, "clearlogo")) else null,
                    logoInk = if (hc.clearlogoOverlay) clearlogoInk?.inkFor(item) else null,   // Phase 232
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
                item.jellyfinId ?: return@mapNotNull null   // hero must be navigable (detail opens by jellyfinId)
                Hero(
                    item = item.toMediaCard(),
                    taglineKicker = hc.tagline,
                    backdropUrl = RaviloImageUrl.heroBackdrop(item.id, artwork.assetVersion(item, "backdrop")),
                    logoUrl = if (hc.clearlogoOverlay) RaviloImageUrl.logo(item.id, artwork.assetVersion(item, "clearlogo")) else null,
                    logoInk = if (hc.clearlogoOverlay) clearlogoInk?.inkFor(item) else null,   // Phase 232
                    badge = hc.badge,
                    synopsis = item.overview,
                )
            }

    // ─── Channels ─────────────────────────────────────────────────────────────

    /**
     * R228 — a channel that resolves to nothing for THIS device (e.g. a restricted profile whose
     * library access excludes everything the channel's own filter would ever match) is left out
     * entirely, rather than rendering a tile that opens onto a blank page. "Empty" has to mean the
     * channel's actual built content is empty, not just [MediaItem.matchesChannel] returning no
     * matches — an inherit-mode channel's Continue Watching row is always Home's own row (R202),
     * independent of the channel's own filter, so it can be genuinely non-empty even when nothing
     * in the library matches that filter. [buildChannelContent] is the same heroes+rows build
     * [getChannelFeed] uses, so this can never disagree with what opening the channel actually shows.
     */
    private fun buildChannels(
        config: RaviloConfig,
        device: DeviceData,
        allItems: List<MediaItem>,
        heroIds: Set<String>,
    ): List<Channel> {
        val result = mutableListOf<Channel>()
        for (ch in config.channels.filter { it.enabled }.sortedBy { it.order }) {
            // Phase 206 (FR-206-1) — the rail only needs a boolean per channel; deciding it no longer
            // costs a full buildChannelContent (sort + take(30) + MediaCard construction for every row
            // of a channel nobody may ever open). filtered is computed once here and reused by
            // channelHasAnyMatch, matching buildChannelContent's own first line exactly.
            val filtered = allItems.filter { it.matchesChannel(ch, heroIds) }
            if (!channelHasAnyMatch(config, device, ch, filtered, allItems, heroIds)) continue
            result.add(Channel(
                id = ch.id,
                name = ch.name,
                logoUrl = ch.logoUrl,
                style = ch.style,
                brandColor = ch.brandColor,
                paddingLogo = ch.paddingLogo,
                paddingText = ch.paddingText,
            ))
        }
        return result
    }

    /** R228: the heroes+rows build shared by [getChannelFeed] and [buildChannels]'s own emptiness
     *  check — factored out so the two can never compute different content for the same channel. */
    private fun buildChannelContent(
        device: DeviceData,
        config: RaviloConfig,
        channelCfg: ChannelConfig,
        allItems: List<MediaItem>,
        heroIds: Set<String>,
    ): Pair<List<Hero>, List<Row>> {
        val filtered = allItems.filter { it.matchesChannel(channelCfg, heroIds) }
        val pageHero = channelCfg.pageHero
        val heroes   = if (pageHero?.enabled == true && pageHero.items.isNotEmpty())
            buildHeroesFromList(pageHero.items, allItems)
        else
            emptyList()
        val rows = buildRows(config, device, filtered, channelFilter = channelCfg)
        return heroes to rows
    }

    /**
     * Phase 206 (FR-206-1) — [buildChannels]'s non-empty verdict for one channel, answering exactly what
     * `heroes.isEmpty() && rows.all { it.items.isEmpty() }` would answer against [buildChannelContent]'s
     * real output, without a sort, a `take(30)`, or a single [MediaCard] construction: heroes are already
     * cheap ([buildHeroesFromList] resolves a handful of configured ids, not the library), and every row
     * kind gets a short-circuiting `any{}` over the *same* predicate [buildFilterRow] uses
     * ([matchesConfiguredRow]) rather than a second, hand-kept-in-sync copy of it — the drift R228 exists
     * to prevent. [filtered] is [allItems] already scoped by [MediaItem.matchesChannel], exactly as
     * [buildChannelContent] computes it — passed in so the caller (looping over every channel) computes
     * it once, not twice.
     */
    private fun channelHasAnyMatch(
        config: RaviloConfig,
        device: DeviceData,
        channelCfg: ChannelConfig,
        filtered: List<MediaItem>,
        libraryAll: List<MediaItem>,
        heroIds: Set<String>,
    ): Boolean {
        val pageHero = channelCfg.pageHero
        if (pageHero?.enabled == true && pageHero.items.isNotEmpty() && buildHeroesFromList(pageHero.items, libraryAll).isNotEmpty()) return true
        if (filtered.isEmpty()) return false  // open question 1's "cheap outer test" — settles the common case with no row work at all

        val cascade = configStore.current.metadata.ageRatingCascade
        fun continueRowNonEmpty(): Boolean {
            val canonical = canonicalContinueList(device)
            return canonical.any { it.mediaItem.matchesChannel(channelCfg, heroIds) }
        }

        val channelRows = channelCfg.rows
        if (channelRows?.mode == "custom") {
            val sys = channelRows.system
            if (sys.cont.show && continueRowNonEmpty()) return true
            if (sys.newly.show) return true  // filtered already known non-empty above; addNewlyAddedRows applies no further per-item filter
            return channelRows.items.filter { it.enabled }.any { rowCfg -> filtered.any { matchesConfiguredRow(it, rowCfg, heroIds, cascade) } }
        }

        // merge-mode injects a merged Newly Added row purely off the config.mergeNewlyAdded flag,
        // independent of whether any RowConfig of kind NEWLY_ADDED is even enabled — see the merge
        // block at the end of buildRows.
        if (config.mergeNewlyAdded) return true
        return config.rows.filter { it.enabled }.any { rowCfg ->
            when (rowCfg.kind) {
                RowKind.CONTINUE -> continueRowNonEmpty()
                RowKind.NEWLY_ADDED -> when (rowCfg.mediaKind) {
                    "MOVIE" -> filtered.any { it.kind == MediaKind.MOVIE }
                    "SERIES" -> filtered.any { it.kind == MediaKind.TV_SHOW }
                    "MUSIC_VIDEO" -> filtered.any { it.kind == MediaKind.MUSIC_VIDEO }
                    else -> true  // null mediaKind splits into Movies/Series; filtered non-empty ⇒ at least one exists
                }
                RowKind.GENRE, RowKind.CUSTOM -> filtered.any { matchesConfiguredRow(it, rowCfg, heroIds, cascade) }
            }
        }
    }

    // ─── Rows ─────────────────────────────────────────────────────────────────

    /**
     * R143: [all] is the context list (the channel-scoped list inside a channel, the full library on Home);
     * [libraryAll] is always the full unscoped library — used for system rows whose scope is "all".
     */
    private fun buildRows(
        config: RaviloConfig,
        device: DeviceData,
        all: List<MediaItem>,
        channelFilter: ChannelConfig?,
    ): List<Row> {
        val channelRows = channelFilter?.rows
        val heroIds = config.heroes.map { it.itemId }.toSet()  // Phase R86-C: hoist out of CUSTOM-row loop
        val result = mutableListOf<Row>()

        // ── R143/R233: custom channel — emit the configured system rows (Continue, Newly Added) ABOVE
        // the filter rows, each honouring its show/merge setting. R233: a system row always shows what
        // is available on the page it's rendered on — never configurable, in either row-list mode (see
        // the phase's "The rule"). `scope` is retired; Continue is unconditionally filtered to this
        // channel, and Newly Added already receives the channel-filtered `all`.
        if (channelRows?.mode == "custom") {
            val sys = channelRows.system
            if (sys.cont.show) {
                // R219 (FR-R219-6): the row and continueWatchingAll's See-all must apply this exact same
                // filter, or the two silently disagree about membership — see the model note in the spec.
                val canonical = canonicalContinueList(device)
                val cont = canonical.filter { it.mediaItem.matchesChannel(channelFilter!!, heroIds) }.capped()
                if (cont.cards.isNotEmpty()) result.add(Row("continue", "Continue Watching", RowKind.CONTINUE, cont.cards, seedTotalCount = cont.total))
            }
            if (sys.newly.show) {
                addNewlyAddedRows(result, all, merge = sys.newly.merge)
            }
            for (rowCfg in channelRows.items.filter { it.enabled }.sortedBy { it.order }) {
                buildFilterRow(rowCfg, all, heroIds, channelFilter)?.let { result.add(it) }
            }
            return result
        }

        // ── Home / inherit-mode channel — unchanged behaviour (config.rows drives system + filter rows).
        val enabledRows = config.rows.filter { it.enabled }.sortedBy { it.order }
        val mergeNewly = config.mergeNewlyAdded
        for (rowCfg in enabledRows) {
            when (rowCfg.kind) {
                RowKind.CONTINUE -> {
                    // R233: built from libraryAll (FR-R233-5 — the canonical list itself stays
                    // library-wide and cached per user, never per channel), then filtered to this page's
                    // channel when there is one — Home's `channelFilter` is null, so this is a no-op
                    // there. Previously unfiltered in every inherit-mode case (R202 FR-RV-R2-1): correct
                    // on Home, wrong on a channel page, which is what R233's live report hit.
                    val canonical = canonicalContinueList(device)
                    val filtered = if (channelFilter != null) canonical.filter { it.mediaItem.matchesChannel(channelFilter, heroIds) } else canonical
                    val cont = filtered.capped()
                    if (cont.cards.isNotEmpty()) result.add(Row(rowCfg.id, rowCfg.title ?: "Continue Watching", RowKind.CONTINUE, cont.cards, seedTotalCount = cont.total))
                }

                RowKind.NEWLY_ADDED -> {
                    if (mergeNewly) continue  // merged → single row injected below
                    // mediaKind=null (the "newly-all" system row) → split into Movies then Series (R71).
                    if (rowCfg.mediaKind == null) {
                        addNewlyAddedRows(result, all, merge = false, idPrefix = rowCfg.id, titlePrefix = rowCfg.title)
                        continue
                    }
                    val filtered = when (rowCfg.mediaKind) {
                        "MOVIE"  -> all.filter { it.kind == MediaKind.MOVIE }
                        "SERIES" -> all.filter { it.kind == MediaKind.TV_SHOW }
                        "MUSIC_VIDEO" -> all.filter { it.kind == MediaKind.MUSIC_VIDEO }
                        else     -> all
                    }
                    val cards = filtered
                        .let { orderRow(it, null) }
                        .mapNotNull { it.toMediaCardOrNull() }
                        .distinctBy { it.id }
                    if (cards.isNotEmpty()) result.add(Row(rowCfg.id, rowCfg.title ?: "Newly Added", RowKind.NEWLY_ADDED, cards))
                }

                RowKind.GENRE, RowKind.CUSTOM -> buildFilterRow(rowCfg, all, heroIds, channelFilter)?.let { result.add(it) }
            }
        }

        // Merge on → inject one merged NEWLY_ADDED row at the first configured NEWLY_ADDED position.
        if (mergeNewly) {
            val mergedCards = all
                .let { orderRow(it, null) }
                .mapNotNull { it.toMediaCardOrNull() }
                .distinctBy { it.id }
            if (mergedCards.isNotEmpty()) {
                val firstIdx = enabledRows.indexOfFirst { it.kind == RowKind.NEWLY_ADDED }
                val insertAt = when {
                    firstIdx < 0 -> result.size
                    firstIdx == 0 -> 0
                    else -> {
                        val predecessorId = enabledRows[firstIdx - 1].id
                        val pos = result.indexOfFirst { it.id == predecessorId }
                        if (pos < 0) result.size else pos + 1
                    }
                }
                result.add(insertAt.coerceIn(0, result.size), Row("newly-added", "Newly Added", RowKind.NEWLY_ADDED, mergedCards))
            }
        }

        return result
    }

    /** R143: append the Newly Added section from [src] — one merged row, or split Movies + Series rows. */
    private fun addNewlyAddedRows(
        result: MutableList<Row>,
        src: List<MediaItem>,
        merge: Boolean,
        idPrefix: String = "newly-added",
        titlePrefix: String? = null,
    ) {
        if (merge) {
            val cards = orderRow(src, null).mapNotNull { it.toMediaCardOrNull() }.distinctBy { it.id }
            if (cards.isNotEmpty()) result.add(Row(idPrefix, titlePrefix ?: "Newly Added", RowKind.NEWLY_ADDED, cards))
            return
        }
        val movies = orderRow(src.filter { it.kind == MediaKind.MOVIE }, null).mapNotNull { it.toMediaCardOrNull() }.distinctBy { it.id }
        val series = orderRow(src.filter { it.kind == MediaKind.TV_SHOW }, null).mapNotNull { it.toMediaCardOrNull() }.distinctBy { it.id }
        if (movies.isNotEmpty()) result.add(Row("$idPrefix-movies", titlePrefix?.let { "$it — Movies" } ?: "Movies — Newly Added", RowKind.NEWLY_ADDED, movies))
        if (series.isNotEmpty()) result.add(Row("$idPrefix-series", titlePrefix?.let { "$it — Series" } ?: "Series — Newly Added", RowKind.NEWLY_ADDED, series))
    }

    /** R187 — ANDs [channelFilter]'s own query (when this row is channel-scoped) onto [query] so a
     *  seeded browse page re-submitted against the seeded-browse endpoint reproduces the same
     *  candidate set this row was built from, not just the row's own filter in isolation. */
    private fun withChannelSeed(channelFilter: ChannelConfig?, query: ConditionGroup): ConditionGroup {
        val channelQuery = channelFilter?.effectiveQuery() ?: return query
        return ConditionGroup(QueryJoin.AND, children = listOf(channelQuery, query))
    }

    private fun genreTermsOf(rowCfg: RowConfig): List<String> =
        (rowCfg.title ?: "").split("&", ",").map { it.trim().lowercase() }.filter { it.isNotBlank() }

    /** Phase 206 (FR-206-1) — the per-item predicate a GENRE or CUSTOM row's membership actually turns
     *  on, factored out of [buildFilterRow] so the rail's cheap non-empty verdict ([channelHasAnyMatch])
     *  consults the *same* logic rather than a hand-kept-in-sync copy of it — the exact trap R228 was
     *  created to close for [buildChannelContent], now closed here too. Does not itself filter a list;
     *  [buildFilterRow] still does that (and still needs `matched`/`genreTerms` again afterward for the
     *  See-all seed), and the verdict path below calls this once per candidate inside an `any{}`. */
    // [resolvedQuery] lets a caller filtering many items hoist rowCfg.effectiveQuery()'s (cheap but
    // non-trivial — it runs migrateFlatQuery for a legacy row) resolution out of the per-item loop,
    // exactly like the pre-Phase-206 code already did; defaults to resolving it here for a one-off caller.
    private fun matchesConfiguredRow(item: MediaItem, rowCfg: RowConfig, heroIds: Set<String>, ageRatingCascade: List<String>, resolvedQuery: ConditionGroup? = null): Boolean =
        when (rowCfg.kind) {
            RowKind.GENRE -> genreTermsOf(rowCfg).let { terms -> terms.isEmpty() || item.genres.any { g -> terms.any { t -> g.lowercase().contains(t) } } }
            RowKind.CUSTOM -> ConditionEvaluator.matches(item, resolvedQuery ?: rowCfg.effectiveQuery(), heroIds, ageRatingCascade) && when (rowCfg.mediaKind) {
                "MOVIE" -> item.kind == MediaKind.MOVIE
                "SERIES" -> item.kind == MediaKind.TV_SHOW
                "MUSIC_VIDEO" -> item.kind == MediaKind.MUSIC_VIDEO
                else -> true
            }
            else -> false  // CONTINUE/NEWLY_ADDED: different data source / no per-item query, handled by their own callers
        }

    /** Phase 225 (FR-225-2) — every row is lined up HERE and nowhere else, through the shared [RowOrder]
     *  (the admin editor previews with the same function). [rowCfg] null = a system row: today's order,
     *  30 titles (FR-225-7). Pins compare on the id the served card carries (`jellyfinId ?: id`). */
    private fun orderRow(items: List<MediaItem>, rowCfg: RowConfig?): List<MediaItem> = RowOrder.resolve(
        items, rowCfg?.sort, rowCfg?.pinned.orEmpty(), rowCfg?.limit,
        id = { it.jellyfinId ?: it.id }, added = { it.recencyKey() }, year = { it.year }, title = { it.title }, sortName = { it.sortName },
    )

    /** R143: build one GENRE or CUSTOM filter row from [all] (already channel-scoped in channel context).
     *  R187: also populates [Row.seedQuery]/[Row.seedMediaKind]/[Row.seedTotalCount] for the "→ See all"
     *  browse page — the total is the pre-[ROW_ITEM_LIMIT] match count, not `cards.size`, so a genuinely
     *  truncated row's tile shows the real number, not the 30-item cap. */
    private fun buildFilterRow(rowCfg: RowConfig, all: List<MediaItem>, heroIds: Set<String>, channelFilter: ChannelConfig? = null): Row? = when (rowCfg.kind) {
        RowKind.GENRE -> {
            val matched = all.filter { matchesConfiguredRow(it, rowCfg, heroIds, configStore.current.metadata.ageRatingCascade) }
            val cards = matched
                .let { orderRow(it, rowCfg) }
                .mapNotNull { it.toMediaCardOrNull() }
                .distinctBy { it.id }
            if (cards.isEmpty()) null else {
                // R187 — the row's own substring match (e.g. title term "sci" catching "Science
                // Fiction") isn't expressible as ConditionEvaluator's exact-match "genre" facet, so
                // resolve it down to the real genre STRINGS it actually matched in this candidate set
                // and seed on those instead — reproducible via the seeded-browse endpoint, and correct
                // for exactly this row's real membership, not an approximation of the substring rule.
                val genreTerms = genreTermsOf(rowCfg)
                val matchedGenreValues = all.asSequence()
                    .flatMap { it.genres }
                    .filter { g -> genreTerms.isEmpty() || genreTerms.any { t -> g.lowercase().contains(t) } }
                    .distinct().toList()
                val seed = withChannelSeed(channelFilter, ConditionGroup(QueryJoin.AND, children = listOf(
                    Condition(facet = "genre", op = "is_any_of", values = matchedGenreValues),
                )))
                Row(rowCfg.id, rowCfg.title ?: "Genre", RowKind.GENRE, cards, seedQuery = seed, seedMediaKind = rowCfg.mediaKind, seedTotalCount = matched.size, sortBy = rowCfg.sort?.by, sortDescending = rowCfg.sort?.descending)
            }
        }
        RowKind.CUSTOM -> {
            // Phase 140 — effectiveQuery() reads rowCfg.query when the editor has migrated this row to
            // the blocks tree (and cleared match/conditions on save); falls back to migrating the
            // legacy flat shape on the fly otherwise. Reading match/conditions directly here would
            // silently stop filtering the moment a row is saved as a tree. Resolved once, outside the
            // per-item filter below (matchesConfiguredRow's resolvedQuery param), same as before Phase 206.
            val query = rowCfg.effectiveQuery()
            val filtered = all.filter { matchesConfiguredRow(it, rowCfg, heroIds, configStore.current.metadata.ageRatingCascade, resolvedQuery = query) }
            val cards = filtered
                .let { orderRow(it, rowCfg) }
                .mapNotNull { it.toMediaCardOrNull() }
                .distinctBy { it.id }
            if (cards.isEmpty()) null else Row(
                rowCfg.id, rowCfg.title ?: "Custom", RowKind.CUSTOM, cards,
                seedQuery = withChannelSeed(channelFilter, query), seedMediaKind = rowCfg.mediaKind, seedTotalCount = filtered.size,
                sortBy = rowCfg.sort?.by, sortDescending = rowCfg.sort?.descending,   // FR-225-8 — the key, never the pins
            )
        }
        else -> null
    }

    /** R187 (§G-4) — Continue Watching's own "→ See all" path: Continue Watching isn't expressible as a
     *  [dev.jellystructure.shared.tv.ConditionGroup] (it's a live Jellyfin resume/next-up join, not a
     *  catalog filter), so it can't reuse [BrowseService.browseByQuery] — this is its dedicated
     *  resolution. R233 (FR-R233-3): [channelId], when it resolves to a real channel, always filters —
     *  no reference to row-list mode, no `scope` to consult (retired). The row and this page can no
     *  longer disagree about membership because there is only one behaviour, not two branch ladders
     *  kept in sync by hand (R219 FR-R219-6's old shape). A channel id that doesn't resolve is
     *  defensive-unfiltered, unchanged from R219. Always uncapped (FR-R219-5). */
    // Phase 205 (FR-205-1) — no longer fetches a tvToken or the whole library; canonicalContinueList is
    // a pure cache read now.
    suspend fun continueWatchingAll(device: DeviceData, channelId: String?): List<MediaCard> {
        val canonical = canonicalContinueList(device)
        val config = configService.getConfig(device.jellyfinUserId)
        val channelCfg = channelId?.let { id -> config.channels.find { it.id == id } }
        val scoped = if (channelCfg != null) {
            val heroIds = config.heroes.map { it.itemId }.toSet()
            canonical.filter { it.mediaItem.matchesChannel(channelCfg, heroIds) }
        } else canonical
        return scoped.map { it.card }
    }

    /**
     * Phase 205 (FR-205-1/FR-205-2) — read-only. This used to build on a cache miss, which is exactly
     * the live Jellyfin wait FR-205-1 forbids (measured: a whole-library `DatePlayed` sort inside this
     * build costs 1.26-8.56s, and a cold-cache window was common precisely because Phase 204's fix
     * hadn't landed yet — a background write invalidated this cache too). [buildCanonicalContinueList]
     * is now called exclusively by the background loop ([start]/[refreshContinueListFor]); this just
     * returns whatever it last wrote for this user, or an empty list if never refreshed — R231's own
     * posture ("a failed build is never worse than an empty row") one layer further out.
     *
     * [allowedHash] is checked as a SAFETY property, not a staleness one: if this device's visibility
     * scope has changed since the cache was last written (a Jellyfin policy edit), the cached list may
     * belong to a broader scope than the viewer currently has — treated as unknown (empty) rather than
     * risked, self-healing on the next background cycle rather than retried live.
     */
    private fun canonicalContinueList(device: DeviceData): List<ContinueEntry> {
        val cached = continueListCache[device.jellyfinUserId] ?: return emptyList()
        val allowedHash = (device.allowedLibraries.hashCode() * 31 + device.allowedTags.hashCode()) * 31 + device.blockedTags.hashCode()
        return if (cached.allowedHash == allowedHash) cached.list else emptyList()
    }

    /** Phase 205 (FR-205-2) — the ONLY caller of [buildCanonicalContinueList] left after this phase.
     *  Also called directly by [invalidatePlaystate] (FR-205-9) for an immediate, best-effort correction
     *  right after a reported playback stop, rather than leaving it to wait out [CONTINUE_REFRESH_INTERVAL_MS]. */
    internal suspend fun refreshContinueListFor(device: DeviceData) {
        continueSourceForTest?.let { source ->
            val allowed = (device.allowedLibraries.hashCode() * 31 + device.allowedTags.hashCode()) * 31 + device.blockedTags.hashCode()
            val built = source(device)?.map { (mi, pct, ts) -> ContinueEntry(mi, mi.toMediaCard(progressPct = pct), ts) } ?: return
            storeContinueList(device.jellyfinUserId, built, allowed)
            return
        }
        val jellyfinBase = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        if (jellyfinBase.isBlank()) return
        val libraryAll = mediaStore.liveItems(device)
        val token = jellyfinClient.tvToken(jellyfinBase, device, configStore.current.apiKeys.jellyfinToken)
        val userId = device.jellyfinUserId
        val allowedHash = (device.allowedLibraries.hashCode() * 31 + device.allowedTags.hashCode()) * 31 + device.blockedTags.hashCode()
        // R231 — a failed build must never overwrite a good cached value; the previous entry (if any)
        // is simply left in place for the next cycle to retry.
        val built = buildCanonicalContinueList(device, libraryAll, jellyfinBase, token) ?: return
        storeContinueList(userId, built, allowedHash)
    }

    /** Phase 229 (FR-229-4) — every successful build re-stamps [ContinueListEntry.builtAt]; only a build
     *  whose visible content differs moves [ContinueListEntry.stamp], so the 60 s loop does not become a
     *  60 s feed TTL (FR-229-3 keys cached feeds on the stamp). */
    private fun storeContinueList(userId: String, built: List<ContinueEntry>, allowedHash: Int) {
        val prev = continueListCache[userId]
        val unchanged = prev != null && prev.allowedHash == allowedHash && prev.list.size == built.size &&
            prev.list.indices.all { prev.list[it].card == built[it].card && prev.list[it].lastActivityAt == built[it].lastActivityAt }
        val stamp = if (unchanged) prev.stamp else ++continueStampSeq
        continueListCache[userId] = ContinueListEntry(built, nowMs(), mediaStore.feedVersion, allowedHash, stamp)
    }

    /** Phase 205 (FR-205-2/FR-205-6) — called once from `Main.kt` at boot. [CONTINUE_REFRESH_INTERVAL_MS]
     *  is this loop's own cadence, chosen for `getRecentlyTouched`'s cost (the most expensive of the
     *  four fetches [buildCanonicalContinueList] runs) rather than inherited from a per-request TTL. */
    fun start(scope: CoroutineScope, deviceService: RaviloDeviceService) {
        scope.launch(GateClass.BACKGROUND) {
            while (true) {
                val cold = runCatching { refreshAllContinueLists(deviceService) }
                    .onFailure { Logger.warn("Continue Watching refresh failed: ${it.message}", "tv") }
                    .getOrDefault(0)
                delay(if (cold > 0) CONTINUE_COLD_RETRY_MS else CONTINUE_REFRESH_INTERVAL_MS)
            }
        }
    }

    /** Returns how many recently-seen users still have no list at all (FR-230-4's fast retry). */
    private suspend fun refreshAllContinueLists(deviceService: RaviloDeviceService): Int {
        if (configStore.current.apiKeys.jellyfinUrl.isBlank()) return 0
        val now = nowMs()
        val users = deviceService.allDevices()
            .filter { now - it.lastSeen < RECENTLY_SEEN_WINDOW_MS }
            .distinctBy { it.jellyfinUserId }
        var refreshed = 0
        var failed = 0
        for (device in users) {
            val before = continueListCache[device.jellyfinUserId]
            refreshContinueListFor(device)
            if (continueListCache[device.jellyfinUserId] !== before) refreshed++ else failed++
            delay(REFRESH_STAGGER_MS)
        }
        // Phase 205 (FR-205-3) — an aggregate, once per cycle: how many recently-seen users have no
        // Continue list at all yet ("unknown") vs how many just got a fresh one vs how many failed.
        // Distinguishing "unknown" from "confirmed empty" in the wire response was already true before
        // this phase (an empty list omits the row either way); this is the log-side half FR-205-3 also
        // asks for, since the wire response alone can't carry the distinction beyond "row present/absent."
        val cold = users.count { continueListCache[it.jellyfinUserId] == null }
        if (users.isNotEmpty()) Logger.info("Continue Watching refresh: $refreshed refreshed, $failed failed, $cold never built", "tv")
        return cold
    }

    /** R219 — one canonical, uncapped, unfiltered-by-channel Continue Watching entry: [mediaItem] (so a
     *  caller can channel-filter via [MediaItem.matchesChannel] without a second lookup), the [card] to
     *  display, and [lastActivityAt] (§4's sort key — the same value §3's conflict rule picked). */
    private data class ContinueEntry(val mediaItem: MediaItem, val card: MediaCard, val lastActivityAt: Long)

    /** R187's total/cap split, still needed at each capped view (Home row, channel row): [total] is the
     *  pre-cap match count, for [Row.seedTotalCount] (FR-R219-5) — `cards.size` alone would be wrong for
     *  a See-all tile's count once a viewer has more than [CONTINUE_ROW_LIMIT] in-progress/next-up titles. */
    private data class ContinueRowResult(val cards: List<MediaCard>, val total: Int)

    private fun List<ContinueEntry>.capped(limit: Int = CONTINUE_ROW_LIMIT): ContinueRowResult =
        ContinueRowResult(cards = take(limit).map { it.card }, total = size)

    /**
     * R219 — builds the canonical Continue Watching list per the spec's §2 (membership), §3 (conflict
     * rule) and §4 (order). [libraryAll] must be the FULL device-visible library (never a channel-
     * filtered subset) — channel scoping is a filter callers apply afterwards (FR-R219-6), never baked
     * in here, so this one build is shareable by every view.
     *
     * THE RULE (top of phase-R219's spec): every Jellyfin input below is fetched to completion before
     * anything is filtered, ranked or capped. No candidate is ever excluded by a bounded `Limit`.
     *
     * R231 — returns `null` (not an empty list) when the build could not be trusted (blank-URL config
     * gap, or the timeout below) — see [canonicalContinueList], which must never cache a `null` build
     * or treat it as "genuinely nothing to show."
     */
    private suspend fun buildCanonicalContinueList(
        device: DeviceData,
        libraryAll: List<MediaItem>,
        jellyfinBase: String,
        token: String,
    ): List<ContinueEntry>? = coroutineScope {
        val jellyfinUrl = configStore.current.apiKeys.jellyfinUrl.takeIf { it.isNotBlank() } ?: return@coroutineScope null
        val sinceTouched = nowMs() / 1000L - CONTINUE_TOUCHED_WINDOW_DAYS * 86_400L

        // R102: bound the wait so a cold/slow Jellyfin can't hang the whole home response on the 30s
        // HttpTimeout. On timeout the asyncs are cancelled and this build is untrustworthy (R231: `null`,
        // never cached) — the SWR cache in [canonicalContinueList] falls back to the previous good value
        // instead. All four fetches are independent — run them in parallel.
        // Phase 219 (FR-219-4) — the permit wait is measured apart from the round trip, so the log can
        // say which of the two things happened when this build times out.
        val recorder = dev.jellystructure.ops.GateWaitRecorder()
        // Phase 230 (FR-230-4) — no list to fall back on ⇒ be patient; R231's 6 s stands once there is one.
        val continueTimeoutMs = if (continueListCache[device.jellyfinUserId] == null) CONTINUE_COLD_TIMEOUT_MS else CONTINUE_TIMEOUT_MS
        val fetched = withTimeoutOrNull(continueTimeoutMs) {
            kotlinx.coroutines.withContext(recorder) {
                coroutineScope {
                    val resumeDeferred   = async { jellyfinClient.getResumeItemsAll(jellyfinUrl, token, device.jellyfinUserId) }
                    val nextUpDeferred   = async { jellyfinClient.getNextUp(jellyfinUrl, token, device.jellyfinUserId) }
                    val finishedDeferred = async { jellyfinClient.getRecentlyPlayedAll(jellyfinUrl, token, device.jellyfinUserId) }
                    val touchedDeferred  = async { jellyfinClient.getRecentlyTouched(jellyfinUrl, token, device.jellyfinUserId, sinceTouched) }
                    listOf(resumeDeferred.await(), nextUpDeferred.await(), finishedDeferred.await(), touchedDeferred.await())
                }
            }
        }
        if (fetched == null) {
            if (recorder.acquisitions == 0 || recorder.waitedMs >= continueTimeoutMs / 2) {
                Logger.info("Continue Watching refresh for ${device.jellyfinUsername} skipped — outbound pool busy (waited ${recorder.waitedMs} ms for a permit), will retry in ${CONTINUE_REFRESH_INTERVAL_MS / 1000} s", "tv")
            } else {
                Logger.warn("Continue Watching refresh for ${device.jellyfinUsername} timed out after ${continueTimeoutMs} ms at Jellyfin (permit wait ${recorder.waitedMs} ms)", "tv")
            }
            return@coroutineScope null
        }
        val (resumeItems, nextUpItems, finishedItems, touchedItems) = fetched

        val byJellyfinId = libraryAll.asSequence().mapNotNull { mi -> mi.jellyfinId?.let { it to mi } }.toMap()

        // R198 — never trust an upstream SortBy as a guarantee (confirmed live: two adjacent Resume
        // items came back out of order while the surrounding ~90 were fine). Own the ordering here.
        val resumeSorted = resumeItems.sortedByDescending { play ->
            play.userData?.lastPlayedDate?.let { dev.jellystructure.util.isoToEpochSeconds(it) } ?: 0L
        }

        // §3/§4 need "when did I last finish an episode of THIS title" and "when was THIS title last
        // touched at all" — both sources are already DatePlayed-descending from the client, so the
        // first hit per key is the most recent.
        val lastFinishedByKey = HashMap<String, Long>()
        for (p in finishedItems) {
            val key = p.seriesId ?: p.id
            val ts = p.userData?.lastPlayedDate?.let { dev.jellystructure.util.isoToEpochSeconds(it) } ?: continue
            if (key !in lastFinishedByKey) lastFinishedByKey[key] = ts
        }
        val lastTouchedByKey = HashMap<String, Long>()
        for (p in touchedItems) {
            val key = p.seriesId ?: p.id
            val ts = p.userData?.lastPlayedDate?.let { dev.jellystructure.util.isoToEpochSeconds(it) } ?: continue
            if (key !in lastTouchedByKey) lastTouchedByKey[key] = ts
        }

        // §2(a) / resume candidate — one per title, the MOST RECENT in-progress episode if several
        // (FR-R219-4: e.g. Tellytots shows S1E5/88%, never a stale S1E3).
        data class ResumeCandidate(val mediaItem: MediaItem, val card: MediaCard, val ts: Long)
        val resumeByKey = LinkedHashMap<String, ResumeCandidate>()
        for (play in resumeSorted) {
            // R185 — an item flagged Played is never resurrected as in-progress, whatever a leaked
            // position says. getResumeItemsAll's own IsPlayed=false already excludes this server-side;
            // this is the defensive backstop for when the two can disagree.
            if (play.userData?.played == true) continue
            val key = play.seriesId ?: play.id
            if (key in resumeByKey) continue  // already holding this key's newest episode (list is sorted)
            val mediaItem = byJellyfinId[key] ?: continue
            val pct = play.userData?.playedPercentage?.toFloat()?.div(100f)
            // R113: carry the resumed episode's season/episode for the on-image badge (null for movies).
            // R199: fall back to jellystructure's own scanned episode number (Phase 152) whenever
            // Jellyfin's own IndexNumber/ParentIndexNumber parse fails on the file's name.
            val (s, e) = resolvedEpisodeNumbers(mediaItem, play.id, play.seasonNumber, play.episodeNumber)
            val ts = play.userData?.lastPlayedDate?.let { dev.jellystructure.util.isoToEpochSeconds(it) } ?: 0L
            resumeByKey[key] = ResumeCandidate(mediaItem, mediaItem.toMediaCard(progressPct = pct, seasonNumber = s, episodeNumber = e), ts)
        }

        // "Something left to watch" half of §2 — next-up candidate per title.
        data class NextUpCandidate(val mediaItem: MediaItem, val card: MediaCard)
        val nextUpByKey = LinkedHashMap<String, NextUpCandidate>()
        for (play in nextUpItems) {
            val key = play.seriesId ?: play.id
            if (key in nextUpByKey) continue
            val mediaItem = byJellyfinId[key] ?: continue
            val (s, e) = resolvedEpisodeNumbers(mediaItem, play.id, play.seasonNumber, play.episodeNumber)
            val label = if (s != null && e != null) "S${s}E${e} · ${play.name}" else play.name
            nextUpByKey[key] = NextUpCandidate(mediaItem, mediaItem.toMediaCard(nextUpLabel = label, seasonNumber = s, episodeNumber = e))
        }

        // §2 membership: "genuinely started" (a: resume, b: finished, c: touched within the window) AND
        // "something left to watch" (resume or next-up). The union of (a)/(b)/(c) is the started set;
        // intersecting it with (resume ∪ next-up) is the actual membership test.
        val startedKeys = resumeByKey.keys + lastFinishedByKey.keys + lastTouchedByKey.keys
        val candidateKeys = startedKeys.filter { it in resumeByKey || it in nextUpByKey }

        // §3 — the conflict rule: most recent activity wins. Resume is newer, or there's no next-up →
        // resume card. The finish is newer and a next-up exists → next-up card. A next-up-only title
        // (§2(c): no resume, no finish, genuinely just "touched") falls to its own touched timestamp —
        // this branch can never lack a timestamp, because membership above required it to be in
        // startedKeys, and the only way in without a resume/finish is via lastTouchedByKey.
        val entries = candidateKeys.mapNotNull { key ->
            val resume = resumeByKey[key]
            val nextUp = nextUpByKey[key]
            val finishedTs = lastFinishedByKey[key]
            when {
                resume != null && nextUp != null && finishedTs != null && finishedTs > resume.ts ->
                    ContinueEntry(nextUp.mediaItem, nextUp.card, finishedTs)
                resume != null -> ContinueEntry(resume.mediaItem, resume.card, resume.ts)
                nextUp != null -> ContinueEntry(nextUp.mediaItem, nextUp.card, finishedTs ?: lastTouchedByKey[key] ?: 0L)
                else -> null  // unreachable — candidateKeys already required resume or nextUp present
            }
        }

        // §4 — one chronological order across the whole merged list. sortedByDescending is stable, so
        // ties keep the order they were built in. R198: unparseable/missing (the ts = 0L sentinels
        // above) sorts last, never first.
        entries.sortedByDescending { it.lastActivityAt }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** R199 — Jellyfin's IndexNumber/ParentIndexNumber parse can fail on scene-release filenames even
     *  when jellystructure's own scanner already resolved the episode via Phase 152's filename fallback.
     *  Each field falls back independently to the matching local [Episode] (by jellyfinId) so a badge
     *  isn't suppressed just because Jellyfin's own metadata is incomplete for that one file. */
    private fun resolvedEpisodeNumbers(mediaItem: MediaItem, jellyfinItemId: String, season: Int?, episode: Int?): Pair<Int?, Int?> {
        if (season != null && episode != null) return season to episode
        val local = mediaItem.episodes.firstOrNull { it.jellyfinId == jellyfinItemId } ?: return season to episode
        return (season ?: local.seasonNumber) to (episode ?: local.episodeNumber)
    }

    private fun MediaItem.toMediaCardOrNull(): MediaCard? {
        jellyfinId ?: return null
        return toMediaCard()
    }

    private fun MediaItem.toMediaCard(
        progressPct: Float? = null,
        nextUpLabel: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        badge: String? = null,
    ): MediaCard {
        val jId = jellyfinId
        val sonarrEnabled = configStore.current.sonarr?.enabled == true
        return MediaCard(
            id = jId ?: id,
            kind = when (kind) {
                MediaKind.TV_SHOW -> dev.jellystructure.shared.tv.MediaKind.SERIES
                MediaKind.MUSIC_VIDEO -> dev.jellystructure.shared.tv.MediaKind.MUSIC_VIDEO
                MediaKind.MOVIE -> dev.jellystructure.shared.tv.MediaKind.MOVIE
            },
            title = title,
            year = year,
            genre = genres.firstOrNull(),
            rating = CertificationResolver.resolve(configStore.current.metadata.ageRatingCascade, certifications)?.code,
            ageRating = CertificationResolver.normalizedAge(configStore.current.metadata.ageRatingCascade, configStore.current.metadata.ageRatingMap, certifications),
            posterUrl = RaviloImageUrl.poster(id, artwork.assetVersion(this, "poster")),     // R133/R214
            backdropUrl = RaviloImageUrl.backdrop(id, artwork.assetVersion(this, "backdrop")),
            progressPct = progressPct,
            nextUpLabel = nextUpLabel,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            badge = badge,
            upcomingEpisode = if (sonarrEnabled && kind == MediaKind.TV_SHOW &&
                sonarrStatus != "ended" && sonarrNextAiringDate != null &&
                sonarrNextAiringSeason != null && sonarrNextAiringEpisode != null)
                "S${sonarrNextAiringSeason.toString().padStart(2,'0')}E${sonarrNextAiringEpisode.toString().padStart(2,'0')}" else null,
        )
    }

    private fun MediaItem.matchesChannel(ch: ChannelConfig, heroIds: Set<String>): Boolean {
        // Phase 140: the blocks tree (ch.query, or the legacy flat shape migrated on the fly by
        // effectiveQuery()) supersedes the legacy single typed filters — same fallback chain as
        // before, just query-aware so a channel doesn't silently stop filtering the moment it's
        // saved as a tree (which clears match/conditions).
        val query = ch.effectiveQuery()
        if (query.isLive()) return ConditionEvaluator.matches(this, query, heroIds, configStore.current.metadata.ageRatingCascade)
        if (ch.filterNetwork != null && network.equals(ch.filterNetwork, ignoreCase = true)) return true
        if (ch.filterStudio  != null && studio.equals(ch.filterStudio,  ignoreCase = true)) return true
        if (ch.filterGenre   != null && genres.any { it.equals(ch.filterGenre, ignoreCase = true) }) return true
        if (ch.filterTag     != null && tags.any { it.equals(ch.filterTag, ignoreCase = true) }) return true
        return false
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun nowMs(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, ts.ptr)
    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}
