package dev.jellystructure.ravilo.ui.seams

/** No reliable browser HDR-passthrough detection today — stay conservative (forces Jellyfin to
 *  tone-map HDR sources to SDR, which is correct for a plain `<video>` element anyway). */
actual fun detectHdrSupport(): HdrSupport = HdrSupport.NONE

/** R183 — no way to ask a browser for its H.264 decode ceiling (`MediaCapabilities.decodingInfo()` is
 *  async and per-configuration, not a limit query), so report nothing and let the server pick its safe
 *  1080p High/L5.1 transcode target — right for a plain `<video>` element anyway. R216: the same applies
 *  to the new bitrate-ceiling fields — no browser API answers this either. */
actual fun detectDecoderLimits(): DecoderLimits = DecoderLimits.UNKNOWN

/**
 * R265 (FR-R265-8) — the kind of link where the browser says (`navigator.connection.type`, Chrome on
 * Android; Safari has no such API and stays unknown). Never a rate: the Network Information API's
 * `downlink` is an estimate capped at 10 Mb/s, which phase 177's link cap would read as a slow link and
 * transcode for. `mbps = 0` keeps that cap off, exactly as before (R216).
 */
actual fun detectLinkState(): LinkState = when (jsLinkType()) {
    "wifi" -> LinkState(kind = "wifi", mbps = 0)
    "ethernet" -> LinkState(kind = "ethernet", mbps = 0)
    "cellular" -> LinkState(kind = "cellular", mbps = 0)
    else -> LinkState.UNKNOWN
}

private fun jsLinkType(): String = js("""((navigator.connection && navigator.connection.type) ? ('' + navigator.connection.type) : '')""")
