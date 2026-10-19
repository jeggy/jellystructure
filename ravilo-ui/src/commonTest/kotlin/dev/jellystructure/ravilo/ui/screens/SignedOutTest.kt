package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.i18n.t
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * DEB-1933 (DEB-1926, FR-R234-7) — what a `signed_out` event does on this device, and the one line the changing device adds.
 * The store and the network are lambdas here, so each test reads what the device was asked to do.
 */
class SignedOutTest {
    private class Device(val stored: MutableList<String>, var active: String?) {
        val signedOut = mutableListOf<String>()
        val forgotten = mutableListOf<String>()

        suspend fun apply(socketUserId: String?) = applySignedOut(
            socketUserId = socketUserId,
            activeUserId = active,
            storedUserIds = stored.toList(),
            // What `signOutActiveSession` does: forgets the active profile, answers whether another remains.
            signOutActive = { signedOut += active!!; stored.remove(active!!); stored.isNotEmpty() },
            forgetProfile = { forgotten += it; stored.remove(it) },
        )
    }

    @Test fun `the only profile signs out and goes to Login`() = runTest {
        val d = Device(mutableListOf("ada"), active = "ada")
        assertEquals(SignedOutRoute.LOGIN, d.apply("ada"))
        assertEquals(listOf("ada"), d.signedOut)
        assertEquals(emptyList(), d.stored)
        assertEquals(emptyList(), d.forgotten)
    }

    @Test fun `the active profile with others remaining goes to the profile picker`() = runTest {
        val d = Device(mutableListOf("ada", "bo"), active = "ada")
        assertEquals(SignedOutRoute.PICKER, d.apply("ada"))
        assertEquals(listOf("ada"), d.signedOut)
        assertEquals(listOf("bo"), d.stored, "the other profile is left alone")
    }

    @Test fun `a stored profile that is not active is removed and the active one is not interrupted`() = runTest {
        val d = Device(mutableListOf("ada", "bo"), active = "ada")
        assertNull(d.apply("bo"), "no route: the viewer is not moved")
        assertEquals(listOf("bo"), d.forgotten)
        assertEquals(emptyList(), d.signedOut, "the active profile was not signed out")
        assertEquals(listOf("ada"), d.stored)
    }

    @Test fun `an event for a profile the device no longer holds changes nothing`() = runTest {
        val d = Device(mutableListOf("ada"), active = "ada")
        assertNull(d.apply("gone"))
        assertEquals(emptyList(), d.signedOut)
        assertEquals(emptyList(), d.forgotten)
        assertEquals(listOf("ada"), d.stored)
    }

    @Test fun `an event with no socket profile changes nothing`() = runTest {
        val d = Device(mutableListOf("ada"), active = "ada")
        assertNull(d.apply(null))
        assertEquals(listOf("ada"), d.stored)
    }

    @Test fun `the other-devices line shows only when the server says a device was signed out`() {
        assertEquals("account.pw_ok_others", passwordChangedOthersLineKey(true))
        assertNull(passwordChangedOthersLineKey(false), "false, or an older server that omits the field")
    }

    @Test fun `both lines exist in every language we ship and are translated`() {
        for (key in listOf("account.pw_ok_others", "account.pw_signed_out")) {
            val en = t(key, "en")
            assertNotEquals(key, en, "$key is in no table")
            for (lang in listOf("da", "fo")) {
                val s = t(key, lang)
                assertNotEquals(key, s, "$key missing in $lang")
                assertNotEquals(en, s, "$key in $lang is the English fallback")
            }
        }
        assertEquals("Some of your other devices will ask you to sign in again.", t("account.pw_ok_others", "en"))
        assertEquals("Your password was changed on another device. Sign in with the new one.", t("account.pw_signed_out", "en"))
    }
}
