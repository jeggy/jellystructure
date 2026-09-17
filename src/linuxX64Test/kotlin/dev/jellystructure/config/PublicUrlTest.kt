package dev.jellystructure.config

import dev.jellystructure.io.FileIo
import dev.jellystructure.model.PublicUrl
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Phase 227 — one public address: an https origin, validated on write, never on load, migrated silently. */
class PublicUrlTest {
    @Test fun anOriginIsValidAndNormalised() {
        assertNull(PublicUrl.problem("https://jelly.example.org"))
        assertNull(PublicUrl.problem("  https://jelly.example.org:8443/  "))
        assertEquals("https://jelly.example.org:8443", PublicUrl.normalize("  https://jelly.example.org:8443/  "))
        assertEquals("https://jelly.example.org/cast/", PublicUrl.receiverUrl("https://jelly.example.org/"))
    }

    @Test fun blankIsUnsetAndAllowed() {
        assertNull(PublicUrl.problem("   "))
        assertNull(PublicUrl.effective(""))
        assertNull(PublicUrl.receiverUrl(""))
    }

    @Test fun anythingThatIsNotAnHttpsOriginIsRefusedWithAReason() {
        for (bad in listOf("http://jelly.example.org", "jelly.example.org", "https://", "https://jelly.example.org/cast",
            "https://jelly.example.org?x=1", "https://jelly.example.org#a", "https://user@jelly.example.org", "https://jelly.example.org:99999", "https://jelly .org")) {
            assertNotNull(PublicUrl.problem(bad), "should be refused: $bad")
            assertNull(PublicUrl.effective(bad), "an invalid stored value is treated as unset: $bad")
        }
    }

    private fun storeWith(toml: String): ConfigStore = runBlocking {
        val path = "/tmp/jellystructure-test-publicurl-${getpid()}-${toml.hashCode()}.toml"
        FileIo.writeText(Path(path), toml)
        ConfigStore(path).also { it.load() }
    }

    @Test fun aNestedChromecastAddressIsAdoptedAtTheRoot() {
        val cfg = storeWith("[chromecast]\nenabled = true\napp_id = \"ABCD1234\"\npublic_url = \"https://old.example.org/\"\n").current
        assertEquals("https://old.example.org", cfg.publicUrl)
        assertEquals("https://old.example.org/cast/", cfg.receiverUrl())
    }

    @Test fun theRootWinsWhenBothAreSet() {
        val cfg = storeWith("public_url = \"https://root.example.org\"\n\n[chromecast]\nenabled = true\npublic_url = \"https://old.example.org\"\n").current
        assertEquals("https://root.example.org", cfg.publicUrl)
    }

    @Test fun aHandEditedInvalidValueDoesNotFailTheLoadAndIsTreatedAsUnset() {
        val cfg = storeWith("public_url = \"http://plain.example.org/path\"\nscan_schedule = \"\"\n").current
        assertEquals("http://plain.example.org/path", cfg.publicUrl)  // kept, so the field can show it marked invalid
        assertNull(cfg.receiverUrl())
    }
}
