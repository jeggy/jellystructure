package dev.jellystructure.music

import dev.jellystructure.model.MusicRecordingFacts
import dev.jellystructure.model.MusicVersionTarget

/**
 * Phase 292 (dev review 2) — what one recording's MusicBrainz relationships say about its version. Pure: the client
 * fetches, this reads. Checked against musicbrainz.org on 2026-10-01:
 * - a recording → work *performance* carries `live` · `cover` · `demo` · `instrumental` · `karaoke` · `partial` …,
 *   and the work carries its `language` / `languages` (`zxx` = no linguistic content);
 * - *remix* reads "{entity0} is a remix of {entity1}" and *edit* "{entity0} is an edit of {entity1}": the remix or
 *   edit is the first entity, so the relationship points `forward` from it;
 * - *instrumental* and *karaoke* read "{entity0} has an instrumental/karaoke version {entity1}": the instrumental is
 *   the second entity, so the relationship points `backward` from it;
 * - a *remixer* is an artist → recording relationship.
 *
 * The rules: Live, Demo, Cover when any performance carries the attribute. Instrumental when the recording performs at
 * least one work with words and every such performance is `instrumental` or `karaoke` (performances of `zxx` works
 * are left out, so a sung song with an instrumental intro piece is not Instrumental), or an *instrumental/karaoke
 * version of* link points from it. No words when it has performances and every one is of a `zxx` work. Remix from
 * a *remix of* link or a remixer credit; Edit from an *edit of* link. The album's release-group types are never used.
 */
object MusicVersionFacts {
    private const val NO_WORDS = "zxx"

    private fun MbWork.noWords(): Boolean = language == NO_WORDS || (language == null && languages.isNotEmpty() && languages.all { it == NO_WORDS })

    fun from(recordingMbid: String, disambiguation: String?, relations: List<MbRelation>, now: Long): MusicRecordingFacts {
        val perfs = relations.filter { it.type == "performance" && it.work != null }
        val sung = perfs.filter { !it.work!!.noWords() }
        fun anyAttr(a: String) = perfs.any { a in it.attributes }
        fun recRel(type: String, direction: String) = relations.firstOrNull { it.type == type && it.targetType == "recording" && it.direction == direction && it.recording != null }
        val instrumentalOf = recRel("instrumental", "backward") ?: recRel("karaoke", "backward")
        val remixOf = recRel("remix", "forward")
        val remixer = relations.any { it.type == "remixer" && it.targetType == "artist" }
        return MusicRecordingFacts(
            recordingMbid = recordingMbid,
            live = anyAttr("live"),
            demo = anyAttr("demo"),
            cover = anyAttr("cover"),
            instrumental = (sung.isNotEmpty() && sung.all { "instrumental" in it.attributes || "karaoke" in it.attributes }) || instrumentalOf != null,
            noWords = perfs.isNotEmpty() && perfs.all { it.work!!.noWords() },
            remix = remixOf != null || remixer,
            edit = recRel("edit", "forward") != null,
            disambiguation = disambiguation?.trim()?.takeIf { it.isNotEmpty() },
            instrumentalOf = instrumentalOf?.recording?.let { MusicVersionTarget(it.id, it.title, MusicScoring.creditText(it.artistCredit).takeIf { a -> a.isNotBlank() }) },
            remixOf = remixOf?.recording?.let { MusicVersionTarget(it.id, it.title, MusicScoring.creditText(it.artistCredit).takeIf { a -> a.isNotBlank() }) },
            fetchedAt = now,
        )
    }

    fun from(rec: MbRecording, now: Long): MusicRecordingFacts = from(rec.id, rec.disambiguation, rec.relations, now)

    /** Every recording on a release (looked up with its relationships), keyed by recording mbid. */
    fun fromRelease(release: MbRelease, now: Long): Map<String, MusicRecordingFacts> =
        release.media.flatMap { it.tracks }.mapNotNull { it.recording }.filter { it.id.isNotBlank() }
            .associate { it.id to from(it, now) }
}
