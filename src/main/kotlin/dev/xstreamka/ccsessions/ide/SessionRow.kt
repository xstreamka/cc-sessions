package dev.xstreamka.ccsessions.ide

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.ui.ClientProperty
import com.intellij.ui.ExpandableItemsHandler
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.xstreamka.ccsessions.core.DayGroup
import dev.xstreamka.ccsessions.core.LiveState
import dev.xstreamka.ccsessions.core.LiveStatus
import dev.xstreamka.ccsessions.core.RelativeTime
import dev.xstreamka.ccsessions.core.SessionMeta
import dev.xstreamka.ccsessions.core.SessionSummary
import dev.xstreamka.ccsessions.core.TranscriptHtml
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Area
import java.time.Duration
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

data class SessionRow(
    val summary: SessionSummary,
    val meta: SessionMeta,
    val live: LiveState?,
    val contentMatch: Boolean = false,
    val ownTab: Boolean = false,
) {
    val id get() = summary.id
    val displayTitle get() = meta.alias?.takeIf { it.isNotBlank() } ?: summary.title
    val liveStatus: LiveStatus? get() = live?.effectiveStatus()
    /** В архиве — по пометке плагина или по префиксу «[archived]», который ставит Air. */
    val archived get() = meta.archived || summary.archivedInAir

    companion object {
        val KEY: DataKey<SessionRow> = DataKey.create("cc.sessions.row")
    }
}

/** Элемент списка панели: заголовок группы по дням или строка сессии. */
sealed interface SessionListItem {
    data class Header(val group: DayGroup, val count: Int, val collapsed: Boolean) : SessionListItem
    data class Session(val row: SessionRow) : SessionListItem
}

/**
 * Рендерер списка панели: заголовки групп рисует сам, сессии отдаёт [SessionCellRenderer].
 * [hoveredIndex] — индекс строки-сессии под мышью (-1 — нет): у неё справа рисуется кнопка «Завершить».
 * [hoveredRow] — индекс любой строки под мышью (и заголовка группы): она подсвечивается.
 * [shownIds] — сессии, чьи вкладки сейчас на виду: их строки подсвечиваются так же, как под мышью (кнопка — только под мышью).
 */
class SessionListRenderer(
    showProject: () -> Boolean,
    private val hoveredIndex: () -> Int = { -1 },
    private val buttonHovered: () -> Boolean = { false },
    private val hoveredRow: () -> Int = { -1 },
    private val shownIds: () -> Set<String> = { emptySet() },
) : ListCellRenderer<SessionListItem> {
    private val session = SessionCellRenderer(showProject)
    private val header = GroupHeaderRenderer()

    override fun getListCellRendererComponent(
        list: JList<out SessionListItem>, value: SessionListItem, index: Int, selected: Boolean, focused: Boolean,
    ): Component = when (value) {
        is SessionListItem.Header -> header.render(value, selected, focused, index >= 0 && index == hoveredRow())
        is SessionListItem.Session -> {
            val hovered = index >= 0 && index == hoveredIndex()
            val highlighted = hovered || value.row.id in shownIds()
            session.render(value.row, selected, focused, highlighted, list, hovered && buttonHovered(), finish = hovered)
        }
    }

    /** Область кнопки «Завершить» строки-сессии с границами [cell] — в координатах списка. */
    fun finishButtonArea(list: JList<*>, cell: Rectangle): Rectangle = session.finishButtonArea(list, cell)
}

/** Заголовок группы: шеврон, жирное название, серым — число сессий. */
class GroupHeaderRenderer {
    private val root = JPanel(BorderLayout())
    private val text = SimpleColoredComponent()

    init {
        text.isOpaque = false
        text.ipad = JBUI.emptyInsets()
        text.iconTextGap = JBUI.scale(4)
        root.add(text, BorderLayout.CENTER)
        root.border = JBUI.Borders.empty(4, 6, 2, 8)
    }

    fun render(value: SessionListItem.Header, selected: Boolean, focused: Boolean, hovered: Boolean = false): Component {
        val fg = if (selected) UIUtil.getListSelectionForeground(focused) else UIUtil.getListForeground()
        root.background = rowBackground(selected, focused, hovered)
        text.clear()
        text.icon = if (value.collapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown
        text.append(value.group.title, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, fg))
        val gray = if (selected) SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, fg) else SimpleTextAttributes.GRAYED_ATTRIBUTES
        text.append("  ${value.count}", gray)
        return root
    }
}

/** Строка сессии в списке (отдельный класс — чтобы справа можно было добавить свои компоненты). */
class SessionCellRenderer(private val showProject: () -> Boolean) {
    /** Кнопка «Завершить» (видна только у строки под мышью). */
    private val finishLabel = FinishButton()
    private var showFinish = false
    private var finishTop = 0
    private val content = JPanel(BorderLayout())
    /**
     * Кнопка лежит поверх правого края первой строки, а не отдельной колонкой: размер и перенос текста
     * при наведении не меняются (иначе строки «прыгали» бы и текст обрезался под кнопкой).
     * Рисуется самой строкой: текст в области кнопки не рисуется вовсе (своя заливка не совпадает с фоном строки —
     * подсветку списка рисует и сам список), затем поверх — кнопка.
     */
    private val root = object : JPanel(BorderLayout()) {
        override fun paintChildren(g: Graphics) {
            if (!showFinish) {
                super.paintChildren(g)
                return
            }
            val p = finishLabel.preferredSize
            val i = insets
            val x = width - i.right - p.width
            val gap = JBUI.scale(FINISH_GAP)
            val hole = Rectangle(x - gap, i.top, width - x + gap, maxOf(title.firstLineHeight(), finishTop + p.height))
            val text = g.create() as Graphics2D
            try {
                val area = Area(text.clip ?: Rectangle(0, 0, width, height))
                area.subtract(Area(hole))
                text.clip = area
                super.paintChildren(text)
            } finally {
                text.dispose()
            }
            finishLabel.setSize(p)
            val g2 = g.create(x, i.top + finishTop, p.width, p.height)
            try {
                finishLabel.paint(g2)
            } finally {
                g2.dispose()
            }
        }
    }
    private val dot = JLabel()
    private val lines = JPanel()
    private val title = WrappedText()
    private val info = WrappedText()
    private val status = WrappedText()

    init {
        lines.layout = BoxLayout(lines, BoxLayout.Y_AXIS)
        lines.isOpaque = false
        listOf(title, info, status).forEach(lines::add)
        dot.verticalAlignment = JLabel.TOP
        dot.border = JBUI.Borders.empty(4, 0, 0, 8)
        content.isOpaque = false
        content.add(dot, BorderLayout.WEST)
        content.add(lines, BorderLayout.CENTER)
        root.add(content, BorderLayout.CENTER)
        root.border = JBUI.Borders.empty(6, 10, 6, 8)
    }

    fun render(
        value: SessionRow, selected: Boolean, focused: Boolean,
        hovered: Boolean = false, list: JList<*>? = null, buttonHovered: Boolean = false,
        /** Показать кнопку «Завершить» — только у строки под мышью (подсветка открытой вкладки — без кнопки). */
        finish: Boolean = hovered,
    ): Component {
        val bg = rowBackground(selected, focused, hovered)
        val fg = if (selected) UIUtil.getListSelectionForeground(focused) else UIUtil.getListForeground()
        root.background = bg
        val s = value.summary
        val live = value.liveStatus

        // Завершённая (в архиве) — галочка; но если сессия сейчас работает, важнее её живой статус
        dot.icon = if (value.archived && live?.active != true) CheckIcon(CcIcons.READY)
        else DotIcon(CcIcons.colorFor(live), ring = live == LiveStatus.ENDED || live == LiveStatus.STALE)

        // Текст переносится по ширине списка (без горизонтальной прокрутки); кнопка «Завершить» ширину не меняет
        val width = textWidth(list)
        val titleParts = buildList {
            if (value.meta.favorite) add("★ " to SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, CcIcons.STAR))
            add(value.displayTitle to SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, fg))
            if (value.meta.tags.isNotEmpty()) {
                add("  " + value.meta.tags.joinToString(" ") { "#$it" } to SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
        title.setWords(titleParts, width, fg)

        val gray = if (selected) SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, fg) else SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES
        val parts = buildList {
            s.updatedAt?.let { add(RelativeTime.relative(it)) }
            s.gitBranch?.let { add("⎇ $it") }
            add(CcBundle.message("row.prompts", s.prompts))
            if (s.lastContext > 0) add(CcBundle.message("row.context", TranscriptHtml.fmtTokens(s.lastContext)))
            s.model?.let { add(shortModel(it)) }
            if (showProject()) s.cwd?.let { add(it.substringAfterLast('/')) }
            if (value.contentMatch) add(CcBundle.message("row.contentMatch"))
            if (s.isSdk) add("SDK")
        }
        // Пункты («5 нояб.», «⎇ ветка», …) переносятся целиком, разделитель «·» в начале и конце строки не ставится
        info.setItems(parts, "  ·  ", gray, width, fg)

        val l = value.live
        if (l != null && live != null && live != LiveStatus.ENDED) {
            val color = if (selected) fg else (CcIcons.colorFor(live) ?: fg)
            val statusParts = buildList {
                add(l.describe() to SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, color))
                val age = Duration.ofMillis(System.currentTimeMillis() - l.since)
                if (live.active) add("  " + RelativeTime.short(age) to gray)
            }
            // Статус меняется постоянно (команды бывают длинными) — не больше двух строк, чтобы строки не «прыгали»
            status.setWords(statusParts, width, fg, maxLines = 2)
            status.isVisible = true
        } else if (value.ownTab) {
            status.setWords(listOf(CcBundle.message("row.ownTab") to gray), width, fg)
            status.isVisible = true
        } else {
            status.isVisible = false
        }

        showFinish = finish && list != null && !value.archived
        // Пока видна кнопка, «раскрытие» длинной строки поверх списка выключено — иначе оно закрыло бы кнопку
        ClientProperty.put(root, ExpandableItemsHandler.RENDERER_DISABLED, if (showFinish) true else null)
        if (showFinish) {
            finishLabel.text = CcBundle.message("row.finish")
            finishLabel.foreground = fg
            finishLabel.hot = buttonHovered
            // По вертикали — по центру строки заголовка
            finishTop = ((title.firstLineHeight() - finishLabel.preferredSize.height) / 2).coerceAtLeast(0)
        }
        return root
    }

    /** Область кнопки «Завершить» для строки с границами [cell] (координаты списка); по высоте — полоса строки заголовка. */
    fun finishButtonArea(list: JList<*>, cell: Rectangle): Rectangle {
        val size = finishLabel.preferredSize
        val insets = root.insets
        val pad = JBUI.scale(2)
        val right = minOf(cell.x + cell.width, visibleRight(list))
        val left = right - insets.right - size.width - pad
        val bottom = cell.y + insets.top + finishTop + size.height + pad
        return Rectangle(left, cell.y, right - left, bottom - cell.y)
    }

    private fun listRight(list: JList<*>) = list.width - list.insets.right

    /** Ширина под текст строки: ширина списка без отступов строки и кружка статуса; без списка — не переносить. */
    private fun textWidth(list: JList<*>?): Int {
        val w = list?.let { it.width - it.insets.left - it.insets.right } ?: 0
        if (w <= 0) return Int.MAX_VALUE
        val ins = root.insets
        return (w - ins.left - ins.right - dot.preferredSize.width).coerceAtLeast(JBUI.scale(40))
    }

    private fun visibleRight(list: JList<*>): Int = list.visibleRect.let { minOf(it.x + it.width, listRight(list)) }

    companion object {
        private const val FINISH_GAP = 8

        fun shortModel(m: String): String = m.removePrefix("claude-").replace(Regex("-\\d{8}$"), "")
    }
}

/**
 * Кнопка «Завершить» в строке сессии: «пилюля» с зелёной рамкой и заливкой, чтобы не сливалась с фоном.
 * [hot] — мышь над самой кнопкой: заливка ярче.
 */
private class FinishButton : JLabel(CcBundle.message("row.finish"), AllIcons.Actions.Checked, LEFT) {
    var hot = false

    init {
        font = JBUI.Fonts.smallFont()
        iconTextGap = JBUI.scale(3)
        border = JBUI.Borders.empty(1, 6)
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(10)
            val base = CcIcons.READY
            g2.color = Color(base.red, base.green, base.blue, if (hot) 90 else 40)
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = base
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

/** Фон строки списка: выделение, подсветка под мышью (как в списках IDE) или обычный. */
private fun rowBackground(selected: Boolean, focused: Boolean, hovered: Boolean) = when {
    selected -> UIUtil.getListSelectionBackground(focused)
    hovered -> JBUI.CurrentTheme.List.Hover.background(focused)
    else -> UIUtil.getListBackground()
}

/**
 * Текст из фрагментов с переносом на ширину: каждая строка — свой [SimpleColoredComponent].
 * Слова не разрываются, кроме тех, что сами шире строки (их режем по буквам).
 */
private class WrappedText : JPanel() {
    private val pool = ArrayList<SimpleColoredComponent>()
    private val probe = SimpleColoredComponent().apply { ipad = JBUI.emptyInsets() }
    private var used = 0

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private class Piece(val text: String, val attrs: SimpleTextAttributes)

    /** Переносить по словам; [maxLines] — сколько строк максимум (последняя обрезается с «…»). */
    fun setWords(fragments: List<Pair<String, SimpleTextAttributes>>, width: Int, fg: Color, maxLines: Int = Int.MAX_VALUE) {
        val tokens = fragments.flatMap { (text, attrs) -> WORD.findAll(text).map { Piece(it.value, attrs) }.toList() }
        layOut(tokens, width, fg, maxLines, sep = null)
    }

    /** Переносить целыми пунктами [items], между пунктами в одной строке — [sep]. */
    fun setItems(items: List<String>, sep: String, attrs: SimpleTextAttributes, width: Int, fg: Color) {
        layOut(items.map { Piece(it, attrs) }, width, fg, Int.MAX_VALUE, sep = Piece(sep, attrs))
    }

    fun firstLineHeight(): Int = pool.firstOrNull()?.takeIf { used > 0 }?.preferredSize?.height ?: 0

    private fun layOut(tokens: List<Piece>, width: Int, fg: Color, maxLines: Int, sep: Piece?) {
        val out = ArrayList<MutableList<Piece>>()
        var line = ArrayList<Piece>()
        var lineW = 0
        fun newLine() {
            out += line
            line = ArrayList()
            lineW = 0
        }
        for (t in tokens) {
            var text = t.text
            if (line.isEmpty()) text = text.trimStart()
            if (text.isEmpty()) continue
            val sepW = if (sep != null && line.isNotEmpty()) measure(sep.text, sep.attrs) else 0
            val fit = measure(text.trimEnd(), t.attrs)
            if (line.isNotEmpty() && lineW + sepW + fit > width) {
                newLine()
                text = text.trimStart()
            }
            if (sep != null && line.isNotEmpty()) {
                line += sep
                lineW += sepW
            }
            // Слово шире строки — режем по буквам
            while (line.isEmpty() && text.length > 1 && measure(text.trimEnd(), t.attrs) > width) {
                var n = text.length - 1
                while (n > 1 && measure(text.substring(0, n), t.attrs) > width) n--
                line += Piece(text.substring(0, n), t.attrs)
                newLine()
                text = text.substring(n)
            }
            line += Piece(text, t.attrs)
            lineW += measure(text, t.attrs)
        }
        if (line.isNotEmpty()) out += line
        val shown = if (out.size > maxLines) out.subList(0, maxLines) else out
        if (out.size > maxLines) shown.last().let { it.add(Piece("…", it.last().attrs)) }

        used = shown.size
        while (pool.size < used) {
            val c = SimpleColoredComponent()
            c.isOpaque = false
            c.ipad = JBUI.emptyInsets()
            c.alignmentX = Component.LEFT_ALIGNMENT
            pool += c
            add(c)
        }
        pool.forEachIndexed { i, c ->
            c.clear()
            c.isVisible = i < used
            if (i < used) {
                c.foreground = fg
                // Соседние куски одного стиля — одним фрагментом: пробелы на стыке отдельных фрагментов могут не отрисоваться
                val merged = ArrayList<Piece>()
                for (p in shown[i]) {
                    val last = merged.lastOrNull()
                    when {
                        last == null -> merged += p
                        // пробелы между кусками разных стилей — в конец предыдущего (в начале фрагмента тоже теряются)
                        last.attrs == p.attrs || p.text.isBlank() -> merged[merged.lastIndex] = Piece(last.text + p.text, last.attrs)
                        else -> merged += p
                    }
                }
                merged.forEachIndexed { j, p -> c.append(if (j == merged.lastIndex) p.text.trimEnd() else p.text, p.attrs) }
            }
        }
    }

    private fun measure(text: String, attrs: SimpleTextAttributes): Int {
        if (text.isEmpty()) return 0
        probe.clear()
        probe.append(text, attrs)
        return probe.preferredSize.width
    }

    companion object {
        /** Слово вместе с пробелами после него; пробелы в начале фрагмента — отдельным токеном. */
        private val WORD = Regex("\\S+\\s*|\\s+")
    }
}
