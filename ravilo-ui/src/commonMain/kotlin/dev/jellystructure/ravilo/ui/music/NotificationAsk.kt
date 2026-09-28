package dev.jellystructure.ravilo.ui.music

import androidx.compose.runtime.Composable

/**
 * R322 (dev review 12) — Android 13+ asks before a notification may show. Music asks on its **first play**, once per
 * device, never at launch; declining costs only the card (the service still plays). No-op elsewhere.
 */
@Composable
expect fun rememberNotificationAsk(): () -> Unit
