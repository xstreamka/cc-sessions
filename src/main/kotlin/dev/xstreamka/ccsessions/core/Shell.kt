package dev.xstreamka.ccsessions.core

object Shell {
    private val safe = Regex("^[A-Za-z0-9_./:=@%+,-]+$")

    /** POSIX-экранирование аргумента одинарными кавычками. */
    fun quote(s: String): String = when {
        s.isEmpty() -> "''"
        safe.matches(s) -> s
        else -> "'" + s.replace("'", "'\\''") + "'"
    }

    /** Разбивает строку доп. аргументов из настроек (поддерживает кавычки). */
    fun split(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var has = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                quote != null && c == quote -> quote = null
                quote == '"' && c == '\\' && i + 1 < s.length -> { cur.append(s[++i]) }
                quote != null -> cur.append(c)
                c == '\'' || c == '"' -> { quote = c; has = true }
                c == '\\' && i + 1 < s.length -> { cur.append(s[++i]); has = true }
                c.isWhitespace() -> { if (cur.isNotEmpty() || has) { out += cur.toString(); cur.clear(); has = false } }
                else -> { cur.append(c); has = true }
            }
            i++
        }
        if (cur.isNotEmpty() || has) out += cur.toString()
        return out
    }

    fun commandLine(parts: List<String>): String = parts.joinToString(" ") { quote(it) }

    /** Команда для запуска claude: [claudeCommand] может быть «claude» или «npx @anthropic-ai/claude-code». */
    fun claude(claudeCommand: String, extraArgs: String, args: List<String>): String {
        val cmd = split(claudeCommand.ifBlank { "claude" })
        return commandLine(cmd + split(extraArgs) + args)
    }

    /**
     * Текст для ввода в терминал (например, «/rename имя»): одна строка без метасимволов shell.
     * Если claude в вкладке уже завершился, текст попадёт в shell — он не должен ничего выполнить.
     */
    fun safeTerminalText(s: String): String =
        s.replace(Regex("[\\r\\n\\t]"), " ").filter { it !in "$`;|&<>\\(){}\"'!*?#~" }.replace(Regex(" +"), " ").trim()
}
