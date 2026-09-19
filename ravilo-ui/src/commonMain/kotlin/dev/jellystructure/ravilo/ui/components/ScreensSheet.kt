package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.screens.HandsetSheet
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.shared.tv.DeviceKind
import dev.jellystructure.shared.tv.RemoteDevice
import kotlin.time.Clock

/** R265 (FR-R265-6) — what to start on the screen the sheet's user picks; null when the sheet is opened
 *  with nothing queued (join-only — the app-bar glyph's case, FR-R265-7's "reconnect is a list" path). */
data class ScreenPlayContext(val itemId: String, val startPositionMs: Long? = null)

/**
 * R265 — the three-tier "Play on a TV" sheet (FR-R265-1..5), reusing [HandsetSheet]'s chrome. Tier 1 is
 * absent when empty, never an empty box or a "searching…" state (FR-R265-2); tier 2 is collapsed by
 * default (FR-R265-3); tier 3 is R270's footnote-link AirPlay row, shown only where [airplayAvailable] —
 * true and its actual wiring (FR-R265-8) is a deliberate follow-up, not built yet (see the R265 spec's
 * status: the wasmJs seam needs a live Jellyfin HLS-subtitle-delivery probe first).
 */
@Composable
fun ScreensSheet(
    cast: CastController,
    open: Boolean,
    onClose: () -> Unit,
    playContext: ScreenPlayContext?,
    airplayAvailable: Boolean = false,
    onAirplay: () -> Unit = {},
) {
    var devices by remember { mutableStateOf<List<RemoteDevice>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var tier2Open by remember { mutableStateOf(false) }
    var addTvOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        addTvOpen = false
        loaded = false
        devices = cast.screenDevices()
        loaded = true
    }
    fun tapDevice(d: RemoteDevice) {
        onClose()
        val ctx = playContext
        if (ctx != null) cast.castOnScreen(d, ctx.itemId, ctx.startPositionMs) else cast.joinScreen(d)
    }
    HandsetSheet(visible = open, onDismiss = onClose) {
        if (addTvOpen) {
            AddTvSheetBody(cast = cast, onBack = { addTvOpen = false }, onPaired = { addTvOpen = false })
        } else {
            ScreensSheetBody(
                devices = devices, loaded = loaded, tier2Open = tier2Open, onToggleTier2 = { tier2Open = !tier2Open },
                onTapDevice = ::tapDevice, onAddTv = { addTvOpen = true },
                airplayAvailable = airplayAvailable, onAirplay = { onClose(); onAirplay() },
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun ScreensSheetBody(
    devices: List<RemoteDevice>, loaded: Boolean, tier2Open: Boolean, onToggleTier2: () -> Unit,
    onTapDevice: (RemoteDevice) -> Unit, onAddTv: () -> Unit,
    airplayAvailable: Boolean, onAirplay: () -> Unit, onClose: () -> Unit,
) {
    val colors = RaviloTheme.colors
    val screens = devices.filter { it.kind == DeviceKind.SCREEN || it.kind == DeviceKind.CAST }
    val near = screens.filter { it.nearby }
    val rest = screens.filter { !it.nearby }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        SheetHeader(str("screens.title"), onClose)
        if (!loaded) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.textDim, strokeWidth = 2.5.dp, modifier = Modifier.height(28.dp).width(28.dp))
            }
        } else {
            // FR-R265-2 — absent when empty, never an empty box.
            if (near.isNotEmpty()) {
                SectionLabel(str("screens.nearby"))
                near.forEach { DeviceRow(it, onClick = { onTapDevice(it) }) }
            }
            if (rest.isNotEmpty()) {
                CollapsibleRow(str("screens.all"), rest.size, tier2Open, onToggleTier2)
                if (tier2Open) rest.forEach { DeviceRow(it, onClick = { onTapDevice(it) }) }
            }
            if (near.isEmpty() && rest.isEmpty() && !airplayAvailable) {
                Text(str("screens.add"), color = colors.textDim, fontSize = 13.sp, fontFamily = Sora, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
        // Tier 3 — R270's footnote form: one quiet line, the caveat inside the label itself, one step
        // below the TV rows, never a full row with its own second line (supersedes FR-R265-4's original shape).
        if (airplayAvailable) {
            SimpleRow(icon = { AirplayGlyph(colors.textSecondary) }, label = str("screens.airplay_notice"), onClick = onAirplay, labelColor = colors.textSecondary)
        }
        SimpleRow(icon = { PlusGlyph(colors.text) }, label = str("screens.add"), onClick = onAddTv)
    }
}

@Composable
private fun AddTvSheetBody(cast: CastController, onBack: () -> Unit, onPaired: () -> Unit) {
    val colors = RaviloTheme.colors
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    LaunchedEffect(code) {
        if (code.length < 6 || checking) return@LaunchedEffect
        checking = true
        error = false
        val result = cast.pairScreen(code)
        checking = false
        if (result != null) onPaired() else { error = true; code = "" }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        SheetHeader(str("screens.add"), onBack)
        TextField(
            value = code,
            onValueChange = { v -> code = v.uppercase().filter { it.isLetterOrDigit() }.take(6) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            placeholder = { Text("· · · · · ·", color = colors.textDim) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = colors.surfaceVariant, unfocusedContainerColor = colors.surfaceVariant,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = colors.text, unfocusedTextColor = colors.text,
            ),
        )
        val hint = if (error) str("screens.code_failed") else str("screens.code_hint")
        Text(hint, color = if (error) colors.accentSecondary else colors.textDim, fontSize = 13.sp, fontFamily = Sora, modifier = Modifier.padding(horizontal = 14.dp))
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SheetHeader(title: String, onClose: () -> Unit) {
    val colors = RaviloTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, modifier = Modifier.weight(1f))
        Box(
            Modifier.height(36.dp).width(36.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { Text("✕", color = colors.textSecondary, fontSize = 16.sp) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = RaviloTheme.colors.textDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, letterSpacing = 1.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable
private fun CollapsibleRow(label: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val colors = RaviloTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
        Spacer(Modifier.width(6.dp))
        Text("($count)", color = colors.textDim, fontSize = 14.sp, fontFamily = Sora)
        Spacer(Modifier.weight(1f))
        Text(if (expanded) "︿" else "﹀", color = colors.textDim, fontSize = 14.sp)
    }
}

@Composable
private fun DeviceRow(device: RemoteDevice, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    val np = device.nowPlaying
    val busy = np != null && np.loaded && np.sessionUserId != null
    val offline = !device.online
    val tappable = !offline
    val state = when {
        !device.online -> str("screens.offline", mapOf("when" to lastSeenLabel(device.lastSeen)))
        busy -> str("screens.busy", mapOf("user" to (device.pairedUsers.firstOrNull() ?: "")))
        np?.loaded == true -> str("screens.playing", mapOf("title" to (np.title ?: "")))
        else -> str("screens.ready")
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp)
            .clickable(enabled = tappable, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.height(36.dp).width(36.dp).background(colors.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
            TvGlyph(if (tappable) colors.text else colors.textDim)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name, color = if (tappable) colors.text else colors.textDim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state, color = colors.textDim, fontSize = 13.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (tappable) Text("›", color = colors.textDim, fontSize = 18.sp)
    }
}

@Composable
private fun SimpleRow(icon: @Composable () -> Unit, label: String, onClick: () -> Unit, labelColor: Color? = null) {
    val colors = RaviloTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.height(28.dp).width(28.dp), contentAlignment = Alignment.Center) { icon() }
        Text(label, color = labelColor ?: colors.text, fontSize = 15.sp, fontFamily = Sora, textAlign = TextAlign.Start)
    }
}

@Composable private fun TvGlyph(tint: Color) { ScreenCastGlyph(tint = tint, on = false, sizeDp = 20) }
@Composable private fun PlusGlyph(tint: Color) { Text("+", color = tint, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
@Composable private fun AirplayGlyph(tint: Color) { Text("▲", color = tint, fontSize = 14.sp) }

/** A plain relative-time label for an offline device's row — no calendar/weekday logic (R270's
 *  "keeps its weekday" polish is a design-side follow-up, not built here). */
private fun lastSeenLabel(lastSeenMs: Long): String {
    val deltaMs = (Clock.System.now().toEpochMilliseconds() - lastSeenMs).coerceAtLeast(0)
    val mins = deltaMs / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 24 * 60 -> "${mins / 60} h ago"
        else -> "${mins / (24 * 60)} d ago"
    }
}
