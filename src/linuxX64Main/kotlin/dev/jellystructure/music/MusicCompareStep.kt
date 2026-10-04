package dev.jellystructure.music

import dev.jellystructure.io.FileIo
import dev.jellystructure.log.Logger
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.nowEpochSec
import dev.jellystructure.ops.GateClass
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Phase 305 (dev review 3/6) — a song file's full-length raw Chromaprint, on disk like `FingerprintService`'s (tens of
 * KB each, never in SQLite): `fingerprints/music/<track id>-<size>-<mtime>.json`. A changed file misses the cache by
 * its name, so nothing has to notice the change.
 */
class MusicFingerprintCache(
    dataDir: String,
    private val compute: suspend (String) -> List<Int>?,
    private val stat: (String) -> Pair<Long, Long>? = { p -> dev.jellystructure.media.FileIntegrityService.stampOf(p)?.let { it.size to it.mtime } },
) {
    private val dir = "${dataDir.trimEnd('/')}/fingerprints/music"
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(Int.serializer())

    init { runCatching { SystemFileSystem.createDirectories(Path(dir)) } }

    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }

    fun pathOf(t: MusicTrack): String? {
        val p = t.path ?: return null
        val (size, mtime) = stat(p) ?: return null
        return "$dir/${safe(t.id)}-$size-$mtime.json"
    }

    /** The file has a fingerprint for its current size and mtime. */
    fun has(t: MusicTrack): Boolean = pathOf(t)?.let { SystemFileSystem.exists(Path(it)) } == true

    /** The fingerprint, computed (and kept) on a miss; `fresh` = it was computed now. Null when the file is gone or
     *  fpcalc failed. */
    suspend fun get(t: MusicTrack): Pair<List<Int>, Boolean>? {
        val at = pathOf(t) ?: return null
        if (SystemFileSystem.exists(Path(at))) {
            runCatching { json.decodeFromString(ser, FileIo.readText(Path(at))) }.getOrNull()?.let { return it to false }
        }
        val fp = compute(t.path!!) ?: return null
        runCatching {
            val tmp = "$at.tmp"
            FileIo.writeText(Path(tmp), json.encodeToString(ser, fp))
            platform.posix.rename(tmp, at)
        }.onFailure { Logger.warn("music fingerprint cache: ${t.id}: ${it.message}", "music") }
        return fp to true
    }
}

/**
 * Phase 305 (dev review 5/6/7) — the pipeline step `compare_songs`: fingerprint the candidate pairs' files (four at a
 * time, background process class) and store each pair's bit error, coverage and offset. No network. A second run
 * measures only pairs with a new or changed file.
 */
class MusicCompareSongsStep(
    private val store: MusicStore,
    private val cache: MusicFingerprintCache,
    private val concurrency: Int = 4,
) {
    data class Summary(val pairs: Int, val measured: Int, val joined: Int, val suggestions: Int, val failed: Int) {
        fun sentence(): String = if (pairs == 0) "no songs to compare" else buildString {
            append("$measured of $pairs pair${if (pairs == 1) "" else "s"} compared")
            if (joined > 0) append(" · $joined sound the same")
            if (suggestions > 0) append(" · $suggestions may be the same (Dashboard)")
            if (failed > 0) append(" · $failed couldn't be fingerprinted")
        }
    }

    suspend fun run(): Summary = withContext(GateClass.BACKGROUND) {
        val snap = store.snapshot()
        val candidates = MusicCompareSongs.candidates(snap)
        val measured = snap.soundPairs.associateBy { it.a to it.b }
        // Only pairs never measured, or with a file whose fingerprint is not cached for its current size and mtime.
        val due = candidates.filter { c -> (c.a.id to c.b.id) !in measured || !cache.has(c.a) || !cache.has(c.b) }
        val tracks = due.flatMap { listOf(it.a, it.b) }.distinctBy { it.id }
        val gate = Semaphore(concurrency)
        val fps = coroutineScope {
            tracks.map { t -> async { t.id to gate.withPermit { runCatching { cache.get(t)?.first }.getOrNull() } } }.awaitAll()
        }.toMap()
        val now = nowEpochSec()
        var failed = 0
        val out = ArrayList<MusicSoundPair>()
        for (c in due) {
            val a = fps[c.a.id]; val b = fps[c.b.id]
            if (a == null || b == null) { failed++; continue }
            val s = MusicSoundMatch.score(a, b) ?: run { failed++; null } ?: continue
            out += MusicSoundPair(c.a.id, c.b.id, s.ber, s.coverage, s.offsetMs, now)
        }
        store.putSoundPairs(out)
        val after = store.snapshot()
        Summary(candidates.size, out.size, out.count { p -> MusicSoundMatch.joins(p.score) && !(after.tracks[p.a]?.let { trusted(it) } == true && after.tracks[p.b]?.let { trusted(it) } == true) },
            after.songs.suggestions.size, failed)
    }

    private fun trusted(t: MusicTrack) = dev.jellystructure.model.MusicVersions.keyOf(t).startsWith("rec:")
}
