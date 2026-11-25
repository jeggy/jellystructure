package dev.jellystructure.ravilo.screen

import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.Person
import dev.jellystructure.shared.tv.RememberedChoice
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
    /** DEB-5487 (FR-38) — the hero slide showing, so Back from a title opened on slide 2 draws slide 2. */
    var heroSlide: Int = 0,
)

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

    fun hide() { visible = false; ++loadGeneration; root.classList.remove("on"); pageWarm.cancel(); syncHero() }

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
    }

    /** DEB-4663 (FR-11) — Settings is a route like Movies: Back pops to where the viewer came from. */
    fun openSettings() {
        if (!visible || player) return
        if (entries.last().name != ScreenSettings.ROUTE) {
            entries.add(ScreenRouteEntry(ScreenSettings.ROUTE, ScreenSettings.firstFocusId(), mutableMapOf("bar" to "nav-profile")))
        }
        root.classList.add("on")
        render()
    }

    fun openMyList() {
        if (!visible || player) return
        entries.add(ScreenRouteEntry("mylist", "nav-profile", browse = ScreenBrowse("mylist", home)))
        render()
    }

    fun returnFromPlayer() {
        if (!visible) return
        if (player && entries.last().name == "player") entries.removeAt(entries.lastIndex)
        player = false
        root.classList.add("on")
        // DEB-5050 (FR-31) — whichever way the play ended, the page under the player reads its state again; it draws what it holds now.
        entries.lastOrNull()?.detail?.refresh()
        render()
    }

    /** DEB-5050 (FR-31) — `home_changed` or `playstate_changed` on the events socket: every title's page in the stack reads again, under the player too. */
    fun stateChanged() {
        entries.forEach { it.detail?.refresh() }
    }

    fun handleKey(event: KeyboardEvent): Boolean {
        if (!visible || player) return false
        // DEB-4662 (FR-8) — in the search field the keys that type or move the caret are the field's own: taken here
        // (so Backspace there is not a Back out of the search) and left un-prevented.
        val inField = entries.last().focusId == ScreenSearch.FIELD_ID && entries.last().search != null
        if (inField && (event.key == "Backspace" || event.key == "Enter" || event.key == "ArrowLeft" || event.key == "ArrowRight")) return true
        when {
            inField && event.key == "ArrowDown" -> {
                (document.getElementById("${ScreenSearch.TILE_PREFIX}0") as? HTMLElement)?.let(focus::focus)
            }
            isBackKey(event) -> back()
            event.key == "Enter" -> activate()
            event.key == "ArrowLeft" -> if (!stepHero(-1)) focus.move(-1, 0)
            event.key == "ArrowRight" -> if (!stepHero(1)) focus.move(1, 0)
            event.key == "ArrowUp" -> {
                val current = entries.last()
                if (current.browse != null && current.focusId.startsWith("browse-tile-")) {
                    val tab = when (current.name) { "mylist" -> "profile"; "person" -> "home"; else -> current.name }
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
            val feed = current.feed
            if (feed != null) {
                home.render(feed, body, ScreenLanguage.lang, current.heroSlide) { current.heroSlide = it }
                if (current.skeleton) ScreenSkeleton.fadeIn(body.firstElementChild as? HTMLElement)
                current.skeleton = false
            } else if (current.failed) {
                body.appendChild(ScreenSkeleton.failure())
            } else {
                body.appendChild(ScreenSkeleton.home())
                current.skeleton = true
            }
        }
        renderScreenNav(if (current.name == "channel" || current.name == "detail" || current.name == "person") "home" else current.name, ScreenLanguage.lang)
        focus.refocus()
        if ((current.name == "home" || current.name == "channel") && current.feed == null && !current.failed) loadFeed(current)
        current.detail?.load()
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
            val seeded = if (seed != null) runCatching { api.browseSeeded(seed.query, null) }.getOrNull() else null
            val paged = if (seed == null) runCatching { api.browse(kind = browse.kind, page = browse.page + 1, pageSize = 60) }.getOrNull() else null
            browse.loading = false
            if (generation != loadGeneration || !visible || entry !== entries.lastOrNull()) return@launch
            if (seeded != null || paged != null) {
                seeded?.let(browse::appendAll)
                paged?.let(browse::append)
                render()
            } else if (browse.page == 0) {
                // DEB-4893 (FR-20) — a grid's first page that fails says so; the skeleton never pulses forever.
                browse.failed = true
                render()
            }
        }
    }

    private fun loadFeed(entry: ScreenRouteEntry) {
        val generation = ++loadGeneration
        GlobalScope.launch {
            val result = runCatching { entry.channelId?.let { api.getChannel(it) } ?: api.getHome() }
            if (generation != loadGeneration || !visible || entry !== entries.lastOrNull()) return@launch
            val feed = result.getOrNull()
            if (feed != null) entry.feed = feed else entry.failed = true
            render()
        }
    }

    /** DEB-4893 (FR-20) — Retry on a failed page loads it again under the skeleton. */
    private fun retry(entry: ScreenRouteEntry) {
        entry.failed = false
        entry.browse?.failed = false
        render()
    }

    /** The results under the search field changed: if the focused tile went with them, focus falls back to the field. */
    private fun searchChanged() {
        val current = entries.lastOrNull() ?: return
        if (!visible || player || current.search == null) return
        if (current.focusId.startsWith(ScreenSearch.TILE_PREFIX)) focus.refocus()
    }

    /** A title's page has something new to draw (its facts arrived, or a write-through answered): redraw it if it is still the route. */
    private fun detailChanged(page: ScreenDetail) {
        if (!visible || player || entries.lastOrNull()?.detail !== page) return
        render()
    }

    /** DEB-5631 (FR-49) — OK on a face: the person's page, over the title's page it was opened from. */
    private fun openPerson(person: Person, source: String) {
        if (!visible || player) return
        entries.add(ScreenRouteEntry("person", "shell-page-target", title = person.name, browse = ScreenBrowse("person", home, ScreenPersonSeed(person, source))))
        render()
    }

    /** A title's page over the route: from a tile on Home, a grid, a person's page, or DEB-5649's *More like this* row. */
    private fun openTitle(mediaId: String, kind: MediaKind, title: String) {
        val page = ScreenDetail(api, serverUrl, mediaId, kind, title, onChange = ::detailChanged, onPlay = ::playFromPage, onPerson = ::openPerson,
            tiles = home, onTitle = { card -> if (visible && !player) openTitle(card.id, card.kind, card.title) })
        entries.add(ScreenRouteEntry("detail", "shell-page-target", title = title, mediaId = mediaId, mediaKind = kind, detail = page))
        render()
    }

    private fun activate() {
        val current = entries.last()
        val element = document.getElementById(current.focusId) as? HTMLElement ?: return
        // DEB-4660 (FR-7) — on a title's page OK is the page's own: its actions, a season pill, an episode.
        if (current.detail != null && !current.focusId.startsWith("nav-")) { current.detail.activate(element); return }
        if (element.getAttribute("data-action") == "retry") { retry(current); return }
        val channelId = element.getAttribute("data-channel-id")
        val mediaId = element.getAttribute("data-media-id")
        if (channelId != null) {
            entries.add(ScreenRouteEntry("channel", "shell-page-target", channelId = channelId,
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
        entries.add(ScreenRouteEntry(destination, if (search != null) ScreenSearch.FIELD_ID else "shell-page-target",
            mutableMapOf("bar" to focused), browse = browse, search = search))
        render()
    }

    private fun back() {
        val current = entries.last()
        // DEB-4662 (FR-8) — Back from the results returns to the field; the typed text is still in it.
        if (current.search != null && current.focusId.startsWith(ScreenSearch.TILE_PREFIX)) {
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
        /** DEB-5648 (FR-50) — how long the reads for a series tile's target may take before nothing is warmed. */
        const val PREWARM_READ_MS = 5_000L
    }
}
