package dev.jellystructure.shared.tv

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Phase 140 — the workbench query tree, replacing the flat `{ match, conditions[] }` shape
 * everywhere the R32 Workbench opens (Library filter, Channels, Home rows, per-channel rows).
 *
 * The root is always a [ConditionGroup] ([join] = the operator *between* the top-level blocks);
 * its children are the top-level **blocks** (each itself a group, possibly nested to depth 3).
 * Conditions never sit directly at the root. [Condition] (`Models.kt`) is the leaf node.
 *
 * `@JsonClassDiscriminator("kind")` reproduces the design mockup's `kind: "group"|"cond"` JSON
 * exactly. This only affects serialization when going through the sealed [QueryNode] type (e.g. a
 * `List<QueryNode>` field) — [Condition] serialized/deserialized on its own (every existing call
 * site) is completely unaffected, see its doc comment in `Models.kt`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface QueryNode

/** Join mode between a group's children. Lowercase wire values match the design mockup exactly. */
@Serializable
enum class QueryJoin {
    @SerialName("and") AND,
    @SerialName("or") OR,
}

/**
 * A block (or sub-block): [join]s its [children], inverted as a whole by [not] ("exclude everything
 * this block matches"). An empty group (no live children) is **neutral** — matches everything,
 * regardless of [not] — so a fresh block never zeroes the results and an exclusion over nothing
 * excludes nothing. This deliberately diverges from the design mockup's literal `matchNode`, which
 * returns `!n.not` for an empty group (an empty NOT'd block there matches *nothing*).
 */
@Serializable
@SerialName("group")
data class ConditionGroup(
    val join: QueryJoin = QueryJoin.AND,
    val not: Boolean = false,
    val children: List<QueryNode> = emptyList(),
) : QueryNode

// ─── Live / empty semantics ────────────────────────────────────────────────────

/** Does this node currently constrain anything? A `content_row` condition is live by [Condition.rows]
 *  (its `values` are unused for that facet); every other condition is live by [Condition.values]. */
fun QueryNode.isLive(): Boolean = when (this) {
    is Condition -> when (facet) {
        "content_row" -> rows.isNotEmpty()
        // DEB-2837 (FR-2, FR-3) — a range is live when a bound is filled in; a rating when its minimum is.
        "year" -> values.any { it.isNotBlank() }
        "rating" -> values.firstOrNull()?.isNotBlank() == true
        else -> values.isNotEmpty()
    }
    is ConditionGroup -> children.any { it.isLive() }
}

// ─── DEB-2837: the `year` and `rating` conditions ──────────────────────────────

/** FR-1 — the default vote minimum of a `rating` condition (FR-3) and the floor of the rating sort (FR-4). */
const val DEFAULT_MIN_VOTES = 200

/** FR-2 — a `year` condition's inclusive bounds; `null` = no bound on that side (a blank or unreadable value). */
fun Condition.yearBounds(): Pair<Int?, Int?> =
    values.getOrNull(0)?.trim()?.toIntOrNull() to values.getOrNull(1)?.trim()?.toIntOrNull()

/** FR-3 — a `rating` condition's minimum rating and minimum votes (a blank or missing second value = 200);
 *  `null` when the minimum rating is blank or unreadable, which matches nothing. */
fun Condition.ratingThreshold(): Pair<Double, Int>? {
    val r = values.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return null
    val m = values.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: DEFAULT_MIN_VOTES
    return r to m
}

/** FR-8 — what is wrong with a `year` or `rating` condition, as a clause the caller prefixes with the row or
 *  collection; `null` when it is fine (or is another facet). */
fun Condition.valueProblem(): String? = when (facet) {
    "year" -> {
        val bounds = values.take(2).map { it.trim() }
        val parsed = bounds.map { it.toIntOrNull() }
        when {
            bounds.indices.any { bounds[it].isNotEmpty() && (parsed[it] == null || parsed[it] !in 1870..2100) } ->
                "a year must be blank or a whole number from 1870 to 2100."
            parsed.size == 2 && parsed[0] != null && parsed[1] != null && parsed[0]!! > parsed[1]!! ->
                "the year range starts after it ends."
            else -> null
        }
    }
    "rating" -> {
        val r = values.getOrNull(0)?.trim().orEmpty()
        val m = values.getOrNull(1)?.trim().orEmpty()
        when {
            r.isNotEmpty() && r.toDoubleOrNull()?.let { it in 0.0..10.0 } != true -> "a rating must be a number from 0 to 10."
            m.isNotEmpty() && (m.toIntOrNull() ?: -1) < 0 -> "the vote minimum must be a whole number, 0 or more."
            else -> null
        }
    }
    else -> null
}

/** FR-8 — the first [valueProblem] anywhere in this tree, or `null`. */
fun QueryNode.firstValueProblem(): String? = when (this) {
    is Condition -> valueProblem()
    is ConditionGroup -> children.firstNotNullOfOrNull { it.firstValueProblem() }
}

/** Does this node contain any condition matching [predicate], anywhere in the tree — regardless of
 *  liveness (mirrors the flat-era check "does the stack reference this facet at all", used e.g. to
 *  decide whether to bother resolving hero-carousel ids for a `hero_item` condition). */
fun QueryNode.anyCondition(predicate: (Condition) -> Boolean): Boolean = when (this) {
    is Condition -> predicate(this)
    is ConditionGroup -> children.any { it.anyCondition(predicate) }
}

// ─── Pruning (deep-copy, drop empty nodes) ─────────────────────────────────────

private fun pruneNode(n: QueryNode): QueryNode? = when (n) {
    is Condition -> if (n.isLive()) n.copy(rows = n.rows.map { it.pruned() }) else null
    is ConditionGroup -> {
        val kids = n.children.mapNotNull(::pruneNode)
        if (kids.isEmpty()) null else n.copy(children = kids)
    }
}

/** Deep-copies + drops empty conditions/groups; the root always stays a group (possibly with zero
 *  children — a query pruned down to nothing is neutral, i.e. "no filter", same as today). Recurses
 *  into embedded `content_row` rows (R87) via [RowConfig.pruned]. A persisted/saved tree is always
 *  pruned first — it never contains empty nodes. */
fun ConditionGroup.pruned(): ConditionGroup = copy(children = children.mapNotNull(::pruneNode))

/** Prunes this row's own query (if migrated) — used when a `content_row` condition embeds whole
 *  [RowConfig]s verbatim (R87) and they need pruning too. Rows with only the legacy flat shape are
 *  left as-is; migration (not pruning) is what normalizes those, see [RowConfig.migrateQuery]. */
private fun RowConfig.pruned(): RowConfig = query?.let { copy(query = it.pruned()) } ?: this

// ─── Depth (block nesting, root not counted) ───────────────────────────────────

private fun QueryNode.blockDepth(): Int = when (this) {
    is Condition -> 0
    is ConditionGroup -> 1 + (children.maxOfOrNull { it.blockDepth() } ?: 0)
}

/** Max block-nesting depth among this group's top-level blocks — this group itself (typically the
 *  root) isn't counted, matching the editor's cap: top-level block = 1, sub-block = 2, one more
 *  sub-sub-block = 3. A tree respects the cap iff this is `<= 3`. */
fun ConditionGroup.maxBlockDepth(): Int = children.maxOfOrNull { it.blockDepth() } ?: 0

// ─── Legacy migration (one-way, on read) ───────────────────────────────────────

/**
 * Migrates a legacy flat `{match, conditions}` pair to the equivalent tree — lossless.
 * `ALL` -> one single-condition OR-block per condition, AND'd at the root.
 * `ANY` -> one OR-block holding every condition.
 * Recurses into any `content_row` condition's embedded rows ([Condition.rows]), migrating each one
 * that hasn't already been migrated (mirrors the design mockup's `migrateState`).
 */
fun migrateFlatQuery(match: MatchMode, conditions: List<Condition>): ConditionGroup {
    val migrated = conditions.map { it.migrateEmbeddedRows() }
    return when (match) {
        MatchMode.ANY -> ConditionGroup(QueryJoin.AND, children = listOf(ConditionGroup(QueryJoin.OR, children = migrated)))
        MatchMode.ALL -> ConditionGroup(QueryJoin.AND, children = migrated.map { ConditionGroup(QueryJoin.OR, children = listOf(it)) })
    }
}

private fun Condition.migrateEmbeddedRows(): Condition =
    if (rows.isEmpty()) this else copy(rows = rows.map { it.migrateQuery() })

/** This row's own query, migrating its flat `match`/`conditions` on the fly if it hasn't been
 *  migrated yet (`query == null`). Non-destructive — does not mutate/clear the legacy fields; that's
 *  a save-time concern (`RaviloConfigService`). */
fun RowConfig.migrateQuery(): RowConfig = if (query != null) this else copy(query = migrateFlatQuery(match, conditions))

/** This channel's effective query tree: its own [ChannelConfig.query] if already migrated, else the
 *  legacy `match`/`conditions` migrated on the fly. Does not consider the pre-R32 typed single-value
 *  filters (`filterNetwork`/`filterStudio`/`filterGenre`/`filterTag`) — callers that need those as a
 *  final fallback (`HomeFeedService`) check them separately, same as today. */
fun ChannelConfig.effectiveQuery(): ConditionGroup = query ?: migrateFlatQuery(match, conditions)

/** This row's effective query tree — see [ChannelConfig.effectiveQuery]. */
fun RowConfig.effectiveQuery(): ConditionGroup = query ?: migrateFlatQuery(match, conditions)
