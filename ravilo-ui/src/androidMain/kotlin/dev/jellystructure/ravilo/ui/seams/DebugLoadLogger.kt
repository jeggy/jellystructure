package dev.jellystructure.ravilo.ui.seams

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException

/**
 * R291 — a debuggable build's view of every load the player makes: which URL, for which track type, which
 * media time, and how it ended. A release build logs nothing from Media3, which is why the stue TV's stall
 * (the player went BUFFERING at an audio switch and never asked for anything again) could not be pinned to
 * a request. Bound only when the app is debuggable (see [RaviloPlayer.bindEngine]); tag `R291`.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class DebugLoadLogger(private val position: () -> Pair<Long, Long>) : AnalyticsListener {
    private fun type(t: Int) = when (t) { C.TRACK_TYPE_VIDEO -> "video"; C.TRACK_TYPE_AUDIO -> "audio"; C.TRACK_TYPE_TEXT -> "text"; C.TRACK_TYPE_DEFAULT -> "muxed"; else -> "t$t" }
    private fun data(t: Int) = when (t) { C.DATA_TYPE_MEDIA -> "media"; C.DATA_TYPE_MANIFEST -> "manifest"; C.DATA_TYPE_MEDIA_INITIALIZATION -> "init"; else -> "d$t" }
    private fun uri(i: LoadEventInfo) = i.uri.toString().substringAfter("://").substringAfter('/').replace(Regex("(api_key|ApiKey|PlaySessionId|DeviceId)=[^&]+"), "$1=…").take(220)
    private fun where(): String { val (pos, buf) = position(); return "pos=${pos}ms buf=${buf}ms" }
    private fun what(d: MediaLoadData) = "${data(d.dataType)}/${type(d.trackType)} ${d.trackFormat?.let { "${it.sampleMimeType ?: it.containerMimeType} '${it.label ?: ""}'" } ?: ""} t=${d.mediaStartTimeMs}..${d.mediaEndTimeMs}"

    override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData, retryCount: Int) {
        Log.i(TAG, "load START ${what(mediaLoadData)} retry=$retryCount ${where()} ${uri(loadEventInfo)}")
    }
    override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
        Log.i(TAG, "load DONE  ${what(mediaLoadData)} ${loadEventInfo.loadDurationMs}ms ${loadEventInfo.bytesLoaded}B ${where()} ${uri(loadEventInfo)}")
    }
    override fun onLoadCanceled(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
        Log.i(TAG, "load CANCEL ${what(mediaLoadData)} after ${loadEventInfo.loadDurationMs}ms ${where()} ${uri(loadEventInfo)}")
    }
    override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
        Log.w(TAG, "load ERROR ${what(mediaLoadData)} canceled=$wasCanceled ${error.javaClass.simpleName}: ${error.message} ${where()} ${uri(loadEventInfo)}")
    }
    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        Log.i(TAG, "state ${when (state) { Player.STATE_IDLE -> "IDLE"; Player.STATE_BUFFERING -> "BUFFERING"; Player.STATE_READY -> "READY"; else -> "ENDED" }} ${where()}")
    }
    override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.joinToString(" | ") { g ->
            (0 until g.length).joinToString(",") { t -> "${g.getTrackFormat(t).label ?: g.getTrackFormat(t).language}${if (g.isTrackSelected(t)) "*" else ""}" }
        }
        Log.i(TAG, "tracks audio: $audio ${where()}")
    }
    override fun onDownstreamFormatChanged(eventTime: AnalyticsListener.EventTime, mediaLoadData: MediaLoadData) {
        Log.i(TAG, "downstream ${what(mediaLoadData)} ${where()}")
    }
    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: androidx.media3.common.PlaybackException) {
        Log.w(TAG, "player ERROR ${error.errorCodeName}: ${error.message} ${where()}")
    }

    companion object { const val TAG = "R291" }
}
