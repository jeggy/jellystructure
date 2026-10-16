package dev.jellystructure.ravilo.ui.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateCheckTest {
    @Test
    fun `health's version field is read and nothing else`() {
        assertEquals("v1.44-49-g429cffe9", UpdateCheck.parseHealth("""{"status":"ok","version":"v1.44-49-g429cffe9"}"""))
        assertEquals("1.46", UpdateCheck.parseHealth("""{"version":" 1.46 ","extra":{"a":1}}"""))
        assertNull(UpdateCheck.parseHealth("""{"status":"ok"}"""))
        assertNull(UpdateCheck.parseHealth("""{"version":""}"""))
        assertNull(UpdateCheck.parseHealth("<html>not json</html>"))
    }
}
