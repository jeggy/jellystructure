package dev.jellystructure.tv

import dev.jellystructure.db.createDatabase
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.RaviloWireJson
import kotlinx.serialization.json.Json
import platform.posix.getpid
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DEB-4270 (FR-8, replaced) — the Header setting is gone and its field stays: `normalize()` writes `standard` whatever
 * arrives, every read serves `standard` (a layout stored as `slim` before the change included), and the payload still
 * carries `"header_style":"standard"` so a build that reads the field decodes it. (tv-screen-scale-and-slim-header.md)
 */
@Suppress("DEPRECATION")
class HeaderStyleConfigTest {
    private val dbPath = "/tmp/jellystructure-test-headerstyle-${getpid()}.db"

    @AfterTest
    fun tearDown() { unlink(dbPath) }

    private fun normalized(style: String) = RaviloConfigService(createDatabase(dbPath)).normalize(RaviloConfig(headerStyle = style)).headerStyle

    @Test
    fun `slim and standard and junk all normalize to standard`() {
        assertEquals("standard", normalized("slim"))
        assertEquals("standard", normalized("standard"))
        assertEquals("standard", normalized("ultra-slim"))
        assertEquals("standard", normalized(""))
    }

    @Test
    fun `a config that never set it means standard`() {
        assertEquals("standard", RaviloConfig().headerStyle)
        assertEquals("standard", RaviloWireJson.decodeFromString<RaviloConfig>("{}").headerStyle)
    }

    @Test
    fun `saving slim reads back standard`() {
        val service = RaviloConfigService(createDatabase(dbPath))
        service.save(GLOBAL_USER_ID, RaviloConfig(headerStyle = "slim"))
        assertEquals("standard", service.getGlobalConfig().headerStyle)
        assertEquals("standard", service.getConfig("some-viewer").headerStyle)
    }

    @Test
    fun `a layout stored as slim before the setting went is served as standard`() {
        val db = createDatabase(dbPath)
        db.raviloConfigQueries.upsert(user_id = GLOBAL_USER_ID, json = """{"header_style":"slim"}""", updated_at = 1L)
        val service = RaviloConfigService(db)
        assertEquals("standard", service.getGlobalConfig().headerStyle)
        assertEquals("standard", service.getConfig("some-viewer").headerStyle)
    }

    @Test
    fun `the payload still carries header_style standard`() {
        // The server's Json writes no defaults; the field is `@EncodeDefault(ALWAYS)` so the key is there regardless.
        val json = Json { ignoreUnknownKeys = true }.encodeToString(RaviloConfig.serializer(), RaviloConfig(headerStyle = "standard"))
        assertTrue(json.contains("\"header_style\":\"standard\""), json)
        assertFalse(json.contains("headerStyle"))
    }
}
