package dev.jellystructure.advisor

import dev.jellystructure.bazarr.BazarrClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaStore
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 273 (FR-273-18/19) — the Bazarr advisor speaks only where Bazarr's live value differs, and *Apply* names
 *  only its own keys. */
class BazarrAdvisorTest {
    private val dir = "/tmp/jellystructure-test-bazarr-advisor-${getpid()}"

    private fun advisor(): BazarrAdvisorService {
        SystemFileSystem.createDirectories(Path(dir))
        val db = createDatabase("$dir/test.db")
        val config = ConfigStore("$dir/config.toml")
        return BazarrAdvisorService(config, BazarrClient(), db, MediaStore(db, JsTagStore("$dir/tags.json"), config))
    }

    private fun settings(json: String) = Json.parseToJsonElement(json) as JsonObject

    @AfterTest
    fun tearDown() { platform.posix.system("rm -rf '$dir'") }

    @Test
    fun `a Bazarr set as this household's is today gets the hook sync offset and frame-rate findings`() = runBlocking {
        val s = settings("""{"general":{"use_postprocessing":false,"postprocessing_cmd":"","use_postprocessing_threshold":false,
            "use_postprocessing_threshold_movie":false,"upgrade_subs":true,"minimum_score":90,"minimum_score_movie":70},
            "subsync":{"use_subsync":false,"max_offset_seconds":60,"no_fix_framerate":true,"gss":true}}""")
        val ids = advisor().findings(s, command = "curl … /api/webhooks/bazarr?secret=x").map { it.id }
        assertEquals(listOf("bazarr_hook", "bazarr_sync", "bazarr_max_offset", "bazarr_framerate", "bazarr_upgrade"), ids)
    }

    @Test
    fun `a Bazarr already set right says nothing`() = runBlocking {
        val s = settings("""{"general":{"use_postprocessing":true,"postprocessing_cmd":"curl -fsS http://x/api/webhooks/bazarr?secret=y",
            "use_postprocessing_threshold":false,"use_postprocessing_threshold_movie":false,"upgrade_subs":true,"minimum_score":90,
            "minimum_score_movie":70},"subsync":{"use_subsync":true,"max_offset_seconds":300,"no_fix_framerate":false}}""")
        assertEquals(emptyList(), advisor().findings(s, command = "curl").map { it.id })
    }

    @Test
    fun `apply names only its own keys`() {
        val a = advisor()
        assertEquals(mapOf("settings-subsync-max_offset_seconds" to "300"), a.fieldsFor(BazarrAdvisorService.MAX_OFFSET))
        assertEquals(mapOf("settings-subsync-no_fix_framerate" to "false"), a.fieldsFor(BazarrAdvisorService.FRAMERATE))
        assertNull(a.fieldsFor(BazarrAdvisorService.UPGRADE), "a note has nothing to apply")
        assertNull(a.fieldsFor(BazarrAdvisorService.HOOK), "no hook command without an address and a secret")
        assertTrue(a.fieldsFor(BazarrAdvisorService.MIN_SCORE)!!.keys.all { it.startsWith("settings-general-minimum_score") })
    }
}
