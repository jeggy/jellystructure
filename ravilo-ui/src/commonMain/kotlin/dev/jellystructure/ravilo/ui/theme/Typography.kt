package dev.jellystructure.ravilo.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import jellystructure.ravilo_ui.generated.resources.Res
import jellystructure.ravilo_ui.generated.resources.sora
import jellystructure.ravilo_ui.generated.resources.space_grotesk
import org.jetbrains.compose.resources.Font

// Both fonts are variable-weight TTFs. Each Font() entry pointing to the same file
// but with a different FontWeight lets the runtime select the correct wght axis value.

val SpaceGrotesk: FontFamily
    @Composable get() = FontFamily(
        Font(Res.font.space_grotesk, weight = FontWeight.SemiBold),
        Font(Res.font.space_grotesk, weight = FontWeight.Bold),
    )

val Sora: FontFamily
    @Composable get() = FontFamily(
        Font(Res.font.sora, weight = FontWeight.Normal),
        Font(Res.font.sora, weight = FontWeight.Medium),
        Font(Res.font.sora, weight = FontWeight.SemiBold),
    )
