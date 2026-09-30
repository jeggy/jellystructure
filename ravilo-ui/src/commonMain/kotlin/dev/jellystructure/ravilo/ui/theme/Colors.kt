package dev.jellystructure.ravilo.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jellystructure.shared.tv.RaviloThemes
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
    // R338 (FR-R338-1) — the ink every translucent fill, border and track is drawn in, at its own alpha: white on a
    // dark theme, near-black on a light one. Never used over artwork or video, which stays white on every theme.
    val fg: Color = Color.White,
    val isLight: Boolean = false,
    // R338 — where the accent gradient ends: accentSecondary, except Graphite, whose gradient is its one blue, flat.
    val gradientEnd: Color = accentSecondary,
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

// R338 (FR-R338-1) — Graphite: dark and neutral, one blue.
val GraphiteColors = RaviloColors(
    background      = Color(0xFF1C1C1F),
    surface         = Color(0xFF2A2A2E),
    surfaceVariant  = Color(0xFF323237),
    accent          = Color(0xFF4F8EF0),
    accentDim       = Color(0xFF1E3A66),
    onAccent        = Color(0xFFFFFFFF),
    text            = Color(0xFFF2F2F4),
    textSecondary   = Color(0xFFB4B4BB),
    textDim         = Color(0xFF85858D),
    focusRing       = Color(0xFF4F8EF0),
    focusGlow       = Color(0x804F8EF0),
    overlay         = Color(0xCC1C1C1F),
    progressFill    = Color(0xFF4F8EF0),
    progressBg      = Color(0x334F8EF0),
    badgeWatched    = Color(0xFF2DD49A),
    badgeNew        = Color(0xFF6FB0F5),
    accentSecondary = Color(0xFF6FB0F5),
    card            = Color(0xFF242428),
    tileRadius      = 10.dp,
    gradientEnd     = Color(0xFF4F8EF0),
)

// R338 (FR-R338-1) — Daylight: Ravilo's first light theme. Text contrast on bg, bg-2 and card checked at 4.5 : 1
// (textDim #6D7286 on #FBFBFD is 4.8 : 1).
val DaylightColors = RaviloColors(
    background      = Color(0xFFFBFBFD),
    surface         = Color(0xFFF1F2F6),
    surfaceVariant  = Color(0xFFE8EAF0),
    accent          = Color(0xFF5B4EE0),
    accentDim       = Color(0xFFE3E0FB),
    onAccent        = Color(0xFFFFFFFF),
    text            = Color(0xFF15171F),
    textSecondary   = Color(0xFF4A4F63),
    textDim         = Color(0xFF6D7286),
    focusRing       = Color(0xFF5B4EE0),
    focusGlow       = Color(0x4D5B4EE0),
    overlay         = Color(0x8015171F),
    progressFill    = Color(0xFF5B4EE0),
    progressBg      = Color(0x295B4EE0),
    badgeWatched    = Color(0xFF1F9E72),
    badgeNew        = Color(0xFF0F73C2),
    accentSecondary = Color(0xFF0F73C2),
    card            = Color(0xFFF1F2F6),
    tileRadius      = 12.dp,
    fg              = Color(0xFF10121E),
    isLight         = true,
)

/**
 * R338 — the five themes inside the app. [family] is the original skin whose look a theme follows wherever the code
 * branches on one (every such branch is "Noir or not": Noir alone drops the accent tints), so Graphite takes
 * Midnight's side and Daylight Aurora's without any branch changing.
 */
enum class ThemeId(val id: String, val family: Skin) {
    AURORA(RaviloThemes.AURORA, Skin.AURORA),
    MIDNIGHT(RaviloThemes.MIDNIGHT, Skin.MIDNIGHT),
    NOIR(RaviloThemes.NOIR, Skin.NOIR),
    GRAPHITE(RaviloThemes.GRAPHITE, Skin.MIDNIGHT),
    DAYLIGHT(RaviloThemes.DAYLIGHT, Skin.AURORA);

    val isLight: Boolean get() = RaviloThemes.isLight(id)

    fun colors(): RaviloColors = when (this) {
        AURORA -> AuroraColors
        MIDNIGHT -> MidnightColors
        NOIR -> NoirColors
        GRAPHITE -> GraphiteColors
        DAYLIGHT -> DaylightColors
    }

    companion object {
        fun of(id: String?): ThemeId? = entries.firstOrNull { it.id == id }
        fun fromSkin(skin: Skin): ThemeId = when (skin) {
            Skin.AURORA -> AURORA
            Skin.MIDNIGHT -> MIDNIGHT
            Skin.NOIR -> NOIR
        }
    }
}

fun Skin.colors(): RaviloColors = when (this) {
    Skin.AURORA   -> AuroraColors
    Skin.MIDNIGHT -> MidnightColors
    Skin.NOIR     -> NoirColors
}

// Accent gradient (accent → accentSecondary). Creates a new Brush on each property access;
// callers inside Modifier.background() are safe (structural equality prevents recompose).
// Callers storing the result in a val should wrap with remember(colors.accent, colors.accentSecondary).
val RaviloColors.accentGradient: Brush
    get() = Brush.linearGradient(listOf(accent, gradientEnd))
