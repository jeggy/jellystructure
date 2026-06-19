package dev.jellystructure.ravilo.ui.seams

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
actual fun RemoteImage(url: String, contentDescription: String?, modifier: Modifier) {
    Box(modifier = modifier.background(Color(0xFF2A2A3A)))
}
