package dev.jellystructure.ravilo.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jellystructure.shared.tv.Skin

data class RaviloColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val accent: Color,
    val accentDim: Color,
    val onAccent: Color,
    val text: Color,
    val textSecondary: Color,
    val focusRing: Color,
    val focusGlow: Color,
    val overlay: Color,
    val progressFill: Color,
    val progressBg: Color,
    val badgeWatched: Color,
    val badgeNew: Color,
    // R23 additions
    val accentSecondary: Color,  // gradient end; kicker text; "NEW" badge gradient
    val textDim: Color,           // tertiary metadata, role text, "see all" links
    val card: Color,              // inner card surface (channel card bg base, episode card)
    val tileRadius: Dp,           // skin-specific corner radius for tiles and cards
)

val AuroraColors = RaviloColors(
    background      = Color(0xFF0A0C13),
    surface         = Color(0xFF161A28),
    surfaceVariant  = Color(0xFF1B2031),
    accent          = Color(0xFF7B6EF0),
    accentDim       = Color(0xFF3C2C8A),
    onAccent        = Color(0xFFFFFFFF),
    text            = Color(0xFFF3F4FB),
    textSecondary   = Color(0xFFAEB4CB),
    textDim         = Color(0xFF6B7290),
    focusRing       = Color(0xFF8E82FF),
    focusGlow       = Color(0x8C7B6EF0),
    overlay         = Color(0xCC0A0C13),
    progressFill    = Color(0xFF7B6EF0),
    progressBg      = Color(0x337B6EF0),
    badgeWatched    = Color(0xFF2DD49A),
    badgeNew        = Color(0xFF3FB6F5),
    accentSecondary = Color(0xFF3FB6F5),
    card            = Color(0xFF0E111B),
    tileRadius      = 12.dp,
)

val MidnightColors = RaviloColors(
    background      = Color(0xFF04101A),
    surface         = Color(0xFF0A1C28),
    surfaceVariant  = Color(0xFF0E2533),
    accent          = Color(0xFF19D6C6),
    accentDim       = Color(0xFF0A3D38),
    onAccent        = Color(0xFFFFFFFF),
    text            = Color(0xFFEAFCFF),
    textSecondary   = Color(0xFF9FC2CF),
    textDim         = Color(0xFF5A7D8A),
    focusRing       = Color(0xFF28E6D6),
    focusGlow       = Color(0x8028E6D6),
    overlay         = Color(0xCC04101A),
    progressFill    = Color(0xFF19D6C6),
    progressBg      = Color(0x3319D6C6),
    badgeWatched    = Color(0xFF2DD49A),
    badgeNew        = Color(0xFF2A8CF0),
    accentSecondary = Color(0xFF2A8CF0),
    card            = Color(0xFF07151F),
    tileRadius      = 14.dp,
)

val NoirColors = RaviloColors(
    background      = Color(0xFF080807),
    surface         = Color(0xFF16140F),
    surfaceVariant  = Color(0xFF1F1C15),
    accent          = Color(0xFFF5B542),
    accentDim       = Color(0xFF7A5B1F),
    onAccent        = Color(0xFF08070A),
    text            = Color(0xFFF7F3EA),
    textSecondary   = Color(0xFFC7BFAE),
    textDim         = Color(0xFF807868),
    focusRing       = Color(0xFFFFCF6B),
    focusGlow       = Color(0x80FFCF6B),
    overlay         = Color(0xCC080807),
    progressFill    = Color(0xFFF5B542),
    progressBg      = Color(0x33F5B542),
    badgeWatched    = Color(0xFF2DD49A),
    badgeNew        = Color(0xFFC79A3F),
    accentSecondary = Color(0xFFE0792F),
    card            = Color(0xFF0D0C0A),
    tileRadius      = 6.dp,
)

fun Skin.colors(): RaviloColors = when (this) {
    Skin.AURORA   -> AuroraColors
    Skin.MIDNIGHT -> MidnightColors
    Skin.NOIR     -> NoirColors
}

// Accent gradient (accent → accentSecondary). Creates a new Brush on each property access;
// callers inside Modifier.background() are safe (structural equality prevents recompose).
// Callers storing the result in a val should wrap with remember(colors.accent, colors.accentSecondary).
val RaviloColors.accentGradient: Brush
    get() = Brush.linearGradient(listOf(accent, accentSecondary))
