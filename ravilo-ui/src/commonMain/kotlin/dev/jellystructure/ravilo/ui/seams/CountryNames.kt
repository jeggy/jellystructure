package dev.jellystructure.ravilo.ui.seams

/**
 * R325 (FR-R325-1) — the About section's *Country* row names an ISO 3166-1 alpha-2 code. Compose has no locale
 * API in common code, so this is one small table in English (the ISO short names, the way TMDB prints them),
 * covering the countries a European film and series library credits; anything else shows its code, never
 * *Unknown*. A translation of the table is a later, i18n-side job.
 */
fun countryName(code: String?): String? {
    val c = code?.trim()?.uppercase()?.takeIf { it.length == 2 } ?: return null
    return COUNTRY_TABLE[c]
}

internal val COUNTRY_TABLE: Map<String, String> = mapOf(
    "AR" to "Argentina", "AT" to "Austria", "AU" to "Australia", "BE" to "Belgium", "BR" to "Brazil", "CA" to "Canada",
    "CH" to "Switzerland", "CL" to "Chile", "CN" to "China", "CO" to "Colombia", "CZ" to "Czechia", "DE" to "Germany",
    "DK" to "Denmark", "EE" to "Estonia", "EG" to "Egypt", "ES" to "Spain", "FI" to "Finland", "FO" to "Faroe Islands",
    "FR" to "France", "GB" to "United Kingdom", "GL" to "Greenland", "GR" to "Greece", "HK" to "Hong Kong",
    "HU" to "Hungary", "ID" to "Indonesia", "IE" to "Ireland", "IL" to "Israel", "IN" to "India", "IR" to "Iran",
    "IS" to "Iceland", "IT" to "Italy", "JP" to "Japan", "KR" to "South Korea", "LT" to "Lithuania", "LV" to "Latvia",
    "MX" to "Mexico", "NG" to "Nigeria", "NL" to "Netherlands", "NO" to "Norway", "NZ" to "New Zealand", "PH" to "Philippines",
    "PL" to "Poland", "PT" to "Portugal", "RO" to "Romania", "RU" to "Russia", "SE" to "Sweden", "SG" to "Singapore",
    "TH" to "Thailand", "TR" to "Türkiye", "TW" to "Taiwan", "UA" to "Ukraine", "US" to "United States", "ZA" to "South Africa",
)
