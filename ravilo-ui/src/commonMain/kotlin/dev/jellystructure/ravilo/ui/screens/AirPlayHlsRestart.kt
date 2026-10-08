package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.jellystructure.ravilo.ui.seams.airplayNeedsHls
import dev.jellystructure.ravilo.ui.seams.platformAirPlay

/**
 * R376 (owner, 2026-10-08; changes R265 FR-R265-8 for Safari) — Safari direct-plays what it can; the moment the viewer
 * picks AirPlay (WebKit says the picture is on a wireless target) and the stream is not HLS, the same item restarts
 * as HLS at the current position ([onRestart] → `PlayerStore.restreamForAirPlay`), once per item. The `<video>` keeps
 * its AirPlay target across the new source. Nothing where there is no AirPlay (Android, the desktop).
 */
@Composable
internal fun AirPlayHlsRestart(itemId: String?, streamIsHls: Boolean, onRestart: () -> Unit) {
    val airplay = platformAirPlay ?: return
    val wireless by airplay.wireless.collectAsState()
    var restartedFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(wireless, itemId, streamIsHls) {
        if (itemId != null && airplayNeedsHls(wireless, streamIsHls, restartedFor == itemId)) {
            restartedFor = itemId
            onRestart()
        }
    }
}
