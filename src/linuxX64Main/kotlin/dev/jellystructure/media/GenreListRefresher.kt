package dev.jellystructure.media

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.log.Logger
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.TrackKind
import dev.jellystructure.resolver.LanguageResolver
import dev.jellystructure.tmdb.TmdbClient

/**
 * Phase 271 (FR-271-2, FR-271-6) — keeps [GenreCatalog]'s labels complete, and maps stored genre names
 * to their ids.
 *
 * TMDB's two genre lists (`/genre/movie/list`, `/genre/tv/list`) are fetched per language in use, once a
 * week per language: two requests each. "In use" is every language a title's details could have been
 * requested in — each title's own language-priority chain (its audio tracks, then the library's fallback,
 * exactly what the Scanner asks TMDB in), its resolved, original and hand-set metadata languages — plus
 * every language Ravilo's apps speak and English. A language seen for the first time is fetched on the
 * next pass rather than a week later.
 *
 * After a pass that learned anything, [MediaStore.normalizeGenres] maps every stored name to its id in
 * one batched write (FR-271-6).
 */
class GenreListRefresher(
    private val tmdb: TmdbClient,
    private val mediaStore: MediaStore,
    private val configStore: ConfigStore,
    private val db: JellystructureDb,
) {
    /** One pass: fetch whatever language is due, then normalise the library if anything was learned. */
    suspend fun refreshDue(nowSec: Long, force: Boolean = false): RefreshResult {
        if (configStore.current.apiKeys.tmdbV3Key.isBlank()) return RefreshResult(0, 0, 0)
        val fetched = db.genreLabelQueries.allListFetches().executeAsList().associate { it.language to it.fetched_at }
        val due = languagesInUse(mediaStore.allItems()).filter { lang ->
            force || (fetched[lang]?.let { nowSec - it >= WEEK_SEC } ?: true)
        }.sorted()
        var labelsChanged = 0
        var languagesFetched = 0
        for (lang in due) {
            val movie = tmdb.getGenreList(GenreCatalog.KIND_MOVIE, lang) ?: continue
            val tv = tmdb.getGenreList(GenreCatalog.KIND_TV, lang) ?: continue
            labelsChanged += GenreCatalog.recordList(GenreCatalog.KIND_MOVIE, lang, movie.map { it.id to it.name })
            labelsChanged += GenreCatalog.recordList(GenreCatalog.KIND_TV, lang, tv.map { it.id to it.name })
            db.genreLabelQueries.putListFetch(lang, nowSec)
            languagesFetched++
        }
        val rows = mediaStore.normalizeGenres(labelsChanged = labelsChanged > 0)
        if (languagesFetched > 0) {
            Logger.info("Genre lists: fetched $languagesFetched language(s), $labelsChanged label(s) new or changed, $rows title(s) mapped to genre ids", "tmdb")
        }
        return RefreshResult(languagesFetched, labelsChanged, rows)
    }

    data class RefreshResult(val languagesFetched: Int, val labelsChanged: Int, val titlesMapped: Int)

    /** Every language a title's genre names could be in, plus the apps' own and English. */
    fun languagesInUse(items: List<MediaItem>): Set<String> {
        val cfg = configStore.current
        val out = LinkedHashSet<String>()
        out += GenreCatalog.ENGLISH
        out += RAVILO_UI_LANGUAGES
        fun add(code: String?) {
            val c = code?.trim()?.takeIf { it.isNotEmpty() && it != "und" } ?: return
            out += GenreCatalog.normLang(LanguageResolver.normalize(c))
        }
        add(cfg.languageRules.fallbackLanguage)
        for (lib in cfg.libraries) add(lib.fallbackLanguage)
        for (item in items) {
            add(item.resolvedLanguage); add(item.originalLanguage); add(item.metadataLanguage)
            val tracks = if (item.kind == MediaKind.TV_SHOW) item.episodes.asSequence().flatMap { it.tracks } else item.tracks.asSequence()
            tracks.filter { it.kind == TrackKind.AUDIO }.forEach { add(it.language) }
        }
        // A two-letter code is what TMDB's `language` takes; anything else (an unmapped three-letter
        // tag, `mul`, `zxx`) would only cost a request that answers English.
        return out.filterTo(LinkedHashSet()) { it.length == 2 && it.all { ch -> ch in 'a'..'z' } }
    }

    companion object {
        const val WEEK_SEC = 7 * 24 * 3600L
        /** How often the loop wakes to see whether any language is due (a new language waits at most this). */
        const val CHECK_INTERVAL_MS = 6 * 3600 * 1000L
        /** The languages Ravilo's apps are translated into — `i18n/{en,da,fo}.json`. */
        val RAVILO_UI_LANGUAGES = listOf("en", "da", "fo")
    }
}
