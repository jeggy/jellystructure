package dev.jellystructure.filefix

import dev.jellystructure.media.WorkFiles

/**
 * Phase 314 — the command lines, as pure strings (tested). Every work file comes from [WorkFiles.pathFor], inside
 * `<dir>/.jellystructure/` (311), never beside the video. Heavy commands run at background priority. Shapes measured on
 * scratch copies 2026-10-08: an appended AAC/E-AC-3 track leaves every original stream byte-identical (ffmpeg's
 * `streamhash`) and the duration unchanged; dovi_tool's mode 2 on a DV 7 clip gives profile 8.1 with no EL, the same
 * frame count, and keeps the HDR10 mastering and content-light SEI.
 */
object FileFixCommands {
    fun q(s: String): String = "'${s.replace("'", "'\\''")}'"

    private const val BG = "nice -n 19 ionice -c3"

    /** The work file one added track is encoded into. */
    fun addedTrackPath(videoPath: String, part: Int): String = WorkFiles.pathFor(videoPath, WorkFiles.Kind.ADDED_AUDIO, part)

    /**
     * Encode [track] from the file's own audio stream `0:a:<sourceOrder>` (R382 — never Jellyfin's numbering) into a
     * one-track Matroska audio file: the name a viewer sees, the source's language exactly (an untagged source gives an
     * untagged copy), never default or forced, and the two tags that say what it is (FR-314-4).
     */
    fun encodeTrack(videoPath: String, track: PlannedTrack, sourceUid: String?, addedDate: String, out: String): String {
        val codec = if (track.codec == "aac") "-c:a aac" else "-c:a eac3"
        val lang = track.language?.takeIf { langKey(it) != "und" }?.let { "-metadata:s:a:0 language=${q(langKey(it))} " } ?: ""
        val uid = sourceUid?.let { "-metadata:s:a:0 JELLYSTRUCTURE_COPY_OF=${q(it)} " } ?: ""
        return "$BG ffmpeg -nostdin -y -v error -i ${q(videoPath)} -map 0:a:${track.sourceOrder} -vn -sn -dn " +
            "-map_metadata -1 -map_chapters -1 $codec -b:a ${track.bitrateKbps}k -ac ${track.channels} " +
            lang + "-metadata:s:a:0 title=${q(track.name)} " + uid + "-metadata:s:a:0 JELLYSTRUCTURE_ADDED=${q(addedDate)} " +
            "-disposition:a:0 0 -f matroska ${q(out)}"
    }

    /** The added track decodes start to end (FR-314-3 step 4) — on the small encoded file, never the whole video. */
    fun decodeCheck(path: String): String = "$BG ffmpeg -nostdin -v error -i ${q(path)} -f null -"

    /** Kinds A/B in the file: the original with every added track appended **last**, never default or forced. */
    fun appendTracks(videoPath: String, added: List<String>, out: String): String =
        "$BG mkvmerge -q -o ${q(out)} ${q(videoPath)} " +
            added.joinToString(" ") { "--default-track-flag 0:no --forced-display-flag 0:no ${q(it)}" }

    /**
     * Moves a verified work file into place with one `rename(2)` (same filesystem), with the original's owner and mode.
     * [reference] is the library file whose owner/mode the result takes (the video itself for an in-file swap).
     */
    fun moveIntoPlace(work: String, target: String, reference: String): String =
        "{ chown --reference=${q(reference)} ${q(work)} 2>/dev/null; chmod --reference=${q(reference)} ${q(work)} 2>/dev/null; true; } && " +
            "mv -f ${q(work)} ${q(target)}"

    /** One ffprobe of the file as [parseProbe] reads it: streams (with side data and dispositions) and the format. */
    fun probe(path: String): String = "ffprobe -v error -print_format json -show_streams -show_format ${q(path)}"

    /** The track UIDs, from mkvmerge's identification (the copy's `JELLYSTRUCTURE_COPY_OF`). */
    fun identify(path: String): String = "mkvmerge -J ${q(path)}"

    /** The video's frame count, by reading every packet (kind C's verification; whole-file read, at background priority). */
    fun frameCount(path: String): String =
        "$BG ffprobe -v error -select_streams v:0 -count_packets -show_entries stream=nb_read_packets -of csv=p=0 ${q(path)}"

    // ── Kind C ────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The picture as Annex B through `dovi_tool -m 2 convert --discard` (profile 7 → 8.1, the EL dropped), into a raw
     * HEVC work file. `sh` has no `pipefail`, so ffmpeg's exit status is written to [rcFile] and checked after.
     */
    fun dvConvert(videoPath: String, doviTool: String, hevcOut: String, rcFile: String): String =
        "( $BG ffmpeg -nostdin -v error -i ${q(videoPath)} -map 0:v:0 -c:v copy -bsf:v hevc_mp4toannexb -f hevc -; echo \$? > ${q(rcFile)} ) | " +
            "$BG ${q(doviTool)} -m 2 convert --discard - -o ${q(hevcOut)} && [ \"\$(cat ${q(rcFile)})\" = 0 ]"

    /** The original video's own timestamps, so the version keeps them exactly (VFR-safe). Track 0 is the video. */
    fun dvTimestamps(videoPath: String, tsOut: String): String = "mkvextract ${q(videoPath)} timestamps_v2 0:${q(tsOut)}"

    /** The version: the converted picture first, then every other stream, chapter, attachment and tag of the original;
     *  [globalTags] (FR-314-4's `JELLYSTRUCTURE_DV`) says what it is. */
    fun dvMux(hevc: String, ts: String, videoPath: String, videoLanguage: String?, globalTags: String, out: String): String =
        "$BG mkvmerge -q -o ${q(out)} --global-tags ${q(globalTags)} --timestamps 0:${q(ts)} " +
            "--language 0:${q(videoLanguage?.takeIf { it.isNotBlank() } ?: "und")} ${q(hevc)} --no-video ${q(videoPath)}"

    /** The Matroska global tags of a version: `JELLYSTRUCTURE_DV=8.1-from-7` and the date (FR-314-4). */
    fun dvTagsXml(addedDate: String, elPresent: Boolean): String = buildString {
        append("<?xml version=\"1.0\"?>\n<Tags><Tag><Targets><TargetTypeValue>50</TargetTypeValue></Targets>")
        append("<Simple><Name>JELLYSTRUCTURE_DV</Name><String>8.1-from-7${if (elPresent) "-el-dropped" else ""}</String></Simple>")
        append("<Simple><Name>JELLYSTRUCTURE_ADDED</Name><String>").append(addedDate).append("</String></Simple>")
        append("</Tag></Tags>\n")
    }

    /** Phase 314c (Remove) — the original without the tracks [ids] (mkvmerge's track ids from [identify]): our copies only. */
    fun removeTracks(videoPath: String, ids: List<Int>, out: String): String =
        "$BG mkvmerge -q -o ${q(out)} --audio-tracks ${q("!" + ids.joinToString(","))} ${q(videoPath)}"

    /** Free bytes on [dir]'s filesystem. */
    fun freeBytes(dir: String): String = "df -B1 --output=avail ${q(dir)} 2>/dev/null | tail -n1"
}
