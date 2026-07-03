package dev.jellystructure.ravilo.ui.seams

/** R169 — Android/TV: no-op. The video is composed in-scene (TextureView) and Compose's own chrome
 *  already draws over it correctly; there is nothing for this bridge to do. */
actual object PlayerChromeBridge {
    actual fun show(state: PlayerChromeState, actions: PlayerChromeActions) {}
    actual fun hide() {}
}
