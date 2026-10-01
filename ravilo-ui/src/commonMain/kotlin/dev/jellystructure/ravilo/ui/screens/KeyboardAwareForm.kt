package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged

/** R349 — the height (px) the form was given, i.e. what the system keyboard leaves; a change re-runs
 *  [keepInViewWhileFocused]. */
private val LocalFormViewport = compositionLocalOf { 0 }

/**
 * R349 (FR-R349-1) — a sign-in style form that scrolls inside the space it is given. `RaviloApp` already pads every
 * non-playing screen by the system keyboard (`safeAreaPadding()`), so with the keyboard up this box is about half the
 * TV's height; a plain `Column` squeezed its last children to nothing and hid the focused password field. Here every
 * child keeps its height and the column scrolls; while everything fits it looks as before (centred, or top-aligned when
 * [centered] is false). The keyboard is always the system's own (owner, 2026-09-27).
 *
 * Not `BoxWithConstraints`: that composes its content during layout, after the screen's own `LaunchedEffect`s have run,
 * so the `requestFocus()` that puts focus in the first field would find no node. The viewport is read in the layout
 * pass instead (the outer `layout` runs before the inner one, in the same measure), and published for
 * [keepInViewWhileFocused] through `onSizeChanged`.
 */
@Composable
internal fun KeyboardAwareForm(
    modifier: Modifier = Modifier,
    centered: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val viewportPx = remember { IntArray(1) }
    var publishedViewport by remember { mutableIntStateOf(0) }
    CompositionLocalProvider(LocalFormViewport provides publishedViewport) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .onSizeChanged { publishedViewport = it.height }
                .layout { measurable, constraints ->
                    viewportPx[0] = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
                    val p = measurable.measure(constraints)
                    layout(p.width, p.height) { p.place(0, 0) }
                }
                .verticalScroll(rememberScrollState())
                .layout { measurable, constraints ->
                    // At least the viewport tall, so Arrangement.Center still centres a form that fits.
                    val p = measurable.measure(constraints.copy(minHeight = viewportPx[0].coerceAtMost(constraints.maxHeight)))
                    layout(p.width, p.height) { p.place(0, 0) }
                },
            verticalArrangement = if (centered) Arrangement.Center else Arrangement.Top,
            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
            content = content,
        )
    }
}

/**
 * R349 (FR-R349-2) — on a field's group (label + box): while anything inside it has focus, keep the whole group in view,
 * and again each time the form's height changes (the keyboard opening or closing). The text field's own bring-into-view
 * only covers its cursor, which leaves the label above it under the edge.
 */
@Composable
internal fun Modifier.keepInViewWhileFocused(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val viewport = LocalFormViewport.current
    LaunchedEffect(focused, viewport) {
        if (focused) requester.bringIntoView()
    }
    return this
        .bringIntoViewRequester(requester)
        .onFocusChanged { focused = it.hasFocus }
}
