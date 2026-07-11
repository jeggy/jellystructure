package dev.jellystructure.ravilo.ui.seams

import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.SubtitleView

@Composable
actual fun PlayerVideoSurface(player: RaviloPlayer, modifier: Modifier) {
    // R77: collect video geometry and compute display aspect ratio (DAR).
    // pixelWidthHeightRatio (SAR) corrects anamorphic encoding (e.g. DVD 720×480 @ SAR 32:27 → 16:9).
    // ExoPlayer applies rotation itself, so width/height already reflect the on-screen orientation —
    // no swap needed.
    val videoSize by player.videoSize.collectAsState()
    val dar: Float = run {
        val w = videoSize.width
        val h = videoSize.height
        if (w <= 0 || h <= 0) return@run 0f
        w * videoSize.pixelWidthHeightRatio / h
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                // Bug fix (phase-R173): TextureView doesn't correctly propagate HDR (PQ) color/transfer
                // metadata to the display compositor — frames get GL-composited as an ordinary texture,
                // so HDR content renders as if it were SDR gamma (very dark). SurfaceView is its own
                // hardware-composer layer; the decoder's HDR metadata reaches SurfaceFlinger directly and
                // the display tone-maps correctly, with no app-level color-mode API needed — verified
                // against Jellyfin's own Android TV client, which does exactly this (a bare SurfaceView
                // wired via `ExoPlayer.setVideoSurfaceView`, nothing else).
                val surface = SurfaceView(ctx)
                player.setVideoSurfaceView(surface)
                surface
            },
            // R77: constrain to DAR when known — Compose fits the view inside the available space
            // and the PlayerScreen's black background shows through as letterbox/pillarbox bars.
            // Falls back to fillMaxSize() until the first frame is decoded (VideoSize.UNKNOWN).
            modifier = if (dar > 0f) Modifier.aspectRatio(dar) else Modifier.fillMaxSize(),
        )
        // Bug fix: SubtitleView used to live *inside* the DAR-constrained AndroidView above (wrapped
        // together with the SurfaceView in a FrameLayout), so its bottom padding was measured from the
        // bottom of the (possibly letterboxed) video frame, not the true screen edge. Any content
        // narrower than the display (e.g. 2.35:1 cinemascope on a 16:9 TV) left a black letterbox bar
        // below the video, and subtitles sat pinned above that bar — well above the true bottom of the
        // screen. Reported "subtitles too high" on stue TV; the effect is barely visible on a phone
        // (smaller screen, closer viewing distance) which is why it went unnoticed when the 28dp inset
        // was tuned (R77, same day). Now a sibling AndroidView at the outer fillMaxSize() Box level —
        // still drawn on top of the SurfaceView (declared after it, same z-order Compose already gave
        // the old FrameLayout children) — so its padding is always relative to the real screen bottom.
        AndroidView(
            factory = { ctx ->
                val subtitles = SubtitleView(ctx)
                val bottomPx = (28 * ctx.resources.displayMetrics.density).toInt()
                subtitles.setPadding(0, 0, 0, bottomPx)
                player.setSubtitleView(subtitles)
                subtitles
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
