package dev.jellystructure.media

/**
 * Phase 254 (FR-254-2) — what counts as damage in a deep check's output, decided in ONE place.
 *
 * A deep check is a demux pass (`ffmpeg -v error -i f -map 0 -c copy -f null -`): nothing is decoded,
 * so every line it prints is either the container demuxer failing to parse the file, or noise. The
 * phase-201 layout walk stops at the end of the first `Cluster` on purpose; this is the check for
 * everything after it (measured 2026-09-21: 20 of 93 files of one series damaged between 0x16c81 and
 * 0x970ac47, none of them visible to the walk).
 */
object FileIntegrity {
    /** A deep check's own command. Kept here so the page can show the operator exactly what ran. */
    fun checkCommand(filePath: String): String =
        "ffmpeg -nostdin -v error -i '${esc(filePath)}' -map 0 -c copy -f null - 2>&1 </dev/null"

    // Container demuxers only. A decoder/parser tag (`[eac3 @`, `[h264 @`) is deliberately absent:
    // clean sources print `exponent -2 is out-of-range` too (S01E11 is the regression fixture).
    private val DEMUXER_TAGS = listOf("[matroska,webm @", "[mov,mp4,", "[avi @", "[mpegts @", "[in#")
    private val UNTAGGED_DAMAGE = listOf("Truncating packet")
    private val BENIGN = listOf("non monotonically increasing dts", "Last message repeated")

    /** The lines of [ffmpegOutput] that mean the container itself could not be read. Empty = clean. */
    fun damageLines(ffmpegOutput: String): List<String> = ffmpegOutput.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filterNot { line -> BENIGN.any { it in line } }
        .filter { line -> DEMUXER_TAGS.any { line.startsWith(it) } || UNTAGGED_DAMAGE.any { line.startsWith(it) } }
        .toList()

    /** ffmpeg prints its own address in the tag (`[matroska,webm @ 0x55fe3014d280]`); that is noise to
     *  a reader and differs on every run, so the stored/rendered line drops it. */
    fun displayLine(line: String): String = line.replace(Regex(" @ 0x[0-9a-fA-F]+]"), "]")

    internal fun esc(s: String): String = s.replace("'", "'\\''")
}

/** One stream's operator-editable flags — what a replacement must carry over from the library copy
 *  (FR-254-10): those edits are why the file was rewritten in the first place. */
data class StreamFlags(val index: Int, val language: String, val title: String, val dispositions: List<String>)

/** Phase 263 — a stream as pairing sees it: the release's own kind and codec, which no edit in
 *  jellystructure changes, beside the flags an operator may have edited. [filename] is an attachment's. */
data class StreamShape(val flags: StreamFlags, val codecType: String, val codecName: String, val filename: String = "") {
    val index: Int get() = flags.index
}

/** What decided a pair. [CONTENT]: its packets, against one source track. [IDENTICAL]: its packets, against
 *  source tracks that are byte-identical to each other in the window — the label chose among them, and the
 *  replacement proves them identical across the whole file before it swaps. [LABEL]: nothing to compare. */
enum class PairedBy { CONTENT, IDENTICAL, LABEL }

/** Which source track one library track is, and what decided it ([matched] of the library track's
 *  [window] packets in the first two minutes; [identicalTo] = the other source tracks of an [PairedBy.IDENTICAL] group). */
data class TrackPair(
    val libraryIndex: Int,
    val sourceIndex: Int,
    val by: PairedBy,
    val matched: Int = 0,
    val window: Int = 0,
    val identicalTo: List<Int> = emptyList(),
)

sealed interface TrackPairing {
    data class Paired(val pairs: List<TrackPair>) : TrackPairing
    data class Refused(val reason: String) : TrackPairing
}

/**
 * Phase 263 (FR-263-1/2/3) — which source track each library track is, decided by what the tracks carry.
 *
 * Phase 254 carried the library's flags over by stream index, which is only right when the library copy
 * is in the source's order; measured 2026-09-26, 33 of the 36 damaged files with a source are not, so
 * every one would have played English under the Danish label — and passed all three of 254's checks. A
 * stream copy moves packets byte for byte, so the same track in two files has the same packets whatever
 * either file calls it: over the first 120 s, every library track found 98.8–100 % of its packets in the
 * right source track and at most 3 in any other.
 *
 * Two shapes production showed that a plain count cannot settle: a forced subtitle whose cues are a subset
 * of the full one's (both "hold" the forced track's packets — [similarity] counts the extra ones too), and
 * one audio track muxed three times under three labels (28 files of one title: identical `streamhash`es).
 */
object TrackPairer {
    const val WINDOW_SECONDS = 120

    /** A pair needs more than half of both tracks' packets in common… */
    private const val MIN_SIMILARITY = 0.5
    /** …and no other (non-identical) candidate within 10 % of it: a clear winner, never a coin flip. */
    private const val RUNNER_UP_RATIO = 0.9

    /** Per-packet hashes of the first [WINDOW_SECONDS] of a file — a read, no decode, ~0.5 s. */
    fun windowCommand(path: String): String =
        "nice -n 19 ffmpeg -nostdin -v error -t $WINDOW_SECONDS -i '${FileIntegrity.esc(path)}' -map 0 -c copy -f framemd5 - 2>/dev/null </dev/null"

    /** `framemd5` output → per stream, how many times each packet (size + MD5) occurs. */
    fun packets(framemd5: String): Map<Int, Map<String, Int>> {
        val out = mutableMapOf<Int, MutableMap<String, Int>>()
        for (line in framemd5.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val f = line.split(',').map { it.trim() }
            if (f.size < 6) continue
            val stream = f[0].toIntOrNull() ?: continue
            val key = "${f[4]}:${f[5]}"
            val counts = out.getOrPut(stream) { mutableMapOf() }
            counts[key] = (counts[key] ?: 0) + 1
        }
        return out
    }

    /** `streamhash` output (`0,v,MD5=…`, one line per stream) → stream index to hash. Other lines — the
     *  pass's own error output, when stderr is folded in — are ignored here and read by [FileIntegrity.damageLines]. */
    fun streamHashes(out: String): Map<Int, String> = out.lineSequence().mapNotNull { line ->
        STREAMHASH.matchEntire(line.trim())?.let { it.groupValues[1].toInt() to it.groupValues[2] }
    }.toMap()
    private val STREAMHASH = Regex("""(\d+),[a-z],(MD5=[0-9a-f]+)""")

    fun overlap(a: Map<String, Int>, b: Map<String, Int>): Int = a.entries.sumOf { (k, n) -> minOf(n, b[k] ?: 0) }

    /** Packets in common over packets in either (1 = the same track, 0 = nothing shared). Counting the
     *  source's extra packets is what tells a forced subtitle from the full one that contains its cues. */
    fun similarity(a: Map<String, Int>, b: Map<String, Int>): Double {
        val common = overlap(a, b)
        val either = a.values.sum() + b.values.sum() - common
        return if (either == 0) 0.0 else common.toDouble() / either
    }

    fun pair(
        library: List<StreamShape>,
        source: List<StreamShape>,
        libraryPackets: Map<Int, Map<String, Int>>,
        sourcePackets: Map<Int, Map<String, Int>>,
    ): TrackPairing {
        if (library.size != source.size) return TrackPairing.Refused("The source has a different number of tracks (${source.size} vs ${library.size})")
        val pairs = mutableMapOf<Int, TrackPair>()
        val claimedBy = mutableMapOf<Int, Int>()   // source index → library index
        val byIndex = source.associateBy { it.index }
        val wantingGroup = linkedMapOf<List<Int>, MutableList<StreamShape>>()   // identical source tracks → library tracks that match them

        // FR-263-1 — by content, wherever the window holds packets to compare.
        for (lib in library) {
            val mine = libraryPackets[lib.index].orEmpty()
            val n = mine.values.sum()
            if (n == 0) continue
            val scored = source.filter { it.codecType == lib.codecType && it.codecName == lib.codecName }
                .map { it to similarity(mine, sourcePackets[it.index].orEmpty()) }
                .sortedByDescending { it.second }
            val (best, score) = scored.firstOrNull() ?: return TrackPairing.Refused("${describe(lib)} has no ${lib.codecName} track to match in the source")
            val bestPackets = sourcePackets[best.index].orEmpty()
            if (score <= MIN_SIMILARITY) {
                return TrackPairing.Refused("${describe(lib)} matches no track of the source (${overlap(mine, bestPackets)} of its $n packets in the first two minutes at best)")
            }
            val identical = scored.map { it.first.index }.filter { sourcePackets[it].orEmpty() == bestPackets }
            val runnerUp = scored.firstOrNull { it.first.index !in identical }
            if (runnerUp != null && runnerUp.second >= score * RUNNER_UP_RATIO) {
                return TrackPairing.Refused("${describe(lib)} can't be told apart: source tracks ${best.index} and ${runnerUp.first.index} both hold its first two minutes")
            }
            if (identical.size > 1) { wantingGroup.getOrPut(identical.sorted()) { mutableListOf() } += lib; continue }
            claimedBy[best.index]?.let { other -> return TrackPairing.Refused("${describe(lib)} and track $other both match source track ${best.index}") }
            claimedBy[best.index] = lib.index
            pairs[lib.index] = TrackPair(lib.index, best.index, PairedBy.CONTENT, matched = overlap(mine, bestPackets), window = n)
        }

        // FR-263-8 — source tracks identical in the window: which one goes under which label changes no byte
        // (the job proves that over the whole file), so the label chooses — the source's own, where one fits.
        for ((group, libs) in wantingGroup) {
            val free = group.filter { it !in claimedBy }.toMutableList()
            if (libs.size > free.size) {
                return TrackPairing.Refused("${libs.joinToString(", ") { describe(it) }} all match source tracks ${group.joinToString(", ")}, which are identical in the first two minutes, and there are more of ours than theirs")
            }
            val sameLabel = libs.associateWith { lib -> free.filter { byIndex.getValue(it).flags.language.equals(lib.flags.language, ignoreCase = true) } }
            val ordered = libs.filter { sameLabel.getValue(it).size == 1 } + libs.filter { sameLabel.getValue(it).size != 1 }
            for (lib in ordered) {
                val pick = sameLabel.getValue(lib).singleOrNull()?.takeIf { it in free } ?: free.first()
                free.remove(pick)
                claimedBy[pick] = lib.index
                val mine = libraryPackets.getValue(lib.index)
                pairs[lib.index] = TrackPair(lib.index, pick, PairedBy.IDENTICAL, overlap(mine, sourcePackets[pick].orEmpty()), mine.values.sum(), group - pick)
            }
        }

        // FR-263-2 — nothing to compare: the label decides, and only when exactly one source track fits it.
        for (lib in library) {
            if (lib.index in pairs) continue
            val fits = source.filter {
                it.index !in claimedBy && it.codecType == lib.codecType && it.codecName == lib.codecName &&
                    it.flags.language.equals(lib.flags.language, ignoreCase = true) && it.filename == lib.filename
            }
            if (fits.size != 1) {
                return TrackPairing.Refused(
                    "${describe(lib)} has nothing in the first two minutes to compare, and " +
                        (if (fits.isEmpty()) "no source track left carries its label" else "${fits.size} source tracks (${fits.joinToString(", ") { "${it.index}" }}) carry its label"),
                )
            }
            claimedBy[fits[0].index] = lib.index
            pairs[lib.index] = TrackPair(lib.index, fits[0].index, PairedBy.LABEL)
        }
        return TrackPairing.Paired(pairs.values.sortedBy { it.libraryIndex })
    }

    /** FR-263-5 check 2 — the positions of a new file that do not hold what the library copy held there:
     *  less than half in common, or another track of the new file (not identical to this one) more like it.
     *  Deliberately reads no pairing: the goal, checked on its own. */
    fun positionsNotHolding(libraryPackets: Map<Int, Map<String, Int>>, newPackets: Map<Int, Map<String, Int>>): List<Int> =
        libraryPackets.filter { (_, mine) -> mine.values.sum() > 0 }.keys.filter { i ->
            val mine = libraryPackets.getValue(i)
            val here = newPackets[i].orEmpty()
            val score = similarity(mine, here)
            score <= MIN_SIMILARITY || newPackets.any { (k, theirs) -> k != i && theirs != here && similarity(mine, theirs) > score }
        }.sorted()

    /** FR-263-8 — every identical group's source tracks, by source index, as the plan must prove them. */
    fun identicalGroups(pairs: List<TrackPair>): List<List<Int>> =
        pairs.filter { it.by == PairedBy.IDENTICAL }.map { (it.identicalTo + it.sourceIndex).sorted() }.distinct()

    fun describe(s: StreamShape): String = "Track ${s.index} (${s.codecType}${if (s.codecType != "video" && s.flags.language.isNotBlank()) ", ${s.flags.language}" else ""})"
}

/**
 * Phase 254 (FR-254-10/12) — the replacement, as commands. The job runs [copyCommand], verifies the
 * result in Kotlin, then runs [swapCommand]; the page prints [snippet], which is the same three steps
 * with the verification as a shell test. One builder, so the page cannot advertise a command the job
 * does not run. The source is only ever an `-i`.
 *
 * Phase 263 (FR-263-4) — the source is mapped once per library track in the library's order, from
 * [pairs], and the library's flags are stamped by output position. Never `-map 0`: that wrote the
 * source's order under the library's labels.
 */
class FileRepairPlan(
    val libraryPath: String,
    val sourcePath: String,
    val quarantinePath: String,
    private val libraryFlags: List<StreamFlags>,
    val pairs: List<TrackPair>,
) {
    init {
        require(pairs.size == libraryFlags.size && pairs.indices.all { pairs[it].libraryIndex == it && libraryFlags[it].index == it }) {
            "a plan needs one pair per library track, in library order"
        }
    }

    val tmpPath: String = libraryPath.substringBeforeLast('/') + "/.jsreplace_" + libraryPath.substringAfterLast('/')

    private fun q(s: String) = "'${FileIntegrity.esc(s)}'"

    private val maps: String = pairs.joinToString(" ") { "-map 0:${it.sourceIndex}" }

    val copyCommand: String = buildString {
        append("nice -n 19 ffmpeg -nostdin -v error -y -i ${q(sourcePath)} $maps -c copy -cues_to_front 1")
        for ((position, f) in libraryFlags.withIndex()) {
            append(" -disposition:$position ${f.dispositions.joinToString("+").ifEmpty { "0" }}")
            append(" -metadata:s:$position ${q("language=${f.language}")}")
            append(" -metadata:s:$position ${q("title=${f.title}")}")
        }
        append(" ${q(tmpPath)}")
    }

    /** FR-263-5 check 1 — one hash per stream, of the source under [maps] and of the new file as
     *  written; equal line for line means every position holds exactly the planned source track. stderr
     *  is folded in so the same pass is the source's own deep check (FR-254-9) and the new file's. */
    val sourceHashCommand: String = "nice -n 19 ffmpeg -nostdin -v error -i ${q(sourcePath)} $maps -c copy -f streamhash -hash md5 - 2>&1 </dev/null"
    val newHashCommand: String = "nice -n 19 ffmpeg -nostdin -v error -i ${q(tmpPath)} -map 0 -c copy -f streamhash -hash md5 - 2>&1 </dev/null"

    /** FR-263-8 — the source tracks a label chose among because the window could not tell them apart. The
     *  choice changes no byte only if they are identical across the whole file, so that is proved first. */
    val identicalGroups: List<List<Int>> = TrackPairer.identicalGroups(pairs)

    /** The output positions of a group's source tracks — where their hashes sit in [sourceHashCommand]'s pass. */
    fun positionsOf(group: List<Int>): List<Int> = group.map { s -> pairs.indexOfFirst { it.sourceIndex == s } }

    private fun sameCommand(group: List<Int>): String =
        "[ \"\$(nice -n 19 ffmpeg -nostdin -v error -i ${q(sourcePath)} ${group.joinToString(" ") { "-map 0:$it" }} -c copy -f streamhash -hash md5 - 2>/dev/null </dev/null | sed 's/.*=//' | sort -u | wc -l)\" -eq 1 ]"

    /** Ownership/mode from the damaged file, damaged file to quarantine (a rename — a different
     *  filesystem fails here and nothing has been touched), temp into place. */
    val swapCommand: String =
        "mkdir -p ${q(quarantinePath.substringBeforeLast('/'))} && " +
            "chown --reference=${q(libraryPath)} ${q(tmpPath)} && chmod --reference=${q(libraryPath)} ${q(tmpPath)} && " +
            "[ ! -e ${q(quarantinePath)} ] && mv ${q(libraryPath)} ${q(quarantinePath)} && mv ${q(tmpPath)} ${q(libraryPath)}"

    /** FR-263-7 — copy, the new file's damage test, the two hash passes compared, each identical group
     *  proved identical (FR-263-8), then the swap. The comparison keeps the demuxer's complaints beside the
     *  hashes, so a damaged source fails it too. */
    val snippet: String =
        "$copyCommand \\\n && test -z \"\$(${FileIntegrity.checkCommand(tmpPath)} | grep -E '$DAMAGE')\" \\\n" +
            " && [ \"\$($sourceHashCommand | grep -E 'MD5=|$DAMAGE')\" = \"\$($newHashCommand | grep -E 'MD5=|$DAMAGE')\" ] \\\n" +
            identicalGroups.joinToString("") { " && ${sameCommand(it)} \\\n" } +
            " && $swapCommand"

    private companion object { const val DAMAGE = "matroska,webm|Truncating packet" }
}
