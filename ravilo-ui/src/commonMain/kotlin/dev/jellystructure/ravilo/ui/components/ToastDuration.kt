package dev.jellystructure.ravilo.ui.components

/**
 * R152 — how long a server-message toast stays on screen, derived from its text length so a short
 * "Dinner's ready" doesn't linger as long as a full sentence. Mirrors design/ravilo/ravilo-app.js's
 * `calculateToastDurationMs`.
 */
fun calculateToastDurationMs(message: String): Long = dev.jellystructure.shared.tv.serverNoticeLengthMs(message)   // R354 — one rule, in :shared
