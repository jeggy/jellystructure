package dev.jellystructure.imdb

import dev.jellystructure.OutboundHttp
import dev.jellystructure.io.FileIo
import dev.jellystructure.log.Logger
import dev.jellystructure.nowEpochSec
import dev.jellystructure.ops.ProcessGate
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.popen
import platform.posix.pclose
import platform.posix.rename

/** One fetched rating: (aggregateRating 0-10, voteCount). */
data class ImdbFetchedRating(val aggregateRating: Double, val voteCount: Long)

/**
 * Phase 158 — replaces the dead imdbapi.dev source (the domain no longer resolves) and supersedes
 * this phase's own original "crawl imdb.com's title page" plan: live testing during implementation
 * found imdb.com now sits behind an **AWS WAF JavaScript challenge** (`token.awswaf.com/.../challenge.js`,
 * a proof-of-work/browser-fingerprint check), confirmed consistently across multiple User-Agents — not
 * a simple 403, but a challenge a plain HTTP client cannot solve without a JS engine, which this
 * project's own non-goals correctly rule out.
 *
 * Instead, this uses IMDb's own **officially published, non-commercial-use bulk dataset**
 * (`datasets.imdbws.com/title.ratings.tsv.gz` — see https://developer.imdb.com/non-commercial-datasets/),
 * updated daily, served from plain S3/CloudFront with **no anti-bot at all** (verified live: a bare
 * request succeeds, no headers needed). This is a strictly better fit than either the dead API or a
 * scrape: no per-title HTTP calls, no ToS/robots.txt tension, no throttling arms race — one small
 * (~9MB compressed) file download refreshes ratings for the *entire* library at once. `tconst` in the
 * dataset is exactly jellystructure's own `imdbId` format (`tt` + digits) — no id translation needed.
 *
 * The public [getRating] contract is unchanged from Phase 131: best-effort, null on any failure/missing
 * rating, so callers leave a stored rating intact rather than blanking it on a transient error. Ratings
 * are looked up from an in-memory table loaded from the on-disk cache; the cache refreshes at most once
 * per [refreshIntervalSec] (the dataset itself only updates once a day), so a whole pipeline run's worth
 * of per-title lookups costs at most one download.
 */
class ImdbClient(dataDir: String) {
    private val http = OutboundHttp.client
    private val datasetUrl = "https://datasets.imdbws.com/title.ratings.tsv.gz"
    private val gzPath = "$dataDir/imdb-ratings.tsv.gz"
    private val tsvPath = "$dataDir/imdb-ratings.tsv"
    private val metaPath = "$dataDir/imdb-ratings.meta"

    // The dataset updates once a day; re-downloading more often than this would just re-fetch the same
    // bytes. 20h (not 24h) so a daily scheduled sync run never drifts into "always just barely stale."
    private val refreshIntervalSec = 20 * 3600L

    private val mutex = Mutex()
    private var cache: Map<String, ImdbFetchedRating>? = null

    init {
        runCatching { SystemFileSystem.createDirectories(Path(dataDir)) }
    }

    /** Best-effort — null on any failure/missing rating, so callers can leave the stored value intact
     *  on a transient error rather than blanking a good rating. */
    suspend fun getRating(imdbId: String): ImdbFetchedRating? {
        val table = mutex.withLock { ensureLoaded() } ?: return null
        return table[imdbId]
    }

    private suspend fun ensureLoaded(): Map<String, ImdbFetchedRating>? {
        val lastDownloadAt = runCatching { FileIo.readText(Path(metaPath)).trim().toLong() }.getOrNull()
        val staleOnDisk = lastDownloadAt == null || nowEpochSec() - lastDownloadAt > refreshIntervalSec
        val haveTsv = SystemFileSystem.exists(Path(tsvPath))

        if (!haveTsv || staleOnDisk) {
            val downloaded = download()
            if (downloaded) {
                cache = null // force a re-parse below
            } else if (!haveTsv) {
                // Never downloaded and no cached copy on disk at all — nothing to serve.
                return cache
            }
            // A failed refresh with an existing (merely stale) on-disk copy still serves the old
            // data — better than going blank on a transient network hiccup, same "preserve the last
            // good value" spirit as the per-title contract.
        }

        cache?.let { return it }
        val parsed = parseTsv()
        cache = parsed
        return parsed
    }

    private suspend fun download(): Boolean = OutboundHttp.withPermit {
        val result = runCatching {
            val bytes = http.get(datasetUrl).readRawBytes()
            if (bytes.isEmpty()) return@runCatching false
            val tmpGz = "$gzPath.tmp"
            FileIo.writeBytes(Path(tmpGz), bytes)
            rename(tmpGz, gzPath)
            if (!gunzip(gzPath, tsvPath)) return@runCatching false
            FileIo.writeText(Path(metaPath), nowEpochSec().toString())
            Logger.info("imdb: downloaded ratings dataset (${bytes.size} bytes)", "imdb")
            true
        }
        if (result.isFailure) Logger.warn("imdb: dataset download failed: ${result.exceptionOrNull()?.message}", "imdb")
        result.getOrDefault(false)
    }

    /** Decompresses [gzPath] to [outPath] by shelling out to `gunzip` (same idiom as this codebase's
     *  ffmpeg/ffprobe/fpcalc calls — no native zlib binding needed for a once-a-day file). */
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    private suspend fun gunzip(gzPath: String, outPath: String): Boolean = ProcessGate.withPermit {
        val tmpOut = "$outPath.tmp"
        val escapedGz = gzPath.replace("'", "'\\''")
        val escapedOut = tmpOut.replace("'", "'\\''")
        val ok = runCatching {
            val pipe = popen("gunzip -c '$escapedGz' > '$escapedOut'", "r") ?: return@runCatching false
            pclose(pipe)
            SystemFileSystem.exists(Path(tmpOut))
        }.getOrDefault(false)
        if (ok) rename(tmpOut, outPath)
        ok
    }

    /** Parses the TSV (`tconst\taverageRating\tnumVotes`, header row first) into a lookup map. ~1.7M
     *  rows / ~35MB in memory — an acceptable one-time cost for a backend process, versus one HTTP
     *  round-trip per title the old API-based design needed. */
    private suspend fun parseTsv(): Map<String, ImdbFetchedRating> {
        val result = runCatching {
            val text = FileIo.readText(Path(tsvPath))
            val table = HashMap<String, ImdbFetchedRating>(1_800_000)
            var firstLine = true
            for (line in text.lineSequence()) {
                if (firstLine) { firstLine = false; continue }
                if (line.isEmpty()) continue
                val tab1 = line.indexOf('\t')
                if (tab1 < 0) continue
                val tab2 = line.indexOf('\t', tab1 + 1)
                if (tab2 < 0) continue
                val id = line.substring(0, tab1)
                val rating = line.substring(tab1 + 1, tab2).toDoubleOrNull() ?: continue
                val votes = line.substring(tab2 + 1).trim().toLongOrNull() ?: continue
                table[id] = ImdbFetchedRating(rating, votes)
            }
            table
        }
        if (result.isFailure) Logger.warn("imdb: failed to parse ratings dataset: ${result.exceptionOrNull()?.message}", "imdb")
        return result.getOrDefault(emptyMap())
    }
}
