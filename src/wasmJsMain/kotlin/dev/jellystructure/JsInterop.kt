@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.jellystructure

internal fun encodeURIComponent(value: String): String = js("encodeURIComponent(value)")
internal fun decodeURIComponent(value: String): String = js("decodeURIComponent(value)")

internal fun prefersDark(): Boolean = js("window.matchMedia('(prefers-color-scheme: dark)').matches")

// Installs a persistent listener that updates data-theme when the OS preference changes,
// but only when the stored preference is "system".
internal fun installSystemThemeWatcher(): Unit =
    js("window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change',function(e){if(localStorage.getItem('js-theme')==='system'){document.documentElement.setAttribute('data-theme',e.matches?'dark':'light')}})")
