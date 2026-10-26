package dev.jellystructure.media

import dev.jellystructure.auth.JellyfinPolicy
import dev.jellystructure.auth.JellyfinUser
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2518 (FR-7) — a tag the database holds and Jellyfin does not (or the reverse) raises one Dashboard row per tag,
 * at most hourly and after a full scan; *Save to Jellyfin* queues exactly the titles that differ and nothing else.
 */
class FilterTagCheckTest {
    private val tag = "household-tag"
    private var now = 1_000_000L
    private var jellyfin: Map<String, Set<String>?> = emptyMap()
    private var reads = 0
    private val pushed = ArrayList<List<String>>()
    private var library: List<MediaItem> = emptyList()

    private fun film(id: String, vararg tags: String, jfId: String? = id) = MediaItem(
        id = id, title = "Title $id", year = 2020, kind = MediaKind.MOVIE, path = "/lib/$id.mkv", tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0,
        scannedAt = 0L, episodes = emptyList(), jellyfinId = jfId, tags = tags.toList(),
    )

    private fun users(allowed: List<String> = listOf(tag), blocked: List<String> = emptyList()) = listOf(
        JellyfinUser("u1", "viewer", JellyfinPolicy(isAdministrator = false, allowedTags = allowed, blockedTags = blocked)),
    )

    private fun check() = FilterTagCheck(
        items = { library },
        jellyfinIdsWith = { t -> reads++; jellyfin[t] },
        push = { titles -> pushed += titles.map { it.id } },
        scope = CoroutineScope(Dispatchers.Unconfined),
        clock = { now },
        settleMs = 0L,
    )

    @Test
    fun `one title held here and missing in Jellyfin raises the row with 1 and Save queues exactly that title`() = runBlocking {
        library = listOf(film("a", tag), film("b", tag), film("c"))
        jellyfin = mapOf(tag to setOf("a"))
        val c = check()
        c.noteUsers(users())

        val row = c.rows().single()
        assertEquals(1, row.count)
        assertEquals("title", row.unit)
        assertEquals("Save to Jellyfin", row.action)
        assertTrue(row.sentence.startsWith("1 title has the $tag filter tag here but not in Jellyfin. Viewers filtered by it see them and can't play them."), row.sentence)
        assertTrue(row.actionId!!.startsWith("filter_tag_save:"))

        assertEquals(1, c.save(tag))
        assertEquals(listOf(listOf("b")), pushed)
    }

    @Test
    fun `agreeing sides raise no row and Save queues nothing`() = runBlocking {
        library = listOf(film("a", tag), film("b"))
        jellyfin = mapOf(tag to setOf("a"))
        val c = check()
        c.noteUsers(users())

        assertTrue(c.rows().isEmpty())
        assertEquals(0, c.save(tag))
        assertTrue(pushed.isEmpty())
    }

    @Test
    fun `a title Jellyfin has tagged and the database has not gets its own sentence and is pushed too`() = runBlocking {
        library = listOf(film("a", tag), film("b"))
        jellyfin = mapOf(tag to setOf("a", "b"))
        val c = check()
        c.noteUsers(users())

        val row = c.rows().single()
        assertEquals(1, row.count)
        assertTrue(row.sentence.startsWith("1 title has the $tag filter tag in Jellyfin but not here."), row.sentence)
        assertEquals(1, c.save(tag))
        assertEquals(listOf(listOf("b")), pushed)
    }

    @Test
    fun `ids compare without dashes or case and the tag without case`() = runBlocking {
        library = listOf(film("a", "Household-Tag", jfId = "ABCDEF01-2345-6789-ABCD-EF0123456789"))
        jellyfin = mapOf(tag to setOf("abcdef0123456789abcdef0123456789"))
        val c = check()
        c.noteUsers(users())

        assertTrue(c.rows().isEmpty())
    }

    @Test
    fun `a tag only on a blocked list is compared as well`() = runBlocking {
        library = listOf(film("a", "blocked-tag"))
        jellyfin = mapOf("blocked-tag" to emptySet())
        val c = check()
        c.noteUsers(users(allowed = emptyList(), blocked = listOf("blocked-tag")))

        assertEquals(1, c.rows().single().count)
    }

    @Test
    fun `a title with no Jellyfin id is left out of both sides`() = runBlocking {
        library = listOf(film("a", tag, jfId = null))
        jellyfin = mapOf(tag to emptySet())
        val c = check()
        c.noteUsers(users())

        assertTrue(c.rows().isEmpty())
    }

    @Test
    fun `the comparison runs at most hourly and again after a full scan`() = runBlocking {
        library = listOf(film("a", tag))
        jellyfin = mapOf(tag to setOf("a"))
        val c = check()
        c.noteUsers(users())
        assertEquals(1, reads)

        now += 5 * 60_000L
        c.noteUsers(users())
        assertEquals(1, reads, "five minutes later is inside the hour")

        c.afterScan()
        assertEquals(2, reads, "a full scan compares at once")

        now += FilterTagCheck.INTERVAL_MS
        c.noteUsers(users())
        assertEquals(3, reads, "an hour after the last run it compares again")
    }

    @Test
    fun `a failed Jellyfin read keeps the previous row and Save queues nothing`() = runBlocking {
        library = listOf(film("a", tag))
        jellyfin = mapOf(tag to emptySet())
        val c = check()
        c.noteUsers(users())
        assertEquals(1, c.rows().single().count)

        jellyfin = mapOf(tag to null)
        c.afterScan()
        assertEquals(1, c.rows().single().count, "unreachable is not 'agrees'")
        assertNull(c.save(tag))
        assertTrue(pushed.isEmpty())
    }

    @Test
    fun `Save for a tag no account names compares and queues nothing`() = runBlocking {
        library = listOf(film("a", "other-tag"))
        jellyfin = mapOf("other-tag" to emptySet())
        val c = check()
        c.noteUsers(users())

        assertEquals(0, c.save("other-tag"))
        assertTrue(pushed.isEmpty())
    }
}
