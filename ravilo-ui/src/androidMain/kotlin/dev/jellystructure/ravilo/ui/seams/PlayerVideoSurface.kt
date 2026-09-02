package dev.jellystructure.ravilo.ui.seams

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.SubtitleView
import android.util.Log
import kotlinx.coroutines.delay

private const val TAG = "PlayerVideoSurface"

/** Phase R220 (FR-R220-2) — how long the poll loop ticks between checks. Independent of
 *  PlayerScreen's own POLL_MS (a different composable, different concern) but the same order of
 *  magnitude so the detector isn't meaningfully slower than the UI it feeds telemetry to. */
private const val WATCHDOG_POLL_MS = 500L

/** Phase R220 (FR-R220-2) — consecutive stalled ticks before declaring "playing but not rendering".
 *  4 × 500ms ≈ 2s, per the phase's own "bias conservative" instruction — long enough that a normal
 *  resume, a track switch, or a codec re-init cannot trip it. Tune from real on-device evidence once
 *  this has actually reproduced the reported bug (see phase-R220's FR-R220-1). */
private const val STALL_TICKS_TO_TRIGGER = 4

/** Phase R220 (FR-R220-3) — how long each ladder rung is given to restore frames before escalating to
 *  the next one. */
private const val RUNG_SETTLE_MS = 1_500L

/** Phase R220 (FR-R220-3) — after a full ladder run (whether it recovered or exhausted into rung 4),
 *  don't re-trigger for this long — a fresh cold-start/track-switch right after a recovery must not be
 *  immediately re-diagnosed as another stall. */
private const val COOLDOWN_AFTER_LADDER_MS = 5_000L

@Composable
actual fun PlayerVideoSurface(
    player: RaviloPlayer,
    modifier: Modifier,
    onVideoOutputStuck: () -> Unit,
    onVideoOutputRecovering: (Boolean) -> Unit,
) {
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

    // Phase R220 (FR-R220-3 rung 3) — bumping this key forces AndroidView's factory to tear down and
    // re-run, i.e. a genuinely fresh SurfaceView. Rungs 1/2 don't touch this at all.
    var surfaceGeneration by remember { mutableIntStateOf(0) }
    // Phase R220 (FR-R220-3 rung 1) — the currently-attached SurfaceView instance, captured from the
    // factory below, so the watchdog can detach-then-reattach it without going through Compose.
    val currentSurface = remember { mutableStateOf<SurfaceView?>(null) }
    val onVideoOutputStuckState = rememberUpdatedState(onVideoOutputStuck)
    val onVideoOutputRecoveringState = rememberUpdatedState(onVideoOutputRecovering)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        key(surfaceGeneration) {
            AndroidView(
                factory = { ctx ->
                    // Bug fix (phase-R173): TextureView doesn't correctly propagate HDR (PQ) color/transfer
                    // metadata to the display compositor — frames get GL-composited as an ordinary texture,
                    // so HDR content renders as if it were SDR gamma (very dark). SurfaceView is its own
                    // hardware-composer layer; the decoder's HDR metadata reaches SurfaceFlinger directly and
                    // the display tone-maps correctly, with no app-level color-mode API needed — verified
                    // against Jellyfin's own Android TV client, which does exactly this (a bare SurfaceView
                    // wired via `ExoPlayer.setVideoSurfaceView`, nothing else).
                    // Phase R220 (FR-R220-4) — onWindowVisibilityChanged override is a second, independent
                    // re-attach trigger alongside the SurfaceHolder.Callback below, for the TV-standby case
                    // (§2.4) where neither surfaceCreated/surfaceDestroyed may fire at all. Not confirmed
                    // this device actually needs it (FR-R220-1's own on-device capture never ran — no
                    // device access this session, and TVs are off-limits now) — shipped as a cheap,
                    // idempotent bet the same way rungs 1-4 of the ladder were.
                    val surface = object : SurfaceView(ctx) {
                        override fun onWindowVisibilityChanged(visibility: Int) {
                            super.onWindowVisibilityChanged(visibility)
                            if (visibility == VISIBLE) player.setVideoSurfaceView(this)
                        }
                    }
                    player.setVideoSurfaceView(surface)
                    currentSurface.value = surface
                    // Phase R220 (FR-R220-4) — a second line of defence alongside Media3's own internal
                    // SurfaceHolder.Callback (the one this whole phase exists because it can silently fail
                    // to re-attach on): re-attach independently on surfaceCreated too. A redundant
                    // setVideoSurfaceView when Media3 already re-attached fine is the one risk the spec
                    // itself flags as needing on-device verification (phase-R220 §5 open question 2) — not
                    // verified this session, shipped per the spec's explicit instruction regardless.
                    surface.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            player.setVideoSurfaceView(surface)
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) {}
                    })
                    surface
                },
                // R77: constrain to DAR when known — Compose fits the view inside the available space
                // and the PlayerScreen's black background shows through as letterbox/pillarbox bars.
                // Falls back to fillMaxSize() until the first frame is decoded (VideoSize.UNKNOWN).
                modifier = if (dar > 0f) Modifier.aspectRatio(dar) else Modifier.fillMaxSize(),
                onRelease = { sv ->
                    // Phase R220 (FR-R220-4) — don't leave a stale reference behind a torn-down surface;
                    // a subsequent watchdog tick must not try to detach/reattach a view that's already gone.
                    if (currentSurface.value === sv) currentSurface.value = null
                    player.forgetVideoSurfaceView(sv)
                },
            )
        }
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

    // Phase R220 (FR-R220-2/FR-R220-3) — "playing but not rendering" watchdog + escalating recovery.
    // Reported live: pressing Home (or TV standby/resume) then reopening Ravilo mid-playback can leave
    // audio continuing correctly while the picture stays black — the surface is bound exactly once, at
    // AndroidView's factory, with no re-bind of any kind, so the app has no second line of defense if
    // Media3's own SurfaceHolder.Callback ever fails to reattach. See phase-R220's own doc for why every
    // candidate detector short of a real frame COUNT (not R218's hasRenderedFirstFrame boolean, which
    // reads healthy in exactly this failure) misses this case entirely.
    LaunchedEffect(Unit) {
        var lastFrameCount = -1L
        var stalledTicks = 0
        var cooldownUntil = 0L
        var elapsedMs = 0L
        while (true) {
            delay(WATCHDOG_POLL_MS)
            elapsedMs += WATCHDOG_POLL_MS
            if (elapsedMs < cooldownUntil) continue

            val frameCount = player.renderedVideoFrameCount()
            val looksHealthy = !player.isPlaying || player.isBuffering || player.isSeeking
            if (looksHealthy || frameCount != lastFrameCount) {
                stalledTicks = 0
                lastFrameCount = frameCount
                continue
            }
            stalledTicks++
            if (stalledTicks < STALL_TICKS_TO_TRIGGER) continue

            // Threshold crossed: playing, not buffering/seeking, and zero new frames for
            // STALL_TICKS_TO_TRIGGER polls in a row. Run the ladder once, then cool down regardless of
            // outcome — a busy loop re-diagnosing the same stall every tick would just fight itself.
            stalledTicks = 0
            Log.w(TAG, "video output stalled (playing, frame count stuck at $frameCount) — starting recovery ladder")
            // Phase R220 (FR-R220-5) — force R218's STALL presentation on for the ladder's duration past
            // its own ~400ms debounce (PlayerScreen wires this into rawBufferMoment), so a rung that takes
            // longer than a flash never leaves a viewer on a frozen frame with no chrome change.
            onVideoOutputRecoveringState.value(true)

            // Rung 1: detach + reattach the existing surface — cheapest, and the one most likely to be
            // exactly what Media3's own SurfaceHolder.Callback failed to do on its own.
            val sv = currentSurface.value
            var recovered = false
            if (sv != null) {
                player.clearVideoSurfaceView(sv)
                player.setVideoSurfaceView(sv)
                delay(RUNG_SETTLE_MS)
                recovered = player.renderedVideoFrameCount() != frameCount
            }

            // Rung 2: force a video-renderer flush via a no-op seek to the current position.
            if (!recovered) {
                val before = player.renderedVideoFrameCount()
                player.seekTo(player.positionMs)
                delay(RUNG_SETTLE_MS)
                recovered = player.renderedVideoFrameCount() != before
            }

            // Rung 3: recreate the SurfaceView outright (bumps the AndroidView factory's key).
            if (!recovered) {
                val before = player.renderedVideoFrameCount()
                surfaceGeneration++
                delay(RUNG_SETTLE_MS)
                recovered = player.renderedVideoFrameCount() != before
            }

            if (recovered) {
                player.recordVideoOutputRecovery()
                Log.w(TAG, "video output recovered")
            } else {
                // Rung 4: hand off to PlayerScreen — re-prepare the item outright. Recorded as a
                // recovery regardless of whether rung 4 itself visibly succeeds (a full re-prepare is
                // PlayerScreen's own responsibility from here; this ladder's job ends at handing off).
                player.recordVideoOutputRecovery()
                Log.w(TAG, "video output did not recover after rungs 1-3 — handing off to onVideoOutputStuck (rung 4)")
            }
            // FR-R220-5 — clear the forced STALL before handing off rung 4: a re-prepare (armSession)
            // drives its own fresh COLD moment from here, not a continuation of this one.
            onVideoOutputRecoveringState.value(false)
            if (!recovered) onVideoOutputStuckState.value()
            lastFrameCount = player.renderedVideoFrameCount()
            cooldownUntil = elapsedMs + COOLDOWN_AFTER_LADDER_MS
        }
    }
}
