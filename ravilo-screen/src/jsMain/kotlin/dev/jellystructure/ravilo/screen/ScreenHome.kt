package dev.jellystructure.ravilo.screen

import dev.jellystructure.ravilo.i18n.t
import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.RowKind
import dev.jellystructure.shared.tv.TileShape
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/** DEB-4658 FR-5/FR-14: the server owns every Home section and its order. */
internal class ScreenHome(private val serverUrl: String) {
    private var body: HTMLElement? = null

    fun render(feed: HomeFeed, mount: HTMLElement, language: String) {
        body = mount
        mount.textContent = ""
        val home = node("div", "home-feed")
        val heroes = node("div", "home-heroes")
        feed.heroes.forEachIndexed { index, hero ->
            val target = target("home-hero-$index", "heroes", "hero")
            target.setAttribute("data-media-id", hero.item.id)
            target.style.height = "${feed.heroHeightPct.coerceIn(40, 100)}vh"
            image(hero.backdropUrl)?.let { target.appendChild(it) }
            val copy = node("div", "hero-copy")
            hero.taglineKicker?.takeIf { it.isNotBlank() }?.let { copy.appendChild(label("div", "hero-kicker", it)) }
            val logo = hero.logoUrl?.let(::image)
            if (logo != null) {
                logo.className = "hero-logo"
                copy.appendChild(logo)
            } else copy.appendChild(label("div", "hero-title", hero.item.title))
            hero.badge?.takeIf { it.isNotBlank() }?.let { copy.appendChild(label("div", "hero-badge", it)) }
            hero.synopsis?.takeIf { it.isNotBlank() }?.let { copy.appendChild(label("div", "hero-synopsis", it)) }
            target.appendChild(copy)
            heroes.appendChild(target)
        }
        if (feed.heroes.isNotEmpty()) home.appendChild(heroes)
        if (feed.channels.isNotEmpty()) {
            val section = node("section", "home-section")
            section.appendChild(label("h2", "home-heading", t("section.channels", language)))
            val rail = node("div", "home-rail")
            feed.channels.forEachIndexed { index, channel ->
                val target = target("home-channel-$index", "channels", "channel")
                target.setAttribute("data-channel-id", channel.id)
                target.setAttribute("aria-label", channel.name)
                channel.brandColor?.takeIf { it.startsWith("#") || it.startsWith("linear-gradient(") }
                    ?.let { target.style.background = it }
                channel.logoUrl?.let(::image)?.let { target.appendChild(it) }
                target.appendChild(label("span", "channel-name", channel.name))
                rail.appendChild(target)
            }
            section.appendChild(rail)
            home.appendChild(section)
        }
        feed.rows.forEachIndexed { rowIndex, row ->
            val section = node("section", "home-section home-content-row")
            section.appendChild(label("h2", "home-heading", row.title))
            val rail = node("div", "home-rail")
            val shape = if (row.kind == RowKind.CONTINUE) TileShape.LANDSCAPE else feed.tileShape
            row.items.forEachIndexed { tileIndex, card ->
                val target = target("home-row-$rowIndex-tile-$tileIndex", "row-$rowIndex", "tile")
                target.classList.add("tile-${shape.name.lowercase()}")
                target.setAttribute("data-media-id", card.id)
                val art = node("div", "tile-art")
                val artUrl = if (shape == TileShape.LANDSCAPE) card.backdropUrl ?: card.posterUrl
                    else card.posterUrl ?: card.backdropUrl
                artUrl?.let { art.setAttribute("data-art-url", absoluteUrl(it)) }
                target.appendChild(art)
                if (row.kind == RowKind.CONTINUE) card.progressPct?.let { progress ->
                    val track = node("div", "tile-progress")
                    val fill = node("div", "tile-progress-fill")
                    fill.style.width = "${progress.coerceIn(0f, 1f) * 100f}%"
                    track.appendChild(fill)
                    target.appendChild(track)
                }
                target.appendChild(label("div", "tile-title", card.title))
                rail.appendChild(target)
            }
            section.appendChild(rail)
            home.appendChild(section)
        }
        mount.appendChild(home)
    }

    /** Keep artwork at most one screen from focus; the cap also protects a feed with dense rows. */
    fun updateImages(focusId: String) {
        val mount = body ?: return
        val focus = document.getElementById(focusId) as? HTMLElement ?: return
        val focusRect = focus.getBoundingClientRect()
        val screenHeight = mount.clientHeight.toDouble().coerceAtLeast(1.0)
        val holders = mount.getElementsByClassName("tile-art")
        var images = 0
        for (index in 0 until holders.length) {
            val holder = holders.item(index) as? HTMLElement ?: continue
            val section = holder.parentElement?.parentElement?.parentElement as? HTMLElement ?: continue
            val rect = section.getBoundingClientRect()
            val near = rect.top <= focusRect.bottom + screenHeight && rect.bottom >= focusRect.top - screenHeight
            val url = holder.getAttribute("data-art-url")
            if (near && url != null && images < 60) {
                images++
                if (holder.firstChild == null) image(url)?.let { holder.appendChild(it) }
            } else if (holder.firstChild != null) holder.textContent = ""
        }
    }

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
}
