package dev.jellystructure.ravilo.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

/**
 * R383 (FR-R383-3) — a looping 0..1 phase for a small decorative indicator (the playing bars, a pulse), advanced about
 * 12 times a second and only while [active]. `rememberInfiniteTransition` asks for a frame on every vsync for as long as
 * it exists: on a 120 Hz Mac three tiny bars cost 120 frames a second and half a core (DEB-1510), and the transition ran
 * even when the bars were paused. A stepped phase redraws the indicator 12 times a second and not at all when idle.
 */
@Composable
fun rememberSteppedPhase(active: Boolean, periodMs: Int, stepMs: Long = STEP_MS): Float {
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active, periodMs) {
        if (!active) return@LaunchedEffect
        val start = TimeSource.Monotonic.markNow()
        while (true) {
            phase = phaseAt(start.elapsedNow().inWholeMilliseconds, periodMs)
            delay(stepMs)
        }
    }
    return phase
}

/** Where in its loop a phase is [elapsedMs] after it started. */
internal fun phaseAt(elapsedMs: Long, periodMs: Int): Float = (elapsedMs % periodMs) / periodMs.toFloat()

private const val STEP_MS = 83L
