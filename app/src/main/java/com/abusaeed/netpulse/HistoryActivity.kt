package com.abusaeed.netpulse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 24-hour signal chart per operator, 7-day summary, outages and calls. */
class HistoryActivity : BaseScreen() {
    override val titleRes = R.string.hub_history

    override fun buildContent() {
        val now = System.currentTimeMillis()
        val day = HistoryStore.samples(now - 86_400_000L)
        val week = HistoryStore.samples(now - 7 * 86_400_000L)

        section(str(R.string.hist_24h))
        if (day.isEmpty()) note(str(R.string.hist_empty))
        else {
            val chart = HistoryChart(this, p, day.groupBy { it.op }, now - 86_400_000L, now)
            content.addView(chart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)))
            val legend = day.map { it.op }.distinct().joinToString("   ") { "● $it" }
            content.addView(tv(legend, 13f, p.label), mw())
        }

        section(str(R.string.hist_week))
        if (week.isEmpty()) note(str(R.string.hist_empty))
        week.groupBy { it.op }.forEach { (op, list) ->
            val c = card()
            c.addView(tv(op, 15f, Operators.fromCode(list.first().plmn)?.color ?: p.text, true), mw())
            val lv = list.mapNotNull { it.level }
            val good = list.count { val w = Sig.wordRes(it.tech, it.level); w == R.string.q_excellent || w == R.string.q_good }
            val noSvc = list.count { it.level == null }
            kvRow(c, str(R.string.hist_avg), if (lv.isEmpty()) "--" else "${lv.average().toInt()} dBm")
            kvRow(c, str(R.string.hist_good), pct(good, list.size), Sig.GREEN)
            kvRow(c, str(R.string.hist_nosvc), pct(noSvc, list.size), if (noSvc > 0) Sig.ORANGE else p.text)
            kvRow(c, str(R.string.hist_tech), list.mapNotNull { it.tech }.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.joinToString(" ") { "${it.key} ${pct(it.value, list.size)}" })
            kvRow(c, str(R.string.hist_hours), String.format(Locale.US, "%.1f h", list.size / 60.0))
        }

        val fmt = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
        section(str(R.string.hist_outages))
        val outs = HistoryStore.outages(now - 7 * 86_400_000L)
        if (outs.isEmpty()) note(str(R.string.hist_no_outages))
        outs.take(50).forEach { o ->
            val c = card()
            c.addView(tv("⛔  ${fmt.format(Date(o.start))}  •  ${dur(o.end - o.start)}  •  ${o.op}", 14f), mw())
        }

        section(str(R.string.hist_calls))
        val calls = HistoryStore.calls()
        if (calls.isEmpty()) note(str(R.string.hist_no_calls))
        else {
            val drops = calls.count { it.possibleDrop }
            note(getString(R.string.hist_drop_rate_fmt, calls.size, drops, pct(drops, calls.size)))
        }
        calls.take(50).forEach { cl ->
            val c = card()
            c.addView(tv((if (cl.possibleDrop) "⚠️  " else "📞  ") + fmt.format(Date(cl.start)) + "  •  " + dur(cl.end - cl.start) + "  •  " + cl.op, 14f, p.text, true), mw())
            c.addView(tv(getString(R.string.hist_call_detail_fmt, cl.startLevel?.toString() ?: "--", cl.endLevel?.toString() ?: "--",
                cl.minLevel?.toString() ?: "--", cl.techs.ifBlank { "--" }) + if (cl.possibleDrop) "\n" + str(R.string.hist_possible_drop) else "", 13f, p.label), mw())
        }

        note(str(R.string.hist_note))
        content.addView(button(str(R.string.hist_clear), p.button) {
            AlertDialog.Builder(this).setMessage(R.string.hist_clear_confirm)
                .setPositiveButton(R.string.remove) { _, _ -> HistoryStore.clear(); recreate() }
                .setNegativeButton(R.string.cancel, null).show()
        }, mw())
    }

    private fun pct(n: Int, t: Int) = if (t == 0) "0%" else "${n * 100 / t}%"

    private fun dur(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "${s / 3600}h ${(s / 60) % 60}m" else if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"
    }
}

/** Signal level over time, one line per operator (brand colour), gaps where there was no data. */
class HistoryChart(
    ctx: Context, private val p: Palette, private val series: Map<String, List<HistoryStore.Sample>>,
    private val from: Long, private val to: Long
) : View(ctx) {
    private val grid = Paint().apply { color = p.graphGrid; strokeWidth = 1f }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.label; textSize = 24f }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f; style = Paint.Style.STROKE }
    private val fallback = intArrayOf(0xFF42A5F5.toInt(), 0xFFEF5350.toInt(), 0xFF66BB6A.toInt(), 0xFFFFCA28.toInt())

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawColor(p.graphBg)
        val left = 56f; val right = width - 8f; val top = 10f; val bottom = height - 30f
        fun x(t: Long) = left + (t - from).toFloat() / (to - from) * (right - left)
        fun y(v: Int) = top + (-40f - v.coerceIn(-140, -40)) / 100f * (bottom - top)
        for (v in listOf(-60, -80, -100, -120)) { c.drawLine(left, y(v), right, y(v), grid); c.drawText("$v", 4f, y(v) + 8f, axis) }
        val hf = SimpleDateFormat("HH:mm", Locale.US)
        for (i in 0..4) { val t = from + (to - from) * i / 4; c.drawText(hf.format(Date(t)), x(t) - 30f, height - 4f, axis) }
        series.entries.forEachIndexed { i, (_, list) ->
            line.color = Operators.fromCode(list.first().plmn)?.color ?: fallback[i % fallback.size]
            var prev: HistoryStore.Sample? = null
            list.sortedBy { it.t }.forEach { s ->
                val pv = prev
                if (pv != null && s.level != null && pv.level != null && s.t - pv.t < 180_000) {
                    c.drawLine(x(pv.t), y(pv.level), x(s.t), y(s.level), line)
                }
                prev = s
            }
        }
    }
}
