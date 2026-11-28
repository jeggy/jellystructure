package dev.jellystructure.ravilo.ui.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-5838 (FR-7) — Linux reports reduced motion from GNOME's `enable-animations` and KDE's `AnimationDurationFactor`. */
class LinuxReducedMotionTest {
    @Test fun `GNOME animations off is reduced, on is not, no answer is not`() {
        assertTrue(LinuxReducedMotion.gnomeReduced("<false>"))
        assertTrue(LinuxReducedMotion.gnomeReduced("(<<false>>,)"))
        assertFalse(LinuxReducedMotion.gnomeReduced("<true>"))
        assertFalse(LinuxReducedMotion.gnomeReduced(null))
    }

    @Test fun `KDE duration factor 0 is reduced, 1_0 is not, no answer is not`() {
        assertTrue(LinuxReducedMotion.kdeReduced("<0.0>"))
        assertTrue(LinuxReducedMotion.kdeReduced("<0>"))
        assertTrue(LinuxReducedMotion.kdeReduced("<'0'>"))
        assertFalse(LinuxReducedMotion.kdeReduced("<1.0>"))
        assertFalse(LinuxReducedMotion.kdeReduced("<0.5>"))
        assertFalse(LinuxReducedMotion.kdeReduced(null))
    }

    @Test fun `either portal setting is enough and no portal means motion`() {
        val asked = mutableListOf<String>()
        assertTrue(LinuxReducedMotion.reduced { ns, k -> asked += "$ns/$k"; if (ns.startsWith("org.kde")) "<0.0>" else null })
        assertEquals(listOf("org.gnome.desktop.interface/enable-animations", "org.kde.kdeglobals.KDE/AnimationDurationFactor"), asked)
        assertFalse(LinuxReducedMotion.reduced { _, _ -> null })
    }
}
