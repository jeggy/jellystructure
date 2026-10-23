package dev.jellystructure.ravilo.ui.desktop

import dev.jellystructure.ravilo.i18n.LastLanguage
import dev.jellystructure.ravilo.i18n.t
import dev.jellystructure.ravilo.ui.desktop.MacNowPlaying.Command
import dev.jellystructure.ravilo.ui.desktop.MacNowPlaying.Mode
import java.awt.GraphicsEnvironment
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.Taskbar
import javax.swing.SwingUtilities

/**
 * DEB-2249 — playback items at the top of the Mac's Dock menu, a third way to send the commands the media keys send.
 * [MacNowPlaying] drives it: `claim` sets the item set from the mode, `update` flips Play/Pause, `release` empties it.
 * The menu goes in through AWT's `Taskbar.setMenu`, which the JDK's own application delegate returns from
 * `applicationDockMenu:` — `NSApp.delegate` is AWT's and is not touched. macOS draws its own items (and the separator)
 * below ours. Off the Mac, or where `Taskbar.Feature.MENU` is unsupported, nothing is installed (FR-7).
 */
internal object MacDockMenu {
    /** One row of the menu: the i18n key of its label, and what a click asks of the owner. */
    data class Item(val key: String, val command: Command, val seconds: Double = 0.0)

    /** FR-2 / FR-3 — the items for a kind of owner, top to bottom; none when nothing owns the card. */
    fun itemsFor(mode: Mode?, playing: Boolean): List<Item> {
        if (mode == null) return emptyList()
        val toggle = Item(if (playing) "mac.dock.pause" else "mac.dock.play", Command.TOGGLE)
        return when (mode) {
            Mode.MUSIC -> listOf(toggle, Item("mac.dock.next", Command.NEXT), Item("mac.dock.previous", Command.PREVIOUS))
            Mode.BOOK -> listOf(toggle, Item("mac.dock.forward_30", Command.SKIP_FORWARD, 30.0), Item("mac.dock.back_30", Command.SKIP_BACK, 30.0))
            Mode.FILM -> listOf(toggle)
        }
    }

    private val lock = Any()
    private var mode: Mode? = null
    private var playing = false
    private var shown: List<Pair<String, Item>> = emptyList()
    @Volatile private var shownLang: String? = null
    private var installed = false
    private var supported: Boolean? = null

    /** From [MacNowPlaying.claim]: the item set follows [mode]; the label waits for the owner's first update. */
    fun claim(mode: Mode) { synchronized(lock) { this.mode = mode }; refresh() }

    /** From [MacNowPlaying.update] by the owner, which runs on every position tick: the label follows [playing]. */
    fun playing(playing: Boolean) {
        val changed = synchronized(lock) { (this.playing != playing).also { this.playing = playing } }
        // The language can change while nothing else does, so a tick that changes nothing still looks at it.
        if (changed || LastLanguage.read() != shownLang) refresh()
    }

    /** From [MacNowPlaying.release] by the owner: the menu is today's again. */
    fun clear() { synchronized(lock) { mode = null; playing = false }; refresh() }

    private fun refresh() {
        if (!DesktopPaths.isMac || MacNative.lib == null) return
        SwingUtilities.invokeLater { apply() }
    }

    /** On the Swing thread: rebuild the menu only when its labels or items differ from what is installed. */
    private fun apply() {
        val lang = LastLanguage.read() ?: "en"
        shownLang = LastLanguage.read()
        val wanted = synchronized(lock) { itemsFor(mode, playing) }.map { t(it.key, lang) to it }
        if (wanted == shown) return
        if (!supportsMenu()) return
        runCatching {
            val taskbar = Taskbar.getTaskbar()
            if (wanted.isEmpty()) {
                if (installed) taskbar.menu = null
                installed = false
            } else {
                val popup = PopupMenu()
                for ((label, item) in wanted) popup.add(MenuItem(label).also { m -> m.addActionListener { send(item) } })
                taskbar.menu = popup
                installed = true
            }
            shown = wanted
        }.onFailure { println("MacDockMenu: could not set the Dock menu: $it") }
    }

    private fun supportsMenu(): Boolean = supported ?: runCatching {
        !GraphicsEnvironment.isHeadless() && Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.MENU)
    }.getOrDefault(false).also {
        supported = it
        if (!it) println("MacDockMenu: Taskbar.Feature.MENU is not supported here, so the Dock menu is left as macOS makes it")
    }

    /** FR-4 — to whoever owns the card now, by the path the media keys take. */
    private fun send(item: Item) = MacNowPlaying.dispatch(item.command, item.seconds)

    /** FR-8 — the items as they are now (what is installed on a Mac), one `n. label` per item, or `none`. */
    fun describe(): String {
        val lang = LastLanguage.read() ?: "en"
        val items = synchronized(lock) { itemsFor(mode, playing) }
        val list = if (items.isEmpty()) "none" else items.mapIndexed { i, it -> "${i + 1}. ${t(it.key, lang)} [enabled]" }.joinToString(" · ")
        return "$list (installed=$installed)"
    }

    /** FR-8 — click item [n] (1-based) as the Dock would; returns false when there is no such item. */
    fun click(n: Int): Boolean {
        val items = synchronized(lock) { itemsFor(mode, playing) }
        val item = items.getOrNull(n - 1) ?: return false
        send(item)
        return true
    }

}
