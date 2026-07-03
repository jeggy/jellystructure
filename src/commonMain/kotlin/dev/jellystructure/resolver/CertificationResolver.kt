package dev.jellystructure.resolver

/** One supported region in the age-rating catalog — display metadata for the Settings cascade editor
 *  and the admin detail trace card. Mirrors `design/app/settings.html`'s `CAT` table exactly. */
data class CertificationRegion(
    val code: String,
    val name: String,
    val system: String,
    val scale: List<String>,
)

/** The fixed catalog of countries an admin can add to the age-rating region cascade. Not fetched from
 *  TMDB — TMDB only supplies the per-title certification for whatever country codes appear in its
 *  `release_dates`/`content_ratings` response; this catalog is display metadata for our own UI. */
object CertificationCatalog {
    val REGIONS: List<CertificationRegion> = listOf(
        CertificationRegion("DK", "Denmark", "Medierådet", listOf("A", "7", "11", "15")),
        CertificationRegion("US", "United States", "MPA", listOf("G", "PG", "PG-13", "R", "NC-17")),
        CertificationRegion("GB", "United Kingdom", "BBFC", listOf("U", "PG", "12", "15", "18")),
        CertificationRegion("DE", "Germany", "FSK", listOf("0", "6", "12", "16", "18")),
        CertificationRegion("SE", "Sweden", "Statens medieråd", listOf("Btl", "7", "11", "15")),
        CertificationRegion("NO", "Norway", "Medietilsynet", listOf("A", "6", "9", "12", "15", "18")),
        CertificationRegion("FR", "France", "CNC", listOf("U", "10", "12", "16", "18")),
        CertificationRegion("NL", "Netherlands", "Kijkwijzer", listOf("AL", "6", "9", "12", "14", "16")),
        CertificationRegion("IE", "Ireland", "IFCO", listOf("G", "PG", "12", "15", "16", "18")),
        CertificationRegion("IS", "Iceland", "SMÁÍS", listOf("L", "7", "9", "12", "16")),
    )
    val BY_CODE: Map<String, CertificationRegion> = REGIONS.associateBy { it.code }
}

/** The resolved age rating for a title: which region's certification won, the code itself, a 0–4
 *  maturity tier (badge colour / range filtering), and whether the cascade missed (last-resort). */
data class Certification(
    val region: String,
    val code: String,
    val tier: Int,
    val fallback: Boolean,
)

/** One row of the region-cascade resolution trace, for the admin detail page's trace card. */
enum class CascadeStepOutcome { USED, SKIPPED, NOT_REACHED }
data class CascadeStep(val region: String, val outcome: CascadeStepOutcome, val certification: Certification? = null)

/**
 * Phase 106 — resolves a title's shown age-rating certification by walking the admin-configured
 * region cascade and taking the first region present in the title's raw per-country TMDB map.
 * Pure/stateless — shared by the backend (item facets, NFO `<mpaa>`, TV DTOs) and the admin FE
 * (pagebar badge, cascade trace card) so both render exactly the same resolution. Never persisted —
 * called fresh against the stored raw map on every read, so re-ordering the cascade in Settings
 * changes what's shown everywhere with no re-scan.
 */
object CertificationResolver {

    // Named codes that aren't purely numeric. Numeric codes fall through to tierForNumber().
    private val NAMED_TIER = mapOf(
        "A" to 0, "U" to 0, "G" to 0, "AL" to 0, "L" to 0, "AA" to 0, "AAA" to 0,
        "TV-Y" to 0, "TV-G" to 0, "BTL" to 0, "NR" to 0,
        "PG" to 1, "TV-PG" to 1,
        "TV-Y7" to 2, "12A" to 2,
        "PG-13" to 3, "TV-14" to 3,
        "R" to 4, "NC-17" to 4, "TV-MA" to 4, "R18" to 4,
    )

    /** Maps a certification code to a 0 (all-ages) .. 4 (adult) maturity tier. Unknown codes → 2 (neutral). */
    fun tierFor(code: String): Int {
        val upper = code.trim().uppercase()
        NAMED_TIER[upper]?.let { return it }
        val n = upper.takeWhile { it.isDigit() }.toIntOrNull()
        if (n != null) return when {
            n <= 5 -> 0
            n <= 8 -> 1
            n <= 12 -> 2
            n <= 15 -> 3
            else -> 4
        }
        return 2
    }

    /** [cascade] is an ordered list of ISO-3166-1 country codes; [certifications] is the title's raw
     *  per-country map (uppercase keys). Returns null when [certifications] is empty, or when no
     *  cascade region has a certification — Phase 119: the cascade is authoritative, no fallback to
     *  a region the admin never configured. */
    fun resolve(cascade: List<String>, certifications: Map<String, String>): Certification? {
        if (certifications.isEmpty()) return null
        for (region in cascade) {
            val code = certifications[region.uppercase()]
            if (!code.isNullOrBlank()) return Certification(region.uppercase(), code, tierFor(code), fallback = false)
        }
        return null
    }

    /** The full cascade trace for the admin detail page: one row per cascade region (used / skipped). */
    fun trace(cascade: List<String>, certifications: Map<String, String>): List<CascadeStep> {
        if (cascade.isEmpty()) return emptyList()
        var used = false
        val steps = cascade.map { region ->
            if (used) return@map CascadeStep(region, CascadeStepOutcome.NOT_REACHED)
            val code = certifications[region.uppercase()]
            if (!code.isNullOrBlank()) {
                used = true
                CascadeStep(region, CascadeStepOutcome.USED, Certification(region.uppercase(), code, tierFor(code), fallback = false))
            } else {
                CascadeStep(region, CascadeStepOutcome.SKIPPED)
            }
        }
        return steps
    }
}
