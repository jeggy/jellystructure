package dev.jellystructure.media

import dev.jellystructure.io.FileIo
import dev.jellystructure.log.Logger
import dev.jellystructure.OutboundHttp
import dev.jellystructure.tmdb.TmdbClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private const val TMDB_ORIGINAL = "https://image.tmdb.org/t/p/original"
private const val INK_THUMB = 32

data class LogoBatchResult(val fetched: Int, val skipped: Int, val failed: Int)

class LogoDownloader(
    private val dataDir: String,
    private val tmdbClient: TmdbClient,
) {
    private suspend fun httpGet(url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): io.ktor.client.statement.HttpResponse =
        OutboundHttp.withPermit { http.get(url, block) }

    // Phase 129 (FR-OPS1 §B.1) — shared client, one idle connection pool for all outbound callers.
    private val http = OutboundHttp.client

    private fun logoFile(kind: String, name: String): String {
        val slug = name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(120)
        return "$dataDir/artwork/$kind/$slug.png"
    }

    private fun ensureDir(kind: String) {
        val dir = Path("$dataDir/artwork/$kind")
        if (!SystemFileSystem.exists(dir)) SystemFileSystem.createDirectories(dir)
    }

    fun hasLogo(kind: String, name: String): Boolean =
        SystemFileSystem.exists(Path(logoFile(kind, name)))

    // ── Phase 232 — which ink a logo is drawn in ("light"/"dark"), persisted as a `<slug>.ink` sidecar ──
    private val inkCache = HashMap<String, String>()

    /** FR-232-3 — read-only and cheap: never computes (that is [computeMissingInk]'s job, off the request
     *  path). `null` = not known yet (a re-downloaded logo drops its sidecar, FR-232-4). */
    fun logoInk(kind: String, name: String): String? {
        val logo = logoFile(kind, name)
        inkCache[logo]?.let { return it }
        val side = Path(logo.removeSuffix(".png") + ".ink")
        if (!SystemFileSystem.exists(side)) return null
        val ink = runCatching { FileIo.readBytes(side).decodeToString().trim() }.getOrNull()
            ?.takeIf { it == LOGO_INK_LIGHT || it == LOGO_INK_DARK } ?: return null
        inkCache[logo] = ink
        return ink
    }

    // ── R308 — whether the client re-inks a logo to show on the light plate, a `<slug>.reink` sidecar ──
    private val reinkCache = HashMap<String, Boolean>()

    /** FR-R308-4/5 — read-only and cheap, like [logoInk]. `true` only when judged so; `null` = draw as is. */
    fun logoReink(kind: String, name: String): Boolean? {
        val logo = logoFile(kind, name)
        reinkCache[logo]?.let { return it.takeIf { v -> v } }
        val side = Path(logo.removeSuffix(".png") + ".reink")
        if (!SystemFileSystem.exists(side)) return null
        val reink = runCatching { FileIo.readBytes(side).decodeToString().trim() == "1" }.getOrDefault(false)
        reinkCache[logo] = reink
        return reink.takeIf { it }
    }

    /** R308 (FR-R308-4) — one more ffmpeg run per logo, ever: the re-ink verdict on an aspect-keeping,
     *  alpha-correct 128 px thumbnail. An unreadable logo is written `0` (draw as is), so it is not retried. */
    private suspend fun judgeReink(path: String) {
        val side = path.removeSuffix(".png") + ".reink"
        val raw = "$side.rgba"
        val reink = runCatching {
            val (w, h) = FfmpegRunner.probeImageSize(path) ?: return@runCatching false
            val (tw, th) = logoReinkThumbSize(w, h)
            if (!FfmpegRunner.rawRgbaThumbAlpha(path, raw, tw, th)) false
            else logoReinkOf(FileIo.readBytes(Path(raw)), tw, th)
        }.getOrDefault(false)
        runCatching { SystemFileSystem.delete(Path(raw), mustExist = false) }
        runCatching { FileIo.writeBytes(Path(side), (if (reink) "1" else "0").encodeToByteArray()) }
        reinkCache.remove(path)
    }

    /** FR-232-2 — one ffmpeg run per logo, EVER: fills in missing sidecars for every captured studio and
     *  network logo. Called at boot and after a fetch batch, on the background gate class. R308 adds the
     *  re-ink verdict to the same pass, for a logo that has no `.reink` yet. */
    suspend fun computeMissingInk(): Int {
        var done = 0
        for (kind in listOf("studios", "networks")) {
            val dir = Path("$dataDir/artwork/$kind")
            if (!SystemFileSystem.exists(dir)) continue
            for (file in SystemFileSystem.list(dir)) {
                val path = file.toString()
                if (!path.endsWith(".png")) continue
                if (!SystemFileSystem.exists(Path(path.removeSuffix(".png") + ".reink"))) { judgeReink(path); done++ }
                val side = path.removeSuffix(".png") + ".ink"
                if (SystemFileSystem.exists(Path(side))) continue
                val raw = "$side.rgba"
                val ink = runCatching {
                    if (!FfmpegRunner.rawRgbaThumb(path, raw, INK_THUMB)) null
                    else logoInkOf(FileIo.readBytes(Path(raw)), INK_THUMB, INK_THUMB)
                }.getOrNull()
                runCatching { SystemFileSystem.delete(Path(raw), mustExist = false) }
                // An unreadable logo is written as "dark" (the default plate) so it is not retried on every boot.
                runCatching { FileIo.writeBytes(Path(side), (ink ?: LOGO_INK_DARK).encodeToByteArray()) }
                inkCache.remove(path)
                done++
            }
        }
        if (done > 0) Logger.info("Logo ink: judged $done logo(s)", "artwork")
        return done
    }

    fun serveLogo(kind: String, name: String): ByteArray? {
        val path = Path(logoFile(kind, name))
        return if (SystemFileSystem.exists(path)) FileIo.readBytes(path) else null
    }

    suspend fun fetchStudioLogo(name: String, tmdbId: Int?, capturedLogoPath: String?): Boolean {
        ensureDir("studios")
        val destPath = logoFile("studios", name)
        if (SystemFileSystem.exists(Path(destPath))) return true
        val logoPath = capturedLogoPath ?: run {
            tmdbClient.searchCompany(name)?.logoPath
        } ?: return false
        return download("$TMDB_ORIGINAL$logoPath", destPath)
    }

    suspend fun fetchNetworkLogo(name: String, capturedLogoPath: String?): Boolean {
        if (capturedLogoPath.isNullOrBlank()) return false
        ensureDir("networks")
        val destPath = logoFile("networks", name)
        if (SystemFileSystem.exists(Path(destPath))) return true
        return download("$TMDB_ORIGINAL$capturedLogoPath", destPath)
    }

    suspend fun batchFetchStudios(studios: List<Triple<String, Int?, String?>>): LogoBatchResult {
        var fetched = 0; var skipped = 0; var failed = 0
        for ((name, tmdbId, logoPath) in studios) {
            if (hasLogo("studios", name)) { skipped++; continue }
            val ok = runCatching { fetchStudioLogo(name, tmdbId, logoPath) }.getOrDefault(false)
            if (ok) fetched++ else failed++
        }
        if (fetched > 0) runCatching { computeMissingInk() }  // Phase 232 (FR-232-2)
        return LogoBatchResult(fetched, skipped, failed)
    }

    suspend fun batchFetchNetworks(networks: List<Pair<String, String?>>): LogoBatchResult {
        var fetched = 0; var skipped = 0; var failed = 0
        for ((name, logoPath) in networks) {
            if (hasLogo("networks", name)) { skipped++; continue }
            val ok = runCatching { fetchNetworkLogo(name, logoPath) }.getOrDefault(false)
            if (ok) fetched++ else failed++
        }
        if (fetched > 0) runCatching { computeMissingInk() }  // Phase 232 (FR-232-2)
        return LogoBatchResult(fetched, skipped, failed)
    }

    private fun personImageFile(tmdbId: Int) = "$dataDir/artwork/people/$tmdbId.jpg"

    fun servePersonImage(tmdbId: Int): ByteArray? {
        val path = Path(personImageFile(tmdbId))
        return if (SystemFileSystem.exists(path)) FileIo.readBytes(path) else null
    }

    suspend fun fetchPersonImage(tmdbId: Int, profilePath: String?): Boolean {
        if (profilePath.isNullOrBlank()) return false
        ensureDir("people")
        val destPath = personImageFile(tmdbId)
        if (SystemFileSystem.exists(Path(destPath))) return true
        return download("https://image.tmdb.org/t/p/w185$profilePath", destPath)
    }

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun download(url: String, destPath: String): Boolean {
        val result = runCatching {
            val bytes = httpGet(url).readRawBytes()
            if (bytes.isEmpty()) return@runCatching false
            val tmp = "$destPath.tmp"
            FileIo.writeBytes(Path(tmp), bytes)   // Phase 134: use{}-scoped — no FD leak on a mid-write throw
            platform.posix.rename(tmp, destPath)
            // Phase 232 (FR-232-4) — a replaced logo is re-judged: drop its ink sidecar + cache entry.
            if (destPath.endsWith(".png")) {
                inkCache.remove(destPath); runCatching { SystemFileSystem.delete(Path(destPath.removeSuffix(".png") + ".ink"), mustExist = false) }
                // R308 — the re-ink verdict is re-judged with it.
                reinkCache.remove(destPath); runCatching { SystemFileSystem.delete(Path(destPath.removeSuffix(".png") + ".reink"), mustExist = false) }
            }
            Logger.info("Downloaded logo: $destPath", "artwork")
            true
        }
        if (result.isFailure) Logger.warn("Failed to download logo $url: ${result.exceptionOrNull()?.message}")
        return result.getOrDefault(false)
    }
}
