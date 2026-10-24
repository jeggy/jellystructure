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
 * `ingest`, `subtitle_check`, `acquisition` or `ravilo` key at all (the frontend `AppConfig` has no such
 * fields), which is exactly how kotlinx comes to fill in defaults. The assertions cover the family, not just
 * the one bug, so the next block added to `AppConfig` without a restore line fails a test rather than quietly
 * resetting a household value.
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
    // `acquisition`, no `ravilo` — the admin's own AppConfig model does not carry them.
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

    // ── FR-314-20 (DEB-1009) — the versions library's one validate, on the press that configures it ──────────────

    /**
     * Test 18, as corrected on 2026-10-14. The requirement asked for *"exactly once … and asks for nothing on the
     * movies or series libraries"*; measured, no per-library call exists in any refresh mode, so the only call that
     * resolves a never-scanned library is `POST /Library/Refresh`, which takes no library at all. What is asserted
     * here is therefore the **trigger**, which is the whole of the decision: a save that changes the versions
     * configuration validates once, a save that does not validates never, and nothing happens while no root is set.
     */
    private fun savesWithVersionsRoot(before: (ConfigStore) -> Unit, body: String, expectValidates: Int, hint: String) = testApplication {
        runCatching { platform.posix.remove(path) }
        val store = ConfigStore(path).also { runBlocking { it.load() } }
        before(store)
        var validates = 0
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing {
                route("/api") {
                    configureConfigRoutes(store, effectiveScanThreads = 4, validateJellyfinLibraries = { validates++; true })
                }
            }
        }
        val response = client.put("/api/config") { contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.NoContent, response.status, "the save itself must succeed")
        assertEquals(expectValidates, validates, hint)
    }

    /** The mapping FR-314-16 needs: the root in this server's view, and Jellyfin's name for the same folder. */
    private val versionsLibrary = """{"jellyfin_id":"versions-lib","name":"library-versions","collection_type":"movies","jellyfin_path":"/media/versions","local_path":"/mnt/media/jellyfin/versions","skip":true}"""
    private val otherLibrary = """{"jellyfin_id":"movies-lib","name":"library-movies","collection_type":"movies","jellyfin_path":"/media/movies","local_path":"/mnt/media/jellyfin/movies"}"""
    private fun formWith(root: String, libraries: String) =
        """{"public_url":"","scan_schedule":"","file_fix":{"dv_versions_root":"$root"},"libraries":[$libraries]}"""

    @Test
    fun setting_the_versions_root_asks_jellyfin_to_validate_its_libraries_once() = savesWithVersionsRoot(
        before = {},
        body = formWith("/mnt/media/jellyfin/versions", versionsLibrary),
        expectValidates = 1,
        hint = "step 3 — a root where there was none is the press that must make the library resolvable",
    )

    /**
     * The seventh amendment's step 4: the root is already set and the owner now types the library's `local_path`.
     * Until that mapping exists the root has no name in Jellyfin's view, so this is the press that **completes** the
     * configuration on an installation set up in that order, and it has to fire too.
     */
    @Test
    fun filling_in_the_librarys_local_path_afterwards_validates_too() = savesWithVersionsRoot(
        before = { store ->
            runBlocking { store.update(store.current.copy(fileFix = dev.jellystructure.config.FileFixConfig(dvVersionsRoot = "/mnt/media/jellyfin/versions"))) }
        },
        body = formWith("/mnt/media/jellyfin/versions", versionsLibrary),
        expectValidates = 1,
        hint = "step 4 — the mapping that names the root to Jellyfin appeared, so the configuration is only now complete",
    )

    @Test
    fun a_save_that_changes_nothing_about_the_versions_configuration_validates_nothing() = savesWithVersionsRoot(
        before = { store ->
            runBlocking {
                store.update(store.current.copy(
                    fileFix = dev.jellystructure.config.FileFixConfig(dvVersionsRoot = "/mnt/media/jellyfin/versions"),
                    libraries = listOf(dev.jellystructure.config.LibraryMapping(
                        jellyfinId = "versions-lib", name = "library-versions", collectionType = "movies",
                        jellyfinPath = "/media/versions", localPath = "/mnt/media/jellyfin/versions", skip = true)),
                ))
            }
        },
        // The same versions configuration, with an unrelated library added beside it: a later Settings save, or the
        // owner editing a different row. Neither the root nor the root's mapping moved.
        body = formWith("/mnt/media/jellyfin/versions", "$versionsLibrary,$otherLibrary"),
        expectValidates = 0,
        hint = "every Settings save would otherwise validate the whole library — this must fire on a change, not on a save",
    )

    @Test
    fun a_save_with_no_versions_root_validates_nothing() = savesWithVersionsRoot(
        before = {},
        body = formWith("", "$versionsLibrary,$otherLibrary"),
        expectValidates = 0,
        hint = "no root configured: kind C cannot run at all and there is nothing to make resolvable",
    )

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
                        // Phase 304b (FR-304-7, amendment 2026-10-12 — DEB-731) — the household switch, turned on
                        // through its own route. The only one of the family whose reset a viewer notices.
                        ravilo = store.current.ravilo.copy(householdControl = true),
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
            assertEquals(true, c.ravilo.householdControl, "a Settings save must not turn household control back off")
        },
    )

    /**
     * Phase 320 — `[support]` is config-file-only until the admin phase adds its toggle, and the admin's form model has no
     * `support` key. Without a restore line a Settings save wrote `SupportConfig()`'s `enabled = false` and its default
     * limits over the owner's switch, which with a key set quietly takes the lane away from viewers. DEB-1508 (FR-8): the same
     * block carries Hervør's switch and her two rates, so a Settings save never flips her either.
     */
    @Test
    fun a_settings_save_leaves_the_support_block_as_the_config_file_set_it() = savedOverA(
        before = { store ->
            runBlocking {
                store.update(store.current.copy(support = dev.jellystructure.config.SupportConfig(
                    enabled = true, ticketsPerDay = 2, messagesPerDay = 9, maxBodyChars = 500, owedReplyHours = 6, retentionDays = 30, hervorEnabled = true,
                    hervorMessagesPerHour = 3, hervorMessagesPerTicketPerDay = 2,
                )))
            }
        },
        then = { store ->
            assertEquals(
                dev.jellystructure.config.SupportConfig(enabled = true, ticketsPerDay = 2, messagesPerDay = 9, maxBodyChars = 500, owedReplyHours = 6, retentionDays = 30, hervorEnabled = true,
                    hervorMessagesPerHour = 3, hervorMessagesPerTicketPerDay = 2),
                store.current.support, "a Settings save must not touch [support]",
            )
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
                // DEB-1664 (FR-2.4): every write settles the markers to "all seeded" so a rolled-back image seeds nothing back.
                if (scan.musicStepsSeeded != MusicSteps.ALL) add("music_steps_seeded")
                if (!scan.avSyncStepSeeded) add("avsync_step_seeded")
            }
            assertEquals(emptyList<String>(), cleared, "a Settings save carries no seed marker — the route must restore each from the stored config")
        },
    )
}
