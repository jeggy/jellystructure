package dev.jellystructure.ui

import org.w3c.dom.Element

// Phase 148 adds the "Live TV" nav slot ahead of Phase 147's actual admin config page — this
// placeholder holds the route until 147 lands the real Live TV lineup/EPG editor.
fun renderLiveTvPlaceholder(container: Element) {
    container.innerHTML = """
        <div class="pagebar"><h1>Live TV</h1></div>
        <p class="page-sub">Live TV channel &amp; guide configuration lands with Phase 147 — not built yet.</p>
    """.trimIndent()
}
