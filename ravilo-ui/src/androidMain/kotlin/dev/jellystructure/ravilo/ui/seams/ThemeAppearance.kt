package dev.jellystructure.ravilo.ui.seams

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat

/** R338 — the night bits of the configuration. Both activities list `uiMode` in `configChanges`, so a flip
 *  recomposes in place: the activity is not recreated and nothing that plays stops. */
@Composable
actual fun systemDarkAppearance(): Boolean? = isSystemInDarkTheme()

/** R338 — dark bar icons on a light theme, light ones on a dark theme, whatever the system is in. */
@Composable
actual fun SystemBarsAppearance(light: Boolean, background: Color) {
    val view = LocalView.current
    SideEffect {
        var ctx = view.context
        while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
        val window = (ctx as? Activity)?.window ?: return@SideEffect
        WindowInsetsControllerCompat(window, view).run {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
}
