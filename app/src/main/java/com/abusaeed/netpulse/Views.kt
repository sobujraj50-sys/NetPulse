package com.abusaeed.netpulse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.view.View
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/** Colours for dark and light themes. */
class Palette(val dark: Boolean) {
    val bg = if (dark) 0xFF1F1F1F.toInt() else 0xFFF4F5F7.toInt()
    val panel = if (dark) 0xFF2C2C2C.toInt() else 0xFFFFFFFF.toInt()
    val panel2 = if (dark) 0xFF3A3A3A.toInt() else 0xFFE9ECEF.toInt()
    val header = 0xFF101010.toInt()
    val text = if (dark) 0xFFFFFFFF.toInt() else 0xFF1B1B1B.toInt()
    val label = if (dark) 0xFFB0B0B0.toInt() else 0xFF5F6368.toInt()
    val border = if (dark) 0xFF151515.toInt() else 0xFFD0D4D9.toInt()
    val accent = if (dark) 0xFF00E676.toInt() else 0xFF00A152.toInt()
    val cardName = if (dark) 0xFF263238.toInt() else 0xFFE3F2FD.toInt()
    val rowServing = if (dark) 0xFF1B3A24.toInt() else 0xFFDFF5E3.toInt()
    val rowOther = if (dark) 0xFF2A2F3A.toInt() else 0xFFE3E8F3.toInt()
    val graphBg = if (dark) 0xFF161616.toInt() else 0xFFFFFFFF.toInt()
    val graphGrid = if (dark) 0x33FFFFFF else 0x22000000
    val button = if (dark) 0xFF37474F.toInt() else 0xFF546E7A.toInt()
}

object Sig {
    val GREEN = 0xFF00C853.toInt()
    val LIME = 0xFF76D72B.toInt()
    val YELLOW = 0xFFFFC400.toInt()
    val ORANGE = 0xFFFF9100.toInt()
    val RED = 0xFFE53935.toInt()
    val GRAY = 0xFF9E9E9E.toInt()

    private fun thresholds(tech: String?) = when (tech) {
        "LTE", "NR" -> intArrayOf(-85, -95, -105, -115)
        else -> intArrayOf(-70, -85, -95, -105)
    }

    fun color(tech: String?, level: Int?): Int {
        if (level == null) return GRAY
        val t = thresholds(tech)
        return when {
            level >= t[0] -> GREEN
            level >= t[1] -> LIME
            level >= t[2] -> YELLOW
            level >= t[3] -> ORANGE
            else -> RED
        }
    }

    /** String resource for Excellent / Good / Fair / Poor / Bad. */
    fun wordRes(tech: String?, level: Int?): Int {
        if (level == null) return R.string.q_none
        val t = thresholds(tech)
        return when {
            level >= t[0] -> R.string.q_excellent
            level >= t[1] -> R.string.q_good
            level >= t[2] -> R.string.q_fair
            level >= t[3] -> R.string.q_poor
            else -> R.string.q_bad
        }
    }
}

/** Bar graph of the serving-cell level history kept by [Monitor]. */
class SignalGraphView(ctx: Context, private val p: Palette) : View(ctx) {
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint().apply { color = p.graphGrid; strokeWidth = 1f }
    private val density = ctx.resources.displayMetrics.density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = p.label; textSize = 10f * density
    }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = p.text; textSize = 13f * density; isFakeBoldText = true
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawColor(p.graphBg)
        val values = Monitor.history
        val w = width.toFloat()
        val h = height.toFloat()
        val top = -40f
        val bottom = -140f
        fun y(v: Int): Float = (top - v.coerceIn(-140, -40)) / (top - bottom) * h
        for (g in listOf(-60, -80, -100, -120)) {
            val yy = y(g)
            c.drawLine(0f, yy, w, yy, grid)
            c.drawText("$g", 4f * density, yy - 3f * density, text)
        }
        val maxSamples = Monitor.HISTORY_MAX
        val step = w / maxSamples
        val offset = maxSamples - values.size
        values.forEachIndexed { i, (v, tech) ->
            if (v != null) {
                bar.color = Sig.color(tech, v)
                val x = (offset + i) * step
                c.drawRect(x, y(v), x + step - 1f, h, bar)
            }
        }
        val last = values.lastOrNull()
        val label = last?.first?.let { "$it dBm  " + context.getString(Sig.wordRes(last.second, it)) }
            ?: context.getString(R.string.no_signal_data)
        val tw = big.measureText(label)
        c.drawText(label, w - tw - 6f * density, 16f * density, big)
    }
}

data class TrackPoint(val lat: Double, val lon: Double, val color: Int)

/** Draws coloured dots for the drive track, the serving tower and the current position. */
class TrackOverlay(private val points: List<TrackPoint>) : Overlay() {
    var me: GeoPoint? = null
    var tower: GeoPoint? = null
    var towerLabel: String? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2979FF.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val towerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCE53935.toInt(); strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 32f; isFakeBoldText = true
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEEFFFFFF.toInt() }
    private val pt = Point()
    private val pt2 = Point()

    override fun draw(c: Canvas, osmv: MapView, shadow: Boolean) {
        if (shadow) return
        val pj = osmv.projection
        for (p in points) {
            pj.toPixels(GeoPoint(p.lat, p.lon), pt)
            paint.color = p.color
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 9f, paint)
        }
        tower?.let { t ->
            pj.toPixels(t, pt2)
            val tx = pt2.x.toFloat()
            val ty = pt2.y.toFloat()
            me?.let { m ->
                pj.toPixels(m, pt)
                c.drawLine(pt.x.toFloat(), pt.y.toFloat(), tx, ty, linePaint)
            }
            val path = Path().apply {
                moveTo(tx, ty - 34f); lineTo(tx - 22f, ty + 10f); lineTo(tx + 22f, ty + 10f); close()
            }
            c.drawPath(path, towerPaint)
            c.drawPath(path, ring)
            towerLabel?.let { label ->
                val w = labelPaint.measureText(label)
                c.drawRoundRect(tx - w / 2 - 10f, ty + 16f, tx + w / 2 + 10f, ty + 58f, 10f, 10f, labelBg)
                c.drawText(label, tx - w / 2, ty + 48f, labelPaint)
            }
        }
        me?.let {
            pj.toPixels(it, pt)
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 14f, mePaint)
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 14f, ring)
        }
    }
}
