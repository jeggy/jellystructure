package dev.jellystructure.ravilo.ui.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * R298 (FR-R298-1) — whether focus is DRAWN here. True on every TV and on a desktop web window (whose
 * focus follows the mouse hover, R157); false on a handset (R256's `isHandset`), where there is no
 * remote to follow and the TV's ring, scale and glow only made the app look like it was waiting for one.
 * DEB-2347 (FR-7) — a wide web window now follows the keyboard, not the mouse: `LocalFocusVisible` is
 * `KeyboardMode.on` when the window is not handset-sized. Provided once, at the app root. Focus itself
 * — requesters, restorers, key handling — never reads this.
 */
val LocalFocusVisible = staticCompositionLocalOf { true }

/**
 * R298 (FR-R298-2) — a drop-in for `remember { mutableStateOf(false) }` in a component that PAINTS
 * focus: writes go through untouched, reads are false wherever [LocalFocusVisible] is. Never use it for
 * state that drives logic (see ContentRow's `rowFocused`).
 */
@Composable
fun rememberFocusVisual(): MutableState<Boolean> {
    val visible = LocalFocusVisible.current
    val real = remember { mutableStateOf(false) }
    return remember(real, visible) { FocusVisualState(real, visible) }
}

/**
 * DEB-2347 (FR-7) — pure rule for whether focus rings should be drawn on this platform+window.
 * Returns `keyboardOn` on desktop; `keyboardOn` on wide web (not handset); `false` on handset-sized
 * web; `!handset` everywhere else (Android TV and phone, matching today's behaviour).
 */
fun focusVisibleFor(isDesktop: Boolean, isWeb: Boolean, handset: Boolean, keyboardOn: Boolean): Boolean {
    return when {
        isDesktop -> keyboardOn
        isWeb -> !handset && keyboardOn
        else -> !handset
    }
}

private class FocusVisualState(private val real: MutableState<Boolean>, private val visible: Boolean) : MutableState<Boolean> {
    override var value: Boolean
        get() = visible && real.value
        set(v) { real.value = v }
    override fun component1(): Boolean = value
    override fun component2(): (Boolean) -> Unit = { value = it }
}
