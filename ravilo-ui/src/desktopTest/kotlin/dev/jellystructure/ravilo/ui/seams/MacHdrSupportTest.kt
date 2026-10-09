package dev.jellystructure.ravilo.ui.seams

import kotlin.test.Test
import kotlin.test.assertEquals

/** R329 (found live 2026-10-09, M5 Mac) — a Mac that decodes HEVC Main 10 claims HDR, so 313 serves its HEVC HDR rungs. */
class MacHdrSupportTest {
    @Test fun `a Mac that decodes HEVC Main 10 on an HDR-eligible screen claims HDR10 and HLG`() {
        assertEquals(HdrSupport(hdr10 = true, hlg = true), macHdrSupport(mpv = false, hevc = true, main10 = true, eligible = true, optOut = false))
    }

    @Test fun `no Main 10, no HEVC, or the measurement switch claims nothing`() {
        assertEquals(HdrSupport.NONE, macHdrSupport(mpv = false, hevc = true, main10 = false, eligible = true, optOut = false))
        assertEquals(HdrSupport.NONE, macHdrSupport(mpv = false, hevc = false, main10 = true, eligible = true, optOut = false))
        assertEquals(HdrSupport.NONE, macHdrSupport(mpv = false, hevc = true, main10 = true, eligible = true, optOut = true))
    }

    /** Found live 2026-10-09 (evening): on an SDR monitor AVPlayer drops every HDR variant, so an HDR-only master fails. */
    @Test fun `a screen AVPlayer will not play HDR on claims nothing`() {
        assertEquals(HdrSupport.NONE, macHdrSupport(mpv = false, hevc = true, main10 = true, eligible = false, optOut = false))
    }

    @Test fun `mpv on Linux keeps tone-mapping to the window itself`() {
        assertEquals(HdrSupport(hdr10 = true, hlg = true), macHdrSupport(mpv = true, hevc = false, main10 = false, eligible = false, optOut = false))
    }
}
