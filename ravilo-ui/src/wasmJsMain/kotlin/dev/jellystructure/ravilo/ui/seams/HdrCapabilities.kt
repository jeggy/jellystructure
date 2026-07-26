package dev.jellystructure.ravilo.ui.seams

/** No reliable browser HDR-passthrough detection today — stay conservative (forces Jellyfin to
 *  tone-map HDR sources to SDR, which is correct for a plain `<video>` element anyway). */
actual fun detectHdrSupport(): HdrSupport = HdrSupport.NONE

/** R183 — no way to ask a browser for its H.264 decode ceiling (`MediaCapabilities.decodingInfo()` is
 *  async and per-configuration, not a limit query), so report nothing and let the server pick its safe
 *  1080p High/L5.1 transcode target — right for a plain `<video>` element anyway. */
actual fun detectAvcDecoderLimits(): AvcDecoderLimits = AvcDecoderLimits.UNKNOWN
