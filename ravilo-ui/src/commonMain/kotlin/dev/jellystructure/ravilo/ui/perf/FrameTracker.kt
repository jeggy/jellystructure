package dev.jellystructure.ravilo.ui.perf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// R94: debug frame-rate tracker. Platform-specific to avoid shipping junk on prod.
expect object FrameTracker {
    fun start()
    fun stop()
    fun fps(): Float
    fun dropped(): Int
}

// Toggle at runtime with Key.F5; hidden until then. Red <45fps / Yellow 45-54 / Green 55+.
@Composable
fun FrameTrackerOverlay(enabled: Boolean) {
    if (!enabled) return
    DisposableEffect(Unit) {
        FrameTracker.start()
        onDispose { FrameTracker.stop() }
    }
    var fps by remember { mutableFloatStateOf(0f) }
    var dropped by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            fps = FrameTracker.fps()
            dropped = FrameTracker.dropped()
        }
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Box(
            modifier = Modifier
                .padding(6.dp)
                .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
                .padding(horizontal = 7.dp, vertical = 3.dp),
        ) {
            Text(
                text = "${fps.toInt()} fps  drop:$dropped",
                color = when {
                    fps in 1f..44f -> Color(0xFFFF5252)
                    fps in 45f..54f -> Color(0xFFFFD740)
                    else            -> Color(0xFF69F0AE)
                },
                fontSize = 11.sp,
            )
        }
    }
}
