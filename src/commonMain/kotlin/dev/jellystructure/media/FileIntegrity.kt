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

/**
 * Phase 254 (FR-254-10/12) — the replacement, as commands. The job runs [copyCommand], verifies the
 * result in Kotlin, then runs [swapCommand]; the page prints [snippet], which is the same three steps
 * with the verification as a shell test. One builder, so the page cannot advertise a command the job
 * does not run. The source is only ever an `-i`.
 */
class FileRepairPlan(
    val libraryPath: String,
    val sourcePath: String,
    val quarantinePath: String,
    private val libraryFlags: List<StreamFlags>,
) {
    val tmpPath: String = libraryPath.substringBeforeLast('/') + "/.jsreplace_" + libraryPath.substringAfterLast('/')

    private fun q(s: String) = "'${FileIntegrity.esc(s)}'"

    val copyCommand: String = buildString {
        append("nice -n 19 ffmpeg -nostdin -v error -y -i ${q(sourcePath)} -map 0 -c copy -cues_to_front 1")
        for (f in libraryFlags) {
            append(" -disposition:${f.index} ${f.dispositions.joinToString("+").ifEmpty { "0" }}")
            append(" -metadata:s:${f.index} ${q("language=${f.language}")}")
            append(" -metadata:s:${f.index} ${q("title=${f.title}")}")
        }
        append(" ${q(tmpPath)}")
    }

    /** Ownership/mode from the damaged file, damaged file to quarantine (a rename — a different
     *  filesystem fails here and nothing has been touched), temp into place. */
    val swapCommand: String =
        "mkdir -p ${q(quarantinePath.substringBeforeLast('/'))} && " +
            "chown --reference=${q(libraryPath)} ${q(tmpPath)} && chmod --reference=${q(libraryPath)} ${q(tmpPath)} && " +
            "[ ! -e ${q(quarantinePath)} ] && mv ${q(libraryPath)} ${q(quarantinePath)} && mv ${q(tmpPath)} ${q(libraryPath)}"

    val snippet: String =
        "$copyCommand \\\n && test -z \"\$(${FileIntegrity.checkCommand(tmpPath)} | grep -E 'matroska,webm|Truncating packet')\" \\\n && $swapCommand"
}
