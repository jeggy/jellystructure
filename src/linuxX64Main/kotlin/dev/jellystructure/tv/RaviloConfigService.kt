package dev.jellystructure.tv

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.shared.tv.BehaviourOverlay
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.RaviloThemes
import dev.jellystructure.shared.tv.ResolvedBehaviour
import dev.jellystructure.shared.tv.ResolvedBehaviourField
import dev.jellystructure.shared.tv.RowConfig
import dev.jellystructure.shared.tv.RowKind
import dev.jellystructure.shared.tv.Skin
import dev.jellystructure.shared.tv.SkipMode
import dev.jellystructure.shared.tv.TileShape
import dev.jellystructure.shared.tv.maxBlockDepth
import dev.jellystructure.shared.tv.pruned
import kotlinx.serialization.json.Json
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.timespec
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr

private val json = Json { ignoreUnknownKeys = true }

/** R51 sentinel key for the global (all-users) layout. Not a real Jellyfin user id. */
const val GLOBAL_USER_ID = "__global__"

/** R182 — the only valid Skip Intro/Credits countdown lengths. */
val SKIP_SECS_OPTIONS = listOf(4, 6, 8)

private val DEFAULT_ROWS = listOf(
    RowConfig(id = "continue",  kind = RowKind.CONTINUE,    title = "Continue Watching", enabled = true, order = 0),
    RowConfig(id = "newly-all", kind = RowKind.NEWLY_ADDED, title = "Newly Added",       enabled = true, order = 1),
)

private val DEFAULT_CONFIG = RaviloConfig(
    heroes = emptyList(),
    channels = emptyList(),
    rows = DEFAULT_ROWS,
    mergeNewlyAdded = false,
    defaultSkin = Skin.AURORA,
    allowSkinOverride = true,
    showContinueProgress = true,
    tileShape = TileShape.POSTER,
    // R338 (D5) — a new install follows the system: Daylight when it is light, Aurora when it is dark. An existing
    // server is migrated to follow OFF instead ([RaviloConfigService.migrateThemeDefaults]).
    defaultTheme = RaviloThemes.AURORA,
    defaultThemeFollow = true,
    defaultThemeLight = RaviloThemes.DAYLIGHT,
    defaultThemeDark = RaviloThemes.AURORA,
)

class RaviloConfigService(
    private val db: JellystructureDb,
    private val eventBus: TvEventBus? = null,
    // Phase 139 — resolves the request-language catalog's default-flagged intent id, the "global
    // default" fallback for the requestLanguage overlay field below (unlike uiLanguage/skin/etc., that
    // fallback isn't a RaviloConfig field — it lives in AppConfig's admin-managed catalog). Null (not
    // wired) ⇒ the field always reads as "global" with an empty-string default, same as an empty catalog.
    private val requestLanguageService: dev.jellystructure.arr.RequestLanguageService? = null,
    // Phase 218 (FR-218-3) — resolves the installation's cast capability (from config.toml, never from
    // stored per-user JSON) on every read path, the way focusDetail is resolved. Null = never a button.
    private val castCapability: (() -> dev.jellystructure.shared.tv.CastCapability?)? = null,
    // Phase 236 (dev review item 9) / R265 (dev review item 5) — the viewer's screens capability, resolved
    // on every per-user read the way [castCapability] is. It was specified, given a DTO, and never set:
    // `RaviloConfig.screens` reached every phone as null, so the sheet's TV tiers, *Add a TV* and R265's
    // reconnect were unreachable in production (found 2026-09-25 finishing R265). Null = not wired.
    private val screensCapability: ((userId: String) -> dev.jellystructure.shared.tv.ScreensCapability?)? = null,
) {

    /**
     * R51/R162: resolve config for a viewer.
     * - If the user has their own **layout** record → use it (full override) as the base.
     * - Otherwise fall through to the global record (`__global__`).
     * - If neither exists, seed and return the global default.
     * - R162: the five behaviour & preference fields (skin/tileShape/showContinueProgress/
     *   autoplayNext/uiLanguage) are then **always** overwritten with the resolved behaviour overlay
     *   (viewer entry → admin entry → global default) — independent of the layout record above, so
     *   a stale value left in an old per-user record (or the global record itself) never wins.
     */
    fun getConfig(userId: String): RaviloConfig {
        val base = if (userId != GLOBAL_USER_ID) {
            val userStored = db.raviloConfigQueries.getByUser(userId).executeAsOneOrNull()
            if (userStored != null) {
                runCatching { json.decodeFromString<RaviloConfig>(userStored) }.getOrDefault(getGlobalConfig())
            } else getGlobalConfig()
        } else getGlobalConfig()
        if (userId == GLOBAL_USER_ID) return base
        val resolved = resolveBehaviour(userId)
        // R338 (dev review 1) — an app from before R338 draws only [Skin]: give it the mirror of the viewer's dark pick
        // (those apps were dark-only), and only when something about the viewer's look is not the global default.
        val themeSources = listOf(resolved.skin.source, resolved.theme.source, resolved.themeFollow.source, resolved.themeLight.source, resolved.themeDark.source)
        // allowSkinOverride gates the theme settings as it gates skin: off, every viewer gets the global defaults.
        val own = base.allowSkinOverride
        val g = if (own) null else getGlobalConfig()
        return base.copy(
            viewerSkinOverride = RaviloThemes.legacySkin(resolved.themeDark.value).takeIf { themeSources.any { it != "global" } },
            theme = g?.globalTheme() ?: resolved.theme.value,
            themeFollow = g?.globalThemeFollow() ?: resolved.themeFollow.value,
            themeLight = g?.globalThemeLight() ?: resolved.themeLight.value,
            themeDark = g?.globalThemeDark() ?: resolved.themeDark.value,
            showContinueProgress = resolved.showContinueProgress.value,
            autoplayNext = resolved.autoplayNext.value,
            tileShape = resolved.tileShape.value,
            uiLanguage = resolved.uiLanguage.value,
            skipIntro = resolved.skipIntro.value,
            skipCredits = resolved.skipCredits.value,
            skipSecs = resolved.skipSecs.value,
        ).withResolvedFocusDetail().let { cfg -> screensCapability?.invoke(userId)?.let { cfg.copy(screens = it) } ?: cfg }
    }

    fun getGlobalConfig(): RaviloConfig {
        val stored = db.raviloConfigQueries.getByUser(GLOBAL_USER_ID).executeAsOneOrNull()
        if (stored != null) {
            return runCatching { json.decodeFromString<RaviloConfig>(stored) }.getOrDefault(DEFAULT_CONFIG).withResolvedFocusDetail()
        }
        save(GLOBAL_USER_ID, DEFAULT_CONFIG)
        return DEFAULT_CONFIG.withResolvedFocusDetail()
    }

    /** FR-202-2 — never trust a stored/round-tripped `focus_detail` value; always recompute it from the
     *  two admin-side booleans before a config leaves the server, on every read path. */
    private fun RaviloConfig.withResolvedFocusDetail(): RaviloConfig = copy(focusDetail = resolvedFocusDetail(), cast = castCapability?.invoke())

    fun hasCustomConfig(userId: String): Boolean =
        userId != GLOBAL_USER_ID && db.raviloConfigQueries.getByUser(userId).executeAsOneOrNull() != null

    fun removeCustomConfig(userId: String) {
        if (userId == GLOBAL_USER_ID) return
        db.raviloConfigQueries.deleteByUser(userId)
        eventBus?.notifyConfigChanged(userId)
    }

    fun save(userId: String, config: RaviloConfig) {
        db.raviloConfigQueries.upsert(
            user_id = userId,
            json = json.encodeToString(normalize(config)),
            updated_at = nowMs(),
        )
        // R33/R51: a global write notifies all users on the global layout; a per-user write notifies just them.
        if (userId == GLOBAL_USER_ID) {
            eventBus?.notifyGlobalConfigChanged()
        } else {
            eventBus?.notifyConfigChanged(userId)
        }
    }

    /**
     * Clamp ranges and assign contiguous ordering before persisting, so the stored layout is always
     * well-formed regardless of which surface wrote it. Idempotent. Drops heroes with a blank itemId.
     */
    fun normalize(config: RaviloConfig): RaviloConfig = config.copy(
        // Phase 218 — server-resolved on read, never persisted (a stale stored value could resurrect a
        // cast button after the admin switched Chromecast off).
        cast = null,
        // R338 — the viewer's resolved theme fields are written on every read, never stored; the global defaults are
        // kept only when they name a theme of the right kind, and default_skin follows default_theme as its mirror.
        theme = null, themeFollow = null, themeLight = null, themeDark = null,
        defaultTheme = config.defaultTheme?.takeIf { RaviloThemes.isKnown(it) },
        defaultThemeLight = config.defaultThemeLight?.takeIf { RaviloThemes.isLight(it) },
        defaultThemeDark = config.defaultThemeDark?.takeIf { RaviloThemes.isDark(it) },
        defaultSkin = config.defaultTheme?.takeIf { RaviloThemes.isKnown(it) }
            ?.let { RaviloThemes.legacySkin(it, config.defaultThemeDark) } ?: config.defaultSkin,
        heroes = config.heroes
            .filter { it.itemId.isNotBlank() }
            .mapIndexed { i, h -> h.copy(order = i) },
        channels = config.channels.mapIndexed { i, c ->
            c.copy(
                order = i,
                brandColor = sanitizeBrandColor(c.brandColor),
                // Phase 140 — a persisted tree is always pruned first, so it never contains empty
                // conditions/groups (round-tripping a saved query gives back exactly what was live).
                query = c.query?.pruned(),
                pageHero = c.pageHero?.let { h ->
                    h.copy(items = h.items.filter { it.itemId.isNotBlank() }.mapIndexed { j, item -> item.copy(order = j) })
                },
                paddingLogo = c.paddingLogo?.let { p -> p.copy(top = p.top.coerceIn(0,40), right = p.right.coerceIn(0,40), bottom = p.bottom.coerceIn(0,40), left = p.left.coerceIn(0,40)) },
                paddingText = c.paddingText?.let { p -> p.copy(top = p.top.coerceIn(0,40), right = p.right.coerceIn(0,40), bottom = p.bottom.coerceIn(0,40), left = p.left.coerceIn(0,40)) },
                // Phase 225 (FR-225-11) — pins deduplicated, first occurrence wins; an absent sort/limit STAYS absent.
                rows = c.rows?.let { rc -> rc.copy(items = rc.items.map { it.copy(query = it.query?.pruned(), pinned = it.pinned.distinct()) }) },
            )
        },
        rows = config.rows.mapIndexed { i, r -> r.copy(order = i, query = r.query?.pruned(), pinned = r.pinned.distinct()) },
        heroHeightPct = config.heroHeightPct.coerceIn(40, 100),
        autoAdvanceSeconds = config.autoAdvanceSeconds.coerceIn(0, 120),
        // R174 — poster-grid items per row: landscape 2..10, portrait 1..4 (fewer, phone-sized).
        gridColumns = config.gridColumns.coerceIn(2, 10),
        // R159 — portrait can go smaller than landscape's 40 floor (a phone hero at 40% is still huge).
        portrait = config.portrait?.let { p ->
            p.copy(
                heroHeightPct = p.heroHeightPct?.coerceIn(20, 100),
                gridColumns = p.gridColumns?.coerceIn(1, 4),
            )
        },
        // R182 — the countdown is one of exactly three lengths; snap a stray value (hand-edited config,
        // or a future UI slider) to the nearest of them rather than silently accepting an odd number.
        skipSecs = snapSkipSecs(config.skipSecs),
        focusDetailDelayMs = clampFocusDetailDelayMs(config.focusDetailDelayMs),
    )

    private fun snapSkipSecs(v: Int): Int = SKIP_SECS_OPTIONS.minByOrNull { kotlin.math.abs(it - v) } ?: 6

    /**
     * Validate an incoming admin-edited config before persisting. Returns an error message, or null
     * if the config is sound. Channels and rows must carry unique, non-blank ids (the TV and the
     * editor both key off them); an empty id would silently collide on the next edit.
     */
    fun validate(config: RaviloConfig): String? {
        val channelIds = config.channels.map { it.id }
        if (channelIds.any { it.isBlank() }) return "Every channel must have an id."
        if (channelIds.size != channelIds.toSet().size) return "Channel ids must be unique."
        val rowIds = config.rows.map { it.id }
        if (rowIds.any { it.isBlank() }) return "Every row must have an id."
        if (rowIds.size != rowIds.toSet().size) return "Row ids must be unique."
        // Phase 137 — a parameterised Seerr feed (genre/language/studio/network) needs its value; the
        // endpoint itself is already type-safe (an invalid enum name fails deserialization earlier).
        val badFeed = config.discover.feeds.firstOrNull { it.endpoint.needsParam && it.param.isNullOrBlank() }
        if (badFeed != null) return "Request feed '${badFeed.name}' (${badFeed.endpoint}) needs a value."
        // Phase 140 — the editor caps block nesting at 3 (block -> sub-block -> one more); enforce it
        // here too so a malformed/hand-edited payload can't bypass the editor's own limit. Depth-agnostic
        // model/evaluator (maxBlockDepth is just a validation helper), checked over every channel query,
        // every per-channel row query, and every top-level Home row query.
        val allQueries = config.channels.mapNotNull { it.query } +
            config.channels.flatMap { it.rows?.items.orEmpty() }.mapNotNull { it.query } +
            config.rows.mapNotNull { it.query }
        if (allQueries.any { it.maxBlockDepth() > 3 }) return "A filter is nested too deep (max 3 levels: block, sub-block, one more)."
        // Phase 225 (FR-225-11) — sort key, limit range, pins ≤ limit, and no order at all on a system row.
        (config.rows + config.channels.flatMap { it.rows?.items.orEmpty() }).firstNotNullOfOrNull { dev.jellystructure.shared.tv.RowOrder.problem(it) }?.let { return it }
        return null
    }

    // ── R162: field-level behaviour & preferences overlay (independent of the layout record above) ──

    fun getBehaviourOverlay(userId: String): BehaviourOverlay {
        val stored = db.raviloBehaviourQueries.getByUser(userId).executeAsOneOrNull() ?: return BehaviourOverlay()
        return runCatching { json.decodeFromString<BehaviourOverlay>(stored) }.getOrDefault(BehaviourOverlay())
    }

    private fun saveBehaviourOverlay(userId: String, overlay: BehaviourOverlay) {
        db.raviloBehaviourQueries.upsert(user_id = userId, json = json.encodeToString(overlay), updated_at = nowMs())
        eventBus?.notifyConfigChanged(userId)
    }

    /** Resolution: viewer-tagged entry → admin-tagged entry → global default. Never touches the
     *  `ravilo_config` layout table — this is the whole point of the R162 split. */
    fun resolveBehaviour(userId: String): ResolvedBehaviour {
        val overlay = getBehaviourOverlay(userId)
        val global = getGlobalConfig()
        fun <T> field(value: T?, writer: String?, fallback: T): ResolvedBehaviourField<T> =
            if (value != null) ResolvedBehaviourField(value, writer ?: "admin") else ResolvedBehaviourField(fallback, "global")
        val catalogDefault = requestLanguageService?.resolveIntentId(null, null, false).orEmpty()
        return ResolvedBehaviour(
            uiLanguage = field(overlay.uiLanguage, overlay.uiLanguageWriter, global.uiLanguage),
            skin = field(overlay.skin, overlay.skinWriter, global.defaultSkin),
            // R338 (FR-R338-6) — a skin a viewer picked before R338 stands in for their one pick and their dark pick
            // until they pick a theme (every skin is dark), so nobody's look changes on the upgrade.
            theme = if (overlay.theme != null) field(overlay.theme, overlay.themeWriter, global.globalTheme())
                else field(overlay.skin?.let { RaviloThemes.fromSkin(it) }, overlay.skinWriter, global.globalTheme()),
            themeFollow = field(overlay.themeFollow, overlay.themeFollowWriter, global.globalThemeFollow()),
            themeLight = field(overlay.themeLight, overlay.themeLightWriter, global.globalThemeLight()),
            themeDark = if (overlay.themeDark != null) field(overlay.themeDark, overlay.themeDarkWriter, global.globalThemeDark())
                else field(overlay.skin?.let { RaviloThemes.fromSkin(it) }, overlay.skinWriter, global.globalThemeDark()),
            tileShape = field(overlay.tileShape, overlay.tileShapeWriter, global.tileShape),
            showContinueProgress = field(overlay.showContinueProgress, overlay.showContinueProgressWriter, global.showContinueProgress),
            autoplayNext = field(overlay.autoplayNext, overlay.autoplayNextWriter, global.autoplayNext),
            skipIntro = field(overlay.skipIntro, overlay.skipIntroWriter, global.skipIntro),
            skipCredits = field(overlay.skipCredits, overlay.skipCreditsWriter, global.skipCredits),
            skipSecs = field(overlay.skipSecs, overlay.skipSecsWriter, global.skipSecs),
            requestLanguage = field(overlay.requestLanguage, overlay.requestLanguageWriter, catalogDefault),
        )
    }

    /**
     * R162 (bug fix — was `applyViewerSettings`): apply the viewer-tweakable settings from
     * `PUT /api/tv/settings`. Writes **only** to the behaviour overlay, tagged `"viewer"` — never
     * reads or writes the `ravilo_config` layout table, so a global-layout viewer changing a single
     * setting from the on-TV Settings screen no longer snapshots the whole resolved layout into a
     * personal record (the R141 §D orphaning trap). Setting a value equal to the current global
     * default clears back to "follow global" instead of storing a stale override.
     */
    fun applyViewerSettings(
        userId: String, skin: Skin?, showContinueProgress: Boolean?, autoplayNext: Boolean?, tileShape: TileShape?, uiLanguage: String? = null,
        theme: String? = null, themeFollow: Boolean? = null, themeLight: String? = null, themeDark: String? = null,
    ) {
        val global = getGlobalConfig()
        var current = getBehaviourOverlay(userId)
        // R338 — the first theme write from a new app turns a skin picked before R338 into the theme fields it stood in
        // for, then drops it; otherwise a later pick equal to the global default would clear itself and let the old
        // skin show through again (see resolveBehaviour).
        val themeWrite = theme != null || themeFollow != null || themeLight != null || themeDark != null
        val legacySkin = current.skin
        if (themeWrite && legacySkin != null) {
            val id = RaviloThemes.fromSkin(legacySkin)
            current = current.copy(
                theme = current.theme ?: id, themeWriter = current.themeWriter ?: current.skinWriter,
                themeDark = current.themeDark ?: id, themeDarkWriter = current.themeDarkWriter ?: current.skinWriter,
                skin = null, skinWriter = null,
            )
        }
        // R338 (D6) — an app from before R338 picks a skin on a dark-only screen: that is a dark pick, and also the one
        // pick while the viewer is not following the system and their one pick is dark — the same rule the new TV uses.
        var inTheme = theme
        var inThemeDark = themeDark
        if (skin != null && !themeWrite) {
            val r = resolveBehaviour(userId)
            inThemeDark = RaviloThemes.fromSkin(skin)
            if (!r.themeFollow.value && RaviloThemes.isDark(r.theme.value)) inTheme = RaviloThemes.fromSkin(skin)
        }
        fun <T> next(incoming: T?, curValue: T?, curWriter: String?, globalDefault: T): Pair<T?, String?> = when {
            incoming == null -> curValue to curWriter
            incoming == globalDefault -> null to null   // follow-global is sticky (FR-R162-3)
            else -> incoming to "viewer"
        }
        val (uiLang, uiLangW) = next(uiLanguage, current.uiLanguage, current.uiLanguageWriter, global.uiLanguage)
        val (sk, skW) = next(skin, current.skin, current.skinWriter, global.defaultSkin)
        val (ts, tsW) = next(tileShape, current.tileShape, current.tileShapeWriter, global.tileShape)
        val (scp, scpW) = next(showContinueProgress, current.showContinueProgress, current.showContinueProgressWriter, global.showContinueProgress)
        val (apn, apnW) = next(autoplayNext, current.autoplayNext, current.autoplayNextWriter, global.autoplayNext)
        val (th, thW) = next(inTheme, current.theme, current.themeWriter, global.globalTheme())
        val (thf, thfW) = next(themeFollow, current.themeFollow, current.themeFollowWriter, global.globalThemeFollow())
        val (thl, thlW) = next(themeLight, current.themeLight, current.themeLightWriter, global.globalThemeLight())
        val (thd, thdW) = next(inThemeDark, current.themeDark, current.themeDarkWriter, global.globalThemeDark())
        saveBehaviourOverlay(
            userId,
            current.copy(
                uiLanguage = uiLang, uiLanguageWriter = uiLangW,
                skin = sk, skinWriter = skW,
                theme = th, themeWriter = thW,
                themeFollow = thf, themeFollowWriter = thfW,
                themeLight = thl, themeLightWriter = thlW,
                themeDark = thd, themeDarkWriter = thdW,
                tileShape = ts, tileShapeWriter = tsW,
                showContinueProgress = scp, showContinueProgressWriter = scpW,
                autoplayNext = apn, autoplayNextWriter = apnW,
            ),
        )
    }

    /** Admin config-editor override — same "equal to global ⇒ follow global" stickiness. Refuses to
     *  overwrite a viewer-tagged entry (the editor's only action on those is [resetBehaviourField]). */
    private fun <T> setAdminBehaviourOverride(viewerValue: T?, viewerWriter: String?, incoming: T, globalDefault: T): Pair<T?, String?> {
        if (viewerWriter == "viewer") return viewerValue to viewerWriter
        return if (incoming == globalDefault) null to null else incoming to "admin"
    }

    fun setAdminSkin(userId: String, value: Skin) {
        val cur = getBehaviourOverlay(userId); val global = getGlobalConfig()
        val (v, w) = setAdminBehaviourOverride(cur.skin, cur.skinWriter, value, global.defaultSkin)
        saveBehaviourOverlay(userId, cur.copy(skin = v, skinWriter = w))
    }
    // R338 — the theme settings, admin side: same stickiness, same refusal to overwrite a viewer's entry.
    fun setAdminTheme(userId: String, value: String) {
        if (!RaviloThemes.isKnown(value)) return
        val cur = getBehaviourOverlay(userId); val global = getGlobalConfig()
        val (v, w) = setAdminBehaviourOverride(cur.theme, cur.themeWriter, value, global.globalTheme())
        saveBehaviourOverlay(userId, cur.copy(theme = v, themeWriter = w))
    }
    fun setAdminThemeFollow(userId: String, value: Boolean) {
        val cur = getBehaviourOverlay(userId); val global = getGlobalConfig()
        val (v, w) = setAdminBehaviourOverride(cur.themeFollow, cur.themeFollowWriter, value, global.globalThemeFollow())
        saveBehaviourOverlay(userId, cur.copy(themeFollow = v, themeFollowWriter = w))
    }
    fun setAdminThemeLight(userId: String, value: String) {
        if (!RaviloThemes.isLight(value)) return
        val cur = getBehaviourOverlay(userId); val global = getGlobalConfig()
        val (v, w) = setAdminBehaviourOverride(cur.themeLight, cur.themeLightWriter, value, global.globalThemeLight())
        saveBehaviourOverlay(userId, cur.copy(themeLight = v, themeLightWriter = w))
    }
    fun setAdminThemeDark(userId: String, value: String) {
        if (!RaviloThemes.isDark(value)) return
        val cur = getBehaviourOverlay(userId); val global = getGlobalConfig()
        val (v, w) = setAdminBehaviourOverride(cur.themeDark, cur.themeDarkWriter, value, global.globalThemeDark())
        saveBehaviourOverlay(userId, cur.copy(themeDark = v, themeDarkWriter = w))
    }
    fun setAdminTileShape(userId: String, value: TileShape) {
        val cur = getBehaviourOverlay(userId); val global = getGlobalConfig()
        val (v, w) = setAdminBehaviourOverride(cur.tileShape, cur.tileShapeWriter, value, global.tileShape)
        saveBehaviourOverlay(userId, cur.copy(tileShape = v, tileShapeWriter = w))
    }
    fun setAdminShowContinueProgress(userId: String, value: Boolean) {
        val cur = getBehaviourOverlay(userId); val global = getGlobalConfig()
        val (v, w) = setAdminBehaviourOverride(cur.showContinueProgress, cur.showContinueProgressWriter, value, global.showContinueProgress)
        saveBehaviourOverlay(userId, cur.copy(showContinueProgress = v, showContinueProgressWriter = w))
    }
    fun setAdminAutoplayNext(userId: String, value: Boolean) {
        val cur = getBehaviourOverlay(userId); val global = getGlobalConfig()
        val (v, w) = setAdminBehaviourOverride(cur.autoplayNext, cur.autoplayNextWriter, value, global.autoplayNext)
        saveBehaviourOverlay(userId, cur.copy(autoplayNext = v, autoplayNextWriter = w))
    }
    fun setAdminUiLanguage(userId: String, value: String) {
        val cur = getBehaviourOverlay(userId); val global = getGlobalConfig()
        val (v, w) = setAdminBehaviourOverride(cur.uiLanguage, cur.uiLanguageWriter, value, global.uiLanguage)
        saveBehaviourOverlay(userId, cur.copy(uiLanguage = v, uiLanguageWriter = w))
    }

    /** Phase 139 — admin-editor-only per-viewer default request language (no on-TV Settings control
     *  yet, unlike the other fields' viewer-writable twins). */
    fun setAdminRequestLanguage(userId: String, value: String) {
        val cur = getBehaviourOverlay(userId)
        val catalogDefault = requestLanguageService?.resolveIntentId(null, null, false).orEmpty()
        val (v, w) = setAdminBehaviourOverride(cur.requestLanguage, cur.requestLanguageWriter, value, catalogDefault)
        saveBehaviourOverlay(userId, cur.copy(requestLanguage = v, requestLanguageWriter = w))
    }

    /** R182 — admin-editor-only Skip Intro / Skip Credits + countdown length (no on-TV Settings
     *  control, same shape as [setAdminRequestLanguage]). */
    fun setAdminSkipIntro(userId: String, value: SkipMode) {
        val cur = getBehaviourOverlay(userId); val global = getGlobalConfig()
        val (v, w) = setAdminBehaviourOverride(cur.skipIntro, cur.skipIntroWriter, value, global.skipIntro)
        saveBehaviourOverlay(userId, cur.copy(skipIntro = v, skipIntroWriter = w))
    }
    fun setAdminSkipCredits(userId: String, value: SkipMode) {
        val cur = getBehaviourOverlay(userId); val global = getGlobalConfig()
        val (v, w) = setAdminBehaviourOverride(cur.skipCredits, cur.skipCreditsWriter, value, global.skipCredits)
        saveBehaviourOverlay(userId, cur.copy(skipCredits = v, skipCreditsWriter = w))
    }
    fun setAdminSkipSecs(userId: String, value: Int) {
        val cur = getBehaviourOverlay(userId); val global = getGlobalConfig()
        val (v, w) = setAdminBehaviourOverride(cur.skipSecs, cur.skipSecsWriter, value, global.skipSecs)
        saveBehaviourOverlay(userId, cur.copy(skipSecs = v, skipSecsWriter = w))
    }

    /** Reset (clears back to "follow global") — works on both admin- and viewer-tagged entries; this
     *  is the editor's only action on a viewer-set value. */
    fun resetBehaviourField(userId: String, field: String) {
        val cur = getBehaviourOverlay(userId)
        val next = when (field) {
            "ui_language" -> cur.copy(uiLanguage = null, uiLanguageWriter = null)
            "skin" -> cur.copy(skin = null, skinWriter = null)
            "theme" -> cur.copy(theme = null, themeWriter = null)
            "theme_follow" -> cur.copy(themeFollow = null, themeFollowWriter = null)
            "theme_light" -> cur.copy(themeLight = null, themeLightWriter = null)
            "theme_dark" -> cur.copy(themeDark = null, themeDarkWriter = null)
            "tile_shape" -> cur.copy(tileShape = null, tileShapeWriter = null)
            "show_continue_progress" -> cur.copy(showContinueProgress = null, showContinueProgressWriter = null)
            "autoplay_next" -> cur.copy(autoplayNext = null, autoplayNextWriter = null)
            "request_language" -> cur.copy(requestLanguage = null, requestLanguageWriter = null)
            "skip_intro" -> cur.copy(skipIntro = null, skipIntroWriter = null)
            "skip_credits" -> cur.copy(skipCredits = null, skipCreditsWriter = null)
            "skip_secs" -> cur.copy(skipSecs = null, skipSecsWriter = null)
            else -> cur
        }
        saveBehaviourOverlay(userId, next)
    }

    /**
     * R162 migration (best-effort, idempotent — safe to call repeatedly): lift the legacy per-user
     * layout record's behaviour fields into the new overlay, then leave the layout record's own
     * fields untouched (only the 5 behaviour fields become irrelevant there, per [getConfig]'s merge).
     * `viewerSkinOverride` is viewer-set by construction; the rest are best-guess `admin` entries when
     * they differ from the global record. No-ops when an overlay already exists for the user (so it
     * can't clobber a real post-migration change) or the user has no per-user record at all.
     */
    fun migrateLegacyBehaviourFields(userId: String) {
        if (userId == GLOBAL_USER_ID) return
        if (db.raviloBehaviourQueries.getByUser(userId).executeAsOneOrNull() != null) return
        val stored = db.raviloConfigQueries.getByUser(userId).executeAsOneOrNull() ?: return
        val legacy = runCatching { json.decodeFromString<RaviloConfig>(stored) }.getOrNull() ?: return
        val global = getGlobalConfig()
        val overlay = BehaviourOverlay(
            skin = legacy.viewerSkinOverride, skinWriter = if (legacy.viewerSkinOverride != null) "viewer" else null,
            tileShape = legacy.tileShape.takeIf { it != global.tileShape }, tileShapeWriter = "admin".takeIf { legacy.tileShape != global.tileShape },
            showContinueProgress = legacy.showContinueProgress.takeIf { it != global.showContinueProgress }, showContinueProgressWriter = "admin".takeIf { legacy.showContinueProgress != global.showContinueProgress },
            autoplayNext = legacy.autoplayNext.takeIf { it != global.autoplayNext }, autoplayNextWriter = "admin".takeIf { legacy.autoplayNext != global.autoplayNext },
            uiLanguage = legacy.uiLanguage.takeIf { it != global.uiLanguage }, uiLanguageWriter = "admin".takeIf { legacy.uiLanguage != global.uiLanguage },
        )
        if (overlay != BehaviourOverlay()) saveBehaviourOverlay(userId, overlay)
    }

    /**
     * R338 (FR-R338-6, dev review 7) — once, at boot: a server that already has a global config gets the theme
     * defaults written with **follow the system OFF**, so no viewer's Ravilo turns light the first time their phone
     * does. The one pick and the dark pick are today's default skin; the light pick is Daylight. A fresh install never
     * gets here with a stored config lacking the fields, so it keeps [DEFAULT_CONFIG]'s follow ON. Viewers need no rows:
     * a skin they picked stands in for their theme in [resolveBehaviour]. Idempotent.
     */
    fun migrateThemeDefaults() {
        val stored = db.raviloConfigQueries.getByUser(GLOBAL_USER_ID).executeAsOneOrNull() ?: return
        val global = runCatching { json.decodeFromString<RaviloConfig>(stored) }.getOrNull() ?: return
        if (global.defaultThemeFollow != null) return
        val skinId = RaviloThemes.fromSkin(global.defaultSkin)
        save(GLOBAL_USER_ID, global.copy(
            defaultTheme = global.defaultTheme ?: skinId,
            defaultThemeFollow = false,
            defaultThemeLight = global.defaultThemeLight ?: RaviloThemes.DAYLIGHT,
            defaultThemeDark = global.defaultThemeDark ?: skinId,
        ))
    }

    /** Run [migrateLegacyBehaviourFields] once at boot for every existing per-user layout record. */
    fun migrateAllLegacyBehaviourFields() {
        for (userId in db.raviloConfigQueries.allUserIds().executeAsList()) {
            runCatching { migrateLegacyBehaviourFields(userId) }
        }
    }
}

/** First channel-fill preset; the fallback when an authored brandColor is malformed. */
private const val DEFAULT_BRAND_COLOR = "linear-gradient(135deg,#3b2a78,#15102e)"

/**
 * R36/R26: a channel `brandColor` is a CSS fill — a solid hex color OR a single
 * `linear-gradient(<deg>, <c1>, <c2>)`. Anything else is clamped to the default preset so the TV
 * always has a renderable fill; a null/blank value stays null (the card falls back to the skin accent).
 */
private fun sanitizeBrandColor(value: String?): String? {
    val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (isValidBrandColor(v)) v else DEFAULT_BRAND_COLOR
}

private fun isValidBrandColor(v: String): Boolean {
    val s = v.trim()
    if (s.startsWith("linear-gradient(", ignoreCase = true) && s.endsWith(")")) {
        val inner = s.substringAfter('(').substringBeforeLast(')')
        val colorParts = inner.split(',').map { it.trim() }
            .filter { it.isNotEmpty() && !it.endsWith("deg") && !it.startsWith("to ", ignoreCase = true) }
        // A stop can carry a trailing CSS position (e.g. "#7FD6F2 60%", ChannelCard's parseColorStop
        // accepts these for 3+-stop gradients) — strip it before validating the color itself, else a
        // perfectly valid positioned stop reads as malformed and gets clamped to DEFAULT_BRAND_COLOR
        // on every save (normalize() re-sanitizes every channel, not just the one being edited).
        return colorParts.size >= 2 && colorParts.all { isColorToken(it.substringBefore(' ')) }
    }
    return isColorToken(s)
}

private fun isColorToken(t: String): Boolean {
    val s = t.trim()
    if (s.startsWith("#")) {
        val h = s.removePrefix("#")
        return (h.length == 3 || h.length == 6 || h.length == 8) &&
            h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }
    return s.startsWith("rgb(") || s.startsWith("rgba(") // lenient; the editor emits hex
}

/** FR-202-3 — any non-negative ms value is valid and untouched (no step, no ceiling); only a value
 *  that is NOT a non-negative number (a hand-edited row, an older client) resolves to the 170 default —
 *  never an error. */
fun clampFocusDetailDelayMs(v: Int): Int = if (v >= 0) v else 170

@OptIn(ExperimentalForeignApi::class)
private fun nowMs(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, ts.ptr)
    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}
