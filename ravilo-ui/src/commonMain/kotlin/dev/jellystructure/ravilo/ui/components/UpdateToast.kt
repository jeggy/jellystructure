package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.reloadForUpdate
import dev.jellystructure.ravilo.ui.seams.rememberUpdateAvailable
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora

/**
 * R263 (FR-R263-5) — "while a new build is waiting, the app shows one toast." A no-op on every
 * platform but the web (`rememberUpdateAvailable` is a constant `false` there); mounted once at the
 * app root, same tier as [ServerMessageHost], so it floats over whatever screen is current.
 */
@Composable
fun UpdateToast() {
    if (!rememberUpdateAvailable()) return
    val colors = RaviloTheme.colors
    Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            str("update.toast"),
            color = colors.onAccent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = Sora,
            modifier = Modifier
                .background(colors.accent, RoundedCornerShape(10.dp))
                .dpadFocusable(onSelect = { reloadForUpdate() })
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
