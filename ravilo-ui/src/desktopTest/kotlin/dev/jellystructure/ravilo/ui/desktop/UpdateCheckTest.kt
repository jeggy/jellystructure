package dev.jellystructure.ravilo.ui.desktop

import dev.jellystructure.ravilo.ui.components.AppUpdateOffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateCheckTest {
    @Test
    fun `a newer release offers R331's dmg on its tag`() {
        assertEquals(
            AppUpdateOffer("1.46", "https://github.com/jeggy/jellystructure/releases/download/v1.46/ravilo-mac-1.46.dmg"),
            UpdateCheck.offerFor("1.45", "v1.46", isMac = true),
        )
    }

    @Test
    fun `nothing is offered off a Mac, for the same release, or around a dev build`() {
        assertNull(UpdateCheck.offerFor("1.45", "1.46", isMac = false))
        assertNull(UpdateCheck.offerFor("1.46", "1.46", isMac = true))
        assertNull(UpdateCheck.offerFor("1.45-3-gabcdef0", "1.46", isMac = true))
        assertNull(UpdateCheck.offerFor("1.45", "v1.46-49-g429cffe9", isMac = true))
    }

    @Test
    fun `health's version field is read and nothing else`() {
        assertEquals("v1.44-49-g429cffe9", UpdateCheck.parseHealth("""{"status":"ok","version":"v1.44-49-g429cffe9"}"""))
        assertEquals("1.46", UpdateCheck.parseHealth("""{"version":" 1.46 ","extra":{"a":1}}"""))
        assertNull(UpdateCheck.parseHealth("""{"status":"ok"}"""))
        assertNull(UpdateCheck.parseHealth("""{"version":""}"""))
        assertNull(UpdateCheck.parseHealth("<html>not json</html>"))
    }
}
