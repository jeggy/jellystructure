package dev.jellystructure.ravilo.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** DEB-5851 (FR-4) — Google Play is the installer `com.android.vending`; anything else, or nothing, is a download. */
@RunWith(RobolectricTestRunner::class)
class InstallSourceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** API 30+: the install source the shadow reports. */
    private fun installSourceInfo(installing: String?) {
        shadowOf(context.packageManager).setInstallSourceInfo(context.packageName, null, installing)
    }

    /** Below API 30: a hidden API, absent from the compile stubs and present in the Robolectric framework. */
    private fun installer(name: String?) {
        val pm = context.packageManager
        pm.javaClass.getMethod("setInstallerPackageName", String::class.java, String::class.java).invoke(pm, context.packageName, name)
    }

    @Test @Config(sdk = [34]) fun `API 34 reads the installing package from the install source`() {
        installSourceInfo(PLAY_INSTALLER)
        assertEquals(PLAY_INSTALLER, installerPackage(context))
        assertEquals(InstallSource.GooglePlay, installSourceOf(installerPackage(context)))
    }

    @Test @Config(sdk = [34]) fun `a sideload on API 34 is a download`() {
        installSourceInfo("com.android.packageinstaller")
        assertEquals(InstallSource.Download, installSourceOf(installerPackage(context)))
    }

    @Test @Config(sdk = [24]) fun `API 24 reads getInstallerPackageName`() {
        installer(PLAY_INSTALLER)
        assertEquals(InstallSource.GooglePlay, installSourceOf(installerPackage(context)))
    }

    @Test @Config(sdk = [24]) fun `no installer at all is a download`() {
        installer(null)
        assertNull(installerPackage(context))
        assertEquals(InstallSource.Download, installSourceOf(null))
    }
}
