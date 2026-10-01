package dev.jellystructure.ravilo.ui.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * R338 (FR-R338-4) — the computer's own light/dark, for the theme that follows it.
 *
 * - **macOS:** the system's `AppleInterfaceStyle`, read through the Mac library once a second. Not
 *   `NSApp.effectiveAppearance`: `Main.kt` pins the app to DarkAqua (R328), so the app's own appearance always says
 *   dark (dev review 5). A library without the call (an older build) leaves this unknown.
 * - **Linux:** the Settings portal's `org.freedesktop.appearance color-scheme` ([LinuxPortal]), followed through its
 *   `SettingChanged` signal; `0` (no preference) is light, as GNOME's *Default* style is.
 *
 * Unknown (`null`) is drawn as dark, Ravilo's look before R338.
 */
object DesktopAppearance {
    private val _dark = MutableStateFlow<Boolean?>(null)
    val dark: StateFlow<Boolean?> = _dark.asStateFlow()

    private val _iconStyle = MutableStateFlow<String?>(null)

    /**
     * R342 (FR-R342-3, FR-R342-7) — the Mac's icon style as the Dock icon will draw it (light/dark already folded in),
     * read in the same one-second tick as [dark]. Null off the Mac, or with a library that has no such call.
     */
    val iconStyle: StateFlow<String?> = _iconStyle.asStateFlow()

    init {
        if (DesktopPaths.isMac) {
            _dark.value = macDark()
            _iconStyle.value = macIconStyle()
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                while (isActive) {
                    delay(1_000)
                    macDark()?.let { _dark.value = it }
                    macIconStyle()?.let { _iconStyle.value = it }
                }
            }
        } else {
            // R337/R338 — the Settings portal: read before the first frame, then followed through SettingChanged.
            _dark.value = LinuxPortal.colorSchemeDark()
            LinuxPortal.watchColorScheme { dark -> _dark.value = dark }
        }
    }

    private fun macDark(): Boolean? = runCatching { MacNative.lib?.ravilo_appearance_dark()?.let { it == 1 } }.getOrNull()

    private fun macIconStyle(): String? = runCatching { MacNative.lib?.let { MacNative.take(it.ravilo_dock_style()) } }.getOrNull()


    /** The window's own appearance on the Mac (traffic lights, menus, the About window's title bar). */
    fun applyToWindow(light: Boolean) {
        if (!DesktopPaths.isMac) return
        SwingUtilities.invokeLater {
            (DesktopWindow.awtWindow as? JFrame)?.rootPane?.putClientProperty(
                "apple.awt.windowAppearance", if (light) "NSAppearanceNameAqua" else "NSAppearanceNameDarkAqua",
            )
        }
    }
}
