package dev.jellystructure.ravilo.screen

import dev.jellystructure.shared.tv.StreamTicket
import dev.jellystructure.shared.tv.VariantKind
import dev.jellystructure.shared.tv.VersionGroup
import dev.jellystructure.shared.tv.VersionCore
import dev.jellystructure.shared.tv.VersionInput
import dev.jellystructure.shared.tv.endonymOf
import dev.jellystructure.shared.tv.flagCountryFor
import dev.jellystructure.shared.tv.groupVersions
import dev.jellystructure.shared.tv.languageName
import dev.jellystructure.shared.tv.receiverSelectedAudio
import dev.jellystructure.shared.tv.receiverSelectedSub
import dev.jellystructure.shared.tv.receiverSubtitles

/**
 * R264 (FR-R264-3) — R180/R195's two-level audio & subtitles picker, drawn on the TV when the TV remote
 * asks for it. Level 1 is one row per language (the phone's and the TV app's own grouping, `:shared`'s
 * [groupVersions], so a language counts its versions exactly as they do); a language with several
 * versions opens level 2. Positions are the ones the remote uses: [receiverSubtitles] for subtitles,
 * `ticket.audio` for audio — so a pick here and a pick from the phone land on the same track.
 *
 * Only the model lives here; [Screen] draws it and applies what a row does.
 */
internal enum class PickerTab { SUBTITLES, AUDIO }

internal sealed class PickerAction {
    data class Subtitle(val index: Int) : PickerAction()          // -1 = Off
    data class Audio(val index: Int) : PickerAction()
    data class Open(val group: Int) : PickerAction()
    data object Size : PickerAction()
}

internal data class PickerRow(
    val label: String,
    val line: String? = null,
    val right: String? = null,
    val selected: Boolean = false,
    val action: PickerAction,
    /** DEB-4899 — the flag file's country code (`gb`, `dk`, …) to draw before [label]; null for Off, Subtitle size and a language with no flag. */
    val flag: String? = null,
)

internal fun subtitleGroups(ticket: StreamTicket?): List<VersionGroup> =
    groupVersions(receiverSubtitles(ticket).map { VersionInput(it.language, it.label, it.forced, it.isDefault) })

internal fun audioGroups(ticket: StreamTicket?): List<VersionGroup> =
    groupVersions((ticket?.audio ?: emptyList()).map { VersionInput(it.language, it.label, forced = false, isDefault = it.isDefault) })

/**
 * The name a language row carries, language first (DEB-4899; R180 FR-RV-ASP1-1): the language's own name from `:shared`'s table
 * (`da` / `dan` → "Dansk"), which is what Android TV's picker leads with. Only a code the table does not know falls back to
 * Jellyfin's display title up to its first `" - "` / `" ("` ("Dansk - Dolby Digital 5.1" → "Dansk"), then the code itself;
 * Tizen 5.0's browser has no `Intl.DisplayNames`, so there is no other source of a language name. [unnamed] when none exist.
 */
internal fun groupName(label: String?, language: String?, unnamed: String): String =
    endonymOf(language)
        ?: label?.substringBefore(" - ")?.substringBefore(" (")?.trim()?.takeIf { it.isNotBlank() }
        ?: language?.uppercase()?.takeIf { it.isNotBlank() }
        ?: unnamed

/**
 * What a track's label says beyond its language (DEB-4899): "Dansk - Dolby Digital 5.1" → "Dolby Digital 5.1", "E-AC-3 Atmos
 * 5.1" stays as it is, a label that is only the language's name → null. The track's [codec] and [channels] stand in when the
 * label says nothing else, so two tracks of one language that differ by codec are never both blank.
 */
internal fun trackDetail(label: String?, language: String?, codec: String? = null, channels: Int? = null): String? {
    val names = listOfNotNull(endonymOf(language), languageName(language)).map { it.lowercase() }
    var rest = label?.trim().orEmpty()
    val lower = rest.lowercase()
    names.firstOrNull { lower == it || lower.startsWith("$it - ") || lower.startsWith("$it (") }?.let {
        rest = rest.substring(it.length).trim().trimStart('-').trim()
        if (rest.startsWith("(") && rest.endsWith(")")) rest = rest.substring(1, rest.length - 1).trim()
    }
    if (rest.isNotBlank()) return rest
    val spec = listOfNotNull(codec?.takeIf { it.isNotBlank() }?.uppercase(), channelWord(channels)).joinToString(" ")
    return spec.takeIf { it.isNotBlank() }
}

private fun channelWord(channels: Int?): String? = when (channels) {
    null, 0 -> null
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$channels ch"
}

/** The words Android TV's picker badges a row with (`subtitleBadges` / `audioBadges`), for [v]'s kind. */
internal fun versionBadges(v: VersionCore, showDefault: Boolean, t: (String) -> String): List<String> {
    val badges = mutableListOf<String>()
    if (showDefault && v.isDefault) badges += t("player.badge_default")
    if (v.forced) badges += t("player.badge_signs_only")
    when (v.kind) {
        VariantKind.SDH -> badges += t("player.badge_sound_described")
        VariantKind.DESCRIBE -> badges += t("player.badge_describes_action")
        VariantKind.COMMENTARY -> badges += t("player.badge_commentary")
        else -> {}
    }
    return badges
}

/** R195's one-sentence description of a version, as a string key. */
internal fun variantLineKey(kind: VariantKind): String = when (kind) {
    VariantKind.PLAIN -> "player.variant_plain"
    VariantKind.SDH -> "player.variant_sdh"
    VariantKind.FORCED -> "player.variant_forced"
    VariantKind.DESCRIBE -> "player.variant_describe"
    VariantKind.COMMENTARY -> "player.variant_commentary"
}

/**
 * The rows for [tab] at level 1 ([group] null) or level 2 (the versions of [group]). [selectedText] is
 * the drawn text subtitle's position (-1 = none); a burned-in subtitle is the selection while it is on.
 * [t] resolves a string key; `{n}` is filled by [tn].
 */
internal fun pickerRows(
    ticket: StreamTicket?, tab: PickerTab, group: Int?, selectedText: Int,
    t: (String) -> String, tn: (String, Int) -> String,
): List<PickerRow> {
    val subs = receiverSubtitles(ticket)
    val audio = ticket?.audio ?: emptyList()
    val labels = if (tab == PickerTab.SUBTITLES) subs.map { it.label } else audio.map { it.label }
    val languages = if (tab == PickerTab.SUBTITLES) subs.map { it.language } else audio.map { it.language }
    val selected = if (tab == PickerTab.SUBTITLES) receiverSelectedSub(ticket, selectedText) else receiverSelectedAudio(ticket)
    val groups = if (tab == PickerTab.SUBTITLES) subtitleGroups(ticket) else audioGroups(ticket)
    fun pick(i: Int) = if (tab == PickerTab.SUBTITLES) PickerAction.Subtitle(i) else PickerAction.Audio(i)
    fun detail(i: Int): String? = trackDetail(
        labels.getOrNull(i), languages.getOrNull(i),
        codec = if (tab == PickerTab.AUDIO) audio.getOrNull(i)?.codec else null,
        channels = if (tab == PickerTab.AUDIO) audio.getOrNull(i)?.channels else null,
    )
    if (group != null) {
        val g = groups.getOrNull(group) ?: return emptyList()
        return g.versions.map { v ->
            PickerRow(
                label = detail(v.flatIndex) ?: tn("player.version_n", v.ordinal + 1),
                line = t(variantLineKey(v.kind)),
                selected = v.flatIndex == selected,
                action = pick(v.flatIndex),
            )
        }
    }
    val rows = mutableListOf<PickerRow>()
    if (tab == PickerTab.SUBTITLES) rows += PickerRow(t("off"), selected = selected < 0, action = PickerAction.Subtitle(-1))
    groups.forEachIndexed { gi, g ->
        val first = g.versions.first().flatIndex
        val name = groupName(labels.getOrNull(first), languages.getOrNull(first), t("player.unnamed"))
        val flag = flagCountryFor(languages.getOrNull(first))
        val isSel = g.versions.any { it.flatIndex == selected }
        rows += if (g.versions.size == 1) {
            val only = g.versions.first()
            val line = (listOfNotNull(detail(first)) + versionBadges(only, showDefault = false, t = t)).joinToString(" · ").takeIf { it.isNotBlank() }
            PickerRow(name, line = line, selected = isSel, action = pick(first), flag = flag)
        } else PickerRow(name, right = tn("player.versions_count", g.versions.size) + "  ›", selected = isSel, action = PickerAction.Open(gi), flag = flag)
    }
    if (tab == PickerTab.SUBTITLES) rows += PickerRow(t("pl.sub_size"), action = PickerAction.Size)
    return rows
}
