package com.abusaeed.netpulse

import android.content.Intent
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DataUsageActivity : BaseScreen() {
    override val titleRes = R.string.hub_data

    override fun buildContent() {}

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        content.removeAllViews()
        if (!DataUsage.hasAccess(this)) {
            val c = card()
            c.addView(tv(str(R.string.data_need_access), 15f), mw())
            c.addView(button(str(R.string.data_grant), 0xFF1565C0.toInt()) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }, mw().apply { topMargin = dp(10) })
            return
        }
        Thread {
            val now = System.currentTimeMillis()
            val today = DataUsage.dayStart(0)
            val rows = (0..6).map { d ->
                val s = DataUsage.dayStart(d)
                val e = if (d == 0) now else DataUsage.dayStart(d - 1)
                Triple(s, DataUsage.bytes(this, false, s, e) ?: 0L, DataUsage.bytes(this, true, s, e) ?: 0L)
            }
            val cycleStart = DataUsage.cycleStart()
            val mMobile = DataUsage.bytes(this, false, cycleStart, now)
            val mWifi = DataUsage.bytes(this, true, cycleStart, now)
            runOnUiThread { show(rows, today, cycleStart, mMobile, mWifi) }
        }.start()
    }

    private fun show(rows: List<Triple<Long, Long, Long>>, today: Long, cycleStart: Long, mMobile: Long?, mWifi: Long?) {
        content.removeAllViews()
        section(str(R.string.data_today))
        val t = card()
        kvRow(t, str(R.string.data_mobile), DataUsage.fmt(rows[0].second))
        kvRow(t, "Wi-Fi", DataUsage.fmt(rows[0].third))

        section(getString(R.string.data_cycle_fmt, SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(cycleStart))))
        val m = card()
        kvRow(m, str(R.string.data_mobile), DataUsage.fmt(mMobile))
        kvRow(m, "Wi-Fi", DataUsage.fmt(mWifi))
        val limit = Prefs.dataLimitMb
        if (limit > 0 && mMobile != null) {
            val pct = (mMobile * 100 / (limit * 1_000_000L)).toInt()
            val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = pct.coerceAtMost(100) }
            m.addView(bar, mw().apply { topMargin = dp(8) })
            m.addView(tv(getString(R.string.data_limit_used_fmt, pct, DataUsage.fmt(limit * 1_000_000L)), 13f,
                if (pct >= 100) Sig.RED else if (pct >= 80) Sig.ORANGE else p.label), mw())
        }

        section(str(R.string.data_week))
        val maxB = rows.maxOf { maxOf(it.second, it.third) }.coerceAtLeast(1)
        val w = card()
        val dayFmt = SimpleDateFormat("EEE d", Locale.getDefault())
        rows.forEach { (day, mob, wifi) ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            r.addView(tv(dayFmt.format(Date(day)) + "   " + str(R.string.data_mobile) + " " + DataUsage.fmt(mob) + "  •  Wi-Fi " + DataUsage.fmt(wifi), 13f), mw())
            fun bar(v: Long, col: Int) = android.view.View(this).apply { setBackgroundColor(col) }.also {
                r.addView(it, LinearLayout.LayoutParams(((v.toDouble() / maxB) * (resources.displayMetrics.widthPixels - dp(60))).toInt().coerceAtLeast(2), dp(7)).apply { topMargin = dp(2) })
            }
            bar(mob, 0xFFFF9100.toInt()); bar(wifi, 0xFF42A5F5.toInt())
            w.addView(r, mw())
        }
        note(str(R.string.data_note))

        section(str(R.string.data_settings))
        val limits = listOf(0, 1024, 2048, 5120, 10240, 20480, 30720, 51200, 102400)
        val names = limits.map { if (it == 0) str(R.string.data_no_limit) else "${it / 1024} GB" }
        val li = limits.indexOf(Prefs.dataLimitMb).coerceAtLeast(0)
        content.addView(button(str(R.string.data_limit) + ": " + names[li], p.button) {
            AlertDialog.Builder(this).setTitle(R.string.data_limit).setSingleChoiceItems(names.toTypedArray(), li) { d, i ->
                d.dismiss(); Prefs.dataLimitMb = limits[i]; Prefs.dataAlertState = ""; render()
            }.show()
        }, mw())
        val days = (1..28).map { it.toString() }
        content.addView(button(str(R.string.data_billing_day) + ": " + Prefs.billingDay, p.button) {
            AlertDialog.Builder(this).setTitle(R.string.data_billing_day).setSingleChoiceItems(days.toTypedArray(), Prefs.billingDay - 1) { d, i ->
                d.dismiss(); Prefs.billingDay = i + 1; render()
            }.show()
        }, mw().apply { topMargin = dp(8) })
        content.addView(spacer(), ViewGroup.LayoutParams(1, dp(8)))
    }
}
