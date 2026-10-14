package dev.jellystructure.ui

/*
 * Phase 278 FR-278-16 (correction 2026-10-14, DEB-1113) — the pure string helpers that build a cover box, and
 * the HTML escape every page in the admin frontend builds its markup with.
 *
 * They live in `commonMain` rather than in `wasmJsMain` for one reason: the admin frontend has no test target
 * of its own, and these are the functions whose *output* is the requirement. Moving them here puts that output
 * under `linuxX64Test`. Nothing here may touch the DOM or `kotlinx.browser` — the moment it does it has to go
 * back to `wasmJsMain` and loses its tests. The package and the `internal` visibility are unchanged from where
 * these came from (`MusicUi.kt`, `Library.kt`), so every call site compiles untouched.
 */

internal fun String.esc(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** The mockup's string hash (`h * 31 + c`, unsigned), so a title keeps its colour everywhere. */
private fun muHash(s: String): Long {
    var h = 0L
    for (c in s) h = (h * 31 + c.code) and 0xFFFFFFFFL
    return h
}

internal fun muWordmarkStyle(name: String): String {
    val h = (muHash(name) % 360).toInt()
    return "background:linear-gradient(150deg, hsl($h 46% 36%), hsl(${(h + 40) % 360} 52% 14%))"
}

/**
 * A cover painted as a background. Still the idiom for the artwork tiles and the album page's lightbox — but
 * no longer for the cells, because a background image cannot be lazy (FR-278-16's correction).
 */
internal fun muImageStyle(url: String): String = "background:#0c0e14 center/cover no-repeat url('${url.esc()}')"

/**
 * The `<img>` inside a `.mu-cov`, filling the box the background used to paint. `object-fit:cover` is
 * `center/cover`; `inset:0` resolves against `.mu-cov`'s padding box, which is the box
 * `background-origin:padding-box` covered, so the picture lands exactly where it did. Out of flow, so
 * `.mu-cov`'s flex layout and the wordmark branch are unaffected, and the chip — which follows this image in
 * document order and is also absolutely positioned — still paints over it.
 *
 * `loading="lazy"` is the requirement. `alt` is empty on purpose: the cover is decorative, the title is its
 * sibling `.ttl`, and an empty `alt` is also what keeps a 404 from drawing a broken-image glyph over the
 * backdrop.
 */
internal fun muCoverImgHtml(url: String): String =
    """<img class="mu-covim" loading="lazy" decoding="async" alt="" src="${url.esc()}" style="position:absolute;inset:0;width:100%;height:100%;object-fit:cover">"""

/** An album cover or its wordmark (FR-277-3). [extraClass] joins `mu-cov`; [extraStyle] is appended to the style. */
internal fun muCoverHtml(title: String, url: String?, extraClass: String = "", chip: String = "", extraStyle: String = ""): String =
    if (url != null) """<div class="mu-cov $extraClass" style="background:#0c0e14$extraStyle">${muCoverImgHtml(url)}$chip</div>"""
    else """<div class="mu-cov $extraClass" style="${muWordmarkStyle(title)}$extraStyle"><span class="mu-wm">${title.esc()}</span><i class="mu-nocov" title="No cover on disk — a task on this side"></i>$chip</div>"""
