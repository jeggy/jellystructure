package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

/** DEB-5836, FR-2: a re-auth refusal names the device it happened on. */
class ReauthWordingTest {
    private fun keys(tv: Boolean = false, mac: Boolean = false, desktop: Boolean = false, web: Boolean = false) =
        reauthKeys(tv, mac, desktop, web)

    @Test fun tvKeepsTheOriginalWording() =
        assertEquals("error.play.reauth.title" to "error.play.reauth.body", keys(tv = true))

    @Test fun aPhoneSaysPhone() =
        assertEquals("error.play.reauth.title_phone" to "error.play.reauth.body_phone", keys())

    @Test fun aMacSaysMacEvenThoughItIsAlsoADesktop() =
        assertEquals("error.play.reauth.title_mac" to "error.play.reauth.body_mac", keys(mac = true, desktop = true))

    @Test fun linuxSaysComputer() =
        assertEquals("error.play.reauth.title_desk" to "error.play.reauth.body_desk", keys(desktop = true))

    @Test fun webNamesNoDevice() =
        assertEquals("error.play.reauth.title_web" to "error.load.reauth.body", keys(web = true))
}
