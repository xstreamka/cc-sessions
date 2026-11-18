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
import dev.xstreamka.ccsessions.core.SessionMeta
import dev.xstreamka.ccsessions.core.SessionSummary
import dev.xstreamka.ccsessions.core.TranscriptHtml
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.border.EmptyBorder

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
 */
class SessionListRenderer(
    showProject: () -> Boolean,
    private val hoveredIndex: () -> Int = { -1 },
    private val buttonHovered: () -> Boolean = { false },
    private val hoveredRow: () -> Int = { -1 },
) : ListCellRenderer<SessionListItem> {
    private val session = SessionCellRenderer(showProject)
    private val header = GroupHeaderRenderer()

    override fun getListCellRendererComponent(
        list: JList<out SessionListItem>, value: SessionListItem, index: Int, selected: Boolean, focused: Boolean,
    ): Component = when (value) {
        is SessionListItem.Header -> header.render(value, selected, focused, index >= 0 && index == hoveredRow())
        is SessionListItem.Session -> {
            val hovered = index >= 0 && index == hoveredIndex()
            session.render(value.row, selected, focused, hovered, list, hovered && buttonHovered())
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
    private val finishBox = JPanel(BorderLayout())
    private var finishTop = 0
    private val root = object : JPanel(BorderLayout()) {
        // Кнопка не влияет на размер строки: ни высота строк, ни ширина списка при наведении не меняются
        override fun getPreferredSize(): Dimension {
            val d = super.getPreferredSize()
            if (finishBox.isVisible) d.width -= finishBox.preferredSize.width
            return d
        }
    }
    private val dot = JLabel()
    private val lines = JPanel()
    private val title = SimpleColoredComponent()
    private val info = SimpleColoredComponent()
    private val status = SimpleColoredComponent()

    init {
        lines.layout = BoxLayout(lines, BoxLayout.Y_AXIS)
        lines.isOpaque = false
        listOf(title, info, status).forEach {
            it.isOpaque = false
            it.ipad = JBUI.emptyInsets()
            it.alignmentX = Component.LEFT_ALIGNMENT
            lines.add(it)
        }
        dot.verticalAlignment = JLabel.TOP
        dot.border = JBUI.Borders.empty(4, 0, 0, 8)
        root.add(dot, BorderLayout.WEST)
        root.add(lines, BorderLayout.CENTER)
        finishBox.isOpaque = false
        finishBox.add(finishLabel, BorderLayout.NORTH)
        finishBox.isVisible = false
        root.add(finishBox, BorderLayout.EAST)
        root.border = JBUI.Borders.empty(6, 10, 6, 8)
    }

    fun render(
        value: SessionRow, selected: Boolean, focused: Boolean,
        hovered: Boolean = false, list: JList<*>? = null, buttonHovered: Boolean = false,
    ): Component {
        val bg = rowBackground(selected, focused, hovered)
        val fg = if (selected) UIUtil.getListSelectionForeground(focused) else UIUtil.getListForeground()
        root.background = bg
        val s = value.summary
        val live = value.liveStatus

        // Завершённая (в архиве) — галочка; но если сессия сейчас работает, важнее её живой статус
        dot.icon = if (value.archived && live?.active != true) CheckIcon(CcIcons.READY)
        else DotIcon(CcIcons.colorFor(live), ring = live == LiveStatus.ENDED || live == LiveStatus.STALE)

        title.clear()
        title.foreground = fg
        if (value.meta.favorite) title.append("★ ", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, CcIcons.STAR))
        title.append(value.displayTitle, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, fg))
        if (value.meta.tags.isNotEmpty()) {
            title.append("  " + value.meta.tags.joinToString(" ") { "#$it" }, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }

        info.clear()
        val gray = if (selected) SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, fg) else SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES
        val parts = buildList {
            s.updatedAt?.let { add(relative(it)) }
            s.gitBranch?.let { add("⎇ $it") }
            add("${s.prompts} пр.")
            if (s.lastContext > 0) add("ктх ${TranscriptHtml.fmtTokens(s.lastContext)}")
            s.model?.let { add(shortModel(it)) }
            if (showProject()) s.cwd?.let { add(it.substringAfterLast('/')) }
            if (value.contentMatch) add("найдено в тексте")
            if (s.isSdk) add("SDK")
        }
        info.append(parts.joinToString("  ·  "), gray)

        status.clear()
        val l = value.live
        if (l != null && live != null && live != LiveStatus.ENDED) {
            val color = if (selected) fg else (CcIcons.colorFor(live) ?: fg)
            status.append(l.describe(), SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, color))
            val age = Duration.ofMillis(System.currentTimeMillis() - l.since)
            if (live.active) status.append("  " + short(age), gray)
            status.isVisible = true
        } else if (value.ownTab) {
            status.append("открыта во вкладке терминала", gray)
            status.isVisible = true
        } else {
            status.isVisible = false
        }

        val showFinish = hovered && list != null && !value.archived
        finishBox.isVisible = showFinish
        // Пока видна кнопка, «раскрытие» длинной строки поверх списка выключено — иначе оно закрыло бы кнопку
        ClientProperty.put(root, ExpandableItemsHandler.RENDERER_DISABLED, if (showFinish) true else null)
        if (showFinish) {
            finishLabel.foreground = fg
            finishLabel.hot = buttonHovered
            // По вертикали — по центру строки заголовка
            finishTop = ((title.preferredSize.height - finishLabel.preferredSize.height) / 2).coerceAtLeast(0)
            // Если список шире видимой области (горизонтальная прокрутка) — прижимаем кнопку к видимому правому краю
            val overflow = (listRight(list) - visibleRight(list)).coerceAtLeast(0)
            finishBox.border = EmptyBorder(finishTop, JBUI.scale(FINISH_GAP), 0, overflow)
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

    private fun visibleRight(list: JList<*>): Int = list.visibleRect.let { minOf(it.x + it.width, listRight(list)) }

    companion object {
        const val FINISH_TEXT = "Завершить"
        private const val FINISH_GAP = 8
        private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        private val dateFmt = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ru"))

        fun relative(ts: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
            val d = Duration.between(ts, now)
            val day = ts.atZone(zone).toLocalDate()
            val today = LocalDate.now(zone)
            return when {
                d.toMinutes() < 1 -> "только что"
                d.toMinutes() < 60 -> "${d.toMinutes()} мин назад"
                day == today -> "сегодня " + timeFmt.format(ts.atZone(zone))
                day == today.minusDays(1) -> "вчера " + timeFmt.format(ts.atZone(zone))
                else -> dateFmt.format(ts.atZone(zone))
            }
        }

        fun short(d: Duration): String = when {
            d.seconds < 60 -> "${d.seconds} с"
            d.toMinutes() < 60 -> "${d.toMinutes()} мин"
            else -> "${d.toHours()} ч"
        }

        fun shortModel(m: String): String = m.removePrefix("claude-").replace(Regex("-\\d{8}$"), "")
    }
}

/**
 * Кнопка «Завершить» в строке сессии: «пилюля» с зелёной рамкой и заливкой, чтобы не сливалась с фоном.
 * [hot] — мышь над самой кнопкой: заливка ярче.
 */
private class FinishButton : JLabel(SessionCellRenderer.FINISH_TEXT, AllIcons.Actions.Checked, LEFT) {
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
