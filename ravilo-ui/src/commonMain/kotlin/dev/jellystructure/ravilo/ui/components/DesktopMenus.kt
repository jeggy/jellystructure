package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.isMacPlatform
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.SystemUiFont

/** R337 (FR-R337-10) — ⌘ on the Mac, Ctrl everywhere else, written the platform's way. */
private fun keys(mac: String, other: String) = if (isMacPlatform) mac else other

/**
 * R337 (FR-R337-10) — GNOME's primary menu ☰, under the sidebar's header bar: the viewer's name, *Films & series*
 * Ctrl+1 · *Music* Ctrl+2, *Settings* Ctrl+, · *Keyboard shortcuts* Ctrl+? · *About Ravilo* · *Sign out*. A click
 * outside it or Esc closes it.
 */
@Composable
fun DesktopPrimaryMenu(
    viewerName: String,
    musicAvailable: Boolean,
    onDismiss: () -> Unit,
    onMode: (music: Boolean) -> Unit,
    onSettings: () -> Unit,
    onShortcuts: () -> Unit,
    onAbout: () -> Unit,
    onSignOut: () -> Unit,
) {
    val colors = RaviloTheme.colors
    Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)) {
        Column(
            Modifier.padding(start = 40.dp, top = 44.dp).width(240.dp)
                .shadow(16.dp, RoundedCornerShape(10.dp))
                .background(colors.surface, RoundedCornerShape(10.dp))
                .border(1.dp, colors.fg.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                .padding(6.dp),
        ) {
            if (viewerName.isNotBlank()) {
                Text(viewerName, color = colors.textDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = SystemUiFont, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
            MenuItem(str("mode.video"), "Ctrl+1") { onMode(false) }
            if (musicAvailable) MenuItem(str("mode.music"), "Ctrl+2") { onMode(true) }
            MenuRule()
            MenuItem(str("pm.settings"), "Ctrl+,", onSettings)
            MenuItem(str("desk.shortcuts"), "Ctrl+?", onShortcuts)
            MenuItem(str("mac.menu_about"), null, onAbout)
            MenuRule()
            MenuItem(str("pm.sign_out"), null, onSignOut)
        }
    }
}

@Composable
private fun MenuItem(label: String, shortcut: String?, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    Row(
        Modifier.fillMaxWidth().height(34.dp).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.text, fontSize = 13.5.sp, fontFamily = SystemUiFont, modifier = Modifier.weight(1f))
        if (shortcut != null) Text(shortcut, color = colors.textDim, fontSize = 12.sp, fontFamily = SystemUiFont)
    }
}

@Composable
private fun MenuRule() {
    Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(RaviloTheme.colors.fg.copy(alpha = 0.08f)))
}

/** R337 (FR-R337-10) — the keyboard shortcuts window (Ctrl+? on GNOME; the Mac lists the same from Help). */
@Composable
fun DesktopShortcutsOverlay(onDismiss: () -> Unit) {
    val colors = RaviloTheme.colors
    val rows = listOf(
        str("mode.video") to keys("⌘1", "Ctrl+1"),
        str("mode.music") to keys("⌘2", "Ctrl+2"),
        str("nav.search") to keys("⌘F", "Ctrl+F"),
        str("desk.key_play") to str("desk.key_space"),
        str("desk.key_next") to keys("⌘→", "Ctrl+→"),
        str("desk.key_prev") to keys("⌘←", "Ctrl+←"),
        str("desk.key_volume") to keys("⌘↑ ⌘↓", "Ctrl+↑ Ctrl+↓"),
        str("desk.show_queue") to keys("⌥⌘U", "Ctrl+U"),
        str("desk.show_lyrics") to keys("⌥⌘L", "Ctrl+L"),
        str("desk.hide_sidebar") to keys("⌃⌘S", "Ctrl+Shift+S"),
        str("desk.fullscreen") to keys("F · ⌃⌘F", "F · F11"),
        str("pm.settings") to keys("⌘,", "Ctrl+,"),
        str("action.back") to "Esc",
    )
    Box(
        Modifier.fillMaxSize().background(colors.overlay).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 460.dp).background(colors.surface, RoundedCornerShape(14.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(str("desk.shortcuts"), color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = SystemUiFont)
            Spacer(Modifier.height(6.dp))
            rows.forEach { (label, k) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = colors.textSecondary, fontSize = 13.5.sp, fontFamily = SystemUiFont, modifier = Modifier.weight(1f))
                    Text(k, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = SystemUiFont,
                        modifier = Modifier.background(colors.fg.copy(alpha = 0.08f), RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 3.dp))
                }
            }
        }
    }
}
