package dev.jellystructure.config

import dev.jellystructure.io.FileIo
import dev.jellystructure.model.PasswordChangeUrl
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** DEB-1854 (FR-4) — the password page: refused on a save unless it is an absolute https URL, never refused on a load. */
class PasswordChangeUrlTest {
    @Test fun anAbsoluteHttpsUrlWithAPathAndAQueryIsValid() {
        assertNull(PasswordChangeUrl.problem("https://account.example.org/if/user/#/settings"))
        assertNull(PasswordChangeUrl.problem("  https://account.example.org:8443/password?next=/x  "))
        assertEquals("https://account.example.org/p", PasswordChangeUrl.effective(" https://account.example.org/p "))
    }

    @Test fun blankIsNotSetAndAllowed() {
        assertNull(PasswordChangeUrl.problem("   "))
        assertNull(PasswordChangeUrl.effective(""))
    }

    @Test fun anythingThatIsNotAnAbsoluteHttpsUrlIsRefusedAndNeverSentToAClient() {
        for (bad in listOf("http://account.example.org", "account.example.org/p", "https://", "https:///path", "javascript:alert(1)",
            "https://user@account.example.org", "https://account .example.org")) {
            assertNotNull(PasswordChangeUrl.problem(bad), "should be refused: $bad")
            assertNull(PasswordChangeUrl.effective(bad), "an invalid stored value reads as not set: $bad")
        }
    }

    @Test fun aHandEditedInvalidValueDoesNotFailTheLoad() {
        val path = "/tmp/jellystructure-test-pwurl-${getpid()}.toml"
        FileIo.writeText(Path(path), "password_change_url = \"http://plain.example.org/p\"\nscan_schedule = \"\"\n")
        val store = runBlocking { ConfigStore(path).also { it.load() } }
        runCatching { platform.posix.remove(path) }
        assertEquals("http://plain.example.org/p", store.current.passwordChangeUrl)   // kept, so the admin field can show it marked
        assertNull(store.passwordChangeUrl)                                          // but what a client may receive is nothing
    }
}
