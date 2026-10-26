package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.media.visibleTo
import dev.jellystructure.model.MediaItem
import dev.jellystructure.shared.tv.HeroConfig
import dev.jellystructure.shared.tv.ChannelConfig
import dev.jellystructure.shared.tv.Condition
import dev.jellystructure.shared.tv.ConditionGroup
import dev.jellystructure.shared.tv.QueryNode
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.RowConfig
import dev.jellystructure.shared.tv.RowKind

/**
 * Phase 269 (FR-269-2, dev review item 1) — the config as an installed app may read it. The apps decode
 * [RowKind] strictly, so a value they do not know fails `/api/tv/config` outright: every
 * [RowKind.RECOMMENDED] goes out as [RowKind.CUSTOM] (the apps never evaluate rows; the server builds
 * them). Wherever a [RowConfig] appears — Home's rows, a channel's own rows, and a `content_row` filter's
 * row references inside any condition tree. Only the TVs' route calls this; the admin's own config route
 * keeps the real kind, or the editor would save the row back as CUSTOM.
 */
fun RaviloConfig.forClients(): RaviloConfig = copy(
    rows = rows.map { it.forClients() },
    channels = channels.map { it.forClients() },
)

private fun ChannelConfig.forClients(): ChannelConfig = copy(
    conditions = conditions.map { it.forClients() },
    query = query?.forClients(),
    rows = rows?.let { r -> r.copy(items = r.items.map { it.forClients() }) },
)

private fun RowConfig.forClients(): RowConfig = copy(
    kind = if (kind == RowKind.RECOMMENDED) RowKind.CUSTOM else kind,
    conditions = conditions.map { it.forClients() },
    query = query?.forClients(),
)

private fun Condition.forClients(): Condition =
    if (rows.isEmpty()) this else copy(rows = rows.map { it.forClients() })

private fun ConditionGroup.forClients(): ConditionGroup = copy(children = children.map { it.forClients() })

private fun QueryNode.forClients(): QueryNode = when (this) {
    is Condition -> forClients()
    is ConditionGroup -> forClients()
}

/**
 * DEB-2518 (FR-5) — this device's copy of the config carries only the heroes it can see. A curated hero entry (Home's, and
 * each channel page's) whose catalogue item [MediaItem.visibleTo] rejects for [device] is removed, with its id and its
 * display title, meta and backdrop: the entry is what a sniffing client would read, and Home already drops the hero
 * itself. An id that matches no catalogue item is left alone, so a device that sees everything gets today's config.
 */
fun RaviloConfig.withoutHeroesHiddenFrom(device: DeviceData, catalogue: List<MediaItem>): RaviloConfig {
    val byId = HashMap<String, MediaItem>(catalogue.size * 2)
    for (item in catalogue) { byId[item.id] = item; item.jellyfinId?.let { byId[it] = item } }
    fun List<HeroConfig>.seen() = filter { h -> byId[h.itemId]?.visibleTo(device) != false }
    return copy(
        heroes = heroes.seen(),
        channels = channels.map { c -> c.pageHero?.let { c.copy(pageHero = it.copy(items = it.items.seen())) } ?: c },
    )
}
