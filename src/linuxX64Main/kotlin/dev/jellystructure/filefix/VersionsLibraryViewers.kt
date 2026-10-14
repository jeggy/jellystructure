package dev.jellystructure.filefix

/**
 * Phase 314 (FR-314-15, DEB-875) — the versions library seen from a viewer's side.
 *
 * A merged version is a version of the film **only for a viewer who may see the library it lives in**: measured on
 * 12.1, a viewer denied that library reads the film as one `MediaSource` and gets `NoCompatibleStream` for the
 * version, where a folder-grouped version on the same device profile plays. And playback is negotiated per viewer
 * (`tvTokenForClient` + the device's `jellyfinUserId`), so this reaches Ravilo and not only Jellyfin's own clients.
 *
 * That splits in two, and the split is the whole rule:
 *
 * - **Access is the owner's.** A new user defaults to `EnableAllFolders: true` (measured), so the version plays for
 *   them with nothing written. A *restricted* account whose explicit list omits the library is **reported, never
 *   widened** — its devices get the original, which is a degradation and not a break, and widening a permission the
 *   owner set is not ours to do.
 * - **Display is ours.** `MyMediaExcludes`/`LatestItemsExcludes` both default to `[]`, so without this pass a viewer's
 *   home screen gains a second library whose films are duplicates. Those are display preferences, reversible, and the
 *   only thing jellystructure writes on a viewer's account.
 */
data class ViewerVisibility(
    val id: String,
    val name: String,
    /** `EnableAllFolders` — true means every library, including any added later. */
    val seesAllLibraries: Boolean,
    /** `EnabledFolders` — the explicit list, meaningful only when [seesAllLibraries] is false. */
    val enabledLibraries: List<String>,
    /** Does the viewer's own `MyMediaExcludes` **and** `LatestItemsExcludes` already name the versions library? */
    val excludesVersions: Boolean,
)

/**
 * FR-314-15 — what the pass does about the versions library, per viewer: [toExclude] gets the two display lists
 * written, [restricted] is reported to the owner and otherwise left exactly as it is.
 */
data class VersionsLibraryPlan(val toExclude: List<ViewerVisibility>, val restricted: List<ViewerVisibility>) {
    val nothingToDo: Boolean get() = toExclude.isEmpty() && restricted.isEmpty()
}

/**
 * FR-314-15 — pure: who needs the display excludes, and who cannot see the versions library at all. A viewer who
 * cannot see it needs no exclude (the library is not in their views to begin with), so the two lists never overlap.
 */
fun planVersionsLibraryVisibility(viewers: List<ViewerVisibility>, libraryId: String): VersionsLibraryPlan {
    val (canSee, cannotSee) = viewers.partition { it.seesAllLibraries || libraryId in it.enabledLibraries }
    return VersionsLibraryPlan(toExclude = canSee.filterNot { it.excludesVersions }, restricted = cannotSee)
}

/**
 * FR-314-15 — which Jellyfin library holds the versions root. [rootInJellyfinView] is the root as Jellyfin names it
 * ([jellyfinViewOf]); a library whose location *is* the root or contains it counts, so a root inside an existing
 * library is found rather than silently unmatched.
 */
fun versionsLibraryIdOf(libraries: List<dev.jellystructure.auth.JellyfinLibrary>, rootInJellyfinView: String): String? =
    libraries.firstOrNull { lib ->
        lib.locations.any { loc ->
            val l = loc.trimEnd('/')
            l.isNotEmpty() && (rootInJellyfinView == l || rootInJellyfinView.startsWith("$l/"))
        }
    }?.id

/**
 * FR-314-15 (DEB-875) — the pass: every viewer who can see the versions library gets it excluded from their own
 * *My Media* and *Latest*, and every account that cannot see it is named for the owner. Rate-limited, because it is
 * asserted after every grouping and a grouping is asserted on every pass.
 *
 * Nothing here grants access. A restricted account's devices are served the original instead of the version, which is
 * a degradation and not a break — and it is the owner's to widen, in Jellyfin.
 */
class VersionsLibraryVisibility(
    private val jellyfin: dev.jellystructure.auth.JellyfinClient,
    private val now: () -> Long,
    private val minIntervalS: Long = 3600,
) {
    private var lastRunAt = 0L

    /**
     * FR-314-19 (DEB-986) — the accounts the last completed pass found cannot see the versions library, in the order
     * Jellyfin lists them. The admin card reads this: the log line alone is not a surface, and the decision to leave
     * those accounts on the original is deliberate and indefinite, so it has to be visible where the switch is.
     *
     * Written by the pass and nowhere else — no stored state and no Jellyfin call of its own, which is why it is
     * empty until a pass has run. A pass that finds none clears it, so a permission the owner widens in Jellyfin
     * takes the row away on the next pass.
     *
     * FR-314-22 (DEB-1033) — a pass runs on **every grouping tick that has a version on disk**, which is what makes
     * that *until* bounded: the earlier mechanism ran it only when kind C merged, and the steady state FR-314-21
     * converges on merges nothing, so on prod this field was emptied by a restart and nothing refilled it. Two
     * different bounds come out of the rate limit below, and both are deliberate: after a restart `lastRunAt == 0L`
     * skips it, so the row is back within one grouping tick (fifteen minutes); a permission the owner changes in
     * Jellyfin enters or leaves the row within [minIntervalS], an hour. Still nothing stored — a remembered list
     * would keep naming an account after the owner had widened it, which is the worse failure of the two.
     */
    var restricted: List<String> = emptyList()
        private set

    suspend fun assertNow(cfg: dev.jellystructure.config.AppConfig) {
        val root = cfg.fileFix.versionsRoot() ?: return run { restricted = emptyList() }
        val base = cfg.apiKeys.jellyfinUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() } ?: return
        val token = cfg.apiKeys.jellyfinToken
        val at = now()
        if (lastRunAt != 0L && at - lastRunAt < minIntervalS) return
        lastRunAt = at
        val libraryId = versionsLibraryIdOf(jellyfin.getLibraries(base, token), jellyfinViewOf(root, cfg.libraries))
        if (libraryId == null) {
            restricted = emptyList()   // no library to be shut out of: whatever a previous pass found no longer holds
            dev.jellystructure.log.Logger.warn("file fix: no Jellyfin library holds the versions root $root — a version written there is not grouped with its film (314, FR-314-15)", "jobs")
            return
        }
        val viewers = jellyfin.getUsers(base, token).map { u ->
            ViewerVisibility(
                id = u.id, name = u.name,
                seesAllLibraries = u.policy.enableAllFolders,
                enabledLibraries = u.policy.enabledFolders,
                excludesVersions = u.configuration?.let { libraryId in it.myMediaExcludes && libraryId in it.latestItemsExcludes } == true,
            )
        }
        val plan = planVersionsLibraryVisibility(viewers, libraryId)
        restricted = plan.restricted.map { it.name }   // FR-314-19 — before the early return: "none" is a result too
        if (plan.nothingToDo) return
        var written = 0
        for (v in plan.toExclude) if (jellyfin.addUserDisplayExcludes(base, token, v.id, libraryId)) written++
        if (written > 0) dev.jellystructure.log.Logger.info("file fix: the versions library is now out of My Media and Latest for $written ${if (written == 1) "viewer" else "viewers"} (314, FR-314-15)", "jobs")
        if (plan.restricted.isNotEmpty()) {
            dev.jellystructure.log.Logger.warn(
                "file fix: ${plan.restricted.joinToString { it.name }} cannot see the versions library, so their devices get the original rather than the Dolby Vision version — " +
                    "granting it is the owner's call in Jellyfin (314, FR-314-15)",
                "jobs",
            )
        }
    }
}
