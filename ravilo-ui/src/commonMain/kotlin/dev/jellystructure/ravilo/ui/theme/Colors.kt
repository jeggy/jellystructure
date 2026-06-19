package dev.jellystructure.ravilo.ui.theme

import androidx.compose.ui.graphics.Color
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
)

val AuroraColors = RaviloColors(
    background     = Color(0xFF06060F),
    surface        = Color(0xFF0F0F1E),
    surfaceVariant = Color(0xFF16162A),
    accent         = Color(0xFF8B5CF6),
    accentDim      = Color(0xFF4C2889),
    onAccent       = Color(0xFFFFFFFF),
    text           = Color(0xFFE2E8F0),
    textSecondary  = Color(0xFF94A3B8),
    focusRing      = Color(0xFF8B5CF6),
    focusGlow      = Color(0x558B5CF6),
    overlay        = Color(0xCC06060F),
    progressFill   = Color(0xFF8B5CF6),
    progressBg     = Color(0x338B5CF6),
    badgeWatched   = Color(0xFF10B981),
    badgeNew       = Color(0xFF3B82F6),
)

val MidnightColors = RaviloColors(
    background     = Color(0xFF080C18),
    surface        = Color(0xFF0D1426),
    surfaceVariant = Color(0xFF141D35),
    accent         = Color(0xFF3B82F6),
    accentDim      = Color(0xFF1D3A7A),
    onAccent       = Color(0xFFFFFFFF),
    text           = Color(0xFFE2E8F0),
    textSecondary  = Color(0xFF94A3B8),
    focusRing      = Color(0xFF3B82F6),
    focusGlow      = Color(0x553B82F6),
    overlay        = Color(0xCC080C18),
    progressFill   = Color(0xFF3B82F6),
    progressBg     = Color(0x333B82F6),
    badgeWatched   = Color(0xFF10B981),
    badgeNew       = Color(0xFF8B5CF6),
)

val NoirColors = RaviloColors(
    background     = Color(0xFF0A0A0A),
    surface        = Color(0xFF141414),
    surfaceVariant = Color(0xFF1E1E1E),
    accent         = Color(0xFFE2E8F0),
    accentDim      = Color(0xFF64748B),
    onAccent       = Color(0xFF0A0A0A),
    text           = Color(0xFFE2E8F0),
    textSecondary  = Color(0xFF94A3B8),
    focusRing      = Color(0xFFE2E8F0),
    focusGlow      = Color(0x44E2E8F0),
    overlay        = Color(0xCC0A0A0A),
    progressFill   = Color(0xFFE2E8F0),
    progressBg     = Color(0x33E2E8F0),
    badgeWatched   = Color(0xFF10B981),
    badgeNew       = Color(0xFF94A3B8),
)

fun Skin.colors(): RaviloColors = when (this) {
    Skin.AURORA   -> AuroraColors
    Skin.MIDNIGHT -> MidnightColors
    Skin.NOIR     -> NoirColors
}
