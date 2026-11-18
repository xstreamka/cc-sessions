package dev.xstreamka.ccsessions.core

import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Экспорт транскрипта в Markdown (для заметок, ревью, передачи коллегам). */
object MarkdownExport {

    data class Options(
        val includeThinking: Boolean = false,
        val includeToolOutput: Boolean = true,
        val maxOutputLines: Int = 60,
        val zone: ZoneId = ZoneId.systemDefault(),
    )

    fun export(t: Transcript, alias: String? = null, o: Options = Options()): String = buildString {
        val s = t.summary
        val dt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(o.zone)
        val tm = DateTimeFormatter.ofPattern("HH:mm").withZone(o.zone)
        append("# ").append(alias?.takeIf { it.isNotBlank() } ?: s.title).append("\n\n")
        fun field(key: String) = append("- ").append(I18n.msg(key)).append(": ")
        field("export.session").append("`").append(s.id).append("`\n")
        s.cwd?.let { field("export.directory").append("`").append(it).append("`\n") }
        s.gitBranch?.let { field("export.branch").append("`").append(it).append("`\n") }
        s.model?.let { field("export.model").append(it).append("\n") }
        s.createdAt?.let { field("export.started").append(dt.format(it)).append("\n") }
        s.updatedAt?.let { field("export.lastActivity").append(dt.format(it)).append("\n") }
        append("- ").append(I18n.msg("export.counts", s.prompts, s.toolCalls)).append("\n\n---\n\n")
        val you = I18n.msg("export.you")
        val thinking = I18n.msg("export.thinking")

        for (item in t.items) {
            val time = item.ts?.let { " · " + tm.format(it) } ?: ""
            when (item) {
                is TItem.UserPrompt -> append("## 🧑 ").append(you).append(time).append("\n\n").append(item.text.trim()).append("\n\n")
                is TItem.SlashCommand -> append("> `").append(item.command).append(if (item.args.isNotBlank()) " " + item.args else "").append("`$time\n\n")
                is TItem.AssistantText -> append("### 🤖 Claude$time\n\n").append(item.text.trim()).append("\n\n")
                is TItem.Thinking -> if (o.includeThinking) {
                    append("<details><summary>").append(thinking).append("</summary>\n\n").append(item.text.trim()).append("\n\n</details>\n\n")
                }
                is TItem.ToolCall -> tool(item, o)
                is TItem.Note -> append("> _").append(item.title).append("_\n\n")
            }
        }
    }

    private fun StringBuilder.tool(c: TItem.ToolCall, o: Options) {
        val i = c.input
        val head = when (c.name) {
            "Bash" -> "`$ " + (i.str("command") ?: "").lineSequence().first() + "`"
            "Edit", "MultiEdit", "Write", "Read" -> "`" + (i.str("file_path") ?: "") + "`"
            "Grep", "Glob" -> "`" + (i.str("pattern") ?: "") + "`"
            "Task", "Agent" -> (i.str("description") ?: i.str("subagent_type") ?: "")
            else -> listOf("description", "file_path", "query", "url").firstNotNullOfOrNull { i.str(it) } ?: ""
        }
        val err = if (c.result?.isError == true) " ⚠️" else ""
        append("- 🔧 **").append(TranscriptHtml.prettyToolName(c.name)).append("** ").append(head).append(err).append("\n")
        when (c.name) {
            "Edit" -> diffBlock(LineDiff.diff(i.str("old_string").orEmpty(), i.str("new_string").orEmpty()))
            "MultiEdit" -> i.arr("edits")?.forEach { e ->
                if (e.isJsonObject) diffBlock(LineDiff.diff(e.asJsonObject.str("old_string").orEmpty(), e.asJsonObject.str("new_string").orEmpty()))
            }
            "Bash" -> if (o.includeToolOutput) outputBlock(c.result?.text, o.maxOutputLines)
        }
        if (c.result?.isError == true && c.name != "Bash") outputBlock(c.result?.text, o.maxOutputLines)
    }

    private fun StringBuilder.diffBlock(lines: List<LineDiff.Line>) {
        append("\n  ```diff\n")
        for (l in LineDiff.collapse(lines)) {
            val sign = when (l.op) { LineDiff.Op.ADD -> "+"; LineDiff.Op.DEL -> "-"; LineDiff.Op.KEEP -> " "; LineDiff.Op.SKIP -> "@@" }
            append("  ").append(sign).append(if (l.op == LineDiff.Op.SKIP) " " else "").append(l.text).append("\n")
        }
        append("  ```\n\n")
    }

    private fun StringBuilder.outputBlock(text: String?, maxLines: Int) {
        val t = text?.trimEnd().orEmpty()
        if (t.isEmpty()) return
        val lines = t.lines()
        val shown = lines.take(maxLines)
        append("\n  ```\n")
        shown.forEach { append("  ").append(it.replace("```", "ˋˋˋ")).append("\n") }
        if (lines.size > maxLines) append("  ").append(I18n.msg("export.moreLines", lines.size - maxLines)).append("\n")
        append("  ```\n\n")
    }
}
