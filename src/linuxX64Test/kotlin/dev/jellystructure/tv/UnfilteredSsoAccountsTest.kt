package dev.jellystructure.tv

import dev.jellystructure.auth.JellyfinPolicy
import dev.jellystructure.auth.JellyfinUser
import dev.jellystructure.auth.SSO_AUTHENTICATION_PROVIDER_ID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2518 (FR-12) — a single-sign-on account that is not an administrator and has empty allowed and blocked lists
 * raises one Dashboard row with the count; an administrator, a password account or an account with a tag does not.
 */
class UnfilteredSsoAccountsTest {
    private val tag = "household-tag"

    private fun user(
        id: String, provider: String? = SSO_AUTHENTICATION_PROVIDER_ID, admin: Boolean = false,
        allowed: List<String> = emptyList(), blocked: List<String> = emptyList(),
    ) = JellyfinUser(id, "name-$id", JellyfinPolicy(isAdministrator = admin, allowedTags = allowed, blockedTags = blocked, authenticationProviderId = provider))

    private fun store() = UnfilteredSsoAccounts("/tmp/jellystructure-test-sso-unfiltered-${Random.nextLong()}.json")

    @Test
    fun `one single-sign-on account with no tag raises the row with 1`() {
        val s = store()
        s.noteUsers(listOf(user("a"), user("b", allowed = listOf(tag))))

        val row = assertNotNull(s.row())
        assertEquals(1, row.count)
        assertEquals("account", row.unit)
        assertTrue(row.label.startsWith("1 single-sign-on account sees the whole library"), row.label)
        assertTrue("name-a" in row.sentence, row.sentence)
        assertEquals("sso_unfiltered_dismiss", row.actionId)
    }

    @Test
    fun `two accounts read as accounts see the whole library and they have no tag filter`() {
        val s = store()
        s.noteUsers(listOf(user("a"), user("b")))
        assertEquals("2 single-sign-on accounts see the whole library — they have no tag filter", s.row()!!.label)
    }

    @Test
    fun `an administrator a password account and an account with a tag raise nothing`() {
        val s = store()
        s.noteUsers(listOf(
            user("admin", admin = true),
            user("password", provider = "Jellyfin.Server.Implementations.Users.DefaultAuthenticationProvider"),
            user("none", provider = null),
            user("allowed", allowed = listOf(tag)),
            user("blocked", blocked = listOf(tag)),
        ))
        assertNull(s.row())
    }

    @Test
    fun `a dismissal is remembered per account across a restart and a later account raises the row again`() {
        val path = "/tmp/jellystructure-test-sso-unfiltered-${Random.nextLong()}.json"
        val s = UnfilteredSsoAccounts(path)
        s.noteUsers(listOf(user("a")))
        assertEquals(1, s.dismissAll())
        assertNull(s.row())

        val restarted = UnfilteredSsoAccounts(path).also { it.load() }
        restarted.noteUsers(listOf(user("a")))
        assertNull(restarted.row())

        restarted.noteUsers(listOf(user("a"), user("b")))
        assertEquals(1, restarted.row()!!.count)
        assertTrue("name-b" in restarted.row()!!.sentence)
    }

    @Test
    fun `an account that gets a filter leaves the row`() {
        val s = store()
        s.noteUsers(listOf(user("a")))
        assertEquals(1, s.row()!!.count)
        s.noteUsers(listOf(user("a", allowed = listOf(tag))))
        assertNull(s.row())
    }
}
