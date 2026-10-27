package dev.jellystructure.autofetch

/**
 * DEB-2476 (DEB-2474, FR-4.1) — the `html` reader's page model. A small, forgiving HTML tree and the CSS selector subset a
 * recipe may use (tag, `#id`, `.class`, `[attr]`, `[attr=v]`, `^=` `$=` `*=`, `:first-child`, `:last-child`,
 * `:nth-child(n)`, descendant and child combinators, comma lists). Nothing in a recipe is executed (FR-4.1): the selector
 * is data, read by this fixed code. The parser never throws on bad markup, and is bounded in size and depth so a hostile
 * page cannot cost more than a bounded amount of memory (FR-5.3: page content is data).
 */
class HtmlNode(val tag: String, val attrs: Map<String, String>, val parent: HtmlNode?) {
    val children = ArrayList<HtmlNode>()

    /** Text and child elements in document order (a [String] or an [HtmlNode]). */
    internal val content = ArrayList<Any>()

    fun attr(name: String): String? = attrs[name.lowercase()]

    /** The visible text of this element: descendant text, `script` and `style` left out, whitespace collapsed. */
    fun text(): String {
        val sb = StringBuilder()
        collectText(this, sb)
        return collapseWhitespace(sb.toString())
    }

    private fun collectText(n: HtmlNode, sb: StringBuilder) {
        val stack = ArrayList<Any>()
        stack.add(n)
        // Iterative so a 256-deep page cannot overflow the stack.
        while (stack.isNotEmpty()) {
            when (val top = stack.removeAt(stack.size - 1)) {
                is String -> sb.append(top)
                is HtmlNode -> {
                    if (top.tag == "script" || top.tag == "style") continue
                    if (top.tag == "br") sb.append(' ')
                    for (i in top.content.indices.reversed()) stack.add(top.content[i])
                }
            }
        }
    }

    /** Every descendant element, document order. */
    fun descendants(): List<HtmlNode> {
        val out = ArrayList<HtmlNode>()
        val stack = ArrayList<HtmlNode>()
        for (i in children.indices.reversed()) stack.add(children[i])
        while (stack.isNotEmpty()) {
            val n = stack.removeAt(stack.size - 1)
            out.add(n)
            for (i in n.children.indices.reversed()) stack.add(n.children[i])
        }
        return out
    }

    /** Descendants of this element that match [selector], document order. A blank selector is the element itself. */
    fun select(selector: String): List<HtmlNode> {
        if (selector.isBlank()) return listOf(this)
        val sel = Selector.parse(selector) ?: return emptyList()
        return descendants().filter { sel.matches(it, this) }
    }

    fun selectFirst(selector: String): HtmlNode? = select(selector).firstOrNull()
}

private fun collapseWhitespace(s: String): String {
    val sb = StringBuilder(s.length)
    var space = false
    for (c in s) {
        if (c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == ' ' || c == '\u000c') space = true
        else {
            if (space && sb.isNotEmpty()) sb.append(' ')
            space = false
            sb.append(c)
        }
    }
    return sb.toString()
}

object Html {
    const val MAX_BYTES = 5_000_000
    private const val MAX_NODES = 200_000
    private const val MAX_DEPTH = 256
    private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")
    private val RAW_TEXT = setOf("script", "style", "textarea", "title")

    /** Tags an opening tag of the key implicitly closes when it is the innermost open element. */
    private val AUTO_CLOSE = mapOf(
        "li" to setOf("li"), "p" to setOf("p"), "dt" to setOf("dt", "dd"), "dd" to setOf("dt", "dd"),
        "tr" to setOf("tr", "td", "th"), "td" to setOf("td", "th"), "th" to setOf("td", "th"), "option" to setOf("option"),
    )

    /** The parsed document: a synthetic `#document` root. Never throws; a body over [MAX_BYTES] is cut there. */
    fun parse(source: String): HtmlNode {
        val html = if (source.length > MAX_BYTES) source.substring(0, MAX_BYTES) else source
        val root = HtmlNode("#document", emptyMap(), null)
        var cur = root
        var depth = 0
        var nodes = 0
        var i = 0
        val n = html.length
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) { cur.content.add(decodeEntities(text.toString())); text.setLength(0) }
        }
        while (i < n) {
            val c = html[i]
            if (c != '<') { text.append(c); i++; continue }
            if (html.startsWith("<!--", i)) {
                val end = html.indexOf("-->", i + 4)
                i = if (end < 0) n else end + 3
                continue
            }
            if (i + 1 < n && (html[i + 1] == '!' || html[i + 1] == '?')) {
                val end = html.indexOf('>', i + 1)
                i = if (end < 0) n else end + 1
                continue
            }
            if (i + 1 < n && html[i + 1] == '/') {
                val end = html.indexOf('>', i + 2)
                if (end < 0) { i = n; continue }
                val name = html.substring(i + 2, end).trim().lowercase()
                flush()
                // Pop to the nearest open element of that name; an end tag with no open match is ignored.
                var probe: HtmlNode? = cur
                while (probe != null && probe !== root && probe.tag != name) probe = probe.parent
                if (probe != null && probe !== root && probe.tag == name) {
                    while (cur !== probe) { cur = cur.parent!!; depth-- }
                    cur = cur.parent ?: root; depth--
                }
                i = end + 1
                continue
            }
            if (i + 1 >= n || !(html[i + 1].isLetter())) { text.append(c); i++; continue }
            // An opening tag.
            var j = i + 1
            while (j < n && !html[j].isWhitespace() && html[j] != '>' && html[j] != '/') j++
            val name = html.substring(i + 1, j).lowercase()
            val attrs = LinkedHashMap<String, String>()
            var selfClose = false
            while (j < n && html[j] != '>') {
                if (html[j].isWhitespace()) { j++; continue }
                if (html[j] == '/') { selfClose = true; j++; continue }
                val ns = j
                while (j < n && !html[j].isWhitespace() && html[j] != '=' && html[j] != '>' && html[j] != '/') j++
                val an = html.substring(ns, j).lowercase()
                while (j < n && html[j].isWhitespace()) j++
                var value = ""
                if (j < n && html[j] == '=') {
                    j++
                    while (j < n && html[j].isWhitespace()) j++
                    if (j < n && (html[j] == '"' || html[j] == '\'')) {
                        val q = html[j]
                        val e = html.indexOf(q, j + 1).let { if (it < 0) n else it }
                        value = html.substring(j + 1, e)
                        j = minOf(e + 1, n)
                    } else {
                        val vs = j
                        while (j < n && !html[j].isWhitespace() && html[j] != '>') j++
                        value = html.substring(vs, j)
                    }
                }
                if (an.isNotEmpty() && an !in attrs) attrs[an] = decodeEntities(value)
            }
            i = if (j < n) j + 1 else n
            flush()
            if (nodes >= MAX_NODES) continue
            AUTO_CLOSE[name]?.let { closes -> if (cur !== root && cur.tag in closes) { cur = cur.parent ?: root; depth-- } }
            val node = HtmlNode(name, attrs, cur)
            cur.children.add(node); cur.content.add(node); nodes++
            if (name in VOID || selfClose) continue
            if (name in RAW_TEXT) {
                val close = html.indexOf("</$name", i, ignoreCase = true).let { if (it < 0) n else it }
                val raw = html.substring(i, close)
                if (raw.isNotEmpty()) node.content.add(if (name == "script" || name == "style") raw else decodeEntities(raw))
                val gt = html.indexOf('>', close).let { if (it < 0) n else it + 1 }
                i = gt
                continue
            }
            if (depth < MAX_DEPTH) { cur = node; depth++ }
        }
        flush()
        return root
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "laquo" to "«", "raquo" to "»",
    )

    /** Named (the common few), decimal and hex character references; anything else is left as written. */
    fun decodeEntities(s: String): String {
        if (s.indexOf('&') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') { sb.append(c); i++; continue }
            val semi = s.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 10) { sb.append(c); i++; continue }
            val ref = s.substring(i + 1, semi)
            val rep: String? = when {
                ref.startsWith("#x") || ref.startsWith("#X") -> ref.substring(2).toIntOrNull(16)?.let(::codePointString)
                ref.startsWith("#") -> ref.substring(1).toIntOrNull()?.let(::codePointString)
                else -> NAMED[ref]
            }
            if (rep == null) { sb.append(c); i++ } else { sb.append(rep); i = semi + 1 }
        }
        return sb.toString()
    }

    private fun codePointString(cp: Int): String? = when {
        cp <= 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF -> null
        cp < 0x10000 -> cp.toChar().toString()
        else -> { val v = cp - 0x10000; "${(0xD800 + (v shr 10)).toChar()}${(0xDC00 + (v and 0x3FF)).toChar()}" }
    }
}

/** A parsed CSS selector list. [parse] returns null for anything outside the subset, so an unsupported selector matches nothing. */
class Selector private constructor(private val alternatives: List<List<Step>>) {
    private enum class Comb { DESCENDANT, CHILD }
    private class Cond(val kind: Char, val a: String, val b: String = "", val c: String = "")
    private class Step(val comb: Comb, val tag: String?, val conds: List<Cond>)

    /** True when [node] matches, with its ancestors considered only up to and including [scope]. */
    fun matches(node: HtmlNode, scope: HtmlNode): Boolean = alternatives.any { matchChain(it, it.size - 1, node, scope) }

    private fun matchChain(steps: List<Step>, idx: Int, node: HtmlNode, scope: HtmlNode): Boolean {
        val s = steps[idx]
        if (!matchStep(s, node)) return false
        if (idx == 0) return true
        if (node === scope) return false
        return when (s.comb) {
            Comb.CHILD -> node.parent?.let { it !== scope.parent && matchChain(steps, idx - 1, it, scope) } ?: false
            Comb.DESCENDANT -> {
                var p = node.parent
                while (p != null && p.tag != "#document") {
                    if (matchChain(steps, idx - 1, p, scope)) return true
                    if (p === scope) break
                    p = p.parent
                }
                false
            }
        }
    }

    private fun matchStep(s: Step, node: HtmlNode): Boolean {
        if (s.tag != null && s.tag != "*" && s.tag != node.tag) return false
        for (c in s.conds) {
            val ok = when (c.kind) {
                '#' -> node.attr("id") == c.a
                '.' -> node.attr("class")?.split(' ', '\t', '\n')?.contains(c.a) == true
                'e' -> node.attr(c.a) != null
                '=' -> node.attr(c.a) == c.c
                '^' -> node.attr(c.a)?.startsWith(c.c) == true && c.c.isNotEmpty()
                '$' -> node.attr(c.a)?.endsWith(c.c) == true && c.c.isNotEmpty()
                '*' -> node.attr(c.a)?.contains(c.c) == true && c.c.isNotEmpty()
                '~' -> node.attr(c.a)?.split(' ', '\t', '\n')?.contains(c.c) == true
                'f' -> node.parent?.children?.firstOrNull() === node
                'l' -> node.parent?.children?.lastOrNull() === node
                'n' -> node.parent?.children?.indexOf(node)?.plus(1) == c.a.toIntOrNull()
                else -> false
            }
            if (!ok) return false
        }
        return true
    }

    companion object {
        private const val MAX_LEN = 300

        fun parse(source: String): Selector? {
            if (source.isBlank() || source.length > MAX_LEN) return null
            val alts = ArrayList<List<Step>>()
            for (part in splitTop(source, ',')) {
                val steps = parseChain(part.trim()) ?: return null
                alts.add(steps)
            }
            return if (alts.isEmpty()) null else Selector(alts)
        }

        private fun splitTop(s: String, sep: Char): List<String> {
            val out = ArrayList<String>(); var depth = 0; var quote = '\u0000'; var start = 0
            for ((i, c) in s.withIndex()) {
                if (quote != '\u0000') { if (c == quote) quote = '\u0000'; continue }
                when (c) {
                    '"', '\'' -> quote = c
                    '[', '(' -> depth++
                    ']', ')' -> depth--
                    sep -> if (depth == 0) { out.add(s.substring(start, i)); start = i + 1 }
                }
            }
            out.add(s.substring(start))
            return out
        }

        private fun parseChain(s: String): List<Step>? {
            if (s.isEmpty()) return null
            val steps = ArrayList<Step>()
            var i = 0
            var comb = Comb.DESCENDANT
            while (i < s.length) {
                while (i < s.length && s[i] == ' ') i++
                if (i >= s.length) break
                if (s[i] == '>') { comb = Comb.CHILD; i++; continue }
                val (step, next) = parseCompound(s, i, comb) ?: return null
                steps.add(step); i = next; comb = Comb.DESCENDANT
                // A combinator after whitespace is read at the top of the loop.
            }
            return steps.takeIf { it.isNotEmpty() }
        }

        private fun isIdent(c: Char) = c.isLetterOrDigit() || c == '-' || c == '_'

        private fun parseCompound(s: String, from: Int, comb: Comb): Pair<Step, Int>? {
            var i = from
            var tag: String? = null
            val conds = ArrayList<Cond>()
            if (i < s.length && (s[i] == '*' || isIdent(s[i]))) {
                val st = i
                if (s[i] == '*') i++ else while (i < s.length && isIdent(s[i])) i++
                tag = s.substring(st, i).lowercase()
            }
            while (i < s.length && s[i] != ' ' && s[i] != '>') {
                when (s[i]) {
                    '#', '.' -> {
                        val k = s[i]; i++
                        val st = i
                        while (i < s.length && isIdent(s[i])) i++
                        if (i == st) return null
                        conds.add(Cond(k, s.substring(st, i)))
                    }
                    '[' -> {
                        val end = s.indexOf(']', i)
                        if (end < 0) return null
                        conds.add(parseAttr(s.substring(i + 1, end)) ?: return null)
                        i = end + 1
                    }
                    ':' -> {
                        i++
                        val st = i
                        while (i < s.length && (isIdent(s[i]))) i++
                        val name = s.substring(st, i)
                        when (name) {
                            "first-child" -> conds.add(Cond('f', ""))
                            "last-child" -> conds.add(Cond('l', ""))
                            "nth-child" -> {
                                if (i >= s.length || s[i] != '(') return null
                                val end = s.indexOf(')', i)
                                if (end < 0) return null
                                val num = s.substring(i + 1, end).trim().toIntOrNull() ?: return null
                                conds.add(Cond('n', num.toString())); i = end + 1
                            }
                            else -> return null
                        }
                    }
                    else -> return null
                }
            }
            if (tag == null && conds.isEmpty()) return null
            return Step(comb, tag, conds) to i
        }

        private fun parseAttr(body: String): Cond? {
            val m = Regex("""^\s*([A-Za-z_][\w:-]*)\s*(?:([~^$*]?=)\s*(?:"([^"]*)"|'([^']*)'|([^\s"']+)))?\s*$""").find(body) ?: return null
            val name = m.groupValues[1].lowercase()
            val op = m.groupValues[2]
            if (op.isEmpty()) return Cond('e', name)
            val value = m.groupValues[3].ifEmpty { m.groupValues[4] }.ifEmpty { m.groupValues[5] }
            val kind = when (op) { "=" -> '='; "^=" -> '^'; "$=" -> '$'; "*=" -> '*'; "~=" -> '~'; else -> return null }
            return Cond(kind, name, c = value)
        }
    }
}
