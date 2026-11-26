package dev.jellystructure.ravilo.screen

import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.ravilo.receiver.nowMs
import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.Person
import dev.jellystructure.shared.tv.RememberedChoice
import dev.jellystructure.shared.tv.Row
import dev.jellystructure.shared.tv.TvApiClient
import dev.jellystructure.shared.tv.TvSegmentMarkers
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/** Every Back-stack entry owns both its focused element and its last focused tile per row. */
internal data class ScreenRouteEntry(
    val name: String,
    var focusId: String,
    val rowFocus: MutableMap<String, String> = mutableMapOf(),
    val channelId: String? = null,
    val title: String? = null,
    val mediaId: String? = null,
    val mediaKind: MediaKind = MediaKind.MOVIE,
    var feed: HomeFeed? = null,
    val detail: ScreenDetail? = null,
    val browse: ScreenBrowse? = null,
    val search: ScreenSearch? = null,
    /** DEB-4893 (FR-20) — the page last drew a skeleton: the content that replaces it fades in; a page drawn from held data does not. */
    var skeleton: Boolean = false,
    /** DEB-4893 (FR-20) — the feed's load failed: the page shows the failure and Retry instead of loading again by itself. */
    var failed: Boolean = false,
    /** DEB-5696 (FR-60) — the cause of that failure: what the failure view says and which button it offers. */
    var failedKind: ScreenLoadKind = ScreenLoadKind.UNREACHABLE,
    /** DEB-5487 (FR-38) — the hero slide showing, so Back from a title opened on slide 2 draws slide 2. */
    var heroSlide: Int = 0,
    /** DEB-5695 (FR-59) — the feed was told it changed while it could not be read (a page over it, the player up): it is read again when it shows. */
    var stale: Boolean = false,
    /** FR-59 — a silent re-read of this feed is in flight. */
    var reading: Boolean = false,
    /** DEB-5697 (FR-61) — the feed on show is the last one saved, not yet answered by the network; set once the saved feed is looked for, so a failed load does not look again. */
    var saved: Boolean = false,
    var savedLooked: Boolean = false,
    /** FR-61 — the network read behind a saved feed failed: the line under the bar says so until an answer draws. */
    var savedFailed: Boolean = false,
) {
    /** Home's and a channel's feed are the pages FR-59 keeps current. */
    val isFeed get() = name == "home" || name == "channel"
}

/**
 * What the title's page already knows about a play — the part of a phone's push the TV would otherwise ask for (DEB-4660, FR-7).
 * [kind] is `movie` or `episode`; an episode carries its S·E line, its series' name and logo, and the episode after it.
 */
internal data class ScreenPlayContext(
    val kind: String,
    val kicker: String? = null,
    /** DEB-4932 (FR-23) — the series' own id, the key of the remembered audio and subtitle tier; null for a film. */
    val seriesId: String? = null,
    val seriesName: String? = null,
    val logoUrl: String? = null,
    val logoInk: String? = null,
    val segments: TvSegmentMarkers? = null,
    val nextId: String? = null,
    val nextTitle: String? = null,
    val nextKicker: String? = null,
    /** DEB-4865 — the counted episodes after [nextId], in order, so the next-up card can start the next one on the TV itself. */
    val following: List<ScreenFollowing> = emptyList(),
    /** DEB-5652 (DEB-5640, FR-54) — every season of the series with its counted episodes, for the player's episode rail; empty (no rail) for a film. */
    val seasons: List<ScreenRailSeason> = emptyList(),
    /** FR-54 — a newest-first series has no episode after any other: a rail pick starts alone. */
    val newestFirst: Boolean = false,
)

/** DEB-5652 (FR-54) — one season of the rail: [index] 0 is Specials, [label] the heading (`playerRailSeasonLabel`: *Season 1*, a Specials season's own name). */
internal data class ScreenRailSeason(val index: Int, val label: String, val entries: List<ScreenRailEntry>)

/**
 * DEB-5652 (FR-54) — one rail card, Android TV's `PlayerEpisodeEntry`: what the card draws, and what starting it needs.
 * [number] is null for a multi-episode file (its title already says which), [progress] is 0–1 and [watched] the page's overlay.
 */
internal data class ScreenRailEntry(
    val id: String,
    val number: Int?,
    val title: String,
    val kicker: String,
    val runtimeMin: Int,
    val stillUrl: String?,
    val progress: Float,
    val watched: Boolean,
    val segments: TvSegmentMarkers? = null,
)

/** One episode the TV can play next without asking the backend to push it: what a phone's `play_item` would have carried. */
internal data class ScreenFollowing(
    val id: String,
    val title: String,
    val kicker: String,
    val segments: TvSegmentMarkers? = null,
)

/** What the title's page hands the player when OK plays it (FR-9). [startOver] plays from 0; [context] is the page's own facts. */
internal data class ScreenPlayRequest(
    val id: String,
    val kind: MediaKind,
    val title: String,
    val startOver: Boolean = false,
    val context: ScreenPlayContext? = null,
)

/** The DOM shell stays mounted while R264's player covers it. */
internal class ScreenShell(
    private val api: TvApiClient,
    private val serverUrl: String,
    private val onChangeServer: () -> Unit = {},
    private val onProfileMenu: () -> Unit = {},
    /** DEB-5696 (FR-60) — OK on a failure's *Sign in*: this profile is removed and the sign-in screen opens. */
    private val onSignIn: () -> Unit = {},
    /** DEB-5697 (FR-61) — the profile showing, whose saved Home feed is read and written; null saves and reads nothing. */
    private val userId: () -> String? = { null },
    private val onPlay: (ScreenPlayRequest) -> Unit = {},
    private val playback: ScreenPlaybackSwitches = ScreenPlaybackSwitches(api, { null }, {}),
    prewarmCapabilities: () -> ClientCapabilities = { ClientCapabilities() },
    prewarmRemembered: (seriesId: String?) -> RememberedChoice? = { null },
) {
    /** DEB-5396 (FR-35) — the encode Play would use for the page that is showing. */
    private val pageWarm = ScreenPrewarm(api, prewarmCapabilities, prewarmRemembered)
    private val root get() = document.getElementById("shell") as HTMLElement
    private val body get() = document.getElementById("shell-body") as HTMLElement
    private val entries = mutableListOf(ScreenRouteEntry("home", "shell-page-target"))
    private val home = ScreenHome(serverUrl) { playback.showProgress }
    private val focus = ScreenFocus({ root }, { entries.last() }) {
        entries.last().browse?.follow(it.id)   // DEB-5698 (FR-64) — a grid draws the rows near the focused one before their images are judged
        home.updateImages(it.id)
        syncHero()
        val entry = entries.last()
        if (entry.browse?.nearEnd(it.id) == true) loadBrowse(entry)
        syncPrewarm()   // DEB-5648 (FR-50) — a Continue Watching tile that holds focus warms its page's Play
    }
    private var visible = false
    private var player = false
    private var loadGeneration = 0
    private var away = false
    /** DEB-5695 (FR-59) — the one gathering window that makes several triggers inside 1 s one read, and the 5 s wait behind a failure. */
    private var gatherTimer: Int? = null
    private var quietRetryTimer: Int? = null
    /** FR-59 — a silent redraw in progress: what [render] puts back once the feed is drawn and before focus settles. */
    private var keep: Pair<ScreenFeedKeep, HomeFeed>? = null

    fun hide() { visible = false; ++loadGeneration; root.classList.remove("on"); pageWarm.cancel(); syncHero(); syncQuietRetry() }

    fun resetHome() {
        entries.clear(); entries.add(ScreenRouteEntry("home", "shell-page-target"))
        ++loadGeneration
        player = false
        pageWarm.cancel()
        showHome()
    }

    fun showHome() {
        visible = true
        if (!player) {
            root.classList.add("on")
            render()
        }
    }

    fun isPlayer() = player

    /** DEB-5632 (FR-5) — the viewer's config arrived after Home drew: a Home on screen is drawn again with it. */
    fun redrawHome() {
        if (visible && !player && entries.last().name == "home" && entries.last().feed != null) render()
    }

    /** DEB-4925 (FR-22) — back from a long absence: a fresh Home, abandoning the stack and every load in flight. Nothing while another screen (sign-in, picker, setup) is up. */
    fun refreshAfterAway() {
        if (!visible || player) return
        resetHome()
    }

    fun showPlayer() {
        if (!visible) return
        if (!player) entries.add(ScreenRouteEntry("player", ""))
        player = true
        root.classList.remove("on")
        syncHero()
        syncPrewarm()   // a phone's push over the page cancels; Play released it first
        syncQuietRetry()
    }

    /** DEB-4663 (FR-11) — Settings is a route like Movies: Back pops to where the viewer came from. */
    fun openSettings() {
        if (!visible || player) return
        if (entries.last().name != ScreenSettings.ROUTE) {
            push(ScreenRouteEntry(ScreenSettings.ROUTE, ScreenSettings.firstFocusId(), mutableMapOf("bar" to "nav-profile")))
        }
        root.classList.add("on")
        render()
    }

    fun openMyList() {
        if (!visible || player) return
        push(ScreenRouteEntry("mylist", "nav-profile", browse = ScreenBrowse("mylist", home)))
        render()
    }

    fun returnFromPlayer() {
        if (!visible) return
        val closed = player && entries.last().name == "player"
        if (closed) entries.removeAt(entries.lastIndex)
        player = false
        root.classList.add("on")
        // DEB-5050 (FR-31) — whichever way the play ended, the page under the player reads its state again; it draws what it holds now.
        entries.lastOrNull()?.detail?.refresh()
        // DEB-5695 (FR-59) — and a Home or channel feed under it is read again (Continue Watching moved), drawn in place when it lands.
        if (closed) entries.lastOrNull()?.takeIf { it.isFeed && it.feed != null }?.stale = true
        render()
    }

    /**
     * DEB-5698 (DEB-5685, FR-64) — a route goes on top of the stack. The stack holds [MAX_ROUTES]: a push past that drops the oldest route
     * above Home, so Home stays at the bottom and Back always ends there. Following *More like this* and faces for an evening no longer
     * grows it without end.
     */
    private fun push(entry: ScreenRouteEntry) {
        entries.add(entry)
        while (entries.size > MAX_ROUTES) entries.removeAt(1)
    }

    /**
     * DEB-5050 (FR-31) — `home_changed` or `playstate_changed` on the events socket. DEB-5698 (FR-64): only what shows reads again. The top
     * route's title page reads now; one under it (or under the player) is marked stale and reads when it shows, which FR-31's rules then
     * decide whether to redraw.
     */
    fun stateChanged() {
        val top = entries.lastOrNull()
        entries.forEach {
            if (it === top) it.detail?.refresh() else it.detail?.markStale()
            // DEB-5695 (FR-59) — a feed that has drawn is read again; one under the top route (or the player) is read when it shows.
            if (it.isFeed && it.feed != null) it.stale = true
        }
        requestFeedRead()
    }

    fun handleKey(event: KeyboardEvent): Boolean {
        if (!visible || player) return false
        // DEB-4662 (FR-8) — in the search field the keys that type or move the caret are the field's own: taken here
        // (so Backspace there is not a Back out of the search) and left un-prevented.
        val inField = entries.last().focusId == ScreenSearch.FIELD_ID && entries.last().search != null
        // DEB-5685 (FR-57) — Right from the field reaches Clear while it shows.
        val clear = document.getElementById(ScreenSearch.CLEAR_ID) as? HTMLElement
        if (inField && event.key == "ArrowRight" && clear != null && !clear.hidden) {
            focus.focus(clear)
            event.preventDefault()
            return true
        }
        if (inField && (event.key == "Backspace" || event.key == "Enter" || event.key == "ArrowLeft" || event.key == "ArrowRight")) return true
        when {
            inField && event.key == "ArrowDown" -> {
                val first = document.getElementById("${ScreenSearch.TILE_PREFIX}0") ?: document.getElementById(ScreenSearch.RETRY_ID)
                (first as? HTMLElement)?.let(focus::focus)
            }
            isBackKey(event) -> back()
            event.key == "Enter" -> activate()
            event.key == "ArrowLeft" -> if (!stepHero(-1)) focus.move(-1, 0)
            event.key == "ArrowRight" -> if (!stepHero(1)) focus.move(1, 0)
            event.key == "ArrowUp" -> {
                val current = entries.last()
                if (current.browse != null && current.focusId.startsWith("browse-tile-")) {
                    val tab = when (current.name) { "mylist" -> "profile"; "person", SEE_ALL_ROUTE -> "home"; else -> current.name }
                    focus.focus(document.getElementById("nav-$tab") as HTMLElement)
                } else focus.move(0, -1)
            }
            event.key == "ArrowDown" -> focus.move(0, 1)
            else -> return false
        }
        event.preventDefault()
        return true
    }

    fun refocus() = focus.refocus()

    /** DEB-5396 (FR-35) — the app was sent to another one (FR-22): the page's warm encode stops at once, and starts its 2 s again on the way back. */
    fun awayFromApp(away: Boolean) {
        this.away = away
        syncPrewarm()
        syncHero()
    }

    /** DEB-5487 (FR-38) — the hero counts only while Home is the page showing, the player is not up and the app is in front. */
    private fun syncHero() {
        home.syncHero(visible && !player && !away && entries.lastOrNull()?.feed != null)
    }

    private fun stepHero(direction: Int) = entries.last().focusId == ScreenHome.HERO_ID && home.stepHero(direction)

    private fun render() {
        val current = entries.last()
        if (current.name == ScreenSettings.ROUTE) {
            ScreenSettings.render(body, serverUrl, playback, onLanguage = { render() }, onChangeServer = onChangeServer)
        } else if (current.detail != null) {
            // DEB-4660 — focus lands on the primary action once the page has drawn, not on the loading skeleton.
            if (current.detail.ready && current.focusId == "shell-page-target") current.focusId = current.detail.primaryFocusId
            // FR-20 — the page that replaces a skeleton fades in; one redrawn from what the route holds (a write-through, a season) does not.
            current.detail.render(body, fade = current.skeleton && current.detail.ready)
            current.skeleton = !current.detail.ready
        } else if (current.browse != null) {
            current.browse.render(body, fade = current.skeleton && current.browse.page > 0)
            current.skeleton = current.browse.page == 0
        } else if (current.search != null) {
            current.search.render(body)
        } else {
            body.textContent = ""
            seedFromSaved(current)
            val feed = current.feed
            if (feed != null) {
                home.render(feed, body, ScreenLanguage.lang, current.heroSlide) { current.heroSlide = it }
                keep?.let { (kept, next) -> kept.restoreRails(body, next) }   // FR-59 — before focus settles, which judges a rail by where it rests
                if (current.skeleton) ScreenSkeleton.fadeIn(body.firstElementChild as? HTMLElement)
                current.skeleton = false
            } else if (current.failed) {
                body.appendChild(ScreenSkeleton.failure(current.failedKind))
            } else {
                body.appendChild(ScreenSkeleton.home())
                current.skeleton = true
            }
        }
        renderScreenNav(if (current.name == "channel" || current.name == "detail" || current.name == "person" || current.name == SEE_ALL_ROUTE) "home" else current.name, ScreenLanguage.lang)
        focus.refocus()
        syncSavedLine()
        if (current.isFeed && current.feed == null && !current.failed) loadFeed(current)
        // FR-61 — a saved feed is read at once (FR-59's silent re-read, without its gathering second); any other stale feed gathers.
        if (current.saved && current.stale) readStaleFeed()
        else if (current.isFeed && current.stale) requestFeedRead()   // FR-59 — a feed told it changed while covered is read now that it shows
        syncQuietRetry()
        current.detail?.load()
        current.detail?.shown()   // FR-64 — a read it was told about while covered
        // FR-64 — a title's page that another route covers keeps its data and lets go of its drawn nodes.
        for (covered in entries) if (covered !== current) covered.detail?.cover()
        syncHero()
        syncPrewarm()
        if (current.browse != null && current.browse.page == 0 && !current.browse.loading && !current.browse.failed) loadBrowse(current)
    }

    /**
     * DEB-5396 (FR-35) — the page that shows holds the encode of what Play would play, after 2 s. Anything else showing — Back, another
     * page, Home, the app hidden, a phone's push — cancels it at once; a target that moves cancels the old item and starts the 2 s
     * again for the new one. [playFromPage] lets go of it first, so leaving by Play does not.
     * DEB-5648 (FR-50) — Home holds the same for the Continue Watching tile in focus, and the page that tile opens keeps that hold
     * while it loads and carries on from it once it is ready ([ScreenPrewarm.settle]).
     */
    private fun syncPrewarm() {
        val entry = entries.lastOrNull()
        if (!visible || player || away || entry == null) { pageWarm.cancel(); return }
        val page = entry.detail
        if (page != null) {
            if (!page.ready) {
                if (page.failed || pageWarm.holdFor != entry.mediaId) pageWarm.cancel()
                return
            }
            val target = page.prewarmTarget()
            when {
                target == null -> pageWarm.cancel()
                pageWarm.holdFor == entry.mediaId -> pageWarm.settle(target, page.prewarmSeriesId)
                else -> pageWarm.warm(target, page.prewarmSeriesId, ScreenPrewarm.DWELL_MS)
            }
            return
        }
        val tile = (document.getElementById(entry.focusId) as? HTMLElement)
            ?.takeIf { entry.name == "home" && entry.feed != null && it.getAttribute("data-row-kind") == ScreenHome.CONTINUE_ROW }
        val mediaId = tile?.getAttribute("data-media-id")
        when (tile?.getAttribute("data-media-kind")) {
            "movie" -> pageWarm.warmTile(mediaId!!, null, ScreenPrewarm.DWELL_MS)
            "series" -> pageWarm.warmTile(mediaId!!, mediaId, ScreenPrewarm.DWELL_MS) { seriesPlayTarget(mediaId) }
            else -> pageWarm.cancel()
        }
    }

    /**
     * DEB-5648 (FR-50) — what a series' page would play, read the way the page reads it: the series, then its playstate. A failed or late
     * (5 s) read answers null, so nothing is warmed.
     */
    private suspend fun seriesPlayTarget(id: String): String? = withTimeoutOrNull(PREWARM_READ_MS) {
        runCatching {
            val detail = api.getSeries(id)
            val ids = (listOf(id) + detail.seasons.flatMap { s -> s.episodes.map { it.id } }).distinct()
            seriesPrimary(detail, id, api.getPlaystateStrict(ids))?.episode?.id
        }.getOrNull()
    }

    /** Play on a title's page: the server adopts the warm job when the plan matches, or stops it for its own, so nothing is cancelled here. */
    private fun playFromPage(request: ScreenPlayRequest) {
        pageWarm.release()
        onPlay(request)
    }

    private fun loadBrowse(entry: ScreenRouteEntry) {
        val browse = entry.browse ?: return
        if (browse.loading || browse.complete) return
        browse.loading = true
        val generation = loadGeneration
        GlobalScope.launch {
            // DEB-5631 (FR-49) — a person page asks the seeded endpoint, as Android TV's `openPersonBrowse` does: one read, the whole set.
            val seed = browse.seed
            val rowSeed = browse.rowSeed
            var error: Throwable? = null   // DEB-5696 (FR-60) — what the failed read said, for the failure view
            val seeded = if (seed != null) runCatching { api.browseSeeded(seed.query, null) }.onFailure { error = it }.getOrNull() else null
            // DEB-5690 (FR-55) — a row's See all is the same one read, whichever of the three Android TV's store would make.
            val rowItems = if (rowSeed != null) runCatching { rowSeed.read(api) }.onFailure { error = it }.getOrNull() else null
            val paged = if (seed == null && rowSeed == null) runCatching { api.browse(kind = browse.kind, page = browse.page + 1, pageSize = 60) }.onFailure { error = it }.getOrNull() else null
            browse.loading = false
            if (generation != loadGeneration || !visible || entry !== entries.lastOrNull()) return@launch
            if (seeded != null || paged != null || rowItems != null) {
                seeded?.let(browse::appendAll)
                rowItems?.let(browse::appendRow)
                paged?.let(browse::append)
                render()
            } else if (browse.page == 0) {
                // DEB-4893 (FR-20) — a grid's first page that fails says so; the skeleton never pulses forever.
                browse.failed = true
                browse.failedKind = ScreenLoadKind.of(error)
                render()
            }
        }
    }

    private fun loadFeed(entry: ScreenRouteEntry) {
        val generation = ++loadGeneration
        GlobalScope.launch {
            val result = runCatching { readFeed(entry) }
            if (generation != loadGeneration || !visible || entry !== entries.lastOrNull()) return@launch
            val feed = result.getOrNull()
            if (feed != null) { entry.feed = feed; answered(entry, feed) } else { entry.failed = true; entry.failedKind = ScreenLoadKind.of(result.exceptionOrNull()) }
            render()
        }
    }

    private suspend fun readFeed(entry: ScreenRouteEntry): HomeFeed = entry.channelId?.let { api.getChannel(it) } ?: api.getHome()

    /**
     * DEB-5697 (DEB-5685, FR-61) — Home, at start and on a profile switch, draws the profile's last saved feed at once when it is fresh, with no
     * skeleton; [render] then reads the network as FR-59's silent re-read does, and the answer replaces it in place. A channel is never seeded.
     */
    private fun seedFromSaved(entry: ScreenRouteEntry) {
        if (entry.name != "home" || entry.feed != null || entry.failed || entry.savedLooked) return
        entry.savedLooked = true
        val feed = userId()?.let { ScreenHomeSnapshot.load(it, nowMs()) } ?: return
        entry.feed = feed
        entry.saved = true
        entry.stale = true
    }

    /** FR-61 — the network answered for [entry]: a Home feed is saved under the profile, and a saved feed on show is no longer one. */
    private fun answered(entry: ScreenRouteEntry, feed: HomeFeed) {
        entry.saved = false; entry.savedFailed = false
        if (entry.name == "home") userId()?.let { ScreenHomeSnapshot.save(it, feed, nowMs()) }
    }

    /** FR-61 — the read behind a saved feed failed: the line under the bar says so, and the 5 s ask behind a failure goes on until an answer draws. */
    private fun savedReadFailed(entry: ScreenRouteEntry) {
        if (!entry.saved) return
        entry.savedFailed = true
        syncSavedLine()
        syncQuietRetry()
    }

    /** FR-61 — `home.showing_saved` under the bar while the route showing is Home on a saved feed whose read failed; gone when an answer draws. */
    private fun syncSavedLine() {
        val entry = entries.lastOrNull()
        val on = visible && !player && entry != null && entry.savedFailed
        var line = document.getElementById(SAVED_LINE_ID) as? HTMLElement
        if (!on) { line?.hidden = true; return }
        if (line == null) {
            line = document.createElement("div") as HTMLElement
            line.id = SAVED_LINE_ID
            root.appendChild(line)
        }
        line.textContent = ScreenLanguage.t("home.showing_saved")
        line.hidden = false
    }

    /**
     * DEB-5695 (FR-59) — every trigger that says a feed is out of date (the player closing, `home_changed`, `playstate_changed`, a socket back
     * after a gap, a stale feed showing) opens one 1 s window, and the window's end makes one read: several triggers inside it are one read.
     */
    private fun requestFeedRead() {
        if (gatherTimer != null) return
        gatherTimer = window.setTimeout({ gatherTimer = null; readStaleFeed() }, FEED_GATHER_MS)
    }

    /**
     * FR-59 — a silent re-read of the feed on screen. The feed stays drawn until the new one lands; a read that fails changes nothing and shows
     * nothing; an answer equal to what is held draws nothing. A feed under the top route or the player stays stale until it shows.
     */
    private fun readStaleFeed() {
        val entry = entries.lastOrNull() ?: return
        if (!visible || player || !entry.isFeed || !entry.stale || entry.feed == null || entry.reading) return
        entry.stale = false
        entry.reading = true
        GlobalScope.launch {
            val feed = runCatching { readFeed(entry) }.getOrNull()
            entry.reading = false
            if (entry.stale) requestFeedRead()   // told again while this read was out: its answer may predate it
            if (entries.none { it === entry }) return@launch
            if (feed == null) { savedReadFailed(entry); return@launch }
            val shown = entry.saved
            answered(entry, feed)
            if (feed == entry.feed) { if (shown) syncSavedLine(); return@launch }
            redrawFeed(entry, feed)
        }
    }

    /**
     * FR-59 — the new feed replaces the old one in place: focus stays on the tile by its media id, each row's memory, the hero slide and the
     * rails stay where they were ([ScreenFeedKeep]), and nothing fades or shows a skeleton. A feed under the top route (or the player) is stored
     * and drawn when it shows.
     */
    private fun redrawFeed(entry: ScreenRouteEntry, feed: HomeFeed) {
        val old = entry.feed ?: return
        if (!visible || player || entry !== entries.lastOrNull()) { entry.feed = feed; return }
        val kept = ScreenFeedKeep(old, entry, body)
        entry.feed = feed
        kept.retarget(entry, feed)
        keep = kept to feed
        try { render() } finally { keep = null }
        ScreenFeedKeep.showImagesAtOnce(body)
    }

    /**
     * FR-59 — a Home or channel feed whose load failed is asked again every 5 s while its failure shows ([HomeLoadPolicy.quietRetryMs] on Android TV),
     * and draws when an answer comes, focus on its first target. Retry still asks at once, under the skeleton.
     */
    private fun syncQuietRetry() {
        val entry = entries.lastOrNull()
        val waiting = visible && !player && entry != null && entry.isFeed &&
            ((entry.failed && entry.feed == null && entry.failedKind == ScreenLoadKind.UNREACHABLE) || entry.savedFailed)   // FR-61 — the line under a saved feed waits for an answer the same way
        if (!waiting) {
            quietRetryTimer?.let { window.clearTimeout(it) }
            quietRetryTimer = null
            return
        }
        if (quietRetryTimer != null) return
        quietRetryTimer = window.setTimeout({ quietRetryTimer = null; quietRetry() }, QUIET_RETRY_MS)
    }

    private fun quietRetry() {
        val entry = entries.lastOrNull() ?: return
        if (!visible || player || !entry.isFeed || entry.reading) return
        if (entry.savedFailed && entry.saved) {   // FR-61 — a saved feed's read failed: ask again, and the answer replaces it in place
            entry.stale = true
            readStaleFeed()
            return
        }
        if (!entry.failed || entry.failedKind != ScreenLoadKind.UNREACHABLE || entry.feed != null) return
        entry.reading = true
        val generation = loadGeneration
        GlobalScope.launch {
            val result = runCatching { readFeed(entry) }
            val feed = result.getOrNull()
            entry.reading = false
            // Retry pressed, or the route left, meanwhile: that load owns the page now.
            if (generation != loadGeneration || !visible || player || entry !== entries.lastOrNull() || !entry.failed) return@launch
            if (feed == null) {
                // FR-59 — a read that failed again is asked again 5 s later ("every 5 s while its failure shows"): the timer was left spent, so a server
                // that was still down at the first ask was never asked a second time. DEB-5696 (FR-60) — and the page says what it now is: a sign-in
                // that has gone since reads as that and is not asked again.
                val kind = ScreenLoadKind.of(result.exceptionOrNull())
                if (kind != entry.failedKind) { entry.failedKind = kind; render() } else syncQuietRetry()
                return@launch
            }
            entry.feed = feed; entry.failed = false; entry.stale = false
            answered(entry, feed)
            render()
        }
    }

    /** DEB-4893 (FR-20) — Retry on a failed page loads it again under the skeleton. */
    private fun retry(entry: ScreenRouteEntry) {
        entry.failed = false
        entry.browse?.failed = false
        entry.detail?.retry()   // DEB-5696 (FR-60) — a failed title's page loads again, which it never did before
        render()
    }

    /** The results under the search field changed: if the focused tile went with them, focus falls back to the field. */
    private fun searchChanged() {
        val current = entries.lastOrNull() ?: return
        if (!visible || player || current.search == null) return
        if (ScreenSearch.isResult(current.focusId)) focus.refocus()
    }

    /** A title's page has something new to draw (its facts arrived, or a write-through answered): redraw it if it is still the route. */
    private fun detailChanged(page: ScreenDetail) {
        if (!visible || player || entries.lastOrNull()?.detail !== page) return
        render()
    }

    /** DEB-5631 (FR-49) — OK on a face: the person's page, over the title's page it was opened from. */
    private fun openPerson(person: Person, source: String) {
        if (!visible || player) return
        push(ScreenRouteEntry("person", "shell-page-target", title = person.name, browse = ScreenBrowse("person", home, ScreenPersonSeed(person, source))))
        render()
    }

    /** DEB-5690 (FR-55) — OK on a row's *See all* tile: the row's whole list as a grid over Home or the channel page. */
    private fun openSeeAll(row: Row, channelId: String?) {
        if (!visible || player) return
        push(ScreenRouteEntry(SEE_ALL_ROUTE, "shell-page-target", title = row.title,
            browse = ScreenBrowse("seeall", home, rowSeed = ScreenRowSeed(row, channelId), showProgress = { playback.showProgress })))
        render()
    }

    /** A title's page over the route: from a tile on Home, a grid, a person's page, or DEB-5649's *More like this* row. */
    private fun openTitle(mediaId: String, kind: MediaKind, title: String) {
        val page = ScreenDetail(api, serverUrl, mediaId, kind, title, onChange = ::detailChanged, onPlay = ::playFromPage, onPerson = ::openPerson,
            tiles = home, onTitle = { card -> if (visible && !player) openTitle(card.id, card.kind, card.title) })
        push(ScreenRouteEntry("detail", "shell-page-target", title = title, mediaId = mediaId, mediaKind = kind, detail = page))
        render()
    }

    private fun activate() {
        val current = entries.last()
        val element = document.getElementById(current.focusId) as? HTMLElement ?: return
        // DEB-5696 (FR-60) — a failure's one button, on any page: Retry asks again, Sign in leaves this profile, Back is Back.
        when (element.getAttribute("data-action")?.takeIf { element.classList.contains("load-failure-retry") }) {
            "retry" -> { retry(current); return }
            "sign-in" -> { onSignIn(); return }
            "back" -> { back(); return }
        }
        // DEB-4660 (FR-7) — on a title's page OK is the page's own: its actions, a season pill, an episode.
        if (current.detail != null && !current.focusId.startsWith("nav-")) { current.detail.activate(element); return }
        // DEB-5685 (FR-57) — Clear empties the field and puts focus back in it; a failed search's Retry asks for the same text again.
        if (current.search != null && element.id == ScreenSearch.CLEAR_ID) {
            current.search.clear()
            (document.getElementById(ScreenSearch.FIELD_ID) as? HTMLElement)?.let(focus::focus)
            return
        }
        if (current.search != null && element.id == ScreenSearch.RETRY_ID) { current.search.retry(); return }
        if (element.getAttribute("data-action") == "retry") { retry(current); return }
        val seeAll = element.getAttribute("data-see-all")?.toIntOrNull()
        if (seeAll != null) {
            current.feed?.rows?.getOrNull(seeAll)?.let { openSeeAll(it, current.channelId) }
            return
        }
        val channelId = element.getAttribute("data-channel-id")
        val mediaId = element.getAttribute("data-media-id")
        if (channelId != null) {
            push(ScreenRouteEntry("channel", "shell-page-target", channelId = channelId,
                title = element.textContent ?: ""))
            render()
            return
        }
        if (mediaId != null) {
            val title = element.getAttribute("data-media-title") ?: element.querySelector(".tile-title, .hero-title")?.textContent ?: element.textContent ?: ""
            val kind = MediaKind.entries.firstOrNull { it.name.lowercase() == element.getAttribute("data-media-kind") } ?: MediaKind.MOVIE
            openTitle(mediaId, kind, title)
            return
        }
        val focused = current.focusId
        if (!focused.startsWith("nav-")) { element.click(); return }
        val destination = focused.removePrefix("nav-")
        if (destination == "profile") { onProfileMenu(); return }
        if (current.name == destination) {
            (body.querySelector(".shell-target") as? HTMLElement)?.let(focus::focus)
            return
        }
        val browse = when (destination) {
            "movies" -> ScreenBrowse("movie", home)
            "series" -> ScreenBrowse("series", home)
            else -> null
        }
        val search = if (destination == "search") ScreenSearch(api, home, onResults = ::searchChanged) else null
        push(ScreenRouteEntry(destination, if (search != null) ScreenSearch.FIELD_ID else "shell-page-target",
            mutableMapOf("bar" to focused), browse = browse, search = search))
        render()
    }

    private fun back() {
        val current = entries.last()
        // DEB-4662 (FR-8) — Back from the results returns to the field; the typed text is still in it.
        if (current.search != null && ScreenSearch.isResult(current.focusId)) {
            (document.getElementById(ScreenSearch.FIELD_ID) as? HTMLElement)?.let(focus::focus)
            return
        }
        if (entries.size > 1) {
            ++loadGeneration
            entries.removeAt(entries.lastIndex)
            render()
            return
        }
        if (!current.focusId.startsWith("nav-")) {
            focus.focus(document.getElementById("nav-home") as HTMLElement)
            return
        }
        runCatching { TizenGlobal.application.getCurrentApplication().exit() }
            .onFailure { window.close() }
    }

    private companion object {
        const val SEE_ALL_ROUTE = "seeall"

        /** DEB-5697 (FR-61) — the line under the bar that says Home shows what was saved. */
        const val SAVED_LINE_ID = "shell-saved"

        /** DEB-5698 (FR-64) — the most routes the Back stack holds, Home included. */
        const val MAX_ROUTES = 12

        /** DEB-5648 (FR-50) — how long the reads for a series tile's target may take before nothing is warmed. */
        const val PREWARM_READ_MS = 5_000L
        /** DEB-5695 (FR-59) — triggers this close together are one read. */
        const val FEED_GATHER_MS = 1_000
        /** FR-59 — `HomeLoadPolicy.quietRetryMs`: the pace of the asks behind a failure. */
        const val QUIET_RETRY_MS = 5_000
    }
}
