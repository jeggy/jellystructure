package dev.jellystructure.ui

import dev.jellystructure.api.httpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

// ── Phase 316 (FR-316-3/-4) — *Films with no folder of their own*: the overview, and the owner's Apply ──

@Serializable
private data class LfFile(val name: String, val role: String, val target: String? = null)

@Serializable
private data class LfTorrent(val name: String, val state: String, val hash: String, @SerialName("seeds_library_path") val seedsLibraryPath: Boolean = false)

@Serializable
private data class LfStep(val step: String, val ok: Boolean, val detail: String)

@Serializable
private data class LfFilm(
    val key: String,
    val title: String,
    val year: Int? = null,
    val video: String,
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    val files: List<LfFile> = emptyList(),
    @SerialName("outside_names") val outsideNames: Int = 0,
    val torrents: List<LfTorrent> = emptyList(),
    val radarr: String = "",
    @SerialName("radarr_note") val radarrNote: String? = null,
    @SerialName("target_folder") val targetFolder: String,
    val state: String = "waiting",
    val steps: List<LfStep> = emptyList(),
    val error: String? = null,
)

@Serializable
private data class LfResult(
    @SerialName("ran_at") val ranAt: Long = 0,
    val running: Boolean = false,
    val applying: Boolean = false,
    val films: List<LfFilm> = emptyList(),
    @SerialName("unmatched_root_images") val unmatchedRootImages: List<String> = emptyList(),
    val error: String? = null,
)

@Serializable
private data class LfApply(val keys: List<String>)

private val lfJson = Json { ignoreUnknownKeys = true }

private fun gb(bytes: Long): String = if (bytes >= 1_000_000_000L) "${(bytes / 100_000_000L) / 10.0} GB" else "${bytes / 1_000_000L} MB"

private suspend fun lfGet(): LfResult? = runCatching {
    val r = httpClient.get("/api/loose-films")
    if (r.status == HttpStatusCode.NoContent) null else lfJson.decodeFromString(LfResult.serializer(), r.body<String>())
}.getOrNull()

internal fun openLooseFilms(scope: CoroutineScope, after: () -> Unit) {
    lfModal("""<h3>Films with no folder of their own</h3><p class="tiny muted">Looking at the library…</p>""")
    scope.launch {
        // A fresh look every time it opens (read-only: the disks, qBittorrent and Radarr are only read).
        runCatching { httpClient.post("/api/loose-films/scan") }
        var r: LfResult? = null
        for (i in 0 until 120) {
            r = lfGet()
            if (r != null && !r.running) break
            delay(1_000)
        }
        render(scope, r, after)
    }
}

private fun render(scope: CoroutineScope, r: LfResult?, after: () -> Unit) {
    if (r == null) return lfModal("""<h3>Couldn’t look at the library</h3><div class="row" style="justify-content:flex-end"><span class="btn ghost" data-lfx>Close</span></div>""")
    val waiting = r.films.filter { it.state == "waiting" || it.state == "failed" }
    val rows = r.films.joinToString("") { f ->
        val torrents = when {
            f.torrents.isEmpty() -> "No torrent seeds it"
            else -> "${f.torrents.size} ${if (f.torrents.size == 1) "torrent seeds" else "torrents seed"} it" +
                (if (f.torrents.all { !it.seedsLibraryPath }) " through a hard link elsewhere — the move doesn’t touch them" else " — ${f.torrents.count { it.seedsLibraryPath }} from this very file, repointed and rechecked")
        }
        val files = f.files.joinToString(" · ") { x ->
            when {
                x.role == "video" -> "the film"
                x.target == null -> "${x.name.esc()} → backed up"
                x.target != x.name -> "${x.name.esc()} → ${x.target?.esc()}"
                else -> x.name.esc()
            }
        }
        val state = when (f.state) {
            "moved" -> """<span class="pill ok">Moved</span>"""
            "moving" -> """<span class="pill">Moving…</span>"""
            "partial" -> """<span class="pill warn">In its folder — ${(f.error ?: "something didn’t finish").esc()}</span>"""
            "failed" -> """<span class="pill bad">Not moved — ${(f.error ?: "failed").esc()}</span>"""
            else -> ""
        }
        val tick = if (f.state == "waiting" || f.state == "failed") """<input type="checkbox" data-lfkey="${f.key.esc()}" checked> """ else ""
        val steps = if (f.steps.isEmpty()) "" else """<div class="tiny muted" style="margin-top:4px">${f.steps.joinToString(" · ") { s -> "${if (s.ok) "✓" else "✗"} ${s.detail.esc()}" }}</div>"""
        """<div style="border-top:1px solid var(--line);padding:10px 0">
             <label style="display:flex;gap:6px;align-items:baseline">$tick<b>${f.title.esc()}${f.year?.let { " ($it)" } ?: ""}</b> <span class="tiny muted">${gb(f.sizeBytes)}</span> $state</label>
             <div class="tiny muted">${f.video.esc()}</div>
             <div class="tiny">$torrents.${if (f.outsideNames > 0) " ${f.outsideNames} other ${if (f.outsideNames == 1) "name" else "names"} on the disk." else ""}</div>
             <div class="tiny">Radarr: ${(f.radarrNote ?: "—").esc()}</div>
             <div class="tiny">Moves to <b>${f.targetFolder.substringAfterLast('/').esc()}/</b> with: $files</div>$steps
           </div>"""
    }
    val unmatched = if (r.unmatchedRootImages.isEmpty()) "" else
        """<p class="tiny muted">Also in the root, matching none of these films (left where they are): ${r.unmatchedRootImages.joinToString(", ") { it.substringAfterLast('/').esc() }}</p>"""
    val busy = r.applying || r.films.any { it.state == "moving" }
    lfModal(
        """<h3>Films with no folder of their own</h3>
           <p class="tiny muted">These films’ files sit in the library’s root folder, so their pictures can show up on other films. Moving each into its own folder
           renames the files on the same disk: torrents seeding a hard link keep seeding untouched, a torrent seeding the file itself is pointed at the new folder,
           Radarr gets the new folder without moving anything, and Jellyfin is told — watch history is carried over. Nothing has been changed yet.</p>
           ${if (r.error != null) """<p class="tiny bad">${r.error.esc()}</p>""" else ""}
           ${if (r.films.isEmpty()) """<p>No loose films right now.</p>""" else rows}
           $unmatched
           <div class="row" style="justify-content:flex-end;gap:8px;margin-top:12px"><span class="btn ghost" data-lfx>Close</span>
           ${if (waiting.isNotEmpty() && !busy) """<span class="btn primary" data-lfapply>Move the ticked films</span>""" else ""}</div>""",
    ) { t ->
        if (t.closest("[data-lfapply]") == null) return@lfModal
        val keys = document.querySelectorAll("[data-lfkey]").let { nl -> (0 until nl.length).mapNotNull { i -> (nl.item(i) as? HTMLInputElement)?.takeIf { it.checked }?.getAttribute("data-lfkey") } }
        if (keys.isEmpty()) return@lfModal
        if (!window.confirm("Move ${keys.size} ${if (keys.size == 1) "film" else "films"} into ${if (keys.size == 1) "its own folder" else "their own folders"}?")) return@lfModal
        (t as? HTMLElement)?.textContent = "Moving…"
        scope.launch {
            val ok = runCatching {
                httpClient.post("/api/loose-films/apply") { contentType(ContentType.Application.Json); setBody(lfJson.encodeToString(LfApply.serializer(), LfApply(keys))) }.status == HttpStatusCode.Accepted
            }.getOrDefault(false)
            if (!ok) return@launch lfModal("""<h3>Couldn’t start the move</h3><div class="row" style="justify-content:flex-end"><span class="btn ghost" data-lfx>Close</span></div>""")
            // Follow the move film by film until it's done.
            var cur: LfResult? = lfGet()
            while (cur != null && (cur.applying || cur.films.any { it.state == "moving" })) {
                render(scope, cur, after)
                delay(2_000)
                cur = lfGet()
            }
            render(scope, cur, after)
            after()
        }
    }
}

private fun lfModal(html: String, onClick: ((Element) -> Unit)? = null) {
    (document.getElementById("lf-modal") as? HTMLElement)?.remove()
    val m = document.createElement("div") as HTMLElement
    m.id = "lf-modal"; m.className = "mu-modal ed-modal on"
    m.innerHTML = """<div class="card" style="max-height:86vh;overflow:auto;max-width:760px">$html</div>"""
    document.body?.appendChild(m)
    m.addEventListener("click") { ev ->
        val t = ev.target as? Element ?: return@addEventListener
        if (t == m || t.closest("[data-lfx]") != null) { m.remove(); return@addEventListener }
        onClick?.invoke(t)
    }
}
