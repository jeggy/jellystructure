package dev.jellystructure.ravilo.ui.desktop

import dev.jellystructure.ravilo.ui.components.MacInstallState
import dev.jellystructure.ravilo.ui.components.ServerHostClass
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R385 (FR-R385-1/-2) — the two readings behind the two causes: the launcher's path, and the configured host's
 * address. Both are pure here on purpose; the real paths are the ones QA saw on the Mac walk (DEB-721).
 */
class MacInstallTest {

    /** The path macOS really ran the quarantined copy from, recorded on the walk. */
    private val translocated =
        "/private/var/folders/h4/0m1t3q5d0bk5p0r_0000gn/T/AppTranslocation/3F2A1C44-9E0D-4C1B-9B9D-0C8E8B7A6F21/d/Ravilo.app/Contents/MacOS/Ravilo"

    @Test
    fun a_translocated_launcher_is_recognised() {
        assertEquals(MacInstallState.TRANSLOCATED, MacInstall.stateOf(translocated))
    }

    @Test
    fun the_mounted_disk_image_is_recognised() {
        assertEquals(MacInstallState.ON_VOLUME, MacInstall.stateOf("/Volumes/Ravilo 1.51/Ravilo.app/Contents/MacOS/Ravilo"))
    }

    @Test
    fun a_normal_install_and_a_development_build_say_nothing() {
        assertEquals(MacInstallState.IN_PLACE, MacInstall.stateOf("/Applications/Ravilo.app/Contents/MacOS/Ravilo"))
        assertEquals(MacInstallState.IN_PLACE, MacInstall.stateOf("/Users/someone/Applications/Ravilo.app/Contents/MacOS/Ravilo"))
        // Run from Gradle: neither property is set, and a development build is never translocated.
        assertEquals(MacInstallState.IN_PLACE, MacInstall.stateOf(null))
    }

    /** A folder of the viewer's own called `Volumes` or `AppTranslocation` deeper in the path is not either state. */
    @Test
    fun only_the_shapes_macos_makes_count() {
        assertEquals(MacInstallState.IN_PLACE, MacInstall.stateOf("/Users/someone/Volumes/Ravilo.app/Contents/MacOS/Ravilo"))
        assertEquals(MacInstallState.TRANSLOCATED, MacInstall.stateOf("/x/AppTranslocation/u/d/Ravilo.app"))
        assertEquals(MacInstallState.IN_PLACE, MacInstall.stateOf("/Users/someone/AppTranslocationNotes/Ravilo.app"))
    }

    @Test
    fun the_host_comes_out_of_the_configured_address() {
        assertEquals("jelly.example.net", MacInstall.hostOf("https://jelly.example.net"))
        assertEquals("jelly.example.net", MacInstall.hostOf("https://jelly.example.net/"))
        assertEquals("10.0.0.5", MacInstall.hostOf("http://10.0.0.5:8080/"))
        assertEquals("fd00::1", MacInstall.hostOf("http://[fd00::1]:8080/api"))
        assertEquals(null, MacInstall.hostOf("   "))
    }

    /** FR-R385-2 — the ranges the spec names, including the two no JDK predicate knows. */
    @Test
    fun a_private_address_is_recognised_in_every_range_the_spec_names() {
        for (ip in listOf("10.0.0.5", "172.16.4.1", "172.31.255.254", "192.168.1.9", "169.254.3.4", "127.0.0.1", "100.64.0.1", "100.127.255.1")) {
            assertTrue(MacInstall.isPrivate(InetAddress.getByName(ip)), "$ip is on the household's own network")
        }
        assertTrue(MacInstall.isPrivate(InetAddress.getByName("fd00::1")), "IPv6 unique-local")
        for (ip in listOf("1.1.1.1", "93.184.216.34", "172.32.0.1", "100.128.0.1", "2606:4700::1111")) {
            assertFalse(MacInstall.isPrivate(InetAddress.getByName(ip)), "$ip is routable")
        }
    }

    /** An address literal needs no DNS, which is the case a test can assert without a resolver. */
    @Test
    fun an_address_is_classified_without_asking_dns() {
        assertEquals(ServerHostClass.LAN, MacInstall.classify("10.9.8.7"))
        assertEquals(ServerHostClass.LAN, MacInstall.classify("127.0.0.1"))
        assertEquals(ServerHostClass.PUBLIC, MacInstall.classify("1.1.1.1"))
    }

    /**
     * FR-R385-2 — a name that does not resolve is `UNKNOWN`, which the table treats as `PUBLIC`: the app says the
     * plain *Couldn't reach the server* rather than inventing a permission out of a DNS failure.
     */
    @Test
    fun a_host_that_does_not_resolve_is_unknown() {
        assertEquals(ServerHostClass.UNKNOWN, MacInstall.classify(null))
        assertEquals(ServerHostClass.UNKNOWN, MacInstall.classify("ravilo-r385-no-such-host.invalid"))
    }
}
