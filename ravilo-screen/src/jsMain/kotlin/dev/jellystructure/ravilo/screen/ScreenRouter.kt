package dev.jellystructure.ravilo.screen

import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.TvApiClient
import dev.jellystructure.shared.tv.TvSegmentMarkers
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
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
)

/**
 * What the title's page already knows about a play — the part of a phone's push the TV would otherwise ask for (DEB-4660, FR-7).
 * [kind] is `movie` or `episode`; an episode carries its S·E line, its series' name and logo, and the episode after it.
 */
internal data class ScreenPlayContext(
    val kind: String,
    val kicker: String? = null,
    val seriesName: String? = null,
    val logoUrl: String? = null,
    val logoInk: String? = null,
    val segments: TvSegmentMarkers? = null,
    val nextId: String? = null,
    val nextTitle: String? = null,
    val nextKicker: String? = null,
    /** DEB-4865 — the counted episodes after [nextId], in order, so the next-up card can start the next one on the TV itself. */
    val following: List<ScreenFollowing> = emptyList(),
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
) {
    private val root get() = document.getElementById("shell") as HTMLElement
    private val body get() = document.getElementById("shell-body") as HTMLElement
    private val entries = mutableListOf(ScreenRouteEntry("home", "shell-page-target"))
    private val home = ScreenHome(serverUrl)
    private val focus = ScreenFocus({ root }, { entries.last() }) {
        home.updateImages(it.id)
        val entry = entries.last()
        if (entry.browse?.nearEnd(it.id) == true) loadBrowse(entry)
    }
    private var visible = false
    private var player = false
    private var loadGeneration = 0

    fun hide() { visible = false; ++loadGeneration; root.classList.remove("on") }

    fun resetHome() {
        entries.clear(); entries.add(ScreenRouteEntry("home", "shell-page-target"))
        ++loadGeneration
        player = false
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

    fun showPlayer() {
        if (!visible) return
        if (!player) entries.add(ScreenRouteEntry("player", ""))
        player = true
        root.classList.remove("on")
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
        render()
    }

    fun handleKey(event: KeyboardEvent): Boolean {
        if (!visible || player) return false
        // DEB-4662 (FR-8) — in the search field the keys that type or move the caret are the field's own: taken here
        // (so a held Backspace is not the three-second Back that reopens server setup) and left un-prevented.
        val inField = entries.last().focusId == ScreenSearch.FIELD_ID && entries.last().search != null
        if (inField && (event.key == "Backspace" || event.key == "Enter" || event.key == "ArrowLeft" || event.key == "ArrowRight")) return true
        when {
            inField && event.key == "ArrowDown" -> {
                (document.getElementById("${ScreenSearch.TILE_PREFIX}0") as? HTMLElement)?.let(focus::focus)
            }
            isBackKey(event) -> back()
            event.key == "Enter" -> activate()
            event.key == "ArrowLeft" -> focus.move(-1, 0)
            event.key == "ArrowRight" -> focus.move(1, 0)
            event.key == "ArrowUp" -> {
                val current = entries.last()
                if (current.browse != null && current.focusId.startsWith("browse-tile-")) {
                    val tab = if (current.name == "mylist") "profile" else current.name
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

    private fun render() {
        val current = entries.last()
        if (current.name == ScreenSettings.ROUTE) {
            ScreenSettings.render(body, serverUrl, onLanguage = { render() }, onChangeServer = onChangeServer)
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
                home.render(feed, body, ScreenLanguage.lang)
                if (current.skeleton) ScreenSkeleton.fadeIn(body.firstElementChild as? HTMLElement)
                current.skeleton = false
            } else if (current.failed) {
                body.appendChild(ScreenSkeleton.failure())
            } else {
                body.appendChild(ScreenSkeleton.home())
                current.skeleton = true
            }
        }
        renderScreenNav(if (current.name == "channel" || current.name == "detail") "home" else current.name, ScreenLanguage.lang)
        focus.refocus()
        if ((current.name == "home" || current.name == "channel") && current.feed == null && !current.failed) loadFeed(current)
        current.detail?.load()
        if (current.browse != null && current.browse.page == 0 && !current.browse.loading && !current.browse.failed) loadBrowse(current)
    }

    private fun loadBrowse(entry: ScreenRouteEntry) {
        val browse = entry.browse ?: return
        if (browse.loading || browse.complete) return
        browse.loading = true
        val generation = loadGeneration
        GlobalScope.launch {
            val result = runCatching { api.browse(kind = browse.kind, page = browse.page + 1, pageSize = 60) }
            browse.loading = false
            if (generation != loadGeneration || !visible || entry !== entries.lastOrNull()) return@launch
            val page = result.getOrNull()
            if (page != null) {
                browse.append(page)
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
            val title = element.querySelector(".tile-title, .hero-title")?.textContent ?: element.textContent ?: ""
            val kind = MediaKind.entries.firstOrNull { it.name.lowercase() == element.getAttribute("data-media-kind") } ?: MediaKind.MOVIE
            val page = ScreenDetail(api, serverUrl, mediaId, kind, title, onChange = ::detailChanged, onPlay = onPlay)
            entries.add(ScreenRouteEntry("detail", "shell-page-target", title = title, mediaId = mediaId, mediaKind = kind, detail = page))
            render()
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
}
