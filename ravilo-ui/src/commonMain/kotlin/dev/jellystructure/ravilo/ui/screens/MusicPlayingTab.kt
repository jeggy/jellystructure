package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.jellystructure.ravilo.ui.LocalPortrait
import dev.jellystructure.ravilo.ui.components.AppBar
import dev.jellystructure.ravilo.ui.components.CastController
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.isDesktopPlatform
import dev.jellystructure.ravilo.ui.music.EmptyLine
import dev.jellystructure.ravilo.ui.music.MusicPlayback
import dev.jellystructure.ravilo.ui.music.RadioPlayingScreen
import dev.jellystructure.ravilo.ui.music.playingGround
import dev.jellystructure.ravilo.ui.remote.RemoteExtras
import dev.jellystructure.ravilo.ui.remote.rememberLocalMusicRemote
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.isDesktopLayout

/**
 * DEB-2185 (FR-4) — the music mode's *Playing* tab is the one remote, inside the tab, with the bottom nav under it
 * (`one-remote-a-music-here.png`). It replaces `MusicPlayingScreen`, `BookPlayingScreen` and the desktop's `DeskPlaying`:
 * this device's own song or book, the same queue cast to a speaker, and the last song (FR-R322-3: loaded paused) are all
 * [rememberLocalMusicRemote]'s, and a station keeps its own page (DEB-2659, FR-10). With nothing loaded there is nothing to
 * draw but *Nothing played yet*.
 *
 * [onClose] is the landscape phone's way out: landscape is full-screen, not a tab (FR-R322-6), so its header keeps the back.
 */
@Composable
fun MusicPlayingTab(cast: CastController?, extras: RemoteExtras, onClose: () -> Unit) {
    val st by MusicPlayback.state.collectAsState()
    if (st.radio != null) { RadioPlayingScreen(st, onClose); return }
    val remote = rememberLocalMusicRemote(cast, extras)
    val inTab = isDesktopLayout || LocalPortrait.current
    if (remote == null) {
        Box(Modifier.fillMaxSize().then(if (isDesktopLayout) Modifier.background(RaviloTheme.colors.background) else Modifier.background(playingGround()))) {
            EmptyLine(str("music.nothing_played"))
            if (isDesktopLayout) AppBar()
        }
        return
    }
    Box(Modifier.fillMaxSize()) {
        OneRemoteScreen(remote = remote, onBack = onClose, onPlayAgain = {}, onPlayHere = { _, _, _ -> }, desktop = isDesktopPlatform, embedded = inTab)
        // R337 — on a computer the app bar sits over the page, as it did over the old Playing page.
        if (isDesktopLayout) AppBar()
    }
}
