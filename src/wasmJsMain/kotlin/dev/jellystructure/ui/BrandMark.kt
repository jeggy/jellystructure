package dev.jellystructure.ui

/**
 * Phase 291 (dev review 1) — the admin's mark, the quartet-and-play glyph on its gradient tile, as one drawing for the
 * sidebar, the top bar and the login screen. The glyph sits at `translate(13 13) scale(.74)`: 59 % of the tile
 * (FR-291-1). `favicon.svg` carries the same transform; its rasters come from `scripts/render-brand-icons.sh admin`.
 *
 * [gradientId] must be unique in the page, since the sidebar and the top bar are in the DOM together.
 * [attrs] are the `<svg>` element's own attributes (a `class` or a `style`); the radius stays each caller's 30 (dev
 * review 2).
 */
internal fun brandMarkSvg(gradientId: String, attrs: String): String =
    """<svg $attrs viewBox="0 0 100 100" aria-hidden="true"><defs><linearGradient id="$gradientId" x1="0" y1="0" x2="1" y2="1">""" +
        """<stop offset="0" stop-color="#b15cd0"/><stop offset=".52" stop-color="#7b6ef0"/><stop offset="1" stop-color="#00a4dc"/></linearGradient></defs>""" +
        """<rect width="100" height="100" rx="30" fill="url(#$gradientId)"/>""" +
        """<g transform="translate(13 13) scale(.74)" fill="#fff"><rect x="10" y="10" width="35" height="35" rx="9"/>""" +
        """<rect x="55" y="10" width="35" height="35" rx="9" opacity=".5"/><rect x="10" y="55" width="35" height="35" rx="9" opacity=".5"/>""" +
        """<rect x="55" y="55" width="35" height="35" rx="9" fill="none" stroke="#fff" stroke-width="6"/>""" +
        """<path d="M68 64 L84 72.5 L68 81 Z"/></g></svg>"""

/** The Ravilo jellyfish brand mark (gradient + body + tentacles), matching design/app/ravilo-config.html. */
internal fun raviloMarkSvg(): String =
    """<svg viewBox="12 20 76 76" aria-hidden="true" style="width:1.12em;height:1.12em;flex:none;filter:drop-shadow(0 0 8px rgba(123,110,240,.5))"><defs><linearGradient id="ravJelly" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#7b6ef0"/><stop offset="1" stop-color="#3fb6f5"/></linearGradient></defs><path d="M22 52 C22 24 78 24 78 52 C66 45 59 45 50 49 C41 45 34 45 22 52 Z" fill="url(#ravJelly)"/><g stroke="url(#ravJelly)" stroke-width="4.5" stroke-linecap="round" fill="none"><path d="M33 51 q-5 12 1 20 q5 8 0 14" opacity=".9"/><path d="M44 52 q-4 13 1 21 q4 9 0 13" opacity=".72"/><path d="M56 52 q4 13 -1 21 q-4 9 0 13" opacity=".72"/><path d="M67 51 q5 12 -1 20 q-5 8 0 14" opacity=".9"/></g></svg>"""

/** The one Ravilo page bar: mark + `title` in the h1, one `badge info`, a spacer, then the page's own [controls]. Always sticky. */
internal fun raviloPageBar(title: String, badge: String, controls: String = ""): String =
    """<div class="pagebar" style="position:sticky;top:0;z-index:50;background:var(--bg);border-bottom:1px solid var(--line);margin-bottom:18px">""" +
        """<h1 style="display:flex;align-items:center;gap:.4em">${raviloMarkSvg()} $title</h1>""" +
        """<span class="badge info" style="margin-left:2px">$badge</span><span class="spacer"></span>$controls</div>"""
