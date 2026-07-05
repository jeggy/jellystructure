package dev.jellystructure.ravilo.ui.seams

/** Real display/decoder HDR support — reported to the server (via `ClientCapabilities`) so it knows
 *  whether it's safe to direct-play HDR10/HLG content or whether Jellyfin should tone-map-transcode
 *  it to SDR first (see the bug this fixes: an HDR10+ file played very dark on Ravilo but fine in
 *  Jellyfin's own client, because jellystructure's DeviceProfile never declared a VideoRangeType
 *  constraint and so never gave Jellyfin a reason to tone-map). */
data class HdrSupport(val hdr10: Boolean, val hlg: Boolean) {
    companion object {
        /** Conservative default (assume SDR-only) for platforms with no reliable HDR-passthrough
         *  detection — forces a correctly tone-mapped transcode rather than risking a dark picture. */
        val NONE = HdrSupport(hdr10 = false, hlg = false)
    }
}

/** Platform-specific: Android queries the real `Display.HdrCapabilities`; other targets report
 *  [HdrSupport.NONE]. */
expect fun detectHdrSupport(): HdrSupport
