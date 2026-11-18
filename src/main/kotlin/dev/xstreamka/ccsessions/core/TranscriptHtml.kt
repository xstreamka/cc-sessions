package dev.xstreamka.ccsessions.core

import com.google.gson.JsonObject
import dev.xstreamka.ccsessions.core.Markdown.esc
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Рендер транскрипта в HTML для просмотрщика (JCEF).
 * Стили и скрипт передаются снаружи (ресурсы плагина), чтобы ядро оставалось без зависимостей.
 */
class TranscriptHtml(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val maxOutputChars: Int = 40_000,
) {
    data class Options(
        val dark: Boolean,
        val css: String = "",
        val js: String = "",
        val alias: String? = null,
        val live: String? = null,
        val fontFamily: String = "system-ui, sans-serif",
        val monoFamily: String = "JetBrains Mono, monospace",
        val fontSize: Int = 13,
    )

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val dateFmt = DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.forLanguageTag("ru"))
    private val fullFmt = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", java.util.Locale.forLanguageTag("ru"))

    fun document(t: Transcript, o: Options): String = buildString {
        append("<!DOCTYPE html><html lang=\"ru\"><head><meta charset=\"utf-8\">")
        append("<style>:root{--font:").append(o.fontFamily).append(";--mono:").append(o.monoFamily)
            .append(";--size:").append(o.fontSize).append("px}</style>")
        append("<style>").append(o.css).append("</style></head>")
        append("<body class=\"").append(if (o.dark) "dark" else "light").append("\">")
        append(toolbar())
        append("<header id=\"hdr\">").append(header(t.summary, o)).append("</header>")
        append("<main id=\"items\">").append(items(t)).append("</main>")
        append("<div id=\"empty-search\" hidden>Ничего не найдено</div>")
        append("<script>").append(o.js).append("</script></body></html>")
    }

    private fun toolbar() = """
        <nav id="bar">
          <input id="q" type="search" placeholder="Поиск по транскрипту…" autocomplete="off">
          <label><input type="checkbox" id="f-thinking" checked> мысли</label>
          <label><input type="checkbox" id="f-tools" checked> инструменты</label>
          <label><input type="checkbox" id="f-only-prompts"> только промпты</label>
          <span class="sp"></span>
          <button id="b-expand" title="Развернуть все">⊞</button>
          <button id="b-collapse" title="Свернуть все">⊟</button>
          <button id="b-top" title="В начало">↑</button>
          <button id="b-bottom" title="В конец">↓</button>
        </nav>
    """.trimIndent()

    fun header(s: SessionSummary, o: Options): String = buildString {
        val title = o.alias?.takeIf { it.isNotBlank() } ?: s.title
        append("<h1>").append(esc(title)).append("</h1>")
        append("<div class=\"meta\">")
        fun chip(label: String, value: String?, cls: String = "") {
            if (value.isNullOrBlank()) return
            append("<span class=\"chip $cls\"><i>").append(esc(label)).append("</i>").append(esc(value)).append("</span>")
        }
        o.live?.let { chip("сейчас", it, "live") }
        chip("ветка", s.gitBranch)
        chip("модель", s.model?.removePrefix("claude-"))
        chip("промптов", s.prompts.toString())
        chip("инструментов", s.toolCalls.toString())
        if (s.lastContext > 0) chip("контекст", fmtTokens(s.lastContext))
        if (s.usage.output > 0) chip("вывод", fmtTokens(s.usage.output))
        s.createdAt?.let { chip("начата", fullFmt.format(it.atZone(zone))) }
        s.updatedAt?.let { chip("активность", fullFmt.format(it.atZone(zone))) }
        append("</div><div class=\"meta sub\">")
        append("<span class=\"mono sel\">").append(esc(s.id)).append("</span>")
        s.cwd?.let { append(" · <span class=\"mono\">").append(esc(it)).append("</span>") }
        s.forkedFrom?.let { append(" · ответвлена от <span class=\"mono\">").append(esc(it.take(8))).append("</span>") }
        append("</div>")
    }

    fun items(t: Transcript): String = buildString {
        var day: LocalDate? = null
        var n = 0
        for (item in t.items) {
            val d = item.ts?.atZone(zone)?.toLocalDate()
            if (d != null && d != day) {
                day = d
                append("<div class=\"day\">").append(esc(dateFmt.format(d))).append("</div>")
            }
            n++
            when (item) {
                is TItem.UserPrompt -> prompt(item, n)
                is TItem.SlashCommand -> append(row("cmd", n, item.ts, "<span class=\"slash\">${esc(item.command)}</span> ${esc(item.args)}"))
                is TItem.AssistantText -> append(row("assistant", n, item.ts, Markdown.toHtml(item.text, ::fileAttr)))
                is TItem.Thinking -> append(
                    "<details class=\"item thinking\" id=\"i$n\"><summary>${time(item.ts)}<span class=\"lbl\">мысли</span>" +
                        "<span class=\"preview\">${esc(SessionSummary.oneLine(item.text, 140))}</span></summary>" +
                        "<div class=\"body md\">${Markdown.toHtml(item.text, ::fileAttr)}</div></details>"
                )
                is TItem.ToolCall -> tool(item)
                is TItem.Note -> note(item, n)
            }
        }
        if (t.items.isEmpty()) append("<div class=\"empty\">В транскрипте пока нет сообщений</div>")
    }

    private fun StringBuilder.prompt(p: TItem.UserPrompt, n: Int) {
        val img = if (p.images > 0) "<div class=\"img\">🖼 изображений: ${p.images}</div>" else ""
        append("<section class=\"item user\" id=\"i$n\" data-kind=\"prompt\">")
        append("<div class=\"who\">Вы ${time(p.ts)}</div>")
        append("<div class=\"body md\">").append(Markdown.toHtml(p.text, ::fileAttr)).append(img).append("</div></section>")
    }

    private fun row(cls: String, n: Int, ts: Instant?, html: String) =
        "<section class=\"item $cls\" id=\"i$n\"><div class=\"who\">${if (cls == "assistant") "Claude" else ""} ${time(ts)}</div><div class=\"body md\">$html</div></section>"

    private fun StringBuilder.note(nt: TItem.Note, n: Int) {
        val cls = nt.kind.name.lowercase()
        if (nt.body.isBlank()) {
            append("<div class=\"note $cls\" id=\"i$n\">${time(nt.ts)} ${esc(nt.title)}</div>")
        } else {
            append("<details class=\"note $cls\" id=\"i$n\"><summary>${time(nt.ts)} ${esc(nt.title)}</summary>")
            append("<pre class=\"out\">").append(esc(clip(nt.body))).append("</pre></details>")
        }
    }

    // ---------------- инструменты ----------------

    private fun StringBuilder.tool(c: TItem.ToolCall) {
        val r = c.result
        val state = when {
            r == null -> "pending"
            r.isError -> "error"
            else -> "ok"
        }
        val (title, body, open) = toolParts(c)
        append("<details class=\"item tool $state t-${cssName(c.name)}\" id=\"t-${esc(c.id)}\" data-kind=\"tool\"")
        if (open) append(" open")
        append("><summary>").append(time(c.ts))
        append("<span class=\"tname\">").append(esc(prettyToolName(c.name))).append("</span>")
        append("<span class=\"tsum\">").append(title).append("</span>")
        if (state == "pending") append("<span class=\"badge run\">выполняется</span>")
        if (state == "error") append("<span class=\"badge err\">ошибка</span>")
        append("</summary><div class=\"tbody\">").append(body).append("</div></details>")
    }

    private data class Parts(val title: String, val body: String, val open: Boolean = false)

    private fun toolParts(c: TItem.ToolCall): Parts {
        val i = c.input
        val r = c.result
        return when (c.name) {
            "Bash" -> {
                val cmd = i.str("command").orEmpty()
                val title = i.str("description")?.let { esc(it) } ?: "<code>${esc(SessionSummary.oneLine(cmd, 120))}</code>"
                Parts(title, "<pre class=\"cmd\">$ ${esc(cmd)}</pre>" + output(r))
            }
            "Edit" -> {
                val path = i.str("file_path").orEmpty()
                val d = LineDiff.diff(i.str("old_string").orEmpty(), i.str("new_string").orEmpty())
                val (add, del) = LineDiff.stats(d)
                val extra = if (i.bool("replace_all")) " <span class=\"badge\">replace_all</span>" else ""
                Parts(fileLink(path) + stat(add, del) + extra, diffHtml(d) + errorOnly(r))
            }
            "MultiEdit" -> {
                val path = i.str("file_path").orEmpty()
                var add = 0; var del = 0
                val sb = StringBuilder()
                i.arr("edits")?.forEachIndexed { idx, e ->
                    if (!e.isJsonObject) return@forEachIndexed
                    val eo = e.asJsonObject
                    val d = LineDiff.diff(eo.str("old_string").orEmpty(), eo.str("new_string").orEmpty())
                    val (a, dl) = LineDiff.stats(d); add += a; del += dl
                    sb.append("<div class=\"sub\">правка ${idx + 1}</div>").append(diffHtml(d))
                }
                Parts(fileLink(path) + stat(add, del), sb.toString() + errorOnly(r))
            }
            "Write" -> {
                val path = i.str("file_path").orEmpty()
                val content = i.str("content").orEmpty()
                val lines = content.count { it == '\n' } + 1
                Parts(fileLink(path) + " <span class=\"dim\">$lines строк</span>", "<pre class=\"code\"><code>${esc(clip(content))}</code></pre>" + errorOnly(r))
            }
            "Read" -> {
                val path = i.str("file_path").orEmpty()
                val range = listOfNotNull(i.long("offset")?.let { "с $it" }, i.long("limit")?.let { "$it строк" }).joinToString(", ")
                Parts(fileLink(path, i.long("offset")) + (if (range.isNotEmpty()) " <span class=\"dim\">$range</span>" else ""), output(r))
            }
            "Grep", "Glob" -> {
                val pat = i.str("pattern").orEmpty()
                val where = i.str("path")?.let { " <span class=\"dim\">в ${esc(it)}</span>" }.orEmpty()
                val glob = i.str("glob")?.let { " <span class=\"dim\">${esc(it)}</span>" }.orEmpty()
                Parts("<code>${esc(pat)}</code>$where$glob", output(r))
            }
            "TodoWrite" -> Parts(todoTitle(i), todos(i))
            "Task", "Agent" -> {
                val type = i.str("subagent_type")
                val title = listOfNotNull(type?.let { "<span class=\"badge\">${esc(it)}</span>" }, i.str("description")?.let(::esc)).joinToString(" ")
                val prompt = i.str("prompt").orEmpty()
                Parts(title, "<div class=\"sub\">задание</div><div class=\"md quote\">${Markdown.toHtml(prompt, ::fileAttr)}</div>" +
                    (r?.let { "<div class=\"sub\">результат</div><div class=\"md\">${Markdown.toHtml(clip(it.text), ::fileAttr)}</div>" } ?: ""))
            }
            "WebFetch" -> {
                val url = i.str("url").orEmpty()
                // Ссылкой делаем только http(s): иначе javascript:/file: и т. п. исполнятся в JCEF по клику
                val title = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) "<a href=\"${esc(url)}\">${esc(url)}</a>" else esc(url)
                Parts(title, output(r))
            }
            "WebSearch" -> Parts(esc(i.str("query").orEmpty()), output(r))
            "Skill" -> Parts(esc(i.str("skill") ?: i.str("command") ?: ""), output(r))
            "ExitPlanMode" -> Parts("план", "<div class=\"md\">${Markdown.toHtml(i.str("plan").orEmpty(), ::fileAttr)}</div>" + output(r), open = true)
            "AskUserQuestion" -> Parts("вопрос пользователю", "<pre class=\"json\">${esc(prettyGson.toJson(i))}</pre>" + output(r))
            else -> {
                val summary = listOf("description", "file_path", "path", "query", "url", "command", "name")
                    .firstNotNullOfOrNull { i.str(it) }?.let { esc(SessionSummary.oneLine(it, 120)) }.orEmpty()
                Parts(summary, "<pre class=\"json\">${esc(clip(prettyGson.toJson(i)))}</pre>" + output(r))
            }
        }
    }

    private fun output(r: ToolResult?): String {
        if (r == null) return ""
        val text = r.text.trimEnd()
        if (text.isEmpty()) return "<div class=\"dim\">(пустой вывод)</div>"
        val cls = if (r.isError) "out err" else "out"
        return "<pre class=\"$cls\">${esc(clip(text))}</pre>"
    }

    private fun errorOnly(r: ToolResult?): String = if (r != null && r.isError) output(r) else ""

    private fun diffHtml(lines: List<LineDiff.Line>): String = buildString {
        append("<pre class=\"diff\">")
        for (l in LineDiff.collapse(lines)) {
            val (cls, sign) = when (l.op) {
                LineDiff.Op.ADD -> "add" to "+"
                LineDiff.Op.DEL -> "del" to "−"
                LineDiff.Op.KEEP -> "keep" to " "
                LineDiff.Op.SKIP -> "skip" to ""
            }
            append("<span class=\"$cls\">").append(sign).append(' ').append(esc(l.text).ifEmpty { " " }).append("</span>")
        }
        append("</pre>")
    }

    private fun todoTitle(i: JsonObject): String {
        val todos = i.arr("todos") ?: return "задачи"
        val done = todos.count { it.isJsonObject && it.asJsonObject.str("status") == "completed" }
        val active = todos.firstOrNull { it.isJsonObject && it.asJsonObject.str("status") == "in_progress" }
            ?.asJsonObject?.let { it.str("activeForm") ?: it.str("content") }
        return "задачи $done/${todos.size()}" + (active?.let { " · <span class=\"dim\">${esc(it)}</span>" } ?: "")
    }

    private fun todos(i: JsonObject): String = buildString {
        append("<ul class=\"todos\">")
        i.arr("todos")?.forEach { t ->
            if (!t.isJsonObject) return@forEach
            val o = t.asJsonObject
            // Статус идёт в class="…" — допускаем только известные значения
            val st = when (o.str("status")) { "completed" -> "completed"; "in_progress" -> "in_progress"; else -> "pending" }
            val mark = when (st) { "completed" -> "✓"; "in_progress" -> "◐"; else -> "○" }
            append("<li class=\"$st\"><span class=\"mk\">$mark</span>").append(esc(o.str("content").orEmpty())).append("</li>")
        }
        append("</ul>")
    }

    private fun stat(add: Int, del: Int) = " <span class=\"st\"><span class=\"a\">+$add</span> <span class=\"d\">−$del</span></span>"

    private fun fileLink(path: String, line: Long? = null): String {
        if (path.isBlank()) return ""
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        val dir = path.removeSuffix(name)
        // Ссылка — только имя файла: клик по пути к папке и остальной строке раскрывает блок инструмента
        return "<span class=\"file\"><b data-file=\"${esc(path)}\"" + (line?.let { " data-line=\"$it\"" } ?: "") +
            ">${esc(name)}</b><span class=\"dir\">${esc(dir)}</span></span>"
    }

    private fun time(ts: Instant?) = if (ts == null) "" else "<time>${timeFmt.format(ts.atZone(zone))}</time>"

    private fun clip(s: String) = if (s.length <= maxOutputChars) s else s.substring(0, maxOutputChars) + "\n… (обрезано, всего ${s.length} символов)"

    companion object {
        private val pathLike = Regex("^(~?/|\\./|\\.\\./|[A-Za-z]:\\\\)?[\\w.@~-]+([/\\\\][\\w.@~-]+)*\\.[A-Za-z0-9]{1,8}(:\\d+)?$|^(~?/)[\\w.@~/-]+$")

        /** Атрибуты для `<code>` из Markdown, если это похоже на путь к файлу. */
        fun fileAttr(code: String): String? {
            val c = code.trim()
            if (c.length > 300 || c.contains(' ') || !pathLike.matches(c)) return null
            if (!c.contains('/') && !c.contains('\\') && !c.contains('.')) return null
            val m = Regex("^(.*?)(?::(\\d+))?$").find(c)!!
            val path = m.groupValues[1]
            val line = m.groupValues[2]
            return "data-file=\"${esc(path)}\"" + (if (line.isNotEmpty()) " data-line=\"$line\"" else "")
        }

        fun prettyToolName(n: String): String =
            if (n.startsWith("mcp__")) n.removePrefix("mcp__").replace("__", " › ") else n

        fun cssName(n: String) = n.replace(Regex("[^A-Za-z0-9_-]"), "_")

        fun fmtTokens(n: Long): String = when {
            n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
            n >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", n / 1_000.0).replace(".0k", "k")
            else -> n.toString()
        }
    }
}
