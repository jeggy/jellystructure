package dev.jellystructure.tv

import dev.jellystructure.db.createDatabase
import dev.jellystructure.server.routes.themeFieldError
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.Skin
import dev.jellystructure.shared.tv.SkipMode
import dev.jellystructure.shared.tv.ViewerSettingsRequest
import platform.posix.getpid
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * R338 — the theme settings on the server: the defaults a fresh install and an existing server get, a skin picked
 * before R338 standing in for the theme, the mirror an older app draws, and the refusal of a wrong id.
 */
class ThemeConfigTest {
    private val dbPath = "/tmp/jellystructure-test-themes-${getpid()}.db"

    @AfterTest
    fun tearDown() { unlink(dbPath) }

    private fun service() = RaviloConfigService(createDatabase(dbPath))

    @Test
    fun `a fresh install follows the system with Daylight and Aurora`() {
        val cfg = service().getConfig("viewer")
        assertEquals(true, cfg.themeFollow)
        assertEquals("aurora", cfg.theme)
        assertEquals("daylight", cfg.themeLight)
        assertEquals("aurora", cfg.themeDark)
        assertNull(cfg.viewerSkinOverride)
    }

    @Test
    fun `an existing server is migrated with follow off and its default skin as the picks`() {
        val svc = service()
        svc.save(GLOBAL_USER_ID, RaviloConfig(defaultSkin = Skin.NOIR))   // a global config written before R338
        svc.migrateThemeDefaults()
        val cfg = svc.getConfig("viewer")
        assertEquals(false, cfg.themeFollow)
        assertEquals("noir", cfg.theme)
        assertEquals("noir", cfg.themeDark)
        assertEquals("daylight", cfg.themeLight)
        svc.migrateThemeDefaults()   // idempotent
        assertEquals(false, svc.getConfig("viewer").themeFollow)
    }

    @Test
    fun `a skin picked before R338 stands in for the viewer's theme`() {
        val svc = service()
        svc.save(GLOBAL_USER_ID, RaviloConfig())
        svc.migrateThemeDefaults()
        svc.applyViewerSettings("viewer", skin = Skin.MIDNIGHT, showContinueProgress = null, autoplayNext = null, tileShape = null)
        val cfg = svc.getConfig("viewer")
        assertEquals("midnight", cfg.theme)
        assertEquals("midnight", cfg.themeDark)
        assertEquals(Skin.MIDNIGHT, cfg.viewerSkinOverride)
    }

    @Test
    fun `a new theme reaches an older app as its nearest skin`() {
        val svc = service()
        svc.applyViewerSettings("viewer", skin = null, showContinueProgress = null, autoplayNext = null, tileShape = null, themeDark = "graphite")
        val cfg = svc.getConfig("viewer")
        assertEquals("graphite", cfg.themeDark)
        assertEquals(Skin.MIDNIGHT, cfg.viewerSkinOverride)
    }

    @Test
    fun `picking the global default after an old skin does not bring the old skin back`() {
        val svc = service()
        svc.save(GLOBAL_USER_ID, RaviloConfig())
        svc.migrateThemeDefaults()
        svc.applyViewerSettings("viewer", skin = Skin.NOIR, showContinueProgress = null, autoplayNext = null, tileShape = null)
        svc.applyViewerSettings("viewer", skin = null, showContinueProgress = null, autoplayNext = null, tileShape = null, theme = "aurora", themeDark = "aurora")
        val cfg = svc.getConfig("viewer")
        assertEquals("aurora", cfg.theme)
        assertEquals("aurora", cfg.themeDark)
    }

    @Test
    fun `turning follow on keeps the dark pick an old skin stood for`() {
        val svc = service()
        svc.save(GLOBAL_USER_ID, RaviloConfig())
        svc.migrateThemeDefaults()
        svc.applyViewerSettings("viewer", skin = Skin.NOIR, showContinueProgress = null, autoplayNext = null, tileShape = null)
        svc.applyViewerSettings("viewer", skin = null, showContinueProgress = null, autoplayNext = null, tileShape = null, themeFollow = true)
        val cfg = svc.getConfig("viewer")
        assertEquals(true, cfg.themeFollow)
        assertEquals("noir", cfg.themeDark)
    }

    @Test
    fun `an old TV picking a skin sets the dark pick and the one pick when not following`() {
        val svc = service()
        svc.save(GLOBAL_USER_ID, RaviloConfig())
        svc.migrateThemeDefaults()
        svc.applyViewerSettings("viewer", skin = Skin.MIDNIGHT, showContinueProgress = null, autoplayNext = null, tileShape = null)
        val cfg = svc.getConfig("viewer")
        assertEquals("midnight", cfg.themeDark)
        assertEquals("midnight", cfg.theme)
    }

    @Test
    fun `a viewer's settings write keeps the admin's other overrides`() {
        val svc = service()
        svc.setAdminSkipIntro("viewer", SkipMode.AUTO)
        svc.applyViewerSettings("viewer", skin = null, showContinueProgress = false, autoplayNext = null, tileShape = null)
        assertEquals(SkipMode.AUTO, svc.getBehaviourOverlay("viewer").skipIntro)
    }

    @Test
    fun `with overrides not allowed every viewer gets the global theme`() {
        val svc = service()
        svc.save(GLOBAL_USER_ID, RaviloConfig(allowSkinOverride = false, defaultTheme = "noir", defaultThemeFollow = false, defaultThemeDark = "noir"))
        svc.applyViewerSettings("viewer", skin = null, showContinueProgress = null, autoplayNext = null, tileShape = null, theme = "daylight")
        assertEquals("noir", svc.getConfig("viewer").theme)
    }

    @Test
    fun `a wrong theme id is refused naming the field`() {
        assertNotNull(themeFieldError(ViewerSettingsRequest(themeDark = "daylight")))
        assertNotNull(themeFieldError(ViewerSettingsRequest(themeLight = "noir")))
        assertNotNull(themeFieldError(ViewerSettingsRequest(theme = "ocean")))
        assertNull(themeFieldError(ViewerSettingsRequest(theme = "daylight", themeLight = "daylight", themeDark = "graphite")))
    }
}
