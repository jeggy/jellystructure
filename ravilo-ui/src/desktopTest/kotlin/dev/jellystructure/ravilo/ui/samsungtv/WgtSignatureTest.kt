package dev.jellystructure.ravilo.ui.samsungtv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** DEB-4843 (FR-7, FR-9) — the phone reads the package's distributor certificate as the backend does (DEB-4842 FR-4). The fixture is the v1.70 `.wgt`'s own `signature1.xml`. */
class WgtSignatureTest {
    private val xml = checkNotNull(javaClass.getResourceAsStream("/samsung/signature1.xml")).readBytes().decodeToString()

    @Test fun v170NamesBothTvs() = assertEquals(setOf("EXCKEBBVWCGFC", "XTCJYJZXZBZVK"), WgtSignature.deviceIds(xml))

    @Test fun malformedSignaturesFailClosed() {
        assertNull(WgtSignature.deviceIds("<Signature/>"))
        assertNull(WgtSignature.deviceIds("<X509Certificate>!!!</X509Certificate>"))
        assertNull(WgtSignature.deviceIds("<X509Certificate>AAAA</X509Certificate>"))
    }
}
