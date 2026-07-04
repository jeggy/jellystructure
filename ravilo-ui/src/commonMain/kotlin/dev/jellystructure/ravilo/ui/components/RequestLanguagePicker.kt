package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloColors
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.shared.tv.RequestLanguageOption
import org.jetbrains.compose.resources.painterResource

/**
 * Phase 139 / R172 §A — the Original-vs-Nordic (or whatever the catalog holds) request-language popup.
 * Visual DNA is the player's [dev.jellystructure.ravilo.ui.screens] `TrackPicker` (rounded dark card,
 * accent radio tick) but with **normal Compose D-pad focus traversal** per row (via [dpadFocusable] +
 * [FocusRequester]) rather than the player's manual index tracking — that manual scheme exists only
 * because the player owns exclusive input during video playback; this popup does not.
 *
 * [default] is pre-focused (not just visually marked) so **Select immediately confirms** — the "always
 * show, default pre-selected" one-tap design (§A). Any row's Back dismisses without selecting.
 */
@Composable
fun RequestLanguagePicker(
    languages: List<RequestLanguageOption>,
    default: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = RaviloTheme.colors
    val defaultIdx = languages.indexOfFirst { it.id == default }.let { if (it >= 0) it else 0 }
    val focusRequesters = remember(languages) { languages.map { FocusRequester() } }
    LaunchedEffect(languages) { runCatching { focusRequesters.getOrNull(defaultIdx)?.requestFocus() } }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF0E1119).copy(alpha = 0.96f))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
                .padding(22.dp),
        ) {
            Text(str("request.in_language"), color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
            Spacer(Modifier.height(14.dp))
            languages.forEachIndexed { i, opt ->
                RequestLanguageOptionRow(
                    option = opt,
                    selected = i == defaultIdx,
                    focusRequester = focusRequesters[i],
                    colors = colors,
                    onSelect = { onSelect(opt.id) },
                    onBack = onDismiss,
                )
                if (i < languages.lastIndex) Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun RequestLanguageOptionRow(
    option: RequestLanguageOption,
    selected: Boolean,
    focusRequester: FocusRequester,
    colors: RaviloColors,
    onSelect: () -> Unit,
    onBack: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) colors.focusRing.copy(0.7f) else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .dpadFocusable(focusRequester = focusRequester, onFocused = { focused = true }, onBlurred = { focused = false }, onSelect = onSelect, onBack = onBack)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val drawable = option.flag.takeIf { it.isNotBlank() }?.let { LANG_CC[it.lowercase()] }
        Box(modifier = Modifier.size(width = 28.dp, height = 20.dp), contentAlignment = Alignment.Center) {
            if (drawable != null) {
                Image(painter = painterResource(drawable), contentDescription = null, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp)))
            } else {
                Text("🌐", fontSize = 14.sp)
            }
        }
        Text(option.label, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) colors.accent else Color.Transparent)
                .border(2.dp, if (selected) colors.accent else Color.White.copy(0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Text("✓", color = colors.onAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** The flag for a resolved request-language id, looked up against the catalog already delivered on the
 *  DTO (never a second network call) — null when the id is unknown/blank (renders a globe upstream). */
fun requestLanguageFlag(languages: List<RequestLanguageOption>, id: String?): String? =
    id?.let { i -> languages.firstOrNull { it.id == i }?.flag?.takeIf { it.isNotBlank() } }

fun requestLanguageLabel(languages: List<RequestLanguageOption>, id: String?): String? =
    id?.let { i -> languages.firstOrNull { it.id == i }?.label }
