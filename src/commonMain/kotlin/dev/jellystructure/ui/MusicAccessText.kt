package dev.jellystructure.ui

/**
 * DEB-2518 FR-2 — the wording of the music access line on Users & devices, chosen from the wire's
 * `policy.music` and the account's `allowedTags`. **Pure — no DOM and no `kotlinx.browser`**, so it
 * sits in `commonMain` where `linuxX64Test` can run it. The moment a function here needs the DOM it
 * goes back to `wasmJsMain` and loses its tests.
 *
 * The spec says (FR-2, FR-275-4 amendment line 53):
 *
 * | `music` | `allowedTags` | chip |
 * |---|---|---|
 * | `"yes"` | any | `Music: yes` |
 * | `"no_access"` | non-empty | `Music: no` |
 * | `"no_access"` | empty | `Music: no library access` (FR-275-7, unchanged) |
 * | null or any other string | any | nothing |
 *
 * FR-9 of the same spec: the wire is unchanged, so do **not** add a new `policy.music` value.
 */
internal fun musicAccessLabel(music: String?, allowedTags: List<String>): String? = when {
    music == "yes" -> "Music: yes"
    music == "no_access" && allowedTags.isNotEmpty() -> "Music: no"
    music == "no_access" && allowedTags.isEmpty() -> "Music: no library access"
    else -> null
}
