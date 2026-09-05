package dev.jellystructure.ravilo.ui

/** R234 — see [dev.jellystructure.ravilo.ui.RaviloAppContext.isTelevision]'s own doc for why this is a
 *  runtime hardware check, not a build-flavor one, since :ravilo-android is one universal app. */
actual val isTvPlatform: Boolean
    get() = RaviloAppContext.isTelevision
