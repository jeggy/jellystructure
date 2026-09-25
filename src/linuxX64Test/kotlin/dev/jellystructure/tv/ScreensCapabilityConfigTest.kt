package dev.jellystructure.tv

import dev.jellystructure.db.createDatabase
import dev.jellystructure.shared.tv.ScreensCapability
import platform.posix.getpid
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 236 (dev review item 9) / R265 — `RaviloConfig.screens` reaches a viewer's config, resolved for THAT
 * viewer on every read, the way `cast` is. It was specified and never set, so every phone saw null and
 * the sheet's TV tiers were unreachable.
 */
class ScreensCapabilityConfigTest {
    private val dbPath = "/tmp/jellystructure-test-screenscap-${getpid()}.db"

    @AfterTest
    fun tearDown() { unlink(dbPath) }

    @Test
    fun `a viewer's config carries the screens capability resolved for that viewer`() {
        val db = createDatabase(dbPath)
        val service = RaviloConfigService(db, screensCapability = { userId ->
            ScreensCapability(enabled = true, paired = userId == "with-a-tv")
        })
        assertEquals(ScreensCapability(enabled = true, paired = true), service.getConfig("with-a-tv").screens)
        assertEquals(ScreensCapability(enabled = true, paired = false), service.getConfig("no-tv-yet").screens)
        // The global record is a layout, not a viewer: it never claims a viewer's pairings.
        assertNull(service.getGlobalConfig().screens)
    }

    @Test
    fun `not wired means no capability as before`() {
        val db = createDatabase(dbPath)
        assertNull(RaviloConfigService(db).getConfig("anyone").screens)
    }
}
