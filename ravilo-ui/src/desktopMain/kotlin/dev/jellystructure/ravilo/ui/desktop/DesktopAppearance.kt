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
 * - **Linux:** the Settings portal's `org.freedesktop.appearance color-scheme`, pushed in by the D-Bus client
 *   ([set]); `0` (no preference) is light, as GNOME's *Default* style is.
 *
 * Unknown (`null`) is drawn as dark, Ravilo's look before R338.
 */
object DesktopAppearance {
    private val _dark = MutableStateFlow<Boolean?>(null)
    val dark: StateFlow<Boolean?> = _dark.asStateFlow()

    init {
        if (DesktopPaths.isMac) {
            _dark.value = macDark()
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                while (isActive) { delay(1_000); macDark()?.let { _dark.value = it } }
            }
        }
    }

    private fun macDark(): Boolean? = runCatching { MacNative.lib?.ravilo_appearance_dark()?.let { it == 1 } }.getOrNull()

    /** Linux: the portal's answer, from the D-Bus client (R337's). */
    fun set(dark: Boolean?) { _dark.value = dark }

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
