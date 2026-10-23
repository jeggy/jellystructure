package dev.jellystructure.config

import dev.jellystructure.io.FileIo
import dev.jellystructure.model.ServiceAddress
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** DEB-2233 (FR-1) — `ravilo_web_url` and `jellyfin_internal_url`: blank or an absolute http(s) URL with no query or fragment. */
class ServiceAddressTest {
    private val what = "address"

    @Test fun `blank is not set and allowed`() {
        assertNull(ServiceAddress.problem("", what)); assertNull(ServiceAddress.problem("   ", what))
        assertNull(ServiceAddress.effective("", what))
    }

    @Test fun `an absolute http or https URL with a host is accepted and a trailing slash is dropped`() {
        for (ok in listOf("https://ravilo.example.net", "http://jellyfin:8096", "HTTP://10.0.0.5:8096/", "https://example.org/base/path", "  https://example.org/  ")) {
            assertNull(ServiceAddress.problem(ok, what), "should be accepted: $ok")
        }
        assertEquals("https://ravilo.example.net", ServiceAddress.normalize(" https://ravilo.example.net/ "))
        assertEquals("http://jellyfin:8096", ServiceAddress.effective("http://jellyfin:8096/", what))
    }

    @Test fun `anything else is refused and reads as not set`() {
        for (bad in listOf("ravilo.example.net", "ftp://example.org", "https://", "http:///x", "https://example.org/?x=1", "https://example.org/#top",
            "https://user@example.org", "https://exa mple.org", "javascript:alert(1)")) {
            assertNotNull(ServiceAddress.problem(bad, what), "should be refused: $bad")
            assertNull(ServiceAddress.effective(bad, what), "a stored value that fails reads as not set: $bad")
        }
    }

    @Test fun `a hand-edited bad value does not fail the load and the internal address falls back to the Jellyfin URL`() {
        val path = "/tmp/jellystructure-test-svcaddr-${getpid()}.toml"
        FileIo.writeText(Path(path), "ravilo_web_url = \"nonsense\"\njellyfin_internal_url = \"\"\nscan_schedule = \"\"\n\n[api_keys]\njellyfin_url = \"http://jf.example.org:8096\"\n")
        val store = runBlocking { ConfigStore(path).also { it.load() } }
        runCatching { platform.posix.remove(path) }
        assertEquals("nonsense", store.current.raviloWebUrl)
        assertNull(store.raviloWebUrl)
        assertEquals("http://jf.example.org:8096", store.jellyfinInternalUrl)
    }
}
