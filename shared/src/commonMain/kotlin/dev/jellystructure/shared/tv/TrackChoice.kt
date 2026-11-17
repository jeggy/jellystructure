package dev.jellystructure.shared.tv

/**
 * R181 — a viewer's audio/subtitle language choice, remembered **client-side only** (never sent to the
 * jellystructure backend). `subtitlesOff` is a real, first-class remembered state, distinct from "no
 * subtitle preference" (`subtitleLanguage == null`). DEB-4932 (FR-23) moved it here from `ravilo-ui` so the
 * Samsung app and the Compose clients read one definition; `ravilo-ui` keeps a typealias.
 */
data class RememberedChoice(
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
    val subtitlesOff: Boolean = false,
    /** R195 (FR-RV §5.4) — which same-language VERSION was picked, not just the language: opaque signature
     *  `"<kind>|<regionCode>|<ordinal>"` (see [variantSignature]). Never colons/commas (the wasm store's
     *  hand-rolled parser splits on both). Falls back to a language-only match when the next file has no such
     *  variant. */
    val audioVariant: String? = null,
    val subtitleVariant: String? = null,
)

/**
 * R181's pick, applied to what was remembered: an audio-only (or subtitle-only) pick must not clobber the other axis, *Off* is
 * a real choice, and a variant signature never outlives the language it was computed for (R195).
 */
fun rememberPick(
    existing: RememberedChoice?,
    newAudioLanguage: String? = null, newSubtitleLanguage: String? = null, newSubtitlesOff: Boolean? = null,
    newAudioVariant: String? = null, newSubtitleVariant: String? = null,
): RememberedChoice = RememberedChoice(
    audioLanguage = newAudioLanguage ?: existing?.audioLanguage,
    subtitleLanguage = if (newSubtitlesOff == true) null else (newSubtitleLanguage ?: existing?.subtitleLanguage),
    subtitlesOff = newSubtitlesOff ?: existing?.subtitlesOff ?: false,
    audioVariant = if (newAudioLanguage != null) newAudioVariant else existing?.audioVariant,
    subtitleVariant = if (newSubtitlesOff == true) null else if (newSubtitleLanguage != null) newSubtitleVariant else existing?.subtitleVariant,
)

/** One track as [resolveTrackChoice] reads it. [index] is the caller's own track index (what it hands its player). */
data class TrackInput(val index: Int, val language: String?, val label: String?, val forced: Boolean = false, val isDefault: Boolean = false)

/** R196 (FR-RV-TRK2-4) — result of [resolveTrackChoice]: the indices to select (subtitle `-1` = off). */
data class TrackSelectionResult(val audioIndex: Int, val subIndex: Int)

private fun List<TrackInput>.asVersions() = map { VersionInput(it.language, it.label, it.forced, it.isDefault) }

/**
 * R181's audio tier for one remembered [choice]: the exact variant signature first, else the language's first PLAIN version
 * (R235 — a signs-only/commentary track never substitutes for a soundtrack the viewer already understands), else its first
 * version. A position INTO [audio]; null when there is no choice or its language is not in this file.
 */
fun rememberedAudioIndex(choice: RememberedChoice?, audio: List<TrackInput>): Int? {
    val lang = choice?.audioLanguage ?: return null
    val group = groupVersions(audio.asVersions()).firstOrNull { sameLanguage(it.language, lang) } ?: return null
    val bySignature = choice.audioVariant?.let { sig -> group.versions.firstOrNull { it.signature() == sig } }
    return (bySignature ?: group.versions.firstOrNull { it.kind == VariantKind.PLAIN } ?: group.versions.firstOrNull())?.flatIndex
}

/**
 * R181's subtitle tier for one remembered [choice]: *Off* is `-1`; otherwise the language's version by exact signature, else
 * its first non-forced PLAIN one, else its first non-forced, else any (Phase 210/R241). A position INTO [subs]; null when
 * there is no choice, it names no language, or the language is not in this file.
 */
fun rememberedSubtitleIndex(choice: RememberedChoice?, subs: List<TrackInput>): Int? = when {
    choice == null -> null
    choice.subtitlesOff -> -1
    else -> {
        val lang = choice.subtitleLanguage
        val group = lang?.let { l -> groupVersions(subs.asVersions()).firstOrNull { sameLanguage(it.language, l) } }
        if (group == null) null else {
            val native = group.versions.filter { it.flatIndex < subs.size }
            val bySignature = choice.subtitleVariant?.let { sig -> native.firstOrNull { it.signature() == sig } }
            val nonForced = native.filter { !it.forced }
            (bySignature ?: nonForced.firstOrNull { it.kind == VariantKind.PLAIN } ?: nonForced.firstOrNull() ?: native.firstOrNull())?.flatIndex
        }
    }
}

/**
 * The signature [choice]s remember for the track at [index] of [tracks]: built over the same [groupVersions] the picker draws
 * and the server resolves with, so what a pick remembers is what the next start asks for. Null for an index outside the list.
 */
fun variantSignatureAt(tracks: List<TrackInput>, index: Int): String? =
    groupVersions(tracks.asVersions()).asSequence().flatMap { it.versions.asSequence() }.firstOrNull { it.flatIndex == index }?.signature()

/**
 * R181/R195/R196 (FR-RV-TRK1, FR-RV §5.4, FR-RV-TRK2-4) — the pure core of a player's track resolution. Layered: the
 * per-series remembered choice → the learned global choice → the source's own default track → first track / forced / off.
 * Each tier returns null when it cannot be satisfied in THIS title, falling through to the next. Matches by language, never
 * by track index (indices differ across episodes/files).
 *
 * R246 takes the TRACK LISTS only and derives the groups itself, so no caller can hand it a stale group list.
 * Scope note (unchanged since R181): a caller that wants no picture-subtitle autoplay passes only its native tracks as [subs].
 */
fun resolveTrackChoice(
    seriesChoice: RememberedChoice?,
    globalChoice: RememberedChoice?,
    audio: List<TrackInput>,
    subs: List<TrackInput>,
): TrackSelectionResult {
    val audioIdx = rememberedAudioIndex(seriesChoice, audio) ?: rememberedAudioIndex(globalChoice, audio)
        ?: run {
            // FR-R235-2 — the source's own "default" flag is a packaging habit, not an instruction: a commentary/audio-description
            // track marked default must still lose to a plain track in the same language when one exists.
            val def = audio.firstOrNull { it.isDefault } ?: return@run null
            if (variantKind(def.label, forced = false) == VariantKind.PLAIN) def.index
            else audio.firstOrNull { it.language.equals(def.language, ignoreCase = true) && variantKind(it.label, forced = false) == VariantKind.PLAIN }?.index
                ?: def.index
        }
        ?: audio.firstOrNull()?.index
        ?: 0

    val subIdx = rememberedSubtitleIndex(seriesChoice, subs) ?: rememberedSubtitleIndex(globalChoice, subs)
        ?: run {
            // FR-R235-2 — a forced track that is ALSO flagged the file's default must not beat the full track beside it.
            val def = subs.firstOrNull { it.isDefault } ?: return@run null
            if (!def.forced) def.index
            else subs.firstOrNull { !it.forced && it.language.equals(def.language, ignoreCase = true) }?.index ?: def.index
        }
        ?: subs.firstOrNull { it.forced }?.index
        ?: -1

    return TrackSelectionResult(audioIdx, subIdx)
}
