package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * R354 (FR-R354-10b) — true while a platform video layer sits ABOVE the app's own drawing, so nothing Compose draws can
 * be seen over the picture. Only the web has one: its `<video>` element is lifted over the Compose canvas while no
 * Compose-only overlay is open (R157/R169, [RaviloPlayer.setChromeVisible]). Android, the Mac and Linux draw the
 * picture inside (or under) Compose, so this stays false there.
 */
object VideoOverApp {
    var covers: Boolean by mutableStateOf(false)
}

/** One dashboard message as the over-the-video layer draws it; [elapsedMs] of [durationMs] already shown. */
data class ServerMessageCard(
    val id: Long,
    val header: String?,
    val text: String,
    val durationMs: Long,
    val elapsedMs: Long,
)

/** The toast's look, resolved by Compose (theme and layout), so the other layer draws the same card. */
data class ServerMessageCardStyle(
    val phone: Boolean,
    val desk: Boolean,
    val accent: Color,
    val accentEnd: Color,
    val surface: Color,
    val text: Color,
    val textSecondary: Color,
    val textDim: Color,
)

/**
 * R354 (FR-R354-10b) — draws dashboard messages in a layer above the platform's video while [VideoOverApp.covers] is
 * true (the web's DOM, the way R169's transport bar is drawn there). [show] is given the whole current list, newest
 * last, and keeps what it already shows (no restarted animation); [hide] removes everything.
 */
interface ServerMessageOverVideo {
    fun show(cards: List<ServerMessageCard>, style: ServerMessageCardStyle, onDismiss: (Long) -> Unit)
    fun hide()
}

/** The platform's layer above its video, or null where Compose already draws over the picture. */
expect fun platformServerMessageOverVideo(): ServerMessageOverVideo?
