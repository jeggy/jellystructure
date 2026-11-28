package dev.jellystructure.ravilo.ui.seams

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.test.core.app.ApplicationProvider
import dev.jellystructure.ravilo.ui.RaviloAppContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** DEB-5838 (FR-5) — `screenReaderOn()` follows Android's touch exploration, read on each call. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenReaderSeamTest {
    @Test fun `it is false by default and follows touch exploration both ways`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        RaviloAppContext.init(ctx)
        val shadow = shadowOf(ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
        assertFalse(screenReaderOn())
        shadow.setTouchExplorationEnabled(true)
        assertTrue(screenReaderOn())
        shadow.setTouchExplorationEnabled(false)
        assertFalse(screenReaderOn())
    }
}
