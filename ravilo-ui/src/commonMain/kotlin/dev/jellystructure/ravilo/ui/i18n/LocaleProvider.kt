package dev.jellystructure.ravilo.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf

val LocalLang = compositionLocalOf { "en" }

/** Convenience shorthand so screens can call `str("action.play")` instead of the full `t()`. */
@Composable
fun str(key: String, vars: Map<String, String> = emptyMap()): String {
    val lang = LocalLang.current
    return t(key, lang, vars)
}

@Composable
fun WithLocale(lang: String, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLang provides lang, content = content)
}
