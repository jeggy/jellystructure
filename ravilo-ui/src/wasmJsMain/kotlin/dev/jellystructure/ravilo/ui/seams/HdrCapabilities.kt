package dev.jellystructure.ravilo.ui.seams

/** No reliable browser HDR-passthrough detection today — stay conservative (forces Jellyfin to
 *  tone-map HDR sources to SDR, which is correct for a plain `<video>` element anyway). */
actual fun detectHdrSupport(): HdrSupport = HdrSupport.NONE
