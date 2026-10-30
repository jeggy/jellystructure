package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.i18n.str
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * DEB-2658 (DEB-2628 FR-6) — the radio mark on a tile: the broadcast glyph in a dark plate, drawn in the tile's top-right corner
 * (`.rd-markA` in `design/ravilo/radio/radio.css`: 46 px at 1920 wide, `rgba(8,10,16,.76)`, a 1 px inset ring of white at 22 %).
 * It carries the accessible label *Radio*. Its place is the caller's: the one corner a channel tile leaves empty.
 */
@Composable
fun RadioMarkPlate(modifier: Modifier = Modifier, plateSize: Dp = 34.dp, glyphSize: Dp = 22.dp) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = modifier.size(plateSize)
            .background(Color(0xC2080A10), shape)
            .border(1.dp, Color.White.copy(alpha = 0.22f), shape),
        contentAlignment = Alignment.Center,
    ) { RadioGlyph(Color.White, glyphSize, description = str("radio.label")) }
}

/** DEB-2658 (DEB-2628 FR-7) — the mark inline, with no plate: where there is no art and no corner (the guide's channel cell, Now/Next, the zap banner). */
@Composable
fun RadioMarkInline(tint: Color, glyphSize: Dp = 14.dp, modifier: Modifier = Modifier) =
    RadioGlyph(tint, glyphSize, modifier, description = str("radio.label"))
