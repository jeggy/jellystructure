package dev.jellystructure.ravilo.tizen

import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/**
 * R189 — minimal detail view: title/synopsis + a Play button. Milestone 1 scope only (see phase spec):
 * no cast, no season/episode picker for series. `card.id` is passed straight to `startPlayback` for
 * both kinds — correct for a movie; for a series this plays whatever `/api/tv/playback/start`
 * resolves the series id to server-side (its own "resume/next episode" logic), which is a reasonable
 * Milestone-1 stand-in for a real episode picker, not a guaranteed-correct general solution.
 */
class DetailScreen(private val card: MediaCard) : Screen {
    private var focusIndex = 0
    private lateinit var playButton: HTMLElement

    override fun mount(container: HTMLElement) {
        container.child("div", "detail-screen") {
            child("img", "backdrop") { setAttribute("src", card.backdropUrl ?: card.posterUrl ?: "") }
            child("h1", "title", card.title)
            val meta = listOfNotNull(card.year?.toString(), card.genre, if (card.kind == MediaKind.SERIES) "Series" else "Movie")
            child("p", "meta", meta.joinToString(" · "))
            playButton = child("div", "button", "Play")
        }
        playButton.classList.add("focused")
    }

    override fun onKey(ev: KeyboardEvent): Boolean {
        if (ev.key == "Enter") {
            startPlayback()
            return true
        }
        return false
    }

    private fun startPlayback() {
        app.scope.launch {
            runCatching {
                app.api.startPlayback(card.id, defaultCapabilities())
            }.onSuccess { ticket ->
                app.show(PlayerScreen(card, ticket))
            }.onFailure {
                // Milestone 1: no toast system yet — surface via the title bar so a failed play attempt
                // isn't silently swallowed.
                playButton.textContent = "Playback failed — try again"
            }
        }
    }
}
