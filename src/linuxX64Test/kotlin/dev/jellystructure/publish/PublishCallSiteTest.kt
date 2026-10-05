package dev.jellystructure.publish

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Phase 307 (FR-307-4) — Publish is the only door out: no file but the queue's worker calls LRCLIB's publish.
 * A grep over the backend's sources (the test runs from the project directory).
 */
class PublishCallSiteTest {
    private fun root(): Path {
        var dir = "."
        repeat(6) {
            val p = Path("$dir/src/linuxX64Main/kotlin/dev/jellystructure")
            if (SystemFileSystem.exists(p)) return p
            dir += "/.."
        }
        fail("the backend's sources were not found from the test's working directory")
    }

    private fun kotlinFiles(dir: Path): List<Path> = SystemFileSystem.list(dir).flatMap { p ->
        if (SystemFileSystem.metadataOrNull(p)?.isDirectory == true) kotlinFiles(p) else if (p.name.endsWith(".kt")) listOf(p) else emptyList()
    }

    private fun read(p: Path) = SystemFileSystem.source(p).buffered().use { it.readString() }

    @Test
    fun only_the_worker_calls_the_lrclib_publish() {
        val files = kotlinFiles(root())
        assertTrue(files.size > 100, "found the sources (${files.size} files)")
        val callers = files.filter { f ->
            val text = read(f)
            // A call, not the definition (`fun publishInstrumental(`) or a KDoc link (`[...publishInstrumental]`).
            Regex("""(?<!fun )\bpublishInstrumental\(""").containsMatchIn(text)
        }.map { it.name }
        assertEquals(listOf("PublishQueue.kt"), callers, "Lrclib.publishInstrumental is called from the publish queue's worker only")
        assertTrue(read(files.single { it.name == "PublishQueue.kt" }).contains("Lrclib.publishInstrumental(payload)"), "with the frozen payload")
        // No route reaches the network for LRCLIB's publish except through the queue.
        val routes = files.filter { it.toString().contains("/server/routes/") }
        assertTrue(routes.none { read(it).contains("lrclib.net/api/publish") })
    }
}
