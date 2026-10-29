package dev.jellystructure.media

import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ProcessingFeature
import dev.jellystructure.config.ProcessingFeatures
import kotlinx.serialization.Serializable

// DEB-1664 (FR-5, FR-6, FR-9.1) — the Scan dialog's model and what a manual scan runs. The dialog draws what `GET /api/processing/scan`
// answers: every name, sentence and reason is built here (FR-12.1). The one number the page composes is *n of m*, because it moves with
// every tick a person makes. [ScanDialogs.selection] is the other half: it reads the unticked ids the dialog sends back against the same
// model, so what the toast, the history line and the run call "skipped" is exactly what could have run and was unticked.

/** One part in the right pane. [id] is the part's step id, which is also its id on the wire (FR-13.1: it never reaches a screen). */
@Serializable
data class ScanPartView(
    val id: String,
    val name: String,
    val queuesWork: Boolean = false,
    val canRun: Boolean = true,
    /** Why it cannot be ticked; null while it can. */
    val reason: String? = null,
    /** True when the reason is the part's own switch on Settings ▸ Processing, so the dialog links to that row. */
    val settingsLink: Boolean = false,
)

/** One feature: a row on the left, and the pane on the right when it is chosen. */
@Serializable
data class ScanFeatureView(
    val id: String,
    val name: String,
    /** The *queues work* marker on its left row: every part of it queues work (FR-5.2). */
    val queuesWork: Boolean = false,
    /** Find new & changed: ticked, disabled, *always* (FR-5.3). */
    val locked: Boolean = false,
    /** False for a feature that is not in the library a scoped scan was opened for (FR-5.6). */
    val applies: Boolean = true,
    /** False while nothing in it can be ticked: off in Settings, nothing mapped, not in this library. */
    val canRun: Boolean = true,
    /** Set when the feature is a single tick (no parts to drill into): the id its untick is remembered by. */
    val tickId: String? = null,
    val lede: String = "",
    /** Replaces *n of m* on the left row: *always*, *Off in Settings*, *Not in this library*. */
    val short: String? = null,
    /** The pane's note while the feature cannot run: a bold lead, the sentence, and whether it ends on a link to Settings ▸ Processing. */
    val reasonHead: String? = null,
    val reason: String? = null,
    val settingsLink: Boolean = false,
    val parts: List<ScanPartView> = emptyList(),
)

@Serializable
data class ScanDialogView(
    val title: String,
    val lede: String,
    /** Phone width: the first screen is the list, and says to tap one (FR-5.9). */
    val ledePhone: String,
    /** The library a scoped scan was opened for (FR-5.6); null for a scan of everything. */
    val scopeName: String? = null,
    val scopeLibraryId: String? = null,
    val recheckOff: String,
    val recheckOn: String,
    /** What *Re-check everything* reads again, in numbers; shown only while the switch is on. */
    val recheckNote: String,
    /** FR-5.8 — what *queues work* means, said once. The dialog follows it with a link to Activity. */
    val legendHead: String,
    val legend: String,
    val features: List<ScanFeatureView>,
)

/** `POST /api/processing/scan` (FR-9.1): [skip] is part ids or a feature id (FR-9.2's ids, mapped), [recheckAll] lifts the due rule for this run.
 *  DEB-2826 (DEB-2823, FR-4.5) — [ai] is the part ids whose AI step is asked for (default none): `build_recommendations` re-ranks every viewer switched on, `build_suggestions` asks for AI groups. */
@Serializable
data class ScanRunRequest(val library: String? = null, val skip: List<String> = emptyList(), val recheckAll: Boolean = false, val ai: List<String> = emptyList())

@Serializable
data class ScanRunStarted(
    val status: String,
    val jobId: String,
    val library: String = "all",
    /** The product names of what was unticked and could have run (FR-6.4). */
    val skipped: List<String> = emptyList(),
    val skippedText: String = "",
    /** The toast's second line while something was skipped: it stays unticked until it is ticked again (FR-5.7). */
    val skippedNote: String = "",
)

/** What a scoped scan was opened for: the library, and which features apply to it (FR-5.6). */
class ScanScope(val libraryId: String, val name: String, collectionType: String) {
    private val kind = when (collectionType.lowercase()) { "music" -> "music"; "books" -> "books"; else -> "video" }

    fun applies(featureId: String): Boolean = when (featureId) {
        ProcessingFeatures.FIND -> true
        ProcessingFeatures.MUSIC -> kind == "music"
        ProcessingFeatures.AUDIOBOOKS -> kind == "books"
        ProcessingFeatures.SUGGESTIONS -> false
        else -> kind == "video"
    }

    /** The sentence under the title: what this library is and what is therefore not here. */
    fun lede(counts: ScanCounts): String = when (kind) {
        "music" -> "$name only. Everything that reads films and series is not in this library, so it cannot be ticked."
        "books" -> "$name only. Everything that reads films and series is not in this library, so it cannot be ticked."
        else -> "$name only${counts.words().let { if (it.isEmpty()) "" else " — $it" }}. Recommendations, music and audiobooks are not in this library, so they cannot be ticked."
    }
}

/** How many titles a *Re-check everything* run reads again. */
class ScanCounts(val films: Int = 0, val series: Int = 0, val episodes: Int = 0) {
    fun words(): String {
        val parts = listOfNotNull(
            films.takeIf { it > 0 }?.let { "${group(it)} ${if (it == 1) "film" else "films"}" },
            series.takeIf { it > 0 }?.let { "${group(it)} series" },
            episodes.takeIf { it > 0 }?.let { "${group(it)} ${if (it == 1) "episode" else "episodes"}" },
        )
        return when (parts.size) {
            0 -> ""
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        }
    }

    private fun group(n: Int): String = n.toString().reversed().chunked(3).joinToString(" ").reversed()
}

/** What the run is to do, read from the unticks the dialog sent against the model it was shown (FR-6). */
class ScanSelection(
    /** Ids unticked that could have run: part ids, or `direct_play`. */
    val skippedIds: Set<String>,
    /** Product names of the same, a whole feature by its own name when every part of it was unticked. */
    val skippedNames: List<String>,
    /** The engine steps this run leaves out: the unticked ones and every part of a feature that is not in the scoped library. */
    val skipSteps: Set<String>,
    /** FR-6.2 — *Make files play directly* is ticked and can run: the run queues what its one-minute tick would. */
    val queueDirectPlay: Boolean,
) {
    val skippedText: String get() = skippedNames.joinToString(", ")
    val skippedNote: String get() = when (skippedNames.size) {
        0 -> ""
        1 -> "You unticked it. It stays unticked until you tick it again."
        else -> "You unticked them. They stay unticked until you tick them again."
    }
}

object ScanDialogs {
    private const val LEGEND_HEAD = "A part that queues work is not finished when the scan ends."
    private const val LEGEND = "The reading happens afterwards, in the background, and what it found shows on"

    fun model(cfg: AppConfig, view: ProcessingView, scope: ScanScope?, counts: ScanCounts): ScanDialogView {
        val features = ProcessingFeatures.ALL.mapNotNull { f -> view.features.firstOrNull { it.id == f.id }?.let { feature(f, it, scope) } }
        val what = scope?.name ?: "the library"
        return ScanDialogView(
            title = if (scope == null) "Scan library" else "Scan ${scope.name}",
            lede = scope?.lede(counts) ?: "Everything ticked runs now. What you untick stays unticked the next time, on every page.",
            ledePhone = scope?.lede(counts) ?: "Everything ticked runs now. Tap a feature to open its parts.",
            scopeName = scope?.name, scopeLibraryId = scope?.libraryId,
            recheckOff = "Off · only titles that are due. This is the normal scan.",
            recheckOn = "On · every title in $what is read again, not only the ones that are due.",
            recheckNote = counts.words().let { if (it.isEmpty()) "Every title is read again. It is safe to leave running; nothing waits on it." else "That is $it read again. It is safe to leave running; nothing waits on it." },
            legendHead = LEGEND_HEAD, legend = LEGEND,
            features = features,
        )
    }

    private fun feature(f: ProcessingFeature, v: ProcessingFeatureView, scope: ScanScope?): ScanFeatureView {
        val applies = scope?.applies(f.id) ?: true
        val n = f.parts.size
        val single = !v.locked && n <= 1
        val marker = f.queuesWork && n > 0
        val parts = if (single || v.locked) emptyList() else v.parts.map { p ->
            val canRun = p.canRun && applies
            ScanPartView(
                id = p.id, name = p.name, queuesWork = f.queuesWork, canRun = canRun,
                // The pane's note speaks for a feature that cannot run; a part says why only while its feature is live.
                reason = if (canRun || !v.canRun || !applies) null else "${p.reason ?: ProcessingViews.PART_OFF} It cannot be ticked for one scan either.",
                settingsLink = !canRun && v.canRun && applies && p.reason == ProcessingViews.PART_OFF,
            )
        }
        val canRun = v.canRun && applies && when {
            v.locked -> true
            single -> f.parts.isEmpty() || v.parts.all { it.canRun }   // Make files play directly has no part of its own, only kinds
            else -> parts.any { it.canRun }
        }
        val (short, head, reason, link) = when {
            v.locked -> Why("always", null, null, false)
            !applies -> Why("Not in this library", "Not in this library — nothing here would run.",
                "A scan of one library runs only what applies to it. Scan everything to include this.", false)
            !v.enabled -> Why("Off in Settings", "Off in Settings — nothing here would run.",
                "Its parts cannot be ticked for one scan either. Switch it on, and it runs by itself from then on:", true)
            !v.canRun -> Why(if (f.id == ProcessingFeatures.MUSIC || f.id == ProcessingFeatures.AUDIOBOOKS) "No library mapped" else "Cannot run", null, v.reason, false)
            else -> Why(null, null, null, false)
        }
        return ScanFeatureView(
            id = f.id, name = f.name, queuesWork = marker, locked = v.locked, applies = applies, canRun = canRun,
            tickId = if (single) (f.parts.firstOrNull()?.step ?: f.id) else null,
            lede = lede(f, n), short = short, reasonHead = head, reason = reason, settingsLink = link, parts = parts,
        )
    }

    private data class Why(val a: String?, val b: String?, val c: String?, val d: Boolean)

    private val WORDS = listOf("none", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }
    private fun all(n: Int) = if (n == 2) "Both" else "All ${WORDS[n.coerceIn(0, 9)]}"

    private fun lede(f: ProcessingFeature, n: Int): String = when (f.id) {
        ProcessingFeatures.FIND -> "It is what everything else works on, which is why it cannot be unticked."
        ProcessingFeatures.METADATA -> "${cap(WORDS[n])} parts. ${all(n)} are written during the run itself — none of them queue work."
        ProcessingFeatures.SEGMENTS -> "It queues episodes; the reading happens afterwards, in the background."
        ProcessingFeatures.HEALTH -> "${cap(WORDS[n])} parts. ${all(n)} queue work — the reading happens afterwards, in the background."
        ProcessingFeatures.SUBTITLES -> "${cap(WORDS[n])} parts. ${all(n)} queue work — the fit is read afterwards, in the background."
        ProcessingFeatures.SUGGESTIONS -> "${cap(WORDS[n])} parts, built for the whole library rather than for any one title."
        ProcessingFeatures.MUSIC -> "${cap(WORDS[n])} parts. Write tags is only offered while tag writing is on in Settings."
        ProcessingFeatures.AUDIOBOOKS -> "It reads the audiobook library and stops."
        else -> "It applies the repair kinds that are switched on in Settings."
    }

    /**
     * FR-6 — which of the [requested] unticks count. Only what could have run does: a remembered untick on a part that is off in Settings,
     * behind a switch, or not in the scoped library skips nothing, so it is neither named nor sent. A feature id unticks every tickable part of it.
     */
    fun selection(model: ScanDialogView, requested: Collection<String>): ScanSelection {
        val want = requested.toSet()
        val skippedIds = linkedSetOf<String>()
        val names = mutableListOf<String>()
        val steps = linkedSetOf<String>()
        for (f in model.features) {
            if (f.locked) continue
            if (!f.applies) { steps += ProcessingFeatures.feature(f.id)?.partIds.orEmpty(); continue }
            if (!f.canRun) continue
            val tickable = if (f.tickId != null) listOf(f.tickId to f.name) else f.parts.filter { it.canRun }.map { it.id to it.name }
            val wholeFeature = f.id in want
            val hit = tickable.filter { wholeFeature || it.first in want }
            if (hit.isEmpty()) continue
            skippedIds += hit.map { it.first }
            names += if (hit.size == tickable.size) listOf(f.name) else hit.map { it.second }
        }
        steps += skippedIds.filter { id -> ProcessingFeatures.ALL.any { f -> id in f.partIds } }
        val direct = model.features.firstOrNull { it.id == ProcessingFeatures.DIRECT_PLAY }
        return ScanSelection(
            skippedIds = skippedIds, skippedNames = names, skipSteps = steps,
            queueDirectPlay = direct != null && direct.applies && direct.canRun && ProcessingFeatures.DIRECT_PLAY !in skippedIds,
        )
    }
}
