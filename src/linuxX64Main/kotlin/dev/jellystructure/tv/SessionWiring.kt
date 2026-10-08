package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.jobs.JobEvent
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.log.Logger
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.MusicPipeline
import dev.jellystructure.media.visibleTo
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.fileDurationMs
import dev.jellystructure.music.MusicTvService
import dev.jellystructure.music.musicVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt

/** R368 — how often the session clock runs (holds, reconnect deadlines, the 60 s linger, the hourly sweep). */
private const val SESSION_TICK_MS = 5_000L

/** "S01E05" (feedback: episode codes are always this shape). */
internal fun episodeCode(season: Int?, episode: Int?): String? =
    if (season != null && episode != null) "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}" else null

/**
 * R368 + 304a — builds the sessions, their publisher and the clock, and connects them to the rest of the server:
 * what an item is (the library, the music store, the audiobooks store), who may see it (the same rules as playback),
 * the cast hand-off's minter, an address change, the boot restore and the admin's `/ws`.
 */
suspend fun wirePlaybackSessions(
    db: JellystructureDb,
    scope: CoroutineScope,
    mediaStore: MediaStore,
    musicPipeline: MusicPipeline?,
    devices: RaviloDeviceService,
    bus: TvEventBus,
    playback: PlaybackService,
    castService: CastService,
    broadcaster: WsBroadcaster,
    configStore: dev.jellystructure.config.ConfigStore,
): SessionPublisher {
    val sessions = PlaybackSessions(db)
    sessions.describe = describe@{ itemId, bookId ->
        val books = musicPipeline?.audiobooks?.store
        if (bookId != null && books != null) {
            val b = books.book(bookId) ?: return@describe SessionKind.AUDIOBOOK to SessionItem(itemId)
            return@describe SessionKind.AUDIOBOOK to SessionItem(itemId, b.title, b.authors.joinToString(", ").ifBlank { null },
                dev.jellystructure.audiobooks.AudiobooksTvService.coverUrl(b), b.durationMs.takeIf { it > 0 })
        }
        musicPipeline?.store?.track(itemId)?.let { t ->
            val album = t.albumId?.let { musicPipeline.store.album(it) }
            val sub = listOfNotNull(t.artists.joinToString(", ") { it.name }.ifBlank { null }, album?.title).joinToString(" · ").ifBlank { null }
            return@describe SessionKind.MUSIC to SessionItem(itemId, t.title, sub, album?.let { MusicTvService.albumImage(it) }, t.durationMs)
        }
        mediaStore.resolveByJellyfinId(itemId)?.takeIf { it.episodes.isEmpty() }?.let { film ->
            return@describe SessionKind.FILM to SessionItem(itemId, film.title, null, RaviloImageUrl.backdrop(film.id), film.tracks.fileDurationMs())
        }
        for (series in mediaStore.allItems()) {
            if (series.kind != MediaKind.TV_SHOW) continue
            val ep = series.episodes.firstOrNull { it.jellyfinId == itemId } ?: continue
            val code = episodeCode(ep.seasonNumber, ep.episodeNumber)
            return@describe SessionKind.EPISODE to SessionItem(itemId, series.title, code,
                RaviloImageUrl.still(series.id, ep.filename, ep.episodeNumber), ep.tracks.fileDurationMs())
        }
        null
    }
    val canSee: suspend (DeviceData, String, String, String?) -> Boolean = { viewer, kind, itemId, bookId ->
        when (kind) {
            SessionKind.AUDIOBOOK -> bookId?.let { musicPipeline?.audiobooks?.store?.book(it) }?.let { musicVisible(it.libraryId, viewer.allowedLibraries) } == true
            SessionKind.MUSIC -> musicPipeline?.store?.track(itemId)?.let { musicVisible(it.libraryId, viewer.allowedLibraries) } == true
            else -> (mediaStore.resolveByJellyfinId(itemId)
                ?: mediaStore.allItems().firstOrNull { s -> s.episodes.any { it.jellyfinId == itemId } })?.visibleTo(viewer) == true
        }
    }
    val publisher = SessionPublisher(sessions, devices, bus, canSee, scope)
    publisher.zoneOffsetMs = { ms -> runCatching { TimeZone.currentSystemDefault().offsetAt(kotlin.time.Instant.fromEpochMilliseconds(ms)).totalSeconds * 1000L }.getOrDefault(0L) }
    publisher.adminBroadcast = { list -> broadcaster.broadcast(JobEvent.PlaybackSessions(list)) }
    // 308 (FR-308-5) — *Playing now* shows the variant the session's player last reported.
    publisher.variantOf = { s -> playback.variantOf(s.targetId, s.itemId) }
    publisher.servedOf = { s -> playback.servedOf(s.targetId, s.itemId) }   // 313 (FR-313-13)
    playback.onVariantReported = { publisher.adminChanged() }
    // R369 + 304b — commands through the server, attached controllers, the household switch.
    val control = SessionControl(db, sessions, devices, bus)
    control.clearOnBoot()
    control.householdControl = { configStore.current.ravilo.householdControl }
    control.canSee = { viewer, s -> s.ownerUserId == viewer.jellyfinUserId || canSee(viewer, s.kind, s.itemId, s.bookId) }
    publisher.control = control
    publisher.controlWidened = { true }
    publisher.householdControl = { configStore.current.ravilo.householdControl }
    publisher.controllersOf = { id ->
        control.controllerDevices(id).map { d -> devices.listSessions(d).maxByOrNull { it.lastSeen }?.let { sessions.placeNameOf(it) } ?: d }
    }
    publisher.opsOf = { s -> control.opsOf(s) }
    publisher.setHouseholdControl = { on ->
        configStore.update(configStore.current.copy(ravilo = configStore.current.ravilo.copy(householdControl = on)))
        publisher.publish(SessionChange.List)   // review item 6 — `controllable` flips on every opted-in socket at once
    }
    // R370 — the places list, starts and the relay (owner decision 1).
    val starter = SessionStarter(sessions, control, devices, bus, castService, CastReach(), serverUrl = {
        dev.jellystructure.model.PublicUrl.effective(configStore.current.publicUrl) ?: ""
    })
    starter.trackItem = { id ->
        musicPipeline?.store?.track(id)?.let { t ->
            val album = t.albumId?.let { musicPipeline.store.album(it) }
            dev.jellystructure.shared.tv.CastTrackItem(id = t.id, title = t.title, artist = t.artists.joinToString(", ") { it.name }.ifBlank { null },
                album = album?.title, coverUrl = album?.let { MusicTvService.albumImage(it) }, durationMs = t.durationMs)
        }
    }
    publisher.starter = starter
    // R372 — the old place stops once the new one plays (its stop report then goes through stopPlayback as ever).
    sessions.stopPlace = { s, from ->
        if (bus.isConnected(from)) bus.notifyPlaystateCommand(s.ownerUserId, from, "Stop", null)
    }
    control.relayAvailable = { s -> starter.relayAvailable(s) }
    control.relayLoad = { s -> starter.relayResume(s) }
    // R371 (owner decisions 1–2) — a room op with no link holder goes to a relay app on that network.
    control.relayAppFor = { s -> starter.relayAppFor(s) }
    control.rememberRoomLevel = { id, room, level -> sessions.rememberRoomLevel(id, room, level) }
    sessions.notify = { change -> publisher.publish(change); if (change is SessionChange.List) starter.targetsChanged() }
    PlaybackSessions.current = sessions
    castService.onRedeemed = { receiver, minterDeviceId, castDeviceId, sessionId -> sessions.onReceiverRedeemed(receiver, minterDeviceId, castDeviceId, sessionId) }
    devices.onAddressChanged = { publisher.addressChanged() }
    playback.sessions = sessions
    runCatching {
        playback.restoreSessions { deviceId, userId -> devices.listSessions(deviceId).firstOrNull { it.jellyfinUserId == userId } }
    }.onFailure { Logger.warn("Playback sessions: restore failed: ${it.message}", "tv") }
    scope.launch {
        while (true) {
            delay(SESSION_TICK_MS)
            runCatching { sessions.tick() }.onFailure { Logger.warn("Playback sessions: tick failed: ${it.message}", "tv") }
        }
    }
    return publisher
}

