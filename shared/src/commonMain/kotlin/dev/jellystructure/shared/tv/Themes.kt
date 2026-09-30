package dev.jellystructure.shared.tv

/**
 * R338 — Ravilo's themes, by id.
 *
 * Themes travel as **strings** (`theme`, `theme_follow`, `theme_light`, `theme_dark` on the viewer's settings), never as
 * new [Skin] values: every installed app back to the wire floor decodes [Skin] strictly, so a new value there would fail
 * `/api/tv/config` on the older ones (dev review 1). [Skin] stays the three-value enum and the server writes it as a
 * mirror of the theme for those apps ([legacySkin]). An app that meets an id it does not know uses that mirror.
 */
object RaviloThemes {
    const val AURORA = "aurora"
    const val MIDNIGHT = "midnight"
    const val NOIR = "noir"
    const val GRAPHITE = "graphite"
    const val DAYLIGHT = "daylight"

    /** Every theme, in the order a picker lists them. The list is built to grow. */
    val all: List<String> = listOf(AURORA, MIDNIGHT, NOIR, GRAPHITE, DAYLIGHT)
    private val light: Set<String> = setOf(DAYLIGHT)

    fun isKnown(id: String?): Boolean = id != null && id in all
    fun isLight(id: String?): Boolean = id != null && id in light
    fun isDark(id: String?): Boolean = isKnown(id) && !isLight(id)
    val darkThemes: List<String> get() = all.filter { isDark(it) }
    val lightThemes: List<String> get() = all.filter { isLight(it) }

    /** The id of one of the three original skins. */
    fun fromSkin(skin: Skin): String = when (skin) {
        Skin.AURORA -> AURORA
        Skin.MIDNIGHT -> MIDNIGHT
        Skin.NOIR -> NOIR
    }

    /**
     * The three-value skin an app from before R338 draws for [id]: the theme itself when it is one of the three;
     * Graphite → Midnight (the nearest dark one); Daylight → the mirror of [darkFallback] (an older app was dark-only).
     */
    fun legacySkin(id: String?, darkFallback: String? = null): Skin = when (id) {
        AURORA -> Skin.AURORA
        MIDNIGHT -> Skin.MIDNIGHT
        NOIR -> Skin.NOIR
        GRAPHITE -> Skin.MIDNIGHT
        DAYLIGHT -> legacySkin(darkFallback?.takeIf { isDark(it) } ?: AURORA)
        else -> Skin.AURORA
    }
}

/**
 * R338 (FR-R338-4) — the theme a device shows. [deviceDark] is the device's own appearance; **null means the device has
 * none** (a TV, a receiver, the web app in a desktop browser — D6), and such a device always shows the dark pick. Ids
 * that are not known fall back so the answer is always drawable: a bad dark pick → Aurora, a bad light pick → Daylight.
 */
fun resolveTheme(follow: Boolean, light: String?, dark: String?, single: String?, deviceDark: Boolean?): String {
    val darkId = dark?.takeIf { RaviloThemes.isDark(it) } ?: RaviloThemes.AURORA
    val lightId = light?.takeIf { RaviloThemes.isLight(it) } ?: RaviloThemes.DAYLIGHT
    return when {
        deviceDark == null -> darkId
        follow -> if (deviceDark) darkId else lightId
        else -> single?.takeIf { RaviloThemes.isKnown(it) } ?: darkId
    }
}
