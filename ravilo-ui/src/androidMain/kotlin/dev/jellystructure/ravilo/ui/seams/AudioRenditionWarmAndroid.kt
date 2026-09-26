package dev.jellystructure.ravilo.ui.seams

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.Volatile

/** R291 — the player that last loaded a stream; the picker's warm goes to it. Cleared on [RaviloPlayer.release]. */
@Volatile internal var activeRaviloPlayer: RaviloPlayer? = null

actual fun warmAudioRendition(index: Int) {
    activeRaviloPlayer?.warmAudioRendition(index)
}

/**
 * R291 — fetches one rendition's segment at the playhead, off the main thread, through
 * [DefaultHttpDataSource] — the same class and the same (default) user agent Media3's own
 * `DefaultMediaSourceFactory` loads the stream with, so Jellyfin files the request under the job the
 * switch will read from rather than starting a second one.
 *
 * Bounded (dev review item 5): one thread, so at most one warm is in flight; a warm that has not started
 * when the focus moves on is dropped; a rendition warmed in the last [REWARM_AFTER_MS] is not warmed again.
 * Every job it starts is on a play session the backend minted and hands phase 180's teardown, so Back
 * stops it like any other.
 */
internal object AudioRenditionWarmer {
    private const val TAG = "RaviloPlayer"
    private const val REWARM_AFTER_MS = 20_000L
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ravilo-audio-warm").apply { isDaemon = true } }
    private val generation = AtomicInteger()
    private val warmedAt = ConcurrentHashMap<String, Long>()

    /** [manifest] and [positionMs] are read by the caller on the player's thread. */
    fun warm(manifest: HlsManifest, index: Int, positionMs: Long) {
        val rendition = manifest.multivariantPlaylist.audios
            .firstOrNull { it.name == "a$index" || it.name.startsWith("a$index ") } ?: return
        val playlist: Uri = rendition.url ?: return   // the carried track is muxed in the video: nothing to warm
        val key = playlist.toString()
        warmedAt[key]?.let { if (SystemClock.elapsedRealtime() - it < REWARM_AFTER_MS) return }
        val gen = generation.incrementAndGet()
        executor.execute {
            if (gen != generation.get()) return@execute
            val t0 = SystemClock.elapsedRealtime()
            runCatching {
                val media = HlsPlaylistParser().parse(playlist, ByteArrayInputStream(read(playlist))) as? HlsMediaPlaylist
                    ?: return@runCatching
                val positionUs = positionMs * 1000L
                val segment = media.segments.lastOrNull { it.relativeStartTimeUs <= positionUs } ?: return@runCatching
                read(UriUtil.resolveToUri(media.baseUri, segment.url))
                warmedAt[key] = SystemClock.elapsedRealtime()
                Log.i(TAG, "R291 warm a$index: segment ${media.segments.indexOf(segment)} ready in ${SystemClock.elapsedRealtime() - t0} ms")
            }.onFailure { Log.w(TAG, "R291 warm a$index failed: ${it.javaClass.simpleName}") }
        }
    }

    private fun read(uri: Uri): ByteArray {
        val source = DefaultHttpDataSource.Factory().createDataSource()
        try {
            source.open(DataSpec(uri))
            return DataSourceUtil.readToEnd(source)
        } finally {
            source.close()
        }
    }
}
