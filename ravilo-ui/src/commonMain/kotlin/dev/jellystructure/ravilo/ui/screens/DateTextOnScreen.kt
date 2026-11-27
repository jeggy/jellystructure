package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.i18n.t
import dev.jellystructure.shared.tv.currentYearUtc

/**
 * DEB-5836 (FR-4) — an ISO date as the viewer's language writes it ([airDateText], DEB-2938). A blank or malformed
 * date keeps today's raw text, so nothing disappears from the screen.
 */
internal fun dateTextOrRaw(iso: String, lang: String, thisYear: Int = currentYearUtc()): String =
    airDateText(iso, lang, thisYear) ?: iso

/** The series' next-airing tail: "Airs Thu 29 Oct". The caller adds the `sonarr.airs` word through the same [lang]. */
internal fun nextAiringText(iso: String, lang: String, thisYear: Int = currentYearUtc()): String =
    t("sonarr.airs", lang) + " " + dateTextOrRaw(iso, lang, thisYear)
