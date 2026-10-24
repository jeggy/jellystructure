package dev.jellystructure.tv

import dev.jellystructure.tv.SingleSignOnService.PageKind

/**
 * DEB-2233 (FR-10, FR-15) — the pages jellystructure renders when the identity provider returns, drawn by the design round DEB-2319
 * (surface 4). Each is ONE self-contained document: inline CSS, the mark as inline SVG, a system font stack (no hosted font, no second
 * file, no script that calls out). The plugin's own page is never forwarded; these are all a viewer ever sees.
 *
 * A viewer's page names neither Jellyfin, the directory, Quick Connect, the plugin nor a status code, in any language (FR-15), and
 * `SingleSignOnPagesTest` pins that over every page in every language. The admin's page is the operator's and may name Jellyfin.
 */
object SingleSignOnPages {
    val LANGUAGES = listOf("en", "da", "fo")

    /** The viewer's language from `Accept-Language`: the first of en/da/fo in q order, else English. */
    fun languageFor(acceptLanguage: String?): String {
        val ranked = acceptLanguage.orEmpty().split(',').mapIndexedNotNull { i, part ->
            val tag = part.substringBefore(';').trim().lowercase().substringBefore('-').takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val q = part.substringAfter("q=", "").substringBefore(';').trim().toDoubleOrNull() ?: 1.0
            Triple(tag, q, i)
        }.filter { it.second > 0.0 }.sortedWith(compareBy({ -it.second }, { it.third }))
        return ranked.map { it.first }.firstOrNull { it in LANGUAGES } ?: "en"
    }

    private class Words(val en: String, val da: String, val fo: String) { fun get(lang: String) = when (lang) { "da" -> da; "fo" -> fo; else -> en } }

    // Copy from the round's ts-data.js (`ssopage.*`). `ok_body` and the three error bodies are the round's proposals.
    private val TITLE = mapOf(
        PageKind.SIGNED_IN to Words("You’re signed in — go back to Ravilo", "Du er logget ind — gå tilbage til Ravilo", "Tú ert innritað/ur — far aftur til Ravilo"),
        PageKind.NOT_ALLOWED to Words("This account can’t use Ravilo — ask the person who runs it", "Denne konto kan ikke bruge Ravilo — spørg den, der står for den", "Hesin kontoin kann ikki brúka Ravilo — spyr tann, sum stýrir honum"),
        PageKind.TOO_LONG to Words("That took too long — try again", "Det tog for lang tid — prøv igen", "Tað tók ov langa tíð — royn aftur"),
        PageKind.CANCELLED to Words("You didn’t finish signing in — try again when you’re ready", "Du afsluttede ikke login — prøv igen, når du er klar", "Tú endaðir ikki innritanina — royn aftur, tá tú ert klár/ur"),
        PageKind.UNAVAILABLE to Words("Signing in isn’t available right now — try again in a few minutes", "Login er ikke muligt lige nu — prøv igen om et par minutter", "Tað ber ikki til at rita inn nú — royn aftur um eitt par minuttir"),
    )
    private val TAKING_BACK = Words("Taking you back…", "Fører dig tilbage…", "Fer aftur við tær…")
    private val OPEN_RAVILO = Words("Open Ravilo", "Åbn Ravilo", "Lat Ravilo upp")

    /** The admin's wording (FR-10): English, the operator's page, and free to name Jellyfin. */
    private val ADMIN_TITLE = mapOf(
        PageKind.NOT_ALLOWED to "Jellystructure requires a Jellyfin administrator account",
        PageKind.TOO_LONG to "That took too long — start again from the sign-in page",
        PageKind.CANCELLED to "Sign-in was cancelled — start again from the sign-in page",
        PageKind.UNAVAILABLE to "Single sign-on can’t be reached — check that Jellyfin and its single sign-on plugin are running",
        PageKind.ADMIN_REFUSED to "This sign-in was not started in this browser — start again from the sign-in page",
        PageKind.SIGNED_IN to "Signed in",
    )

    /** The title a page carries, for tests and for the document's `<title>`. */
    fun title(kind: PageKind, lang: String, admin: Boolean): String =
        if (admin) ADMIN_TITLE.getValue(kind) else TITLE.getValue(if (kind == PageKind.ADMIN_REFUSED) PageKind.TOO_LONG else kind).get(lang)

    /** The HTTP status the page is served with. Nothing on the page says it. */
    fun statusFor(kind: PageKind): Int = when (kind) {
        PageKind.SIGNED_IN, PageKind.CANCELLED -> 200
        PageKind.NOT_ALLOWED, PageKind.ADMIN_REFUSED -> 403
        PageKind.TOO_LONG -> 400
        PageKind.UNAVAILABLE -> 503
    }

    /**
     * One page. [handBack] (a signed-in page only) is where the browser is sent by itself after a moment — and, for the phone's private
     * scheme, which a browser may refuse to open without a tap, offered as *Open Ravilo* too. [open] is an error page's way back into the
     * client; a page with none shows no button.
     */
    fun render(kind: PageKind, lang: String, admin: Boolean, handBack: String? = null, open: String? = null): String {
        val tone = when (kind) { PageKind.SIGNED_IN -> "ok"; PageKind.NOT_ALLOWED, PageKind.ADMIN_REFUSED -> "bad"; else -> "warn" }
        val title = esc(title(kind, lang, admin))
        val body = StringBuilder()
        body.append("""<main class="in"><div class="head">$MARK<b>${if (admin) "Jellystructure" else "Ravilo"}</b></div>""")
        body.append("""<div class="glyph">${glyph(tone)}</div><h1>$title</h1>""")
        if (kind == PageKind.SIGNED_IN && handBack != null) {
            body.append("""<p>${esc(TAKING_BACK.get(lang))}</p><div class="hand"><i></i><i></i><i></i></div>""")
            if (handBack.startsWith(SingleSignOnService.APP_SCHEME_TARGET)) body.append("""<a class="btn" href="${esc(handBack)}">${esc(OPEN_RAVILO.get(lang))}</a>""")
        } else if (admin) {
            body.append("""<a class="btn" href="/">Back to sign in</a>""")
        } else if (kind != PageKind.SIGNED_IN && kind != PageKind.NOT_ALLOWED && open != null) {
            body.append("""<a class="btn" href="${esc(open)}">${esc(OPEN_RAVILO.get(lang))}</a>""")
        }
        body.append("</main>")
        val refresh = if (kind == PageKind.SIGNED_IN && handBack != null) """<meta http-equiv="refresh" content="1;url=${esc(handBack)}">""" else ""
        return """<!doctype html>
<html lang="$lang"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer"><meta name="robots" content="noindex">$refresh
<title>$title</title><style>$CSS</style></head><body>$body</body></html>"""
    }

    private fun esc(s: String) = buildString(s.length + 8) {
        for (c in s) when (c) { '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&#39;"); else -> append(c) }
    }

    private fun glyph(tone: String): String = when (tone) {
        "ok" -> """<svg viewBox="0 0 24 24" width="58" height="58" fill="none" stroke="#2dd49a" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 12.5l5 5L20 6.5"/></svg>"""
        "bad" -> """<svg viewBox="0 0 24 24" width="58" height="58" fill="none" stroke="#f5667a" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M9 9l6 6M15 9l-6 6"/></svg>"""
        else -> """<svg viewBox="0 0 24 24" width="58" height="58" fill="none" stroke="#f5b542" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3.5l9.5 16.5h-19z"/><path d="M12 10v4.5M12 17.4v.1"/></svg>"""
    }

    /** The jellyfish mark, as the round draws it (`ts-kit.js` `mark`). */
    private const val MARK = """<svg width="44" height="44" viewBox="12 20 76 76" aria-hidden="true"><defs><linearGradient id="m" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#7b6ef0"/><stop offset="1" stop-color="#3fb6f5"/></linearGradient></defs><path d="M22 52 C22 24 78 24 78 52 C66 45 59 45 50 49 C41 45 34 45 22 52 Z" fill="url(#m)"/><g stroke="url(#m)" stroke-width="4.5" stroke-linecap="round" fill="none"><path d="M33 51 q-5 12 1 20 q5 8 0 14" opacity=".9"/><path d="M44 52 q-4 13 1 21 q4 9 0 13" opacity=".72"/><path d="M56 52 q4 13 -1 21 q-4 9 0 13" opacity=".72"/><path d="M67 51 q5 12 -1 20 q-5 8 0 14" opacity=".9"/></g></svg>"""

    /** The round's `.ts-page` block, written out: colours and a system stack, because one file cannot reach for a token file or a hosted face. */
    private const val CSS = """*{box-sizing:border-box}html,body{margin:0;min-height:100%}body{min-height:100vh;display:flex;align-items:center;justify-content:center;padding:40px 24px;background:#0a0c13;color:#f3f4fb;font-family:'Sora',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;background-image:radial-gradient(60% 46% at 20% 4%,rgba(123,110,240,.20),transparent 68%),radial-gradient(52% 44% at 86% 98%,rgba(63,182,245,.13),transparent 70%)}.in{width:100%;max-width:420px;text-align:center}.head{display:inline-flex;align-items:center;gap:12px;margin-bottom:40px}.head b{font-family:'Space Grotesk',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;font-weight:700;font-size:30px;letter-spacing:-.02em}.glyph{margin:0 auto 26px;display:flex;justify-content:center}h1{font-family:'Space Grotesk',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;font-weight:700;letter-spacing:-.025em;font-size:27px;line-height:1.25;margin:0 0 14px;text-wrap:balance}p{font-size:15px;line-height:1.6;color:#aeb4cb;margin:0 0 8px}.btn{display:inline-flex;align-items:center;justify-content:center;margin-top:24px;min-height:50px;padding:0 26px;border-radius:14px;background:linear-gradient(120deg,#7b6ef0,#3fb6f5);color:#fff;font-weight:600;font-size:15.5px;text-decoration:none}.hand{display:flex;gap:6px;justify-content:center;margin-top:30px}.hand i{width:34px;height:3px;border-radius:2px;background:rgba(255,255,255,.5)}.hand i:nth-child(2){background:rgba(255,255,255,.26)}.hand i:nth-child(3){background:rgba(255,255,255,.12)}@media(min-width:900px){.in{max-width:560px}h1{font-size:34px}.head b{font-size:36px}}"""
}
