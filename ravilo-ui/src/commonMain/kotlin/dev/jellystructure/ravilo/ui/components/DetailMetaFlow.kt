package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

/**
 * R307 (FR-R307-1) — the film and series pages' meta line (year · runtime, the age badge, the IMDb chip,
 * the watched mark). A flow, not a Row: when the pieces are wider than the screen — a phone — a whole piece
 * moves to the next line, where a Row squeezed its last child until the text wrapped inside it
 * (*"Watc / hed"* on the Pixel 9). The pieces themselves must be single-line. On a TV it fits on one line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailMetaFlow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
