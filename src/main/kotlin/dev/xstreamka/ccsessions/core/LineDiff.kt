package dev.xstreamka.ccsessions.core

/** Простой построчный diff (LCS) для показа правок Edit/MultiEdit. */
object LineDiff {
    enum class Op { KEEP, DEL, ADD, SKIP }
    data class Line(val op: Op, val text: String)

    fun diff(old: String, new: String, maxCells: Long = 4_000_000): List<Line> {
        val a = old.split('\n')
        val b = new.split('\n')
        // общий префикс/суффикс — дешёвое сокращение задачи
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val am = a.subList(pre, a.size - suf)
        val bm = b.subList(pre, b.size - suf)
        val out = ArrayList<Line>()
        a.subList(0, pre).forEach { out += Line(Op.KEEP, it) }
        if (am.size.toLong() * bm.size > maxCells) {
            am.forEach { out += Line(Op.DEL, it) }
            bm.forEach { out += Line(Op.ADD, it) }
        } else {
            val n = am.size
            val m = bm.size
            val dp = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
                dp[i][j] = if (am[i] == bm[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
            var i = 0
            var j = 0
            while (i < n && j < m) {
                when {
                    am[i] == bm[j] -> { out += Line(Op.KEEP, am[i]); i++; j++ }
                    dp[i + 1][j] >= dp[i][j + 1] -> { out += Line(Op.DEL, am[i]); i++ }
                    else -> { out += Line(Op.ADD, bm[j]); j++ }
                }
            }
            while (i < n) out += Line(Op.DEL, am[i++])
            while (j < m) out += Line(Op.ADD, bm[j++])
        }
        a.subList(a.size - suf, a.size).forEach { out += Line(Op.KEEP, it) }
        return out
    }

    /** Сворачивает неизменённые участки, оставляя [context] строк вокруг правок. */
    fun collapse(lines: List<Line>, context: Int = 3): List<Line> {
        val changed = lines.indices.filter { lines[it].op != Op.KEEP }
        if (changed.isEmpty()) return lines.take(context * 2)
        val keep = BooleanArray(lines.size)
        for (c in changed) for (k in (c - context).coerceAtLeast(0)..(c + context).coerceAtMost(lines.size - 1)) keep[k] = true
        val out = ArrayList<Line>()
        var skipped = 0
        for (i in lines.indices) {
            if (keep[i]) {
                if (skipped > 0) { out += Line(Op.SKIP, "… $skipped без изменений"); skipped = 0 }
                out += lines[i]
            } else skipped++
        }
        if (skipped > 0) out += Line(Op.SKIP, "… $skipped без изменений")
        return out
    }

    fun stats(lines: List<Line>): Pair<Int, Int> = lines.count { it.op == Op.ADD } to lines.count { it.op == Op.DEL }
}
