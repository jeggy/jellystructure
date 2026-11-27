package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** DEB-5836, FR-4: next airing, Coming Soon's "Was due" and its detail date read as dates in the viewer's language. */
class DateTextOnScreenTest {
    @Test fun aDateThisYearReadsAsWeekdayDayMonth() = assertEquals("Thu 29 Oct", dateTextOrRaw("2026-10-29", "en", 2026))

    @Test fun aDateInAnotherYearCarriesTheYear() = assertEquals("Oct 29, 2027", dateTextOrRaw("2027-10-29", "en", 2026))

    @Test fun danishIsNotEnglish() {
        val da = dateTextOrRaw("2026-10-29", "da", 2026)
        assertNotEquals("Thu 29 Oct", da)
        assertNotEquals("2026-10-29", da)
    }

    @Test fun aBlankOrMalformedDateKeepsItsRawText() {
        assertEquals("", dateTextOrRaw("", "en", 2026))
        assertEquals("soon", dateTextOrRaw("soon", "en", 2026))
    }

    @Test fun nextAiringJoinsTheAirsWordAndTheDate() = assertEquals("airs Thu 29 Oct", nextAiringText("2026-10-29", "en", 2026))
}
