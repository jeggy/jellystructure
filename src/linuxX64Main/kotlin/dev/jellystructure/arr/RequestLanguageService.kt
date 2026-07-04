package dev.jellystructure.arr

import dev.jellystructure.config.ArrConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.RequestLanguageConfig
import dev.jellystructure.config.RequestLanguageIntent
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.RequestLanguageOption
import kotlinx.serialization.Serializable

/** One line of a provisioning preview/result — "what would happen" or "what happened", never "what
 *  will silently happen" (Settings surfaces this before + after applying). */
@Serializable
data class ProvisionPlanLine(val arrKind: String, val kind: String, val name: String, val action: String)

/**
 * Phase 139 — the request-language feature's one orchestration point: resolving which intent a request
 * should use, turning an intent into a Radarr/Sonarr `profileId` for the Seerr payload, auto-provisioning
 * the custom format + cloned quality profile that makes an intent real, and the change-later re-profile.
 * [ArrClient] stays a thin HTTP-primitives client (existing convention); this is the business logic on
 * top of it, mirroring how [dev.jellystructure.seerr.SeerrDiscoverService] sits on [dev.jellystructure.seerr.SeerrClient].
 */
class RequestLanguageService(
    private val configStore: ConfigStore,
    private val arrClient: ArrClient,
) {
    private fun catalog(): RequestLanguageConfig = configStore.current.requestLanguage

    fun intent(id: String?): RequestLanguageIntent? = id?.let { i -> catalog().intents.firstOrNull { it.id == i } }

    /**
     * Effective intent id for a request, in precedence order: explicit pick → this viewer's per-user
     * default (§B) → the global kids default when [isKids] → the catalog's own `default`-flagged intent
     * → its first intent. An empty catalog (feature off) always resolves to null — no steering at all.
     */
    fun resolveIntentId(explicit: String?, viewerDefault: String?, isKids: Boolean): String? {
        val ids = catalog().intents.map { it.id }.toSet()
        if (ids.isEmpty()) return null
        explicit?.takeIf { it in ids }?.let { return it }
        viewerDefault?.takeIf { it in ids }?.let { return it }
        if (isKids) catalog().kidsDefault?.takeIf { it in ids }?.let { return it }
        return catalog().intents.firstOrNull { it.default }?.id ?: catalog().intents.first().id
    }

    /** The TV-facing catalog (flags/labels only) + this viewer's resolved default, for `DiscoverResponse`. */
    fun optionsFor(viewerDefault: String?, isKids: Boolean): Pair<List<RequestLanguageOption>, String?> {
        val opts = catalog().intents.map { RequestLanguageOption(it.id, it.label, it.flag) }
        return opts to resolveIntentId(null, viewerDefault, isKids)
    }

    /**
     * The Radarr/Sonarr `profileId` (+ resolved indexer tag ids) for a resolved intent, to add to the
     * Seerr request payload.
     *
     * Bug fix: a steered intent (non-blank `match`, e.g. "Dansk") whose custom format + scored profile
     * had never been provisioned (the admin hasn't visited Settings ▸ Download tools ▸ Request
     * languages ▸ "Set up profiles" yet) used to silently fall back to resolving [baseProfile] *by
     * name* — the plain, unscored profile, identical to no language preference at all. For a `strict`
     * intent this is the worst possible failure mode: the viewer explicitly picked "Dansk", got zero
     * enforcement, and no error surfaced anywhere. Verified live: requesting "Mood Swing 2" as Dansk
     * grabbed a plain English FjordLeech release, because Radarr had no "Request language: Dansk"
     * custom format or "HD-1080p · Dansk" profile at all — only the stock defaults. Now provisions
     * on-demand (same idempotent create-or-update as the bulk [provision] flow) the first time an
     * intent is actually used, instead of requiring the admin to run it first. A configured tag name
     * with no matching *arr tag is silently dropped (tags are traffic hygiene, never load-bearing).
     */
    suspend fun profileFor(intentId: String?, mediaKind: MediaKind): Pair<Int?, List<Int>> {
        val i = intent(intentId) ?: return null to emptyList()
        val cfg = when (mediaKind) {
            MediaKind.MOVIE -> configStore.current.radarr
            MediaKind.SERIES -> configStore.current.sonarr
        }?.takeIf { it.enabled } ?: return (if (mediaKind == MediaKind.MOVIE) i.radarrProfileId else i.sonarrProfileId) to emptyList()
        val arrKind = if (mediaKind == MediaKind.MOVIE) "radarr" else "sonarr"
        var storedProfileId = if (mediaKind == MediaKind.MOVIE) i.radarrProfileId else i.sonarrProfileId
        if (storedProfileId == null && i.match.isNotBlank()) {
            provisionOneIntent(cfg, arrKind, i)?.let { (cfId, profId) ->
                storedProfileId = profId
                persistProvisioned(i.id, arrKind, cfId, profId)
            }
        }
        val profileId = storedProfileId ?: arrClient.findQualityProfileIdByName(cfg.url, cfg.apiKey, i.baseProfile)
        val tagIds = if (i.tags.isEmpty()) emptyList() else {
            val existing = arrClient.getTags(cfg.url, cfg.apiKey)
            i.tags.mapNotNull { name -> existing.firstOrNull { it.label.equals(name, ignoreCase = true) }?.id }
        }
        return profileId to tagIds
    }

    /** Persists one intent's newly-provisioned ids back into config (the on-demand path in [profileFor]
     *  — the bulk [runProvisioning] does the equivalent for every intent at once). */
    private suspend fun persistProvisioned(intentId: String, arrKind: String, customFormatId: Int, profileId: Int) {
        val cat = configStore.current.requestLanguage
        val intents = cat.intents.map { i ->
            if (i.id != intentId) i
            else if (arrKind == "radarr") i.copy(radarrFormatId = customFormatId, radarrProfileId = profileId)
            else i.copy(sonarrFormatId = customFormatId, sonarrProfileId = profileId)
        }
        configStore.update(configStore.current.copy(requestLanguage = cat.copy(intents = intents)))
    }

    // ── Provisioning (Settings ▸ Download tools ▸ Request languages ▸ Set up profiles) ──────────────

    suspend fun preview(): List<ProvisionPlanLine> = runProvisioning(apply = false)

    suspend fun provision(): List<ProvisionPlanLine> = runProvisioning(apply = true)

    private suspend fun runProvisioning(apply: Boolean): List<ProvisionPlanLine> {
        val cat = catalog()
        val lines = mutableListOf<ProvisionPlanLine>()
        var intents = cat.intents
        configStore.current.radarr?.takeIf { it.enabled }?.let { cfg ->
            val (l, ids) = provisionOneArr(cfg, "radarr", cat.intents, apply)
            lines += l
            if (apply) intents = intents.map { i -> ids[i.id]?.let { (cf, pr) -> i.copy(radarrFormatId = cf ?: i.radarrFormatId, radarrProfileId = pr ?: i.radarrProfileId) } ?: i }
        }
        configStore.current.sonarr?.takeIf { it.enabled }?.let { cfg ->
            val (l, ids) = provisionOneArr(cfg, "sonarr", cat.intents, apply)
            lines += l
            if (apply) intents = intents.map { i -> ids[i.id]?.let { (cf, pr) -> i.copy(sonarrFormatId = cf ?: i.sonarrFormatId, sonarrProfileId = pr ?: i.sonarrProfileId) } ?: i }
        }
        if (apply) configStore.update(configStore.current.copy(requestLanguage = cat.copy(intents = intents)))
        return lines
    }

    /** Provision (or preview) every non-blank-`match` intent against one *arr. Returns the plan lines
     *  plus, per intent id, the resulting `(customFormatId, qualityProfileId)` — null/null on preview. */
    private suspend fun provisionOneArr(
        cfg: ArrConfig,
        arrKind: String,
        intents: List<RequestLanguageIntent>,
        apply: Boolean,
    ): Pair<List<ProvisionPlanLine>, Map<String, Pair<Int?, Int?>>> {
        val lines = mutableListOf<ProvisionPlanLine>()
        val ids = mutableMapOf<String, Pair<Int?, Int?>>()
        val existingCfs = arrClient.getCustomFormats(cfg.url, cfg.apiKey)
        val existingProfiles = arrClient.getQualityProfiles(cfg.url, cfg.apiKey)
        for (i in intents) {
            if (i.match.isBlank()) continue  // "original"-shaped: nothing to provision, uses baseProfile as-is
            val cfName = "Request language: ${i.label}"
            val profileName = "${i.baseProfile} · ${i.label}"
            val cfExists = existingCfs.any { it.name == cfName }
            val profExists = existingProfiles.any { it.name.equals(profileName, ignoreCase = true) }
            if (!apply) {
                lines += ProvisionPlanLine(arrKind, "customformat", cfName, if (cfExists) "update" else "create")
                lines += ProvisionPlanLine(arrKind, "profile", profileName, if (profExists) "update" else "create")
                continue
            }
            val cfId = arrClient.upsertReleaseTitleCustomFormat(cfg.url, cfg.apiKey, cfName, i.match)
            if (cfId == null) {
                lines += ProvisionPlanLine(arrKind, "customformat", cfName, "failed")
                continue
            }
            lines += ProvisionPlanLine(arrKind, "customformat", cfName, if (cfExists) "updated" else "created")
            val profId = arrClient.upsertScoredQualityProfile(cfg.url, cfg.apiKey, i.baseProfile, profileName, cfId, cfName, i.strict)
            lines += ProvisionPlanLine(arrKind, "profile", profileName, if (profId != null) (if (profExists) "updated" else "created") else "failed")
            ids[i.id] = cfId to profId
        }
        return lines to ids
    }

    /** Single-intent create-or-update, shared by the bulk [provisionOneArr] loop above and [profileFor]'s
     *  on-demand path — same idempotent upsert either way, just scoped to one intent instead of the
     *  whole catalog. Returns null if the custom format or profile upsert failed. */
    private suspend fun provisionOneIntent(cfg: ArrConfig, arrKind: String, i: RequestLanguageIntent): Pair<Int, Int>? {
        val cfName = "Request language: ${i.label}"
        val profileName = "${i.baseProfile} · ${i.label}"
        val cfId = arrClient.upsertReleaseTitleCustomFormat(cfg.url, cfg.apiKey, cfName, i.match) ?: return null
        val profId = arrClient.upsertScoredQualityProfile(cfg.url, cfg.apiKey, i.baseProfile, profileName, cfId, cfName, i.strict) ?: return null
        return cfId to profId
    }

    // ── Change-later (§E): switch a still-waiting request's language ────────────────────────────────

    /** Re-points an already-added movie/series at [newIntentId]'s profile and triggers a fresh search.
     *  Returns false if the intent/profile/item can't be resolved — the caller surfaces that as failure,
     *  it never partially applies (profile set but no search, or vice versa). */
    suspend fun changeLanguage(mediaKind: MediaKind, tmdbId: Int, newIntentId: String): Boolean {
        val (profileId, _) = profileFor(newIntentId, mediaKind)
        if (profileId == null) return false
        return when (mediaKind) {
            MediaKind.MOVIE -> {
                val radarr = configStore.current.radarr?.takeIf { it.enabled } ?: return false
                val movieId = arrClient.findMovieId(radarr.url, radarr.apiKey, tmdbId) ?: return false
                arrClient.setMovieQualityProfile(radarr.url, radarr.apiKey, movieId, profileId) &&
                    arrClient.searchMovieNow(radarr.url, radarr.apiKey, movieId)
            }
            MediaKind.SERIES -> {
                val sonarr = configStore.current.sonarr?.takeIf { it.enabled } ?: return false
                val seriesId = arrClient.findSeriesIdByTmdbId(sonarr.url, sonarr.apiKey, tmdbId) ?: return false
                arrClient.setSeriesQualityProfile(sonarr.url, sonarr.apiKey, seriesId, profileId) &&
                    arrClient.searchSeriesNow(sonarr.url, sonarr.apiKey, seriesId)
            }
        }
    }
}
