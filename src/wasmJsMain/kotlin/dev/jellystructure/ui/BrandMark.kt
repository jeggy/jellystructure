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
