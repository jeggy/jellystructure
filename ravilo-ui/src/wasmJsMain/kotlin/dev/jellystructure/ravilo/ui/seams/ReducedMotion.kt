@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui.seams

/** The standard CSS media feature — respects the OS-level "reduce motion" setting on every browser
 *  that implements it (all evergreen browsers); `matches` is `false`, i.e. motion allowed, wherever
 *  the browser has no opinion (no user preference set, or an old engine with no support at all). */
actual fun systemPrefersReducedMotion(): Boolean =
    js("window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches")
