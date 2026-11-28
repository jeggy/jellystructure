package dev.jellystructure.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** DEB-5531 (FR-279-12) — a device's line figure: 0.8 × the last `observed=`, 30 minutes, same address. */
class SongLineFigureTest {
    private val detail = "phase=start pos=0 buffered=0 observed=1420000 why=slow"
    private val min = 60_000L

    @Test fun a_fresh_figure_from_the_same_address_caps_at_eighty_percent() {
        val f = SongLineFigure()
        f.record("d1", "10.0.0.1", detail, 0)
        val cap = f.capFor("d1", "10.0.0.1", min)
        assertEquals(1_136_000L, cap)
        // A 1.34 Mbps file is above the cap (lighter copy); a 320 kbps one fits (direct play).
        assertEquals(true, 1_340_000L > cap!!)
        assertEquals(true, 320_000L <= cap)
    }

    @Test fun a_figure_31_minutes_old_is_ignored() {
        val f = SongLineFigure()
        f.record("d1", "10.0.0.1", detail, 0)
        assertNull(f.capFor("d1", "10.0.0.1", 31 * min))
    }

    @Test fun another_address_gets_no_figure() {
        val f = SongLineFigure()
        f.record("d1", "10.0.0.1", detail, 0)
        assertNull(f.capFor("d1", "10.0.0.2", min))
    }

    @Test fun a_detail_without_observed_records_nothing() {
        val f = SongLineFigure()
        f.record("d1", "10.0.0.1", "phase=start pos=0 buffered=0", 0)
        assertNull(f.capFor("d1", "10.0.0.1", min))
    }
}
