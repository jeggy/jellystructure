package dev.jellystructure.shared.tv

/**
 * Single source of truth for all channel-button visual constants (R66, FR-RV-CB1).
 * Referenced by both the Ravilo TV Compose renderer (ChannelCard.kt) and the
 * Jellystructure admin wasmJs config editor (RaviloConfig.kt).
 *
 * All sizes are in dp; ratios are used by the admin to scale the preview correctly.
 */
object ChannelButtonSpec {
    // Layout — canonical TV size
    const val WIDTH_DP   = 224
    const val HEIGHT_DP  = 94
    val ASPECT_RATIO: Float get() = WIDTH_DP.toFloat() / HEIGHT_DP.toFloat()

    // Shape
    const val CORNER_DP = 13
    val CORNER_RATIO: Float get() = CORNER_DP.toFloat() / WIDTH_DP.toFloat()

    // Focus ring & glow
    const val FOCUS_SCALE     = 1.08f
    const val RING_WIDTH_DP   = 3
    const val GLOW_ELEV_DP    = 22

    // Brand fill defaults
    const val SOLID_WASH_ALPHA = 0.28f   // accent@0.28 when no custom gradient
    const val SHEEN_ALPHA      = 0.08f   // top-start white sheen

    // Watermark (drawn behind logo)
    const val WATERMARK_SIZE_SP  = 29
    const val WATERMARK_ALPHA    = 0.06f
    const val WATERMARK_PAD_START_DP = 12
    const val WATERMARK_PAD_BOT_DP   = 8
    const val WATERMARK_TAKE_CHARS   = 8

    // Text fallback (no logo)
    const val TEXT_SIZE_SP     = 16
    const val TEXT_PAD_DP      = 12

    // Logo fit policy (CSS side: "contain"; Compose side: ContentScale.Fit)
    const val LOGO_FIT_CSS = "contain"
}
