package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.focus.rememberFocusVisual
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** R328 (FR-R328-8) — a newer release this build can be replaced with by hand: its version and where to get it. */
data class AppUpdateOffer(val version: String, val downloadUrl: String)

/**
 * R328 (FR-R328-8) — the Mac's one update line. Only a platform that updates by hand ever sets an offer (the Mac
 * app, from `/api/health`); everywhere else it stays null and the line is absent, never greyed. Nothing downloads
 * or installs by itself.
 */
object AppUpdate {
    private val _offer = MutableStateFlow<AppUpdateOffer?>(null)
    val offer: StateFlow<AppUpdateOffer?> = _offer.asStateFlow()
    fun set(offer: AppUpdateOffer?) { _offer.value = offer }
}

/** R328 (FR-R328-5) — what the operating system asked the app to do from its own menu: *Settings…* (⌘,). */
/** Commands from outside the composition: the Mac's menu bar, the desktop's keys (R337 FR-R337-10). */
enum class AppCommand {
    OPEN_SETTINGS,
    // R337 — the desktop's View menu and keys
    MODE_VIDEO, MODE_MUSIC, SEARCH, TOGGLE_SIDEBAR, TOGGLE_QUEUE, SHOW_LYRICS, SHORTCUTS,
    NEXT_SONG, PREVIOUS_SONG, VOLUME_UP, VOLUME_DOWN, SIGN_OUT,
}

object AppCommands {
    private val _requests = MutableSharedFlow<AppCommand>(extraBufferCapacity = 4)
    val requests: SharedFlow<AppCommand> = _requests.asSharedFlow()
    fun send(command: AppCommand) { _requests.tryEmit(command) }
}

/**
 * *Ravilo {version} for Mac is available* · **Download**, and under it R331's *Open Anyway* sentence, read at the
 * moment it is needed. [focusRequester] and the neighbours keep Settings' explicit D-pad chain intact.
 */
@Composable
fun AppUpdateLine(offer: AppUpdateOffer, focusRequester: FocusRequester, onUp: () -> Unit, onDown: () -> Unit) {
    val colors = RaviloTheme.colors
    val uri = LocalUriHandler.current
    var focused by rememberFocusVisual()
    Column {
        Text(str("mac.update_available", mapOf("version" to offer.version)), color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
                .then(if (focused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(8.dp)) else Modifier)
                .dpadFocusable(
                    focusRequester = focusRequester,
                    onFocused = { focused = true },
                    onBlurred = { focused = false },
                    onUp = onUp,
                    onDown = onDown,
                    onSelect = { runCatching { uri.openUri(offer.downloadUrl) } },
                )
                .padding(horizontal = 24.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(str("mac.download"), color = colors.text, fontSize = 14.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(str("mac.open_anyway"), color = colors.textSecondary, fontSize = 13.sp)
    }
}
