package dev.jellystructure.tv

import dev.jellystructure.db.createDatabase
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.RaviloWireJson
import platform.posix.getpid
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * DEB-3966 (FR-1) — `RaviloConfig.uiScalePct` is the admin's Screen scale: one of 100 / 95 / 90 / 85, snapped
 * to the nearest on save (above 100 → 100, below 85 → 85), 100 when nothing was ever set, and riding the
 * config payload as `ui_scale_pct`.
 * (tv-screen-scale-and-slim-header.md)
 */
class UiScaleConfigTest {
    private val dbPath = "/tmp/jellystructure-test-uiscale-${getpid()}.db"

    @AfterTest
    fun tearDown() { unlink(dbPath) }

    private fun normalized(pct: Int) = RaviloConfigService(createDatabase(dbPath)).normalize(RaviloConfig(uiScalePct = pct)).uiScalePct

    @Test
    fun `the four options survive normalize untouched`() {
        for (pct in listOf(100, 95, 90, 85)) assertEquals(pct, normalized(pct), "$pct")
    }

    @Test
    fun `a stray value snaps to the nearest option`() {
        assertEquals(95, normalized(97))
        assertEquals(95, normalized(93))
        assertEquals(90, normalized(91))
        assertEquals(90, normalized(88))
        assertEquals(85, normalized(87))
    }

    @Test
    fun `above 100 is 100 and below 85 is 85`() {
        assertEquals(100, normalized(140))
        assertEquals(100, normalized(Int.MAX_VALUE))
        assertEquals(85, normalized(50))
        assertEquals(85, normalized(0))
        assertEquals(85, normalized(-20))
        assertEquals(85, normalized(Int.MIN_VALUE))
    }

    @Test
    fun `a config that never set it means 100`() {
        assertEquals(100, RaviloConfig().uiScalePct)
        assertEquals(100, RaviloWireJson.decodeFromString<RaviloConfig>("{}").uiScalePct)
    }

    @Test
    fun `the value is stored with the global config and read back`() {
        val service = RaviloConfigService(createDatabase(dbPath))
        service.save(GLOBAL_USER_ID, RaviloConfig(uiScalePct = 91))
        assertEquals(90, service.getGlobalConfig().uiScalePct)
    }

    @Test
    fun `it rides the config payload as ui_scale_pct`() {
        val json = RaviloWireJson.encodeToString(RaviloConfig.serializer(), RaviloConfig(uiScalePct = 85))
        assertEquals(true, json.contains("\"ui_scale_pct\":85"), json)
        assertFalse(json.contains("uiScalePct"))
    }
}
