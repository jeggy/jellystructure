package dev.jellystructure.ravilo.ui.seams

/**
 * R302 (FR-R302-1) — ask the browser, the way the Chromecast receiver does (R297): AAC and MP3
 * always; AC-3, E-AC-3, Opus and FLAC only when this browser says it decodes them. Desktop Chrome
 * and Firefox answer no to AC-3/E-AC-3, so a title carrying them is transcoded (R297 FR-R297-4)
 * instead of direct-played silent. Answered once per page.
 */
private val probed: List<String> by lazy {
    probe().also { jsPublishAudioCodecs(it.joinToString(",")) }
}

private fun probe(): List<String> =
    listOfNotNull(
        "aac", "mp3",
        "flac".takeIf { jsCanDecode("audio/mp4; codecs=\"flac\"") || jsCanDecode("audio/flac") },
        "opus".takeIf { jsCanDecode("audio/mp4; codecs=\"opus\"") || jsCanDecode("audio/webm; codecs=\"opus\"") },
        "ac3".takeIf { jsCanDecode("audio/mp4; codecs=\"ac-3\"") },
        "eac3".takeIf { jsCanDecode("audio/mp4; codecs=\"ec-3\"") },
    )

actual fun supportedAudioCodecs(): List<String> = probed

/** R302 — the answer, readable by the e2e suite (`window.__raviloAudioCodecs`), the way the install
 *  prompt's state already is; the app itself never reads it back. */
private fun jsPublishAudioCodecs(csv: String): Unit = js("{ window.__raviloAudioCodecs = csv; }")

/** R284 (FR-R284-6) — most browsers have no HEVC decoder at all; the web stays on h264. */
actual fun supportsHevcOverHls(): Boolean = false

// MSE's answer where MSE exists (hls.js), else the <video> element's own (Safari plays HLS natively
// and has no MSE for it): "probably"/"maybe" both count as yes, as hls.js itself treats them.
private fun jsCanDecode(type: String): Boolean = js(
    """((window.MediaSource && window.MediaSource.isTypeSupported) ? window.MediaSource.isTypeSupported(type) : (document.createElement('video').canPlayType(type) !== ''))"""
)

/**
 * R265 (FR-R265-8) — asked of the browser, like the audio list above: h264 always; HEVC, VP9 and AV1
 * only where this browser says it decodes them. A codec left off is transcoded to h264 instead of
 * direct-played into a decoder that is not there.
 */
private val probedVideo: List<String> by lazy {
    probeVideo().also { jsPublishVideoCodecs(it.joinToString(",")) }
}

private fun probeVideo(): List<String> =
    listOfNotNull(
        "h264",
        "hevc".takeIf { jsCanDecode("video/mp4; codecs=\"hvc1.1.6.L120.90\"") || jsCanDecode("video/mp4; codecs=\"hev1.1.6.L120.90\"") },
        "vp9".takeIf { jsCanDecode("video/webm; codecs=\"vp9\"") || jsCanDecode("video/mp4; codecs=\"vp09.00.10.08\"") },
        "av1".takeIf { jsCanDecode("video/mp4; codecs=\"av01.0.05M.08\"") },
    )

actual fun supportedVideoCodecs(): List<String> = probedVideo

/** R265 — the answer, readable by the e2e suite (`window.__raviloVideoCodecs`), like the audio list's. */
private fun jsPublishVideoCodecs(csv: String): Unit = js("{ window.__raviloVideoCodecs = csv; }")

/**
 * R265 (FR-R265-8) — Safari: plays HLS natively AND can AirPlay (WebKit's playback-target event exists).
 * Chrome on Android also plays HLS natively, but has no AirPlay and plays the library's files as they
 * are, so it keeps its negotiation. Published for the e2e suite as `window.__raviloAirPlayHls`.
 */
private val airplayHls: Boolean by lazy { jsAirPlayHls().also { jsPublishAirPlayHls(it) } }
actual fun playsHlsForAirPlay(): Boolean = airplayHls

private fun jsAirPlayHls(): Boolean = js(
    """(typeof window.WebKitPlaybackTargetAvailabilityEvent !== 'undefined') && (document.createElement('video').canPlayType('application/vnd.apple.mpegurl') !== '')"""
)
private fun jsPublishAirPlayHls(v: Boolean): Unit = js("{ window.__raviloAirPlayHls = v; }")
