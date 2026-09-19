package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.CastLoadData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * R265 (dev review item 4) — one [CastSender] for the shared remote UI ([dev.jellystructure.ravilo.ui.screens.CastRemoteScreen],
 * the mini bar, the connecting bar) to read, composed from [screen] (always present, commonMain) and
 * [chromecast] (the platform's own sender — Android only, null elsewhere). The rule this exists to hold:
 * **at most one is linked at a time**. [screen] wins the tie-break during the brief window right after
 * one side stops and the other starts (both transitions the same commit, see [CastController]).
 *
 * `setAppId`/`load` are Chromecast-only operations with no screen equivalent (a screen has no
 * per-installation app id, and starts a title through [ScreenSender.playItem] rather than a load) — they
 * route straight to [chromecast] and no-op where it's null. Every other call goes to whichever side
 * [link] currently reflects.
 */
class ActiveCastSender(
    private val chromecast: CastSender?,
    val screen: ScreenSender,
) : CastSender {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _link = MutableStateFlow(CastLinkState.NONE)
    override val link: StateFlow<CastLinkState> = _link.asStateFlow()
    private val _deviceName = MutableStateFlow<String?>(null)
    override val deviceName: StateFlow<String?> = _deviceName.asStateFlow()
    private val _status = MutableStateFlow<CastRemoteStatus?>(null)
    override val status: StateFlow<CastRemoteStatus?> = _status.asStateFlow()

    // Kept in lockstep with the flows above so play()/pause()/etc. (plain functions, not suspend) know
    // which side to call without re-deriving it from three flows on every tap.
    private var activeIsScreen = false

    init {
        val screenTriple = combine(screen.link, screen.deviceName, screen.status) { l, n, s -> Triple(l, n, s) }
        val castTriple = chromecast?.let { c -> combine(c.link, c.deviceName, c.status) { l, n, s -> Triple(l, n, s) } }
            ?: flowOf(Triple(CastLinkState.NONE, null, null))
        scope.launch {
            combine(screenTriple, castTriple) { s, c -> if (s.first != CastLinkState.NONE) s to true else c to false }
                .collect { (t, isScreen) ->
                    activeIsScreen = isScreen
                    _link.value = t.first
                    _deviceName.value = t.second
                    _status.value = t.third
                }
        }
    }

    private val active: CastSender get() = if (activeIsScreen || chromecast == null) screen else chromecast

    override fun setAppId(appId: String) { chromecast?.setAppId(appId) }
    override fun load(data: CastLoadData) { chromecast?.load(data) }
    override fun play() = active.play()
    override fun pause() = active.pause()
    override fun seekTo(positionMs: Long) = active.seekTo(positionMs)
    override fun stop() = active.stop()
    override fun selectSubtitle(trackId: Long?) = active.selectSubtitle(trackId)
    override fun selectAudio(trackId: Long?) = active.selectAudio(trackId)
    override fun send(json: String) = active.send(json)
}
