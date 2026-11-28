package dev.jellystructure.ravilo.ui.desktop

/**
 * DEB-5838 (FR-7) — Linux's "reduce motion", read from the Settings portal as GNOME and KDE spell it. Pure parsers over
 * the GVariant text [LinuxPortal.readSetting] returns; no answer (null) means motion is allowed.
 */
internal object LinuxReducedMotion {
    /** GNOME `org.gnome.desktop.interface` `enable-animations`: `<false>` (or `(<<false>>,)` on an older portal) ⇒ reduced. */
    fun gnomeReduced(text: String?): Boolean = text != null && Regex("""\bfalse\b""").containsMatchIn(text)

    /** KDE `org.kde.kdeglobals.KDE` `AnimationDurationFactor`: `0`, `0.0` or `'0'` ⇒ reduced; `1.0` and the rest ⇒ not. */
    fun kdeReduced(text: String?): Boolean {
        val n = text?.let { Regex("""-?\d+(\.\d+)?""").find(it)?.value?.toDoubleOrNull() } ?: return false
        return n == 0.0
    }

    /** Reads both settings through [read] (the portal's `readSetting`); either one saying so is enough. */
    fun reduced(read: (namespace: String, key: String) -> String?): Boolean =
        gnomeReduced(read("org.gnome.desktop.interface", "enable-animations")) ||
            kdeReduced(read("org.kde.kdeglobals.KDE", "AnimationDurationFactor"))
}
