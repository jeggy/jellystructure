package dev.jellystructure.media

/**
 * Phase 232 (FR-232-1) — which ink a studio/network logo is drawn in, from a small RGBA raster.
 *
 * Ravilo draws captured logos on a LIGHT plate (R257: 88 of the 135 logos in production are dark ink
 * on transparency — TMDB draws them for a light page). A genuinely light-ink logo (Channel 4's white
 * mark) vanishes there, so the server says which is which, once, at rest.
 *
 * `"light"` when the alpha-weighted mean luminance of the visible pixels is above [LIGHT_ABOVE];
 * `"dark"` otherwise — including any image that is essentially opaque (> [OPAQUE_FRACTION] of its
 * pixels): a logo on its own white or coloured rectangle brings its own ground and sits correctly on
 * the light plate whatever its mean. `null` when nothing is visible at all (or the buffer is short).
 */
const val LOGO_INK_LIGHT = "light"
const val LOGO_INK_DARK = "dark"
private const val LIGHT_ABOVE = 0.6
private const val OPAQUE_FRACTION = 0.90

fun logoInkOf(rgba: ByteArray, width: Int, height: Int): String? {
    val pixels = width * height
    if (pixels <= 0 || rgba.size < pixels * 4) return null
    var weighted = 0.0
    var alphaSum = 0.0
    var opaque = 0
    for (i in 0 until pixels) {
        val o = i * 4
        val a = rgba[o + 3].toInt() and 0xFF
        if (a > 240) opaque++
        if (a <= 16) continue
        val r = rgba[o].toInt() and 0xFF
        val g = rgba[o + 1].toInt() and 0xFF
        val b = rgba[o + 2].toInt() and 0xFF
        weighted += (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0 * a
        alphaSum += a
    }
    if (alphaSum == 0.0) return null
    if (opaque.toDouble() / pixels > OPAQUE_FRACTION) return LOGO_INK_DARK
    return if (weighted / alphaSum > LIGHT_ABOVE) LOGO_INK_LIGHT else LOGO_INK_DARK
}
