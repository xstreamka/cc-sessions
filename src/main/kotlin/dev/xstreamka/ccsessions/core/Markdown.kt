package dev.xstreamka.ccsessions.core

/**
 * Небольшой безопасный Markdown → HTML для ответов Claude:
 * заголовки, списки, цитаты, код (блоки и inline), жирный/курсив, ссылки, таблицы.
 * Всё экранируется; сырой HTML из текста не пропускается.
 */
object Markdown {

    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) when (c) {
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '&' -> sb.append("&amp;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&#39;")
            else -> sb.append(c)
        }
        return sb.toString()
    }

    private val fence = Regex("^\\s*(```+|~~~+)\\s*([\\w#+.-]*)\\s*$")
    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val ul = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val ol = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val tableSep = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val hr = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

    fun toHtml(src: String, linkFile: ((String) -> String?)? = null): String {
        val lines = src.replace("\r\n", "\n").split('\n')
        val out = StringBuilder()
        var i = 0
        val para = ArrayList<String>()
        fun flushPara() {
            if (para.isNotEmpty()) {
                out.append("<p>").append(para.joinToString("<br>") { inline(it, linkFile) }).append("</p>\n")
                para.clear()
            }
        }
        while (i < lines.size) {
            val line = lines[i]
            val fm = fence.find(line)
            if (fm != null) {
                flushPara()
                val marker = fm.groupValues[1]
                val lang = fm.groupValues[2]
                val code = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith(marker)) { code += lines[i]; i++ }
                i++
                out.append("<pre class=\"code\"")
                if (lang.isNotEmpty()) out.append(" data-lang=\"").append(esc(lang)).append('"')
                out.append("><code>").append(esc(code.joinToString("\n"))).append("</code></pre>\n")
                continue
            }
            if (line.isBlank()) { flushPara(); i++; continue }
            val hm = heading.find(line)
            if (hm != null) {
                flushPara()
                val lvl = (hm.groupValues[1].length + 1).coerceAtMost(6)
                out.append("<h$lvl>").append(inline(hm.groupValues[2], linkFile)).append("</h$lvl>\n")
                i++
                continue
            }
            if (hr.matches(line)) { flushPara(); out.append("<hr>\n"); i++; continue }
            if (line.trimStart().startsWith(">")) {
                flushPara()
                val q = ArrayList<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) { q += lines[i].trimStart().removePrefix(">").removePrefix(" "); i++ }
                out.append("<blockquote>").append(toHtml(q.joinToString("\n"), linkFile)).append("</blockquote>\n")
                continue
            }
            if (line.contains('|') && i + 1 < lines.size && tableSep.matches(lines[i + 1])) {
                flushPara()
                val header = cells(line)
                i += 2
                out.append("<table><thead><tr>")
                header.forEach { out.append("<th>").append(inline(it, linkFile)).append("</th>") }
                out.append("</tr></thead><tbody>")
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    out.append("<tr>")
                    cells(lines[i]).forEach { out.append("<td>").append(inline(it, linkFile)).append("</td>") }
                    out.append("</tr>")
                    i++
                }
                out.append("</tbody></table>\n")
                continue
            }
            if (ul.matches(line) || ol.matches(line)) {
                flushPara()
                i = list(lines, i, out, linkFile)
                continue
            }
            para += line
            i++
        }
        flushPara()
        return out.toString()
    }

    private fun list(lines: List<String>, start: Int, out: StringBuilder, linkFile: ((String) -> String?)?): Int {
        var i = start
        val ordered = ol.matches(lines[i])
        val indent = (if (ordered) ol.find(lines[i])!!.groupValues[1] else ul.find(lines[i])!!.groupValues[1]).length
        out.append(if (ordered) "<ol>" else "<ul>")
        while (i < lines.size) {
            val l = lines[i]
            val m = (if (ordered) ol.find(l) else ul.find(l))
            val ind = m?.groupValues?.get(1)?.length ?: -1
            if (m == null || ind < indent) {
                // продолжение пункта (отступ) или вложенный список
                if (l.isNotBlank() && l.takeWhile { it == ' ' }.length > indent) {
                    if (ul.matches(l) || ol.matches(l)) { i = list(lines, i, out, linkFile); continue }
                    out.append("<br>").append(inline(l.trim(), linkFile)); i++; continue
                }
                break
            }
            if (ind > indent) { i = list(lines, i, out, linkFile); continue }
            val text = if (ordered) m.groupValues[3] else m.groupValues[2]
            val task = Regex("^\\[([ xX])]\\s+(.*)$").find(text)
            out.append("<li>")
            if (task != null) out.append(if (task.groupValues[1].isBlank()) "☐ " else "☑ ").append(inline(task.groupValues[2], linkFile))
            else out.append(inline(text, linkFile))
            out.append("</li>")
            i++
        }
        out.append(if (ordered) "</ol>\n" else "</ul>\n")
        return i
    }

    private fun cells(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|")) t = t.substring(0, t.length - 1)
        return t.split('|').map { it.trim() }
    }

    private val codeSpan = Regex("`([^`]+)`")
    private val bold = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__")
    private val italic = Regex("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])|(?<![_\\w])_(?!\\s)(.+?)(?<!\\s)_(?![_\\w])")
    private val strike = Regex("~~(.+?)~~")
    private val link = Regex("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)")
    private val bareUrl = Regex("(?<![\"'=])\\bhttps?://[^\\s<]+[^\\s<.,;:!?)\\]]")

    /** Inline-разметка: код вырезаем первым, чтобы внутри него ничего не форматировалось. */
    fun inline(text: String, linkFile: ((String) -> String?)? = null): String {
        val codes = ArrayList<String>()
        var s = codeSpan.replace(text) { m ->
            codes += m.groupValues[1]
            "\u0000${codes.size - 1}\u0000"
        }
        s = esc(s)
        // Готовые ссылки прячем за метками, чтобы проход по «голым» адресам не заходил внутрь <a> (вложенные ссылки)
        val anchors = ArrayList<String>()
        fun hide(html: String): String {
            anchors += html
            return "\u0001${anchors.size - 1}\u0001"
        }
        s = link.replace(s) { m -> hide("<a href=\"${m.groupValues[2]}\">${m.groupValues[1]}</a>") }
        s = bareUrl.replace(s) { m -> hide("<a href=\"${m.value}\">${m.value}</a>") }
        s = bold.replace(s) { m -> "<b>" + (m.groups[1]?.value ?: m.groups[2]?.value ?: "") + "</b>" }
        s = italic.replace(s) { m -> "<i>" + (m.groups[1]?.value ?: m.groups[2]?.value ?: "") + "</i>" }
        s = strike.replace(s) { m -> "<s>" + m.groupValues[1] + "</s>" }
        s = Regex("\u0001(\\d+)\u0001").replace(s) { m -> Regex.escapeReplacement(anchors[m.groupValues[1].toInt()]) }
        return Regex("\u0000(\\d+)\u0000").replace(s) { m ->
            val code = codes[m.groupValues[1].toInt()]
            val href = linkFile?.invoke(code)
            if (href != null) "<code class=\"file\" $href>${esc(code)}</code>" else "<code>${esc(code)}</code>"
        }
    }
}
