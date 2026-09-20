package dev.jellystructure.ravilo.ui.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import dev.jellystructure.ravilo.ui.theme.LocalHandset
import dev.jellystructure.ravilo.ui.isTvPlatform

/**
 * R55 — Back/Escape scrolls a scrolled content page to the top before leaving it.
 *
 * When the page is **not** at the top ([atTop] returns false), a Back press runs [onBackToTop] — which
 * should scroll the page to the top **and move focus to a top target** (an app-bar entry, the hero, the
 * search keyboard, or the first item) so Compose's bring-into-view doesn't immediately pull the scroll
 * back down to the still-focused lower item — and the event is **consumed**. When the page is already at
 * the top, the event is **not** consumed, so it bubbles up to `RaviloApp`'s handler and does the normal
 * thing (pop to the previous page, or exit at the root). This makes it noticeably harder to close the
 * app by accident.
 *
 * Key events bubble child → ancestor, so a screen applying this on its root runs **before** `RaviloApp`'s
 * Box; consuming here cleanly suppresses the pop. Media-item detail pages deliberately do **not** apply
 * it — Back there pops straight back.
 *
 * R262 (FR-R262-6) — scroll-to-top-on-Back is a TV thing, not a page thing: off the TV a phone's
 * gesture/button Back already pops directly through `PlatformBackHandler`, which this key-event modifier
 * never sees, but a keyboard Escape/Backspace on the web hit this same handler and scrolled to the top
 * instead of leaving. Gated once, here, so none of this modifier's call sites need their own check.
 *
 * ## R275 — the phone gets the same rule through the other door
 *
 * A phone's system Back never becomes a Compose `KeyEvent` at all: `OnBackPressedDispatcher` takes the
 * gesture and the button first. So off the TV this modifier is not merely disabled, it is unreachable —
 * nine call sites that look like they handle Back, none of which can. On a **handset** it therefore
 * registers the same two lambdas with [BackToTopRegistry], which `RaviloApp`'s `PlatformBackHandler`
 * consults before it pops (FR-R275-1/-5). One API, two mechanisms, because the platforms genuinely
 * deliver Back differently; a screen still declares where its top is exactly once.
 */
@Composable
fun Modifier.backToTopOnBack(
    atTop: () -> Boolean,
    onBackToTop: () -> Unit,
): Modifier {
    if (isTvPlatform) {
        return this.onKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
            if (ev.key != Key.Back && ev.key != Key.Escape && ev.key != Key.Backspace) return@onKeyEvent false
            if (atTop()) return@onKeyEvent false        // already at top → let RaviloApp pop/exit
            onBackToTop()                               // scroll to top + move focus to a top target
            true                                        // consumed → no pop/exit
        }
    }
    // Off the TV, only where the bottom bar is drawn (R267's own seam) — a desktop browser window keeps
    // browser Back meaning browser Back.
    if (!LocalHandset.current) return this
    val registry = LocalBackToTop.current
    val entry = remember { BackToTopEntry() }
    // Plain assignment each composition, the rememberUpdatedState idiom: the lambdas are only ever read
    // from the Back callback, never during composition, so they need no snapshot state of their own.
    entry.atTop = atTop
    entry.onBackToTop = onBackToTop
    DisposableEffect(registry, entry) {
        registry.register(entry)
        onDispose { registry.release(entry) }
    }
    return this
}

/** One page's declaration of where its top is. Identity is what the registry tracks. */
class BackToTopEntry internal constructor() {
    internal var atTop: () -> Boolean = { true }
    internal var onBackToTop: () -> Unit = {}
}

/**
 * R275 (FR-R275-5) — the phone's route into [backToTopOnBack], held by `RaviloApp` and consulted by its
 * `PlatformBackHandler`.
 *
 * Last-in-wins with an identity check on release: during an `AnimatedContent` transition both the
 * arriving and the leaving screen are composed, the arriving one registers first and the leaving one
 * disposes second, so without the check the page now on screen would silently lose its Back.
 */
class BackToTopRegistry {
    private var current: BackToTopEntry? = null

    fun register(entry: BackToTopEntry) { current = entry }

    fun release(entry: BackToTopEntry) { if (current === entry) current = null }

    /** True when the current page was scrolled and has now been sent to its top — i.e. Back is spent. */
    fun consumeBack(): Boolean {
        val entry = current ?: return false
        if (entry.atTop()) return false
        entry.onBackToTop()
        return true
    }
}

/** Defaults to an empty registry so a preview or a test composing a screen alone still works. */
val LocalBackToTop = compositionLocalOf { BackToTopRegistry() }
