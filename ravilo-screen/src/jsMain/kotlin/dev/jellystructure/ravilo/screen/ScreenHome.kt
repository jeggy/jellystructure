package dev.jellystructure.ravilo.screen

import dev.jellystructure.ravilo.i18n.t
import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.RowKind
import dev.jellystructure.shared.tv.TileShape
import dev.jellystructure.shared.tv.continueDateBadge
import dev.jellystructure.shared.tv.episodeCode
import dev.jellystructure.shared.tv.offersSeeAll
import dev.jellystructure.shared.tv.rowHeadingCount
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/** DEB-4658 FR-5/FR-14: the server owns every Home section and its order. */
internal class ScreenHome(private val serverUrl: String, private val showProgress: () -> Boolean = { true }) {
    private var body: HTMLElement? = null
    private val hero = ScreenHero(::absoluteUrl)

    fun render(feed: HomeFeed, mount: HTMLElement, language: String, slide: Int = 0, onSlide: (Int) -> Unit = {}) {
        body = mount
        mount.textContent = ""
        val home = node("div", "home-feed lane-page")
        if (feed.heroes.isNotEmpty()) {
            home.appendChild(hero.build(feed.heroes, slide, feed.heroHeightPct, feed.autoAdvanceSeconds, onSlide))
        }
        if (feed.channels.isNotEmpty()) {
            val section = node("section", "home-section")
            section.appendChild(label("h2", "home-heading", t("section.channels", language)))
            val rail = node("div", "home-rail")
            val track = node("div", "rail-track")
            rail.appendChild(track)
            feed.channels.forEachIndexed { index, channel ->
                val target = target(channelId(index), "channels", "channel")
                target.setAttribute("data-channel-id", channel.id)
                target.setAttribute("aria-label", channel.name)
                channel.brandColor?.takeIf { it.startsWith("#") || it.startsWith("linear-gradient(") }
                    ?.let { target.style.background = it }
                channel.logoUrl?.let(::image)?.let { target.appendChild(it) }
                target.appendChild(label("span", "channel-name", channel.name))
                track.appendChild(target)
            }
            section.appendChild(rail)
            home.appendChild(section)
        }
        feed.rows.forEachIndexed { rowIndex, row ->
            val section = node("section", "home-section home-content-row")
            val heading = label("h2", "home-heading", row.title)
            // DEB-5640 FR-52: Android TV's row count, only above 1; a grid's heading has none.
            rowHeadingCount(row.seedTotalCount, row.items.size)?.let { heading.appendChild(label("span", "home-heading-count", "· $it")) }
            section.appendChild(heading)
            val rail = node("div", "home-rail")
            val track = node("div", "rail-track")
            rail.appendChild(track)
            val shape = if (row.kind == RowKind.CONTINUE) TileShape.LANDSCAPE else feed.tileShape
            row.items.forEachIndexed { tileIndex, card ->
                val tile = tile(card, tileId(rowIndex, tileIndex), "row-$rowIndex", shape,
                    row.kind == RowKind.CONTINUE && showProgress(), row.kind)
                // DEB-5648 (FR-50) — the router warms what a Continue Watching tile's page would play.
                if (row.kind == RowKind.CONTINUE) tile.setAttribute("data-row-kind", CONTINUE_ROW)
                track.appendChild(tile)
            }
            if (row.offersSeeAll()) track.appendChild(seeAllTile(rowIndex, shape))
            section.appendChild(rail)
            home.appendChild(section)
        }
        mount.appendChild(home)
        if (feed.heroes.isNotEmpty()) hero.begin()
    }

    /** FR-38: the hero counts while [active] and on screen. */
    fun syncHero(active: Boolean) = hero.sync(active)

    /** FR-38: Left and Right on the hero change its slide, they do not move focus. */
    fun stepHero(direction: Int): Boolean {
        if (hero.slideCount == 0) return false
        hero.step(direction)
        return true
    }

    /**
     * DEB-5487 FR-42: every tile on screen has its image, whatever the cap. The 60 slots go to the on-screen tiles first;
     * slots left over go to off-screen tiles within one screen of focus, nearest first. An image goes only once its tile
     * has left the screen.
     */
    fun updateImages(focusId: String) {
        val mount = body ?: return
        val shell = document.getElementById("shell") as? HTMLElement ?: return
        val focus = document.getElementById(focusId) as? HTMLElement ?: return
        val focusBox = ScreenLane.restBox(focus, shell)
        val screenHeight = mount.clientHeight.toDouble().coerceAtLeast(1.0)
        val viewWidth = window.innerWidth.toDouble()
        val viewHeight = window.innerHeight.toDouble()
        fun showing(box: ScreenLane.Box) = box.width > 0 && box.height > 0 &&
            box.right > 0 && box.left < viewWidth && box.bottom > 0 && box.top < viewHeight
        val holders = mount.getElementsByClassName("tile-art")
        val onScreen = mutableListOf<Pair<HTMLElement, Double>>()
        val nearby = mutableListOf<Pair<HTMLElement, Double>>()
        for (index in 0 until holders.length) {
            val holder = holders.item(index) as? HTMLElement ?: continue
            if (holder.getAttribute("data-art-url") == null) continue
            // Where it rests after the move, or where it is drawn now: a tile still sliding out keeps its image until it is gone.
            val rest = ScreenLane.restBox(holder, shell)
            val dx = rest.centerX - focusBox.centerX
            val dy = rest.centerY - focusBox.centerY
            val distance = dx * dx + dy * dy
            val near = rest.top <= focusBox.bottom + screenHeight && rest.bottom >= focusBox.top - screenHeight
            if (showing(rest) || showing(ScreenLane.screenBox(holder))) onScreen.add(holder to distance)
            else if (near) nearby.add(holder to distance)
        }
        val selected = mutableSetOf<HTMLElement>()
        for ((holder, _) in onScreen.sortedBy { it.second }.take(IMAGE_CAP)) selected.add(holder)
        for ((holder, _) in nearby.sortedBy { it.second }.take(IMAGE_CAP - selected.size)) selected.add(holder)
        for (index in 0 until holders.length) {
            val holder = holders.item(index) as? HTMLElement ?: continue
            if (holder in selected) {
                if (holder.firstChild == null) holder.getAttribute("data-art-url")?.let(::image)
                    ?.let { holder.appendChild(ScreenSkeleton.fadeOnLoad(it)) }
            } else if (holder.firstChild != null) {
                holder.textContent = ""
            }
        }
    }

    /**
     * Shared by Home rows, FR-6's browse grid and FR-51's row. DEB-5640 FR-52: the badges are Android TV's, from
     * `resolveCornerBadges` with `isNew` false. [rowKind] is the row's kind, null outside Home (a grid, a search).
     */
    fun tile(card: MediaCard, id: String, row: String, shape: TileShape, showProgress: Boolean = false, rowKind: RowKind? = null): HTMLElement {
        val language = ScreenLanguage.lang
        val isContinue = rowKind == RowKind.CONTINUE
        val target = target(id, row, "tile")
        target.classList.add("tile-${shape.name.lowercase()}")
        target.setAttribute("data-media-id", card.id)
        target.setAttribute("data-media-kind", card.kind.name.lowercase())
        val frame = node("div", "tile-frame")
        val art = node("div", "tile-art")
        val artUrl = if (shape == TileShape.LANDSCAPE) card.backdropUrl ?: card.posterUrl
            else card.posterUrl ?: card.backdropUrl
        artUrl?.let { art.setAttribute("data-art-url", absoluteUrl(it)) }
        frame.appendChild(art)
        // The badges sit beside the art, not in it: FR-42's image slots empty the art holder when a tile leaves the screen.
        val code = if (isContinue && card.seasonNumber != null && card.episodeNumber != null)
            episodeCode(card.seasonNumber!!, card.episodeNumber!!, card.episodeNumberEnd) else null
        val dateBadge = if (isContinue) continueDateBadge(card.episodeAirDate, card.progressPct ?: 0f) { k, v -> t(k, language, v) } else null
        val episodeBadge = dateBadge ?: code
        val upcoming = card.upcomingEpisode?.let { t("tile.badge_soon", language, mapOf("label" to it)) }
        // resolveCornerBadges: top-left is the episode, else the upcoming; top-right the ✓, else the upcoming the top-left did not take.
        val topStart = when {
            episodeBadge != null -> badge("badge-episode", episodeBadge)
            upcoming != null -> badge("badge-upcoming", upcoming)
            else -> null
        }
        val topEnd = when {
            card.watched -> badge("badge-watched", "✓")
            upcoming != null && episodeBadge != null -> badge("badge-upcoming", upcoming)
            else -> null
        }
        topStart?.let { it.classList.add("badge-start"); frame.appendChild(it) }
        topEnd?.let { it.classList.add("badge-end"); frame.appendChild(it) }
        card.qualityBadge?.takeIf { it.isNotBlank() }?.let { frame.appendChild(badge("badge-quality", it)) }
        target.appendChild(frame)
        if (showProgress) card.progressPct?.let { progress ->
            val track = node("div", "tile-progress")
            val fill = node("div", "tile-progress-fill")
            fill.style.width = "${progress.coerceIn(0f, 1f) * 100f}%"
            track.appendChild(fill)
            target.appendChild(track)
        }
        target.appendChild(label("div", "tile-title", card.title))
        if (!isContinue) card.nextUpLabel?.takeIf { it.isNotBlank() }?.let { target.appendChild(label("div", "tile-sub", it)) }
        return target
    }

    /**
     * DEB-5690 (DEB-5685, FR-55) — Android TV's `SeeAllTile`: the row's last tile at the row's size, the arrow and *See all*. It is a tile
     * for focus and the grow, but it has no `tile-art` holder (FR-42's slots are for pictures) and no title line to carry.
     */
    private fun seeAllTile(rowIndex: Int, shape: TileShape): HTMLElement {
        val target = target("home-row-$rowIndex-see-all", "row-$rowIndex", "tile")
        target.classList.add("tile-${shape.name.lowercase()}")
        target.setAttribute("data-see-all", rowIndex.toString())
        val face = node("div", "tile-see-all")
        face.appendChild(label("div", "see-all-arrow", "→"))
        face.appendChild(label("div", "see-all-label", ScreenLanguage.t("browse.see_all_short")))
        target.appendChild(face)
        return target
    }

    private fun badge(className: String, text: String): HTMLElement = label("div", "tile-badge $className", text)

    private fun image(url: String): HTMLElement? {
        if (url.isBlank()) return null
        val img = node("img", "")
        img.setAttribute("src", absoluteUrl(url))
        img.setAttribute("alt", "")
        return img
    }

    private fun absoluteUrl(url: String): String = if (url.startsWith("/")) serverUrl.trimEnd('/') + url else url

    private fun node(tag: String, className: String): HTMLElement =
        (document.createElement(tag) as HTMLElement).also { it.className = className }

    private fun label(tag: String, className: String, value: String): HTMLElement =
        node(tag, className).also { it.textContent = value }

    private fun target(id: String, row: String, role: String): HTMLElement =
        node("div", "shell-target home-$role").also {
            it.id = id
            it.setAttribute("data-row", row)
            it.setAttribute("tabindex", "-1")
        }

    companion object {
        const val HERO_ID = ScreenHero.ID
        const val CONTINUE_ROW = "continue"
        /** The ids Home's targets carry; DEB-5695's redraw (ScreenFeedKeep) finds a tile by them. */
        fun tileId(row: Int, tile: Int) = "home-row-$row-tile-$tile"
        fun channelId(index: Int) = "home-channel-$index"
        private const val IMAGE_CAP = 60
    }
}
