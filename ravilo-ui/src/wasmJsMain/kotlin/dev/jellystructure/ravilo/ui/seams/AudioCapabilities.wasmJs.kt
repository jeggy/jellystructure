package dev.jellystructure.ravilo.ui.seams

/**
 * R302 (FR-R302-1) — ask the browser, the way the Chromecast receiver does (R297): AAC and MP3
 * always; AC-3, E-AC-3, Opus and FLAC only when this browser says it decodes them. Desktop Chrome
 * and Firefox answer no to AC-3/E-AC-3, so a title carrying them is transcoded (R297 FR-R297-4)
 * instead of direct-played silent. Answered once per page.
 */
private val probed: List<String> by lazy {
    listOfNotNull(
        "aac", "mp3",
        "flac".takeIf { jsCanDecode("audio/mp4; codecs=\"flac\"") || jsCanDecode("audio/flac") },
        "opus".takeIf { jsCanDecode("audio/mp4; codecs=\"opus\"") || jsCanDecode("audio/webm; codecs=\"opus\"") },
        "ac3".takeIf { jsCanDecode("audio/mp4; codecs=\"ac-3\"") },
        "eac3".takeIf { jsCanDecode("audio/mp4; codecs=\"ec-3\"") },
    )
}

actual fun supportedAudioCodecs(): List<String> = probed

/** R284 (FR-R284-6) — most browsers have no HEVC decoder at all; the web stays on h264. */
actual fun supportsHevcOverHls(): Boolean = false

// MSE's answer where MSE exists (hls.js), else the <video> element's own (Safari plays HLS natively
// and has no MSE for it): "probably"/"maybe" both count as yes, as hls.js itself treats them.
private fun jsCanDecode(type: String): Boolean = js(
    """((window.MediaSource && window.MediaSource.isTypeSupported) ? window.MediaSource.isTypeSupported(type) : (document.createElement('video').canPlayType(type) !== ''))"""
)
