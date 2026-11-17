package dev.jellystructure.samsung

import dev.jellystructure.io.FileIo
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WgtCertificateTest {
    @Test fun publicV170PackageCarriesBothApprovedDevices() {
        val bytes = FileIo.readBytes(Path("src/linuxX64Test/resources/samsung/ravilo-tizen-v1.70.wgt"))
        assertEquals(setOf("EXCKEBBVWCGFC", "XTCJYJZXZBZVK"), WgtCertificate.deviceIds(bytes))
    }

    @Test fun malformedPackageCannotMakeARequestReady() {
        assertNull(WgtCertificate.deviceIds("URN:tizen:deviceid=EXCKEBBVWCGFC".encodeToByteArray()))
    }
}
