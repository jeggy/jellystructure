package dev.jellystructure.ravilo.ui.components

/**
 * R152 — how long a server-message toast stays on screen, derived from its text length so a short
 * "Dinner's ready" doesn't linger as long as a full sentence. Mirrors design/ravilo/ravilo-app.js's
 * `calculateToastDurationMs`.
 */
fun calculateToastDurationMs(message: String): Long {
    val baseTimeMs = 1500L      // Time to notice the toast appeared
    val msPerCharacter = 75L    // 75ms per character
    val minDurationMs = 3000L   // 3 seconds
    val maxDurationMs = 15000L  // 15 seconds
    val calculatedTime = baseTimeMs + (message.length * msPerCharacter)
    return calculatedTime.coerceIn(minDurationMs, maxDurationMs)
}
