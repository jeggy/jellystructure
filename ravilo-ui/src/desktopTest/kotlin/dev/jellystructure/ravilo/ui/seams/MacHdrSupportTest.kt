package dev.jellystructure.ravilo.ui.seams

import kotlin.test.Test
import kotlin.test.assertEquals

/** R329 (found live 2026-10-09, M5 Mac) — a Mac that decodes HEVC Main 10 claims HDR, so 313 serves its HEVC HDR rungs. */
class MacHdrSupportTest {
    @Test fun `a Mac that decodes HEVC Main 10 claims HDR10 and HLG`() {
        assertEquals(HdrSupport(hdr10 = true, hlg = true), macHdrSupport(mpv = false, hevc = true, main10 = true, optOut = false))
    }

    @Test fun `no Main 10, no HEVC, or the measurement switch claims nothing`() {
        assertEquals(HdrSupport.NONE, macHdrSupport(mpv = false, hevc = true, main10 = false, optOut = false))
        assertEquals(HdrSupport.NONE, macHdrSupport(mpv = false, hevc = false, main10 = true, optOut = false))
        assertEquals(HdrSupport.NONE, macHdrSupport(mpv = false, hevc = true, main10 = true, optOut = true))
    }

    @Test fun `mpv on Linux keeps tone-mapping to the window itself`() {
        assertEquals(HdrSupport(hdr10 = true, hlg = true), macHdrSupport(mpv = true, hevc = false, main10 = false, optOut = false))
    }
}
