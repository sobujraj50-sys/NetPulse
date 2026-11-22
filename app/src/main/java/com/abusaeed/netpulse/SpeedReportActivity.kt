package com.abusaeed.netpulse

import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.util.Locale

/** Automatic speed tests + monthly report per connection / provider. */
class SpeedReportActivity : BaseScreen() {
    override val titleRes = R.string.hub_report

    override fun buildContent() = render()

    private fun render() {
        content.removeAllViews()
        section(str(R.string.auto_title))
        val opts = listOf(0, 6, 12, 24)
        val names = opts.map { if (it == 0) str(R.string.off) else getString(R.string.auto_every_fmt, it) }
        val i = opts.indexOf(Prefs.autoSpeedHours).coerceAtLeast(0)
        content.addView(button(str(R.string.auto_schedule) + ": " + names[i], p.button) {
            AlertDialog.Builder(this).setTitle(R.string.auto_schedule).setSingleChoiceItems(names.toTypedArray(), i) { d, w ->
                d.dismiss(); Prefs.autoSpeedHours = opts[w]; SpeedWorker.schedule(this); render()
            }.show()
        }, mw())
        content.addView(button(str(R.string.auto_wifi_only) + ": " + str(if (Prefs.autoSpeedWifiOnly) R.string.on else R.string.off), p.button) {
            Prefs.autoSpeedWifiOnly = !Prefs.autoSpeedWifiOnly; SpeedWorker.schedule(this); render()
        }, mw().apply { topMargin = dp(8) })
        note(str(R.string.auto_note))

        section(str(R.string.report_title))
        val h = SpeedTest.history(this)
        if (h.isEmpty()) { note(str(R.string.speed_no_history)); return }
        // month -> provider -> rows
        h.groupBy { it["time"].orEmpty().take(7) }.toSortedMap(compareByDescending { it }).forEach { (month, rows) ->
            content.addView(tv(month, 16f, p.accent, true).apply { setPadding(0, dp(10), 0, dp(4)) }, mw())
            rows.groupBy { r ->
                when (r["connection"]) {
                    "Wi-Fi" -> "Wi-Fi • " + (r["isp"]?.ifBlank { null } ?: r["operator"].orEmpty())
                    "Mobile" -> str(R.string.data_mobile) + " • " + r["operator"].orEmpty()
                    else -> r["operator"].orEmpty().ifBlank { "--" }
                }
            }.forEach { (who, list) ->
                val c = card()
                c.addView(tv(who, 15f, p.text, true), mw())
                fun med(k: String): Double? = list.mapNotNull { it[k]?.toDoubleOrNull() }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
                val downs = list.mapNotNull { it["download_mbps"]?.toDoubleOrNull() }
                kvRow(c, str(R.string.report_tests), list.size.toString())
                kvRow(c, str(R.string.report_median_down), med("download_mbps")?.let { String.format(Locale.US, "%.1f Mbps", it) } ?: "--")
                kvRow(c, str(R.string.report_median_up), med("upload_mbps")?.let { String.format(Locale.US, "%.1f Mbps", it) } ?: "--")
                kvRow(c, str(R.string.report_median_ping), med("ping_ms")?.let { "${it.toInt()} ms" } ?: "--")
                if (downs.isNotEmpty()) kvRow(c, str(R.string.report_range), String.format(Locale.US, "%.1f – %.1f Mbps", downs.min(), downs.max()))
            }
        }
        content.addView(button(str(R.string.speed_share), 0xFF1565C0.toInt()) {
            SpeedTest.historyFile(this)?.let { f ->
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, f.name))
            }
        }, mw().apply { topMargin = dp(12) })
    }
}
