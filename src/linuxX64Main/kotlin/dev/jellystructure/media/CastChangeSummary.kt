package dev.jellystructure.media

import dev.jellystructure.model.Person

/**
 * DEB-5807 (FR-7) — what moved between two stored casts, or null when nothing did. People are matched by
 * `tmdbId`; `role` counts a changed `character`, `order` a changed `order`, `thumb` a changed `profilePath`.
 */
internal fun castChangeSummary(old: List<Person>, fresh: List<Person>): String? {
    if (old == fresh) return null
    val before = old.associateBy { it.tmdbId }
    val after = fresh.associateBy { it.tmdbId }
    var role = 0
    var order = 0
    var thumb = 0
    for ((id, now) in after) {
        val was = before[id] ?: continue
        if (was.character != now.character) role++
        if (was.order != now.order) order++
        if (was.profilePath != now.profilePath) thumb++
    }
    val added = after.keys.count { it !in before }
    val removed = before.keys.count { it !in after }
    val people = role + order + thumb + added + removed
    if (people == 0) return null
    return "$people person(s) ($role role, $order order, $thumb thumb, $added added, $removed removed)"
}
