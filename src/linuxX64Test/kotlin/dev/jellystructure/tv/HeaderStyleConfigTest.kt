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
 * DEB-3967 (FR-8) — `RaviloConfig.headerStyle` is the admin's Header: `standard` or `slim`, anything else is
 * `standard`, `standard` when nothing was ever set, and riding the config payload as `header_style`.
 * (tv-screen-scale-and-slim-header.md)
 */
class HeaderStyleConfigTest {
    private val dbPath = "/tmp/jellystructure-test-headerstyle-${getpid()}.db"

    @AfterTest
    fun tearDown() { unlink(dbPath) }

    private fun normalized(style: String) = RaviloConfigService(createDatabase(dbPath)).normalize(RaviloConfig(headerStyle = style)).headerStyle

    @Test
    fun `standard and slim survive normalize untouched`() {
        assertEquals("standard", normalized("standard"))
        assertEquals("slim", normalized("slim"))
    }

    @Test
    fun `an unknown value is standard`() {
        assertEquals("standard", normalized("ultra-slim"))
        assertEquals("standard", normalized("Slim"))
        assertEquals("standard", normalized(""))
    }

    @Test
    fun `a config that never set it means standard`() {
        assertEquals("standard", RaviloConfig().headerStyle)
        assertEquals("standard", RaviloWireJson.decodeFromString<RaviloConfig>("{}").headerStyle)
    }

    @Test
    fun `the value is stored with the global config and read back`() {
        val service = RaviloConfigService(createDatabase(dbPath))
        service.save(GLOBAL_USER_ID, RaviloConfig(headerStyle = "slim"))
        assertEquals("slim", service.getGlobalConfig().headerStyle)
    }

    @Test
    fun `it rides the config payload as header_style`() {
        val json = RaviloWireJson.encodeToString(RaviloConfig.serializer(), RaviloConfig(headerStyle = "slim"))
        assertEquals(true, json.contains("\"header_style\":\"slim\""), json)
        assertFalse(json.contains("headerStyle"))
    }
}
