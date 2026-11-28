package dev.jellystructure.ravilo.ui.seams

import android.content.Context
import android.view.accessibility.AccessibilityManager
import dev.jellystructure.ravilo.ui.RaviloAppContext

/** DEB-5838 (FR-5) — TalkBack and the other touch-exploration readers. Read per call: the setting can change mid-film. */
actual fun screenReaderOn(): Boolean = runCatching {
    (RaviloAppContext.get().getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).isTouchExplorationEnabled
}.getOrDefault(false)
