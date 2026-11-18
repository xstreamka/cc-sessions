package dev.xstreamka.ccsessions.ide

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import dev.xstreamka.ccsessions.core.LiveStatus
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

object CcIcons {
    @JvmField val ToolWindow: Icon = IconLoader.getIcon("/icons/ccSessions.svg", CcIcons::class.java)

    val WORKING = JBColor(0x2F80ED, 0x4C9AFF)
    val WAITING = JBColor(0xD97706, 0xF0A030)
    val READY = JBColor(0x2E9E50, 0x5FBF73)
    val ERROR = JBColor(0xD32F2F, 0xEF6B6B)
    val MUTED = JBColor(0xB0B4BA, 0x5C6066)
    val STAR = JBColor(0xE0A100, 0xF2C94C)

    fun colorFor(status: LiveStatus?): Color? = when (status) {
        LiveStatus.WORKING, LiveStatus.TOOL, LiveStatus.COMPACTING -> WORKING
        LiveStatus.WAITING_PERMISSION -> WAITING
        LiveStatus.IDLE -> READY
        LiveStatus.ERROR -> ERROR
        LiveStatus.STALE, LiveStatus.ENDED -> MUTED
        null -> null
    }
}

/** Кружок статуса; пустой (прозрачный), если live-статуса нет — чтобы строки не «прыгали». */
class DotIcon(private val color: Color?, private val ring: Boolean = false) : Icon {
    private val size get() = JBUI.scale(10)
    override fun getIconWidth() = size
    override fun getIconHeight() = size
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val col = color ?: return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = col
            val d = JBUI.scale(8)
            val o = (size - d) / 2
            if (ring) {
                g2.stroke = java.awt.BasicStroke(JBUI.scale(2).toFloat())
                g2.drawOval(x + o + 1, y + o + 1, d - 2, d - 2)
            } else {
                g2.fillOval(x + o, y + o, d, d)
            }
        } finally {
            g2.dispose()
        }
    }
}

/** Галочка «сессия завершена (в архиве)» — того же размера, что и [DotIcon], чтобы строки не «прыгали». */
class CheckIcon(private val color: Color) : Icon {
    private val size get() = JBUI.scale(10)
    override fun getIconWidth() = size
    override fun getIconHeight() = size
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g2.color = color
            g2.stroke = java.awt.BasicStroke(JBUIScale.scale(1.8f), java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
            val k = size / 10f
            val path = java.awt.geom.Path2D.Float()
            path.moveTo(x + 1.8f * k, y + 5.4f * k)
            path.lineTo(x + 4.1f * k, y + 7.7f * k)
            path.lineTo(x + 8.4f * k, y + 2.6f * k)
            g2.draw(path)
        } finally {
            g2.dispose()
        }
    }
}
