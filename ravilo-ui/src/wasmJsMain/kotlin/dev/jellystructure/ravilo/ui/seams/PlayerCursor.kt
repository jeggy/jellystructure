package dev.jellystructure.ravilo.ui.seams

import kotlinx.browser.document

actual fun setPointerCursorHidden(hidden: Boolean) {
    runCatching {
        document.body?.style?.cursor = if (hidden) "none" else "auto"
    }
}
