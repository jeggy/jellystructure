package dev.jellystructure.model

import kotlinx.serialization.Serializable

/*
 * Phase 292 — a song's version: Live, Remix, Instrumental, Edit …
 *
 * One answer per recording (FR-292-2): a song that agrees with its release (or whose recording was chosen by hand)
 * is keyed `rec:<recording mbid>` and shares its answer with every other copy of that recording; any other song is
 * keyed `trk:<track id>` (dev review 4: a `DISAGREES` track keeps its own key until *Match this track…*).
 *
 * The automatic set is computed on read from two facts — what MusicBrainz says about the recording
 * ([MusicRecordingFacts]) and every copy's title ([MusicTitleVersions]) — and the owner's own ticks
 * ([MusicVersionChoice]) sit on top. Only the facts and the ticks are stored. [MusicVersions.of] is the one function
 * every reader calls: the album page, Songs and its facet, Metadata's counts, the Dashboard row, the lyrics step and
 * Ravilo's `versions`.
 */

/** A recording another one is a version of — MusicBrainz's *instrumental version of* / *remix of* target. */
@Serializable
data class MusicVersionTarget(val mbid: String, val title: String = "", val artist: String? = null)

/** Phase 292 (dev review 1–3) — what MusicBrainz says about one recording, read with the release at match time. */
@Serializable
data class MusicRecordingFacts(
    val recordingMbid: String,
    /** A performance relationship carries the attribute. */
    val live: Boolean = false,
    val demo: Boolean = false,
    val cover: Boolean = false,
    /** Every performance of a work with words is marked `instrumental`/`karaoke`, or an *instrumental version of* /
     *  *karaoke version of* link points from this recording. */
    val instrumental: Boolean = false,
    /** Every performance relationship points at a work in `zxx` (no linguistic content): a piece never sung. */
    val noWords: Boolean = false,
    /** A *remix of* link from this recording, or a remixer credit on it. */
    val remix: Boolean = false,
    /** An *edit of* link from this recording. */
    val edit: Boolean = false,
    /** MusicBrainz's own note on the recording (*instrumental demo*, *live*), read by the title finder. */
    val disambiguation: String? = null,
    val instrumentalOf: MusicVersionTarget? = null,
    val remixOf: MusicVersionTarget? = null,
    val fetchedAt: Long = 0,
)

/** The owner's own tick on one type of one recording key: on (adds it) or off (removes it). Kept across every run. */
@Serializable
data class MusicVersionChoice(val type: String, val on: Boolean, val setAt: Long = 0)

/** One of the nine types (fixed in round 1, Q3). [color] and [meaning] are the defaults; the owner can change both. */
@Serializable
data class MusicVersionTypeInfo(
    val key: String,
    val name: String,
    /** The chip's short name (*Alternate* for *Alternate version*). */
    val chip: String,
    val meaning: String,
    val color: String,
    /** The Metadata tab's *Found from* column. */
    val foundFrom: String,
)

object MusicVersions {
    const val LIVE = "live"
    const val DEMO = "demo"
    const val REMIX = "remix"
    const val INSTRUMENTAL = "instrumental"
    const val COVER = "cover"
    const val ACOUSTIC = "acoustic"
    const val EDIT = "edit"
    const val ALTERNATE = "alternate"
    const val SESSION = "session"

    /** The facet's value for a song with no version (*the originals*). */
    const val NONE = "none"

    /** Where a type in the set came from — the panel's *from MusicBrainz* · *from the title* · *set by you* ·
     *  *with Session*. A removed type has no source; it is in [Answer.removed]. */
    const val SRC_MUSICBRAINZ = "musicbrainz"
    const val SRC_TITLE = "title"
    const val SRC_USER = "user"
    const val SRC_SESSION = "session"

    val TYPES: List<MusicVersionTypeInfo> = listOf(
        MusicVersionTypeInfo(LIVE, "Live", "Live", "recorded at a concert — and every Session", "#f0795b", "MusicBrainz · title"),
        MusicVersionTypeInfo(DEMO, "Demo", "Demo", "an early, unfinished recording", "#a3aec6", "MusicBrainz · title"),
        MusicVersionTypeInfo(REMIX, "Remix", "Remix", "someone re-made the recording", "#c67fe3", "MusicBrainz · title"),
        MusicVersionTypeInfo(INSTRUMENTAL, "Instrumental", "Instrumental", "a sung song without the voice", "#3fb6f5", "MusicBrainz · title"),
        MusicVersionTypeInfo(COVER, "Cover", "Cover", "one artist playing another artist's song", "#2dd49a", "MusicBrainz"),
        MusicVersionTypeInfo(ACOUSTIC, "Acoustic", "Acoustic", "played unplugged", "#d8ad62", "title"),
        MusicVersionTypeInfo(EDIT, "Edit", "Edit", "the same recording made shorter or longer", "#9d95f7", "MusicBrainz · title"),
        MusicVersionTypeInfo(ALTERNATE, "Alternate version", "Alternate", "a different take or arrangement", "#e9709f", "title"),
        MusicVersionTypeInfo(SESSION, "Session", "Session", "recorded live in a studio for radio, TV or a website", "#f2a65a", "title"),
    )
    val KEYS: List<String> = TYPES.map { it.key }
    private val ORDER = KEYS.withIndex().associate { (i, k) -> k to i }

    /** The nine-colour palette the Metadata swatch cycles through. */
    val PALETTE: List<String> = TYPES.map { it.color }

    fun info(key: String): MusicVersionTypeInfo? = TYPES.firstOrNull { it.key == key }

    /** FR-292-2 / dev review 4 — the key a song's answer is kept under. */
    fun keyOf(t: MusicTrack): String {
        val mbid = t.recordingMbid
        return if (mbid != null && (t.recordingState == MusicRecording.AGREES || t.recordingState == MusicRecording.MANUAL)) "rec:$mbid" else "trk:${t.id}"
    }

    fun recordingOf(key: String): String? = key.takeIf { it.startsWith("rec:") }?.removePrefix("rec:")

    /**
     * One song's answer.
     * - [shown] — the set as it is shown (chips, the panel, the album summary, Ravilo's `versions`), in the table's order.
     * - [sources] — for each shown type, where it came from (one or more of the `SRC_*` values).
     * - [automatic] — the automatic set before the owner's ticks, with its sources (the panel's *Back to automatic*).
     * - [removed] — types the owner took away.
     * - [noWords] — MusicBrainz says the piece was never sung (FR-292-5); it is *No version* unless the owner ticks.
     */
    data class Answer(
        val key: String,
        val shown: List<String>,
        val sources: Map<String, List<String>>,
        val automatic: Map<String, List<String>>,
        val removed: Set<String>,
        val noWords: Boolean,
        val hasChoices: Boolean,
    ) {
        /** FR-292-4 — what a filter matches: Session counts as Live. Every count that opens a filtered list uses this. */
        fun matches(type: String): Boolean = when (type) {
            NONE -> shown.isEmpty()
            LIVE -> LIVE in shown || SESSION in shown
            else -> type in shown
        }

        /** FR-292-5 (owner, 2026-10-01) — Instrumental from any source, and every piece never sung, gets no lyrics. */
        val blocksLyrics: Boolean get() = noWords || INSTRUMENTAL in shown
    }

    /**
     * FR-292-3/4/5 — the automatic set (MusicBrainz ∪ the titles of every copy, Session ⇒ Live inside it, no
     * automatic Instrumental on a piece never sung), then the owner's ticks on top: `on` adds, `off` removes.
     * [facts] count only for a `rec:` key; [copies] are every song sharing the key (the song itself included).
     */
    fun of(track: MusicTrack, copies: List<MusicTrack>, facts: MusicRecordingFacts?, choices: Map<String, MusicVersionChoice>): Answer {
        val key = keyOf(track)
        val f = facts?.takeIf { key.startsWith("rec:") }
        val auto = LinkedHashMap<String, MutableList<String>>()
        fun add(type: String, src: String) { val l = auto.getOrPut(type) { ArrayList() }; if (src !in l) l += src }
        if (f != null) {
            if (f.live) add(LIVE, SRC_MUSICBRAINZ)
            if (f.demo) add(DEMO, SRC_MUSICBRAINZ)
            if (f.cover) add(COVER, SRC_MUSICBRAINZ)
            if (f.instrumental) add(INSTRUMENTAL, SRC_MUSICBRAINZ)
            if (f.remix) add(REMIX, SRC_MUSICBRAINZ)
            if (f.edit) add(EDIT, SRC_MUSICBRAINZ)
            // Dev review 2 — MusicBrainz's own note is read by the title finder, and counts as MusicBrainz.
            MusicTitleVersions.findInNote(f.disambiguation).forEach { add(it, SRC_MUSICBRAINZ) }
        }
        val titles = (copies.ifEmpty { listOf(track) }.map { it.title } + track.title).distinct()
        for (title in titles) MusicTitleVersions.find(title).forEach { add(it, SRC_TITLE) }
        val noWords = f?.noWords == true
        // FR-292-5 — a piece never sung is not Instrumental, whatever its title says.
        if (noWords) auto.remove(INSTRUMENTAL)
        // FR-292-4 (owner, 2026-10-01) — Session ⇒ Live is a rule of the automatic set only.
        if (SESSION in auto && LIVE !in auto) add(LIVE, SRC_SESSION)

        val result = LinkedHashMap<String, List<String>>()
        auto.forEach { (k, v) -> result[k] = v.toList() }
        val removed = HashSet<String>()
        for ((type, c) in choices) {
            if (type !in ORDER) continue
            if (c.on) result[type] = listOf(SRC_USER) else { result.remove(type); removed += type }
        }
        val shown = result.keys.sortedBy { ORDER[it] ?: 99 }
        return Answer(
            key = key, shown = shown, sources = shown.associateWith { result[it].orEmpty() },
            automatic = auto.mapValues { it.value.toList() }, removed = removed, noWords = noWords,
            hasChoices = choices.keys.any { it in ORDER },
        )
    }

    /** FR-292-9 — the album header's phrase, from the shown sets: the most common type held by at least half the songs. */
    fun albumSummary(answers: List<Answer>): String? {
        val n = answers.size
        if (n == 0) return null
        val best = KEYS.map { k -> k to answers.count { k in it.shown } }.filter { it.second * 2 >= n && it.second > 0 }.maxByOrNull { it.second } ?: return null
        val name = info(best.first)?.name ?: return null
        return if (best.second == n) "$name · all $n songs" else "$name · ${best.second} of $n songs"
    }
}

/**
 * Phase 292 (FR-292-3, dev review 15) — the version a title says it is: case-insensitive whole words, only inside
 * brackets or after a dash (*Song (live at the harbour, 2011)*, *Song - Radio Edit*). The list lives here and only
 * here, so it can be tuned without a schema change. *Original mix*, *album version* and *remaster(ed)* are not Remix
 * or Edit; *session* alone names a Session (open question 2).
 */
object MusicTitleVersions {
    private val BRACKET = Regex("\\(([^)]*)\\)|\\[([^]]*)]")
    private val DASH = Regex("\\s[-–—]\\s(.+)$")
    private val RULES: List<Pair<String, Regex>> = listOf(
        MusicVersions.LIVE to Regex("\\blive\\b"),
        MusicVersions.DEMO to Regex("\\bdemos?\\b"),
        MusicVersions.REMIX to Regex("\\bremix(es|ed)?\\b|\\breinterpretation\\b|\\brmx\\b|(?:^|\\s)(?!original |album |stereo |mono )[^\\s]+ mix\\b"),
        MusicVersions.INSTRUMENTAL to Regex("\\binstrumental\\b|\\bkaraoke\\b"),
        MusicVersions.ACOUSTIC to Regex("\\bacoustic\\b|\\bunplugged\\b|\\bakustisk\\b"),
        MusicVersions.EDIT to Regex("\\bedit\\b|\\bextended\\b"),
        MusicVersions.ALTERNATE to Regex("\\balternate\\b|\\balternative (version|take|mix)\\b|\\balt\\.? take\\b"),
        MusicVersions.SESSION to Regex("\\bsessions?\\b"),
    )

    /** The parts of a title that may name a version: each bracket's content and what follows a spaced dash. */
    fun segments(title: String?): List<String> {
        val t = title?.trim().orEmpty()
        if (t.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (m in BRACKET.findAll(t)) out += (m.groupValues[1].ifEmpty { m.groupValues[2] })
        DASH.find(BRACKET.replace(t, " "))?.let { out += it.groupValues[1] }
        return out.map { it.lowercase().trim() }.filter { it.isNotEmpty() }
    }

    fun find(title: String?): Set<String> = match(segments(title))

    /** MusicBrainz's disambiguation is a note, not a title: the whole note is read (*instrumental demo*). */
    fun findInNote(note: String?): Set<String> = note?.lowercase()?.trim()?.takeIf { it.isNotEmpty() }?.let { match(listOf(it)) } ?: emptySet()

    private fun match(parts: List<String>): Set<String> {
        val out = LinkedHashSet<String>()
        for (p in parts) for ((type, re) in RULES) if (re.containsMatchIn(p)) out += type
        return out
    }
}
