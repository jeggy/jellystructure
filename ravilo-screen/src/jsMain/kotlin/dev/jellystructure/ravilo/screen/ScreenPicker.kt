package dev.jellystructure.ravilo.screen

import dev.jellystructure.shared.tv.StreamTicket
import dev.jellystructure.shared.tv.VariantKind
import dev.jellystructure.shared.tv.VersionGroup
import dev.jellystructure.shared.tv.VersionInput
import dev.jellystructure.shared.tv.groupVersions
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
)

internal fun subtitleGroups(ticket: StreamTicket?): List<VersionGroup> =
    groupVersions(receiverSubtitles(ticket).map { VersionInput(it.language, it.label, it.forced, it.isDefault) })

internal fun audioGroups(ticket: StreamTicket?): List<VersionGroup> =
    groupVersions((ticket?.audio ?: emptyList()).map { VersionInput(it.language, it.label, forced = false, isDefault = it.isDefault) })

/**
 * The name a language row carries: Jellyfin's display title up to its first `" - "` / `" ("`
 * ("Dansk - Dolby Digital 5.1" → "Dansk"), else the language code; a receiver has no language-name
 * table of its own, and Tizen 5.0's browser has no `Intl.DisplayNames`. [unnamed] when neither exists.
 */
internal fun groupName(label: String?, language: String?, unnamed: String): String =
    label?.substringBefore(" - ")?.substringBefore(" (")?.trim()?.takeIf { it.isNotBlank() }
        ?: language?.uppercase()?.takeIf { it.isNotBlank() }
        ?: unnamed

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
    val labels = if (tab == PickerTab.SUBTITLES) subs.map { it.label } else (ticket?.audio ?: emptyList()).map { it.label }
    val languages = if (tab == PickerTab.SUBTITLES) subs.map { it.language } else (ticket?.audio ?: emptyList()).map { it.language }
    val selected = if (tab == PickerTab.SUBTITLES) receiverSelectedSub(ticket, selectedText) else receiverSelectedAudio(ticket)
    val groups = if (tab == PickerTab.SUBTITLES) subtitleGroups(ticket) else audioGroups(ticket)
    fun pick(i: Int) = if (tab == PickerTab.SUBTITLES) PickerAction.Subtitle(i) else PickerAction.Audio(i)
    if (group != null) {
        val g = groups.getOrNull(group) ?: return emptyList()
        return g.versions.map { v ->
            PickerRow(
                label = labels.getOrNull(v.flatIndex)?.takeIf { it.isNotBlank() }
                    ?: tn("player.version_n", v.ordinal + 1),
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
        val isSel = g.versions.any { it.flatIndex == selected }
        rows += if (g.versions.size == 1) PickerRow(name, selected = isSel, action = pick(first))
        else PickerRow(name, right = tn("player.versions_count", g.versions.size) + "  ›", selected = isSel, action = PickerAction.Open(gi))
    }
    if (tab == PickerTab.SUBTITLES) rows += PickerRow(t("pl.sub_size"), action = PickerAction.Size)
    return rows
}
