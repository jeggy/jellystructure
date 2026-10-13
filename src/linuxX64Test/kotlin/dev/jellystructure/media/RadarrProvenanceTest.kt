package dev.jellystructure.media

import dev.jellystructure.arr.RadarrProvenance
import dev.jellystructure.arr.provenanceGaps
import dev.jellystructure.arr.provenanceOf
import dev.jellystructure.arr.withProvenance
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 314 (FR-314-14 test 11, DEB-881) — a rename makes Radarr drop its `moviefile` row and rebuild it from a fresh
 * parse of the new filename. These are the two halves of carrying the provenance across that: the field work on
 * Radarr's own resource shape, and the wait for the row it rebuilds.
 *
 * The JSON below is the shape `GET /api/v3/moviefile/{id}` returns, with the fields measured on prod on 2026-10-13:
 * a UHD BluRay remux that came back as a WEBDL-2160p with its group, scene name and flags emptied.
 */
class RadarrProvenanceTest {

    private val oldName = "Some.Film.2023.2160p.UHD.BluRay.REMUX-SomeGroup.mkv"
    private val newName = "Some Film (2023).mkv"

    private fun row(
        id: Int,
        name: String,
        qualityId: Int,
        qualityName: String,
        edition: String? = null,
        group: String? = null,
        scene: String? = null,
        flags: Int = 0,
    ): JsonObject = Json.parseToJsonElement(
        """
        {"id":$id,"movieId":676,"path":"/media/movies/Some Film (2023)/$name","relativePath":"$name","size":40000000000,
         "dateAdded":"2026-07-06T04:02:00Z","sceneName":${scene.q()},"releaseGroup":${group.q()},"edition":${edition.q()},
         "languages":[{"id":1,"name":"English"}],
         "quality":{"quality":{"id":$qualityId,"name":"$qualityName","source":"bluray","resolution":2160},"revision":{"version":1,"real":0}},
         "customFormats":[],"customFormatScore":0,"indexerFlags":$flags,
         "mediaInfo":{"audioCodec":"TrueHD","videoCodec":"HEVC"},"qualityCutoffNotMet":false}
        """.trimIndent(),
    ).jsonObject

    private fun String?.q() = if (this == null) "null" else "\"$this\""

    /** The row as it was before the rename: a remux with everything on it. */
    private fun before() = provenanceOf(row(430, oldName, 31, "Bluray-2160p Remux", "Director's Cut", "SomeGroup", oldName.removeSuffix(".mkv"), 2))!!

    /** The row Radarr builds from the folder's name: a WEBDL with nothing on it. */
    private fun after(id: Int = 655) = provenanceOf(row(id, newName, 18, "WEBDL-2160p"))!!

    // ── the fields ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the provenance of a record is its quality languages edition group scene name and flags`() {
        val p = before()
        assertEquals(430, p.movieFileId)
        assertEquals(oldName, p.fileName, "the name only — Radarr's view of the library is mapped and a name is not")
        assertEquals("Director's Cut", p.edition)
        assertEquals("SomeGroup", p.releaseGroup)
        assertEquals(2, p.indexerFlags)
        assertEquals(listOf("quality", "languages", "edition", "release group", "scene name", "indexer flags"), p.carried())
        // A rebuilt record carries a quality and languages and nothing else: those are what is lost.
        assertEquals(listOf("quality", "languages"), after().carried())
    }

    @Test fun `the write body is the record Radarr has now with only the carried fields overlaid`() {
        val now = row(655, newName, 18, "WEBDL-2160p")
        val body = withProvenance(now, before())
        assertEquals(31, body["quality"]!!.jsonObject["quality"]!!.jsonObject["id"]!!.jsonPrimitive.int)
        assertEquals("Director's Cut", body["edition"]!!.jsonPrimitive.contentOrNull)
        assertEquals("SomeGroup", body["releaseGroup"]!!.jsonPrimitive.contentOrNull)
        assertEquals(oldName.removeSuffix(".mkv"), body["sceneName"]!!.jsonPrimitive.contentOrNull)
        assertEquals(2, body["indexerFlags"]!!.jsonPrimitive.int)
        // Nothing else is touched: the handler assigns the whole resource back, so the row's own values ride along.
        assertEquals(now.keys, body.keys)
        assertEquals(now["path"], body["path"])
        assertEquals(now["dateAdded"], body["dateAdded"], "dateAdded is not in the PUT's set and is not invented here")
        assertEquals(now["mediaInfo"], body["mediaInfo"])
    }

    @Test fun `a field with nothing in it is never written back`() {
        val now = row(655, newName, 18, "WEBDL-2160p", edition = "Theatrical", group = "SomeoneElse")
        val body = withProvenance(now, after(430))   // a record with only a quality and languages on it
        assertEquals("Theatrical", body["edition"]!!.jsonPrimitive.contentOrNull)
        assertEquals("SomeoneElse", body["releaseGroup"]!!.jsonPrimitive.contentOrNull)
    }

    @Test fun `what did not read back is a gap and a record Radarr never rebuilt is all of them`() {
        val want = before()
        assertEquals(emptyList(), provenanceGaps(want, want.copy(movieFileId = 655, fileName = newName)))
        assertEquals(want.carried(), provenanceGaps(want, null))
        // Radarr drops a `sceneName` its own SceneChecker refuses, silently, and answers 202 all the same.
        assertEquals(listOf("scene name"), provenanceGaps(want, want.copy(sceneName = null)))
        // Quality is compared by its id: Radarr re-serialises the rest of the model.
        assertEquals(listOf("quality"), provenanceGaps(want, provenanceOf(row(655, newName, 18, "WEBDL-2160p", "Director's Cut", "SomeGroup", want.sceneName, 2))!!))
        assertEquals(listOf("edition", "release group", "scene name", "indexer flags"), provenanceGaps(want, provenanceOf(row(655, newName, 31, "Bluray-2160p Remux"))!!))
    }

    // ── the wait for the row Radarr rebuilds ────────────────────────────────────────────────────────────────────

    private class Radarr(val rows: List<RadarrProvenance?>) {
        var reads = 0
        val written = ArrayList<Int>()
        var refuseSceneName = false
        suspend fun read(): RadarrProvenance? = rows[minOf(reads++, rows.size - 1)]
        suspend fun write(id: Int, p: RadarrProvenance): RadarrProvenance? {
            written += id
            return p.copy(movieFileId = id, fileName = "Some Film (2023).mkv", sceneName = if (refuseSceneName) null else p.sceneName)
        }
    }

    private fun carry(r: Radarr, prov: RadarrProvenance = before(), passes: Int = 3) = runBlocking {
        carryProvenanceAcrossRename(prov, newName, passes, 0L, r::read, r::write)
    }

    @Test fun `the row Radarr rebuilds is waited for and taken on a later pass`() {
        // Pass 1: the old row is still there. Pass 2: FR-314-12's `movieFileId 0` moment reads as no row at all.
        // Pass 3: the rebuilt row, parsed out of the folder's name.
        val r = Radarr(listOf(before(), null, after()))
        val out = carry(r)
        assertEquals(655, out.movieFileId)
        assertEquals(emptyList(), out.notRestored)
        assertEquals(listOf(655), r.written, "written onto the rebuilt row and no other")
        assertEquals(3, r.reads)
    }

    @Test fun `a Radarr that never rebuilds the row reports instead of looping`() {
        val r = Radarr(listOf(before()))
        val out = carry(r)
        assertNull(out.movieFileId)
        assertEquals(before().carried(), out.notRestored)
        assertTrue(r.written.isEmpty(), "nothing is written onto the row Radarr is about to drop")
        assertEquals(3, r.reads, "three passes and then it reports")
    }

    @Test fun `a record that still carries everything is left alone`() {
        val kept = before().copy(movieFileId = 430, fileName = newName)
        val r = Radarr(listOf(kept))
        val out = carry(r, passes = 2)
        assertEquals(430, out.movieFileId)
        assertTrue(out.kept && out.notRestored.isEmpty())
        assertTrue(r.written.isEmpty())
        // ... but only after the last pass: concluding it on the first is how the loss would go quiet again.
        assertEquals(2, r.reads)
    }

    @Test fun `a scene name Radarr refuses comes back as the one field not restored`() {
        val r = Radarr(listOf(after())).also { it.refuseSceneName = true }
        val out = carry(r)
        assertEquals(655, out.movieFileId)
        assertEquals(listOf("scene name"), out.notRestored)
    }

    @Test fun `a record rebuilt under the old name is never mistaken for the new one`() {
        // Radarr re-imported the file but under a name that is not the one the rename gave it: not this film's row.
        val r = Radarr(listOf(after().copy(fileName = "Something.Else.mkv")))
        assertNull(carry(r).movieFileId)
        assertTrue(r.written.isEmpty())
    }
}
