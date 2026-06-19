package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Platform-specific async image loader composable. Real impl at a future phase. */
@Composable
expect fun RemoteImage(url: String, contentDescription: String?, modifier: Modifier)
