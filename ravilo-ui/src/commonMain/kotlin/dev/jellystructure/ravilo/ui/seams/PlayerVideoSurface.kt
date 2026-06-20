package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun PlayerVideoSurface(player: RaviloPlayer, modifier: Modifier = Modifier)
