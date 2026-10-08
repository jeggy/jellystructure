package dev.jellystructure.ravilo.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.RaviloPlayer
import dev.jellystructure.ravilo.ui.theme.Sora
import kotlinx.coroutines.delay

/**
 * R376 (2026-10-08) — while a browser plays the picture muted because the play did not come from a real click, tap or
 * key ([RaviloPlayer.soundBlockedByBrowser], Safari's autoplay rule), one quiet pill at the top of the player says how
 * to get the sound: any real click, tap or key unmutes it, and the pill goes. Nothing on platforms without the rule.
 * Polled on its own so the player screen gains one call and no state.
 */
@Composable
internal fun BrowserSoundHint(player: RaviloPlayer) {
    var blocked by remember(player) { mutableStateOf(false) }
    LaunchedEffect(player) {
        while (true) {
            blocked = player.soundBlockedByBrowser
            delay(400)
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(visible = blocked, enter = fadeIn(tween(160)), exit = fadeOut(tween(220))) {
            Text(
                str("player.sound_blocked"),
                color = Color.White, fontSize = 15.sp, fontFamily = Sora, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 28.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }
    }
}
