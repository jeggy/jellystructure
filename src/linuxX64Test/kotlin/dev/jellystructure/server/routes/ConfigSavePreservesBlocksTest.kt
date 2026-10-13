package dev.jellystructure.server.routes

import dev.jellystructure.config.AcquisitionConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.EncoderConfig
import dev.jellystructure.config.MusicSteps
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 313 (FR-313-15, amendment 2026-10-12 — DEB-729) — a Settings save carries only the Settings form's
 * blocks, and `PUT /api/config` must restore every other one from the stored config. `[encoder]` was missing
 * from that list, so any save from any tab wrote `EncoderConfig()`'s defaults over the operator's values.
 *
 * The body below is the shape the admin actually posts for the blocks under test: it has no `encoder`,
 * `ingest`, `subtitle_check` or `acquisition` key at all (the frontend `AppConfig` has no such fields), which
 * is exactly how kotlinx comes to fill in defaults. The assertions cover the family, not just the one bug, so
 * the next block added to `AppConfig` without a restore line fails a test rather than quietly resetting a
 * household value.
 */
class ConfigSavePreservesBlocksTest {
    private val path = "/tmp/jellystructure-test-config-save-${getpid()}.toml"

    @AfterTest fun tearDown() { listOf(path, "$path.tmp").forEach { runCatching { platform.posix.remove(it) } } }

    /** Not a default anywhere: a tmpfs elsewhere, a smaller cap, and 313's cast switch turned off by hand. */
    private val tuned = EncoderConfig(
        enabled = false,
        ffmpegDir = "/opt/ffmpeg",
        downloadFfmpeg = false,
        workDir = "/mnt/scratch/js",
        cudaCacheDir = "/opt/cuda-cache",
        maxDiskMb = 1234,
        castReceivers = false,
    )

    // What a Settings save posts: form fields only. No `encoder`, no `ingest`, no `subtitle_check`, no
    // `acquisition` — the admin's own AppConfig model does not carry them.
    private val formBody = """{"public_url":"https://media.example.org","scan_schedule":"","metadata":{"age_rating_cascade":["DK"]}}"""

    private fun savedOverA(before: (ConfigStore) -> Unit, then: (ConfigStore) -> Unit) = testApplication {
        runCatching { platform.posix.remove(path) }
        val store = ConfigStore(path).also { runBlocking { it.load() } }
        before(store)
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing { route("/api") { configureConfigRoutes(store, effectiveScanThreads = 4) } }
        }
        val response = client.put("/api/config") { contentType(ContentType.Application.Json); setBody(formBody) }
        assertEquals(HttpStatusCode.NoContent, response.status, "the save itself must succeed")
        then(store)
    }

    @Test
    fun a_settings_save_leaves_every_encoder_value_as_the_config_file_set_it() = savedOverA(
        before = { store -> runBlocking { store.update(store.current.copy(encoder = tuned)) } },
        then = { store -> assertEquals(tuned, store.current.encoder, "a Settings save must not touch [encoder]") },
    )

    @Test
    fun the_save_still_wrote_the_form_s_own_fields() = savedOverA(
        before = { store -> runBlocking { store.update(store.current.copy(encoder = tuned)) } },
        then = { store ->
            assertEquals("https://media.example.org", store.current.publicUrl)
            assertEquals(listOf("DK"), store.current.metadata.ageRatingCascade)
        },
    )

    @Test
    fun the_blocks_already_on_the_restore_list_survive_beside_it() = savedOverA(
        before = { store ->
            runBlocking {
                store.update(
                    store.current.copy(
                        encoder = tuned,
                        ingest = store.current.ingest.copy(realtime = false, webhookSecret = "s3cret", jellyfinReachUrl = "http://192.0.2.10:9505"),
                        subtitleCheck = store.current.subtitleCheck.copy(action = "report", dailyDownloadBudget = 7),
                        acquisition = AcquisitionConfig(enabled = true, pollSeconds = 42),
                    )
                )
            }
        },
        then = { store ->
            val c = store.current
            assertEquals(listOf(false, "s3cret", "http://192.0.2.10:9505"), listOf(c.ingest.realtime, c.ingest.webhookSecret, c.ingest.jellyfinReachUrl))
            assertEquals(listOf("report", 7), listOf(c.subtitleCheck.action, c.subtitleCheck.dailyDownloadBudget))
            assertEquals(AcquisitionConfig(enabled = true, pollSeconds = 42), c.acquisition)
            assertEquals(tuned, c.encoder)
        },
    )

    /**
     * Phase 274 (FR-274-17, amendment 2026-10-13 — DEB-936) — the same class one level down. `ScanConfig`'s
     * seed markers are server-owned ("a step the operator removes stays removed"), and the admin's own
     * `ScanConfig` carries `pipeline` and `defer_while_playing` and nothing else, so every marker arrives as
     * kotlinx's `false`/empty. `suggestions_step_seeded` was the one missing from the route's restore list, so
     * every save from every tab cleared it — measured on production 2026-10-13 by diffing one real save.
     *
     * Asserted as the whole family and reported by TOML key, so a marker a later phase adds to `ScanConfig`
     * without a line in the route fails here by name instead of quietly re-seeding on the next boot. Before
     * the fix this reads `[suggestions_step_seeded]`.
     */
    @Test
    fun a_settings_save_keeps_every_server_owned_seed_marker() = savedOverA(
        before = { store ->
            runBlocking {
                store.update(
                    store.current.copy(
                        scan = store.current.scan.copy(
                            fileCheckStepsSeeded = true,
                            recommendationsStepSeeded = true,
                            suggestionsStepSeeded = true,
                            subtitleCheckSeeded = true,
                            musicStepsSeeded = listOf(MusicSteps.SCAN, MusicSteps.MATCH),
                            avSyncStepSeeded = true,
                        )
                    )
                )
            }
        },
        then = { store ->
            val scan = store.current.scan
            val cleared = buildList {
                if (!scan.fileCheckStepsSeeded) add("file_check_steps_seeded")
                if (!scan.recommendationsStepSeeded) add("recommendations_step_seeded")
                if (!scan.suggestionsStepSeeded) add("suggestions_step_seeded")
                if (!scan.subtitleCheckSeeded) add("subtitle_check_seeded")
                if (scan.musicStepsSeeded != listOf(MusicSteps.SCAN, MusicSteps.MATCH)) add("music_steps_seeded")
                if (!scan.avSyncStepSeeded) add("avsync_step_seeded")
            }
            assertEquals(emptyList<String>(), cleared, "a Settings save carries no seed marker — the route must restore each from the stored config")
        },
    )
}
