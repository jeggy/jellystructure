package dev.jellystructure.ravilo.ui.seams

import androidx.compose.ui.graphics.Color
import kotlinx.browser.document
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLStyleElement
import org.w3c.dom.events.Event

actual fun platformServerMessageOverVideo(): ServerMessageOverVideo? = DomServerMessages

/**
 * R354 (FR-R354-10b) — the dashboard's message as a DOM card while the `<video>` covers the Compose canvas. The Compose
 * toast is drawn in the canvas, i.e. UNDER the video, so on the web it only showed once the player closed. This layer
 * sits above the video (z 2) and R169's DOM transport (z 3–4), below the fullscreen button (z 100); the app goes full
 * screen on the whole document, so it comes along. It is the Compose card again in CSS: the accent stripe, the gradient
 * glyph tile, the header bold on its own line, the countdown bar. Only the cards take clicks (a click dismisses);
 * nothing in this layer is focusable, so keys keep going to the canvas.
 */
private object DomServerMessages : ServerMessageOverVideo {
    private var layer: HTMLDivElement? = null
    private var column: HTMLDivElement? = null
    private val shown = LinkedHashMap<Long, HTMLDivElement>()
    private var lastPhone: Boolean? = null

    private fun ensureLayer(): HTMLDivElement {
        layer?.let { return it }
        if (document.getElementById("rv-msg-style") == null) {
            val st = document.createElement("style") as HTMLStyleElement
            st.id = "rv-msg-style"
            st.textContent = """
                @keyframes rv-msg-in { from { opacity: 0; transform: translateX(36px); } to { opacity: 1; transform: none; } }
                @keyframes rv-msg-in-phone { from { opacity: 0; transform: translateY(-24px); } to { opacity: 1; transform: none; } }
                @keyframes rv-msg-out { from { opacity: 1; } to { opacity: 0; } }
                @keyframes rv-msg-bar { from { transform: scaleX(var(--rv-from, 0)); } to { transform: scaleX(1); } }
            """.trimIndent()
            document.head?.appendChild(st)
        }
        val l = (document.createElement("div") as HTMLDivElement).also {
            it.id = "rv-msg-layer"
            it.style.cssText = "position:fixed;inset:0;z-index:10;pointer-events:none;display:none"
            document.body?.appendChild(it)
        }
        val c = (document.createElement("div") as HTMLDivElement).also { l.appendChild(it) }
        layer = l
        column = c
        return l
    }

    override fun show(cards: List<ServerMessageCard>, style: ServerMessageCardStyle, onDismiss: (Long) -> Unit) {
        val l = ensureLayer()
        val col = column ?: return
        if (lastPhone != style.phone) {
            lastPhone = style.phone
            col.style.cssText = if (style.phone) {
                "position:absolute;top:calc(env(safe-area-inset-top, 0px) + 8px);left:16px;right:16px;margin:0 auto;" +
                    "max-width:460px;display:flex;flex-direction:column;gap:8px"
            } else {
                "position:absolute;top:100px;right:40px;max-width:480px;display:flex;flex-direction:column;" +
                    "align-items:flex-end;gap:14px"
            }
        }
        val ids = cards.map { it.id }.toSet()
        shown.keys.filter { it !in ids }.forEach { id -> shown.remove(id)?.let { runCatching { col.removeChild(it) } } }
        // Newest first, nearest the anchor (FR-R152-2), as the Compose toast stacks. A card already shown is never moved:
        // moving a node restarts its CSS animations (its countdown would start over).
        for (card in cards) {
            if (card.id in shown) continue
            val el = buildCard(card, style, onDismiss)
            shown[card.id] = el
            col.insertBefore(el, col.firstChild)
        }
        l.style.display = if (cards.isEmpty()) "none" else "block"
    }

    override fun hide() {
        val col = column ?: return
        shown.values.forEach { runCatching { col.removeChild(it) } }
        shown.clear()
        layer?.style?.display = "none"
    }

    private fun buildCard(card: ServerMessageCard, s: ServerMessageCardStyle, onDismiss: (Long) -> Unit): HTMLDivElement {
        val remaining = (card.durationMs - card.elapsedMs).coerceAtLeast(0L)
        val from = if (card.durationMs > 0) (card.elapsedMs.toDouble() / card.durationMs).coerceIn(0.0, 1.0) else 1.0
        val desk = s.desk
        val phone = s.phone
        val font = if (desk) 13 else if (phone) 15 else 19
        val line = if (desk) 18 else if (phone) 20 else 26
        val tile = if (desk) 28 else if (phone) 34 else 40
        val glyph = if (desk) 16 else if (phone) 20 else 24
        val small = desk || phone
        val pad = when {
            desk -> "11px 14px 13px 15px"
            phone -> "12px 14px 14px 16px"
            else -> "16px 19px 18px 19px"
        }
        val width = when {
            phone -> "width:100%"
            desk -> "min-width:240px;max-width:360px"
            else -> "min-width:300px;max-width:460px"
        }
        val gradient = "linear-gradient(135deg, ${css(s.accent)}, ${css(s.accentEnd)})"
        val enter = if (phone) "rv-msg-in-phone" else "rv-msg-in"
        val el = (document.createElement("div") as HTMLDivElement)
        el.className = "rv-msg"
        el.style.cssText = "position:relative;overflow:hidden;box-sizing:border-box;$width;border-radius:15px;" +
            "background:${css(s.surface.copy(alpha = 0.93f))};border:1px solid ${css(s.textDim.copy(alpha = 0.25f))};" +
            "padding:$pad;display:flex;align-items:center;gap:${if (small) 12 else 15}px;pointer-events:auto;cursor:pointer;" +
            "font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;" +
            "animation:$enter 250ms ease-out, rv-msg-out 250ms ease-in ${remaining}ms forwards"
        el.addEventListener("click", { _: Event -> onDismiss(card.id) })

        // design: `border-left: 4px solid var(--accent)` — painted over the card's own height.
        el.appendChild(div("position:absolute;left:0;top:0;bottom:0;width:4px;background:${css(s.accent)}"))
        // design: `.rv-msg-bar` — 3px at the bottom, running over the time the message is shown.
        el.appendChild(div(
            "position:absolute;left:0;bottom:0;height:3px;width:100%;opacity:.9;background:$gradient;transform-origin:left;" +
                "--rv-from:$from;transform:scaleX($from);animation:rv-msg-bar ${remaining}ms linear forwards",
        ))
        val tileEl = div(
            "flex:none;width:${tile}px;height:${tile}px;border-radius:${if (small) 9 else 12}px;background:$gradient;" +
                "display:flex;align-items:center;justify-content:center",
        )
        // EnvelopeGlyph (Glyphs.kt), the same geometry on a 100-unit box; a static string, never the sender's text.
        tileEl.innerHTML = "<svg width=\"$glyph\" height=\"$glyph\" viewBox=\"0 0 100 100\" fill=\"none\" " +
            "stroke=\"currentColor\" stroke-width=\"8\" stroke-linejoin=\"round\" stroke-linecap=\"round\">" +
            "<rect x=\"12\" y=\"24\" width=\"76\" height=\"52\"/><path d=\"M14 27 L50 55 L86 27\"/></svg>"
        tileEl.style.color = "rgb(255,255,255)"
        el.appendChild(tileEl)

        // R354 (FR-R354-9b) — the header on its own line, bold, above the text; textContent, never markup.
        val texts = div("display:flex;flex-direction:column;gap:2px;min-width:0")
        card.header?.let { h ->
            texts.appendChild(div("font-weight:700;font-size:${font}px;line-height:${line}px;color:${css(s.text)}").also { it.textContent = h })
        }
        texts.appendChild(div(
            "font-size:${font}px;line-height:${line}px;color:${css(if (card.header != null) s.textSecondary else s.text)};" +
                "overflow-wrap:anywhere",
        ).also { it.textContent = card.text })
        el.appendChild(texts)
        return el
    }

    private fun div(cssText: String): HTMLDivElement =
        (document.createElement("div") as HTMLDivElement).also { it.style.cssText = cssText }

    private fun css(c: Color): String {
        fun ch(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
        val a = (c.alpha * 1000f).toInt() / 1000.0
        return "rgba(${ch(c.red)},${ch(c.green)},${ch(c.blue)},$a)"
    }
}
