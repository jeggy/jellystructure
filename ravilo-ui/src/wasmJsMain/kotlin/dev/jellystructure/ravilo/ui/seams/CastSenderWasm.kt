package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// R245 — there is no Cast sender in a browser; no sender ⇒ no button, never a greyed one.
@Composable
actual fun rememberCastSender(): CastSender? = null

@Composable
actual fun PlatformCastButton(modifier: Modifier) {}
