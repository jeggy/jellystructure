package dev.jellystructure.ravilo.screen

import dev.jellystructure.shared.tv.Hero
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/**
 * DEB-5487 FR-38: the hero is Android TV's `HeroCarousel` — one focus target holding every slide stacked, a timer that
 * advances it, a crossfade, a drift and the page dots. Left and Right change the slide (they do not move focus), OK opens the
 * one showing, and there is no ring.
 *
 * The slide that shows is route state (the caller keeps it), so a redraw after Back lands on the same slide and starts a full
 * interval. The timer counts only while [sync] says the hero is on screen and the app is in front; it is the router that knows
 * about the page over Home and the app being hidden, the hero that knows whether it has been scrolled out of view.
 */
internal class ScreenHero(private val absoluteUrl: (String) -> String) {
    private var root: HTMLElement? = null
    private var heroes = emptyList<Hero>()
    private var slides = emptyList<HTMLElement>()
    private var dots = emptyList<HTMLElement>()
    private var current = 0
    private var intervalMs = 0
    private var onSlide: (Int) -> Unit = {}
    private var timer: Int? = null
    private var trimTimer: Int? = null
    private var running = false
    private var request = 0

    val slideCount get() = slides.size

    /** Draws the target and its slides; [begin] shows the slide once the target is in the page. */
    fun build(heroes: List<Hero>, slide: Int, heightPct: Int, autoAdvanceSeconds: Int, onSlide: (Int) -> Unit): HTMLElement {
        cancel()
        this.heroes = heroes
        this.onSlide = onSlide
        intervalMs = autoAdvanceSeconds.coerceAtLeast(0) * 1000
        current = slide.coerceIn(0, heroes.size - 1)
        running = false
        val target = node("div", "shell-target home-hero")
        target.id = ID
        target.setAttribute("data-row", "heroes")
        target.setAttribute("tabindex", "-1")
        target.style.height = "${heightPct.coerceIn(40, 100)}vh"
        slides = heroes.map { hero ->
            val slide = node("div", "hero-slide")
            slide.appendChild(node("div", "hero-art"))
            val copy = node("div", "hero-copy")
            hero.taglineKicker?.takeIf { it.isNotBlank() }?.let { copy.appendChild(label("div", "hero-kicker", it)) }
            if (hero.logoUrl != null) copy.appendChild(node("div", "hero-logo-slot"))
            else copy.appendChild(label("div", "hero-title", hero.item.title))
            hero.badge?.takeIf { it.isNotBlank() }?.let { copy.appendChild(label("div", "hero-badge", it)) }
            hero.synopsis?.takeIf { it.isNotBlank() }?.let { copy.appendChild(label("div", "hero-synopsis", it)) }
            slide.appendChild(copy)
            target.appendChild(slide)
            slide
        }
        dots = if (heroes.size >= 2) {
            val bar = node("div", "hero-dots")
            val made = heroes.map { node("div", "hero-dot").also(bar::appendChild) }
            target.appendChild(bar)
            made
        } else emptyList()
        applyTarget(target)
        root = target
        return target
    }

    /**
     * The target is in the page: the slide shows at once, without a fade, and its drift starts from 1.0. The reflow between is
     * what lets a transition run from the state the page already drew.
     */
    fun begin() {
        val target = root ?: return
        load(current)
        target.getBoundingClientRect()
        val slide = slides[current]
        slide.classList.add("instant")
        slide.classList.add("on")
        dots.getOrNull(current)?.classList?.add("on")
        target.getBoundingClientRect()
        slide.classList.remove("instant")
    }

    /** Left (-1) or Right (+1): the next slide, wrapping, and a full interval from now. */
    fun step(direction: Int) {
        if (slides.size < 2) return
        show((current + direction + slides.size) % slides.size)
        restart()
    }

    /**
     * The hero counts while [active] (the shell is the page showing, the app is in front) and the hero is on screen. Called
     * after every redraw and every move of the page; a hero that was out of view and is back starts a full interval.
     */
    fun sync(active: Boolean) {
        val target = root
        val shown = target != null && document.getElementById(ID) === target && active && onScreen(target)
        target?.classList?.toggle("drift-off", !shown)
        running = shown && slides.size >= 2 && intervalMs > 0
        if (!running) cancel() else if (timer == null) schedule()
    }

    private fun onScreen(target: HTMLElement): Boolean {
        val shell = document.getElementById("shell") as? HTMLElement ?: return false
        val bodyTop = (document.getElementById("shell-body") as? HTMLElement)?.getBoundingClientRect()?.top ?: 0.0
        return ScreenLane.restBox(target, shell).bottom > bodyTop
    }

    private fun schedule() {
        timer = window.setTimeout({
            timer = null
            step(1)
        }, intervalMs)
    }

    private fun cancel() {
        timer?.let { window.clearTimeout(it) }
        timer = null
    }

    private fun restart() {
        cancel()
        if (running) schedule()
    }

    /** The slide fades in only once its backdrop has loaded, so it never fades in empty. */
    private fun show(next: Int) {
        if (next == current) return
        val mine = ++request
        load(next)
        val image = slides[next].querySelector(".hero-art img") as? HTMLElement
        val reveal = { if (mine == request) apply(next) }
        if (image == null || image.asDynamic().complete == true) reveal()
        else {
            image.addEventListener("load", { reveal() })
            image.addEventListener("error", { reveal() })
        }
    }

    private fun apply(next: Int) {
        slides[current].classList.remove("on")
        dots.getOrNull(current)?.classList?.remove("on")
        current = next
        slides[next].classList.add("on")
        dots.getOrNull(next)?.classList?.add("on")
        root?.let(::applyTarget)
        load(next)
        onSlide(next)
        trimTimer?.let { window.clearTimeout(it) }
        trimTimer = window.setTimeout({ trimTimer = null; trim() }, FADE_OUT_MS + 50)
    }

    /** OK opens the slide that is showing. */
    private fun applyTarget(target: HTMLElement) {
        val hero = heroes[current]
        target.setAttribute("data-media-id", hero.item.id)
        target.setAttribute("data-media-kind", hero.item.kind.name.lowercase())
        target.setAttribute("data-media-title", hero.item.title)
    }

    /** R88: the slide showing and the next two. */
    private fun load(index: Int) {
        for (offset in 0..2) {
            val at = (index + offset) % slides.size
            val slide = slides[at]
            val art = slide.firstElementChild as HTMLElement
            if (art.firstChild == null) {
                val img = image(heroes[at].backdropUrl)
                if (img != null) art.appendChild(ScreenSkeleton.fadeOnLoad(img))
            }
            val slot = slide.querySelector(".hero-logo-slot") as? HTMLElement
            if (slot != null && slot.firstChild == null) {
                heroes[at].logoUrl?.let(::image)?.let {
                    it.className = "hero-logo"
                    slot.appendChild(ScreenSkeleton.fadeOnLoad(it))
                }
            }
        }
    }

    /** Everything beyond the window lets its images go, once a slide fading out has finished fading. */
    private fun trim() {
        val keep = (0..2).map { (current + it) % slides.size }.toSet()
        slides.forEachIndexed { index, slide ->
            if (index in keep || slide.classList.contains("on")) return@forEachIndexed
            (slide.firstElementChild as? HTMLElement)?.textContent = ""
            (slide.querySelector(".hero-logo-slot") as? HTMLElement)?.textContent = ""
        }
    }

    private fun image(url: String): HTMLElement? {
        if (url.isBlank()) return null
        val img = node("img", "")
        img.setAttribute("alt", "")
        img.setAttribute("src", absoluteUrl(url))
        return img
    }

    private fun node(tag: String, className: String): HTMLElement =
        (document.createElement(tag) as HTMLElement).also { it.className = className }

    private fun label(tag: String, className: String, value: String): HTMLElement =
        node(tag, className).also { it.textContent = value }

    companion object {
        const val ID = "home-hero"
        private const val FADE_OUT_MS = 400
    }
}
