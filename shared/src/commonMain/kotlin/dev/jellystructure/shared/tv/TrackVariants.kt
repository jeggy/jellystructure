package dev.jellystructure.shared.tv

/**
 * R291 (FR-R291-1, dev review item 1) — R195's same-language versioning, moved here from `PlayerScreen`
 * so the SERVER resolves the viewer's remembered audio choice against the file's track list with the
 * exact code the picker groups and signs with. `PlaybackService.buildAudioTracks(itemDetail)` is the
 * list the ticket — and so the picker — carries, so there is no second list to disagree with.
 *
 * Nothing here touches Compose: kinds, the region table (code + name; the flag is `:ravilo-ui`'s),
 * the (kind, region, ordinal) clustering with R195 §5.3's provenance collapse, the opaque signature
 * `"<kind>|<regionCode>|<ordinal>"` (never `:` or `,` — the wasm prefs parser splits on both), and the
 * audio tier of R181's resolver.
 */
enum class VariantKind { PLAIN, SDH, FORCED, DESCRIBE, COMMENTARY }

val SDH_RE = Regex("""\bsdh\b|\bhi\b|hard of hearing|hearing impaired|\bhearing\b|\bcc\b|closed caption""", RegexOption.IGNORE_CASE)
val AD_RE = Regex("""synstolkning|audio description|\bad\b|\bdescribed\b""", RegexOption.IGNORE_CASE)
val COMMENTARY_RE = Regex("""commentary|kommentar|kommentti|commentaire|coment[aá]rio|commento|commentaar""", RegexOption.IGNORE_CASE)

fun variantKind(title: String?, forced: Boolean): VariantKind = when {
    title != null && COMMENTARY_RE.containsMatchIn(title) -> VariantKind.COMMENTARY
    title != null && SDH_RE.containsMatchIn(title) -> VariantKind.SDH
    title != null && AD_RE.containsMatchIn(title) -> VariantKind.DESCRIBE
    forced -> VariantKind.FORCED
    else -> VariantKind.PLAIN
}

/** R195 (FR-RV §5.2) — the region synonym table: a stable code and the region's own name. `\b`-bounded,
 *  first-match-wins. The UI attaches a flag per code where one exists. */
data class RegionCode(val code: String, val name: String)

val REGION_TABLE: List<Pair<Regex, RegionCode>> = listOf(
    Regex("""\bcastilian\b|\bspain\b|\bes[- ]es\b""", RegexOption.IGNORE_CASE) to RegionCode("es", "España"),
    Regex("""\blatin american?\b|\bes[- ]419\b""", RegexOption.IGNORE_CASE) to RegionCode("419", "Latinoamérica"),
    Regex("""\bbrazil(ian)?\b|\bpt[- ]br\b""", RegexOption.IGNORE_CASE) to RegionCode("br", "Brasil"),
    Regex("""\bportugal\b|\biberian\b|\bpt[- ]pt\b""", RegexOption.IGNORE_CASE) to RegionCode("pt", "Portugal"),
    Regex("""\bsimplified\b|\bzh[- ]hans\b|\bzh[- ]cn\b""", RegexOption.IGNORE_CASE) to RegionCode("cn", "简体"),
    Regex("""\btraditional\b|\bzh[- ]hant\b|\bzh[- ]tw\b""", RegexOption.IGNORE_CASE) to RegionCode("tw", "繁體"),
    Regex("""\bcanad(a|ian)\b""", RegexOption.IGNORE_CASE) to RegionCode("ca", "Canada"),
    Regex("""\beuropean\b""", RegexOption.IGNORE_CASE) to RegionCode("eu", "European"),
)

fun regionOf(title: String?): RegionCode? {
    if (title.isNullOrBlank()) return null
    for ((re, info) in REGION_TABLE) if (re.containsMatchIn(title)) return info
    return null
}

/** R195 (FR-RV §5.3) — release-provenance tokens, used ONLY to collapse a duplicate that differs by release plumbing. */
val PROVENANCE_RE = Regex("""\b(bluray|blu-ray|web-?dl|webrip|itunes|amzn|netflix|hdtv|remux|dvdrip)\b""", RegexOption.IGNORE_CASE)
fun hasProvenanceMarker(title: String?): Boolean = title != null && PROVENANCE_RE.containsMatchIn(title)

/** One track as the grouping sees it; index-aligned with the caller's own list. */
data class VersionInput(val language: String?, val title: String?, val forced: Boolean, val isDefault: Boolean)

/** One selectable version within a language: [flatIndex] into the caller's list; [ordinal] within its
 *  (kind, region) cluster; [clusterSize] that cluster's size after the provenance collapse. */
data class VersionCore(
    val flatIndex: Int,
    val kind: VariantKind,
    val region: RegionCode?,
    val ordinal: Int,
    val clusterSize: Int,
    val hadTitleText: Boolean,
    val forced: Boolean,
    val isDefault: Boolean,
)

data class VersionGroup(val language: String?, val versions: List<VersionCore>)

/** R195 (FR-RV §5.4) — the opaque remembered-variant signature. */
fun variantSignature(kind: VariantKind, regionCode: String?, ordinal: Int): String = "${kind.name.lowercase()}|${regionCode ?: ""}|$ordinal"
fun VersionCore.signature(): String = variantSignature(kind, region?.code, ordinal)

/**
 * R195 §3 — one group per language (canonical key, in first-track order), its versions clustered by
 * (kind, region), numbered within the cluster, a provenance-only duplicate collapsed to its first
 * member, and re-flattened in stream order. Exactly what the picker draws and what the signature is
 * built over, on both sides of the wire.
 */
fun groupVersions(entries: List<VersionInput>): List<VersionGroup> {
    val byLanguage = entries.withIndex().groupBy { (_, e) -> canonicalLanguage(e.language) }
    return byLanguage.entries.sortedBy { (_, indexed) -> indexed.first().index }.map { (_, indexed) ->
        val language = indexed.first().value.language
        val withMeta = indexed.map { (flatIdx, e) -> Triple(flatIdx, e, variantKind(e.title, e.forced) to regionOf(e.title)) }
        val versions = withMeta.groupBy { it.third }.values.flatMap { cluster ->
            val anyProvenance = cluster.any { hasProvenanceMarker(it.second.title) }
            val allHaveTitles = cluster.all { !it.second.title.isNullOrBlank() }
            val effective = if (cluster.size > 1 && anyProvenance && allHaveTitles) listOf(cluster.first()) else cluster
            effective.mapIndexed { ordinal, (flatIdx, e, kindRegion) ->
                VersionCore(flatIdx, kindRegion.first, kindRegion.second, ordinal, effective.size, !e.title.isNullOrBlank(), e.forced, e.isDefault)
            }
        }.sortedBy { it.flatIndex }
        VersionGroup(language, versions)
    }
}

/**
 * R181's audio tier, as the server runs it (FR-R291-1): the remembered `{language, variant}` against
 * [tracks] — the exact signature first, else the language's first PLAIN version, else its first version;
 * null when the language is not in this file (the next tier, Jellyfin's own default, then decides).
 * Returns an index INTO [tracks], never a Jellyfin stream index — the caller maps it.
 */
fun resolveAudioChoice(audioLanguage: String?, audioVariant: String?, tracks: List<VersionInput>): Int? {
    val lang = audioLanguage ?: return null
    val group = groupVersions(tracks).firstOrNull { sameLanguage(it.language, lang) } ?: return null
    val bySignature = audioVariant?.let { sig -> group.versions.firstOrNull { it.signature() == sig } }
    return (bySignature ?: group.versions.firstOrNull { it.kind == VariantKind.PLAIN } ?: group.versions.firstOrNull())?.flatIndex
}
