package com.abusaeed.netpulse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import java.util.Locale

/** Ping (live chart, works like a gaming latency monitor), traceroute, DNS lookup & DNS comparison. */
class NetToolsActivity : BaseScreen() {
    override val titleRes = R.string.hub_tools

    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var pinging = false
    @Volatile private var tracing = false
    private lateinit var hostIn: EditText
    private lateinit var pingStats: android.widget.TextView
    private lateinit var pingChart: PingChart
    private lateinit var pingBtn: android.widget.TextView
    private lateinit var traceBox: LinearLayout
    private lateinit var dnsBox: LinearLayout
    private var icmpOk: Boolean? = null

    override fun buildContent() {
        hostIn = EditText(this).apply {
            setText("1.1.1.1"); setSingleLine(); setTextColor(p.text); hint = "google.com / 8.8.8.8"
        }
        section(str(R.string.tools_host))
        content.addView(hostIn, mw())
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("1.1.1.1", "8.8.8.8", "google.com", "facebook.com").forEachIndexed { i, h ->
            presets.addView(button(h, p.button) { hostIn.setText(h) }.apply { textSize = 12f },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(4) })
        }
        content.addView(presets, mw())

        // ---- ping ----
        section(str(R.string.tools_ping))
        pingChart = PingChart(this, p)
        content.addView(pingChart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(160)))
        pingStats = tv("", 14f, p.text).apply { setPadding(0, dp(6), 0, dp(6)) }
        content.addView(pingStats, mw())
        pingBtn = button(str(R.string.tools_ping_start), 0xFF1565C0.toInt()) { togglePing() }
        content.addView(pingBtn, mw())
        note(str(R.string.tools_ping_note))

        // ---- traceroute ----
        section(str(R.string.tools_trace))
        content.addView(button(str(R.string.tools_trace_start), 0xFF00796B.toInt()) { traceroute() }, mw())
        traceBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(traceBox, mw())

        // ---- DNS ----
        section(str(R.string.tools_dns))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(str(R.string.tools_dns_lookup), 0xFF6A1B9A.toInt()) { dnsLookup() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button(str(R.string.tools_dns_compare), 0xFF6A1B9A.toInt()) { dnsCompare() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        content.addView(row, mw())
        dnsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(dnsBox, mw())
    }

    override fun onPause() {
        super.onPause()
        pinging = false; tracing = false
    }

    private fun host() = hostIn.text.toString().trim().ifBlank { "1.1.1.1" }

    // ---------- ping ----------
    private fun togglePing() {
        if (pinging) { pinging = false; return }
        pinging = true
        pingBtn.text = str(R.string.speed_stop)
        pingChart.values.clear()
        val h = host()
        Thread {
            if (icmpOk == null) icmpOk = NetUtil.icmpAvailable()
            var sent = 0; var lost = 0
            val ok = ArrayList<Double>()
            while (pinging) {
                val v: Double? = if (icmpOk == true) NetUtil.icmp(h, 2).let { if (it.ok) it.ms else null }
                else NetUtil.httpRtt("https://$h/")
                sent++
                if (v == null) lost++ else ok.add(v)
                val jitter = if (ok.size < 2) 0.0 else ok.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average()
                val stats = if (ok.isEmpty()) getString(R.string.tools_ping_fail)
                else String.format(Locale.US, "%s %.0f ms   •   min %.0f   avg %.0f   max %.0f   •   jitter %.1f   •   loss %d%%   (%d)",
                    if (icmpOk == true) "ICMP" else "HTTP", v ?: ok.last(), ok.min(), ok.average(), ok.max(), jitter, lost * 100 / sent, sent)
                ui.post {
                    pingChart.values.add(v); if (pingChart.values.size > 120) pingChart.values.removeAt(0)
                    pingChart.invalidate(); pingStats.text = stats
                }
                Thread.sleep(1000)
            }
            ui.post { pingBtn.text = str(R.string.tools_ping_start) }
        }.start()
    }

    // ---------- traceroute ----------
    private fun traceroute() {
        if (tracing) return
        tracing = true
        traceBox.removeAllViews()
        val h = host()
        val status = tv(str(R.string.diag_running), 14f, p.label)
        traceBox.addView(status, mw())
        Thread {
            if (icmpOk == null) icmpOk = NetUtil.icmpAvailable()
            if (icmpOk != true) {
                ui.post { status.text = str(R.string.tools_no_icmp) }; tracing = false; return@Thread
            }
            for (ttl in 1..30) {
                if (!tracing) break
                val r = NetUtil.icmp(h, 2, ttl = ttl)
                val line = String.format(Locale.US, "%2d   %-18s %s", ttl, r.from ?: "*", r.ms?.let { String.format(Locale.US, "%.0f ms", it) } ?: "")
                val cg = r.from?.let { NetUtil.isCgnat(it) } == true
                ui.post {
                    traceBox.addView(tv(line + if (cg) "   (CGNAT)" else "", 13f, if (r.ok) Sig.GREEN else p.text).apply {
                        typeface = android.graphics.Typeface.MONOSPACE
                    }, mw())
                }
                if (r.ok) break
            }
            ui.post { status.text = str(R.string.tools_trace_done) }
            tracing = false
        }.start()
    }

    // ---------- DNS ----------
    private fun dnsLookup() {
        dnsBox.removeAllViews()
        val h = host()
        Thread {
            val (ms, list) = NetUtil.resolve(h)
            ui.post {
                dnsBox.addView(tv(String.format(Locale.US, "%s → %.0f ms", h, ms), 14f, p.text, true).apply { setPadding(0, dp(8), 0, dp(4)) }, mw())
                if (list.isEmpty()) dnsBox.addView(tv(str(R.string.tools_dns_none), 14f, Sig.RED), mw())
                list.forEach { dnsBox.addView(tv("  $it", 14f, p.label), mw()) }
            }
        }.start()
    }

    private fun dnsCompare() {
        dnsBox.removeAllViews()
        val status = tv(str(R.string.diag_running), 14f, p.label).apply { setPadding(0, dp(8), 0, 0) }
        dnsBox.addView(status, mw())
        val isp = try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.firstOrNull { it is java.net.Inet4Address }?.hostAddress
        } catch (e: Exception) { null }
        val servers = listOfNotNull(
            isp?.let { str(R.string.tools_dns_yours) + " ($it)" to it },
            "Cloudflare 1.1.1.1" to "1.1.1.1", "Google 8.8.8.8" to "8.8.8.8", "Quad9 9.9.9.9" to "9.9.9.9",
            "AdGuard 94.140.14.14" to "94.140.14.14"
        )
        val names = listOf("google.com", "facebook.com", "youtube.com", "wikipedia.org", "prothomalo.com", "bkash.com")
        Thread {
            val results = servers.map { (label, ip) ->
                val t = names.mapNotNull { NetUtil.dnsQuery(ip, it) }
                Triple(label, t.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }, names.size - t.size)
            }.sortedBy { it.second ?: Double.MAX_VALUE }
            ui.post {
                status.text = str(R.string.tools_dns_result)
                results.forEachIndexed { i, (label, ms, fail) ->
                    val txt = (if (i == 0 && ms != null) "🏆 " else "    ") + label + "   " +
                        (ms?.let { String.format(Locale.US, "%.0f ms", it) } ?: str(R.string.tools_dns_blocked)) +
                        if (fail > 0 && ms != null) "  ($fail ✗)" else ""
                    dnsBox.addView(tv(txt, 14f, if (i == 0 && ms != null) Sig.GREEN else p.text), mw())
                }
                note(str(R.string.tools_dns_note), dnsBox)
            }
        }.start()
    }
}

/** Live latency chart (null = lost packet, drawn as a red tick). */
class PingChart(ctx: Context, private val p: Palette) : View(ctx) {
    val values = ArrayList<Double?>()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f; color = 0xFF42A5F5.toInt() }
    private val lost = Paint().apply { strokeWidth = 4f; color = Sig.RED }
    private val grid = Paint().apply { color = p.graphGrid }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.label; textSize = 22f }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawColor(p.graphBg)
        val ok = values.filterNotNull()
        val maxV = maxOf(100.0, (ok.maxOrNull() ?: 0.0) * 1.2)
        fun y(v: Double) = (height - 8f) - (v / maxV * (height - 20f)).toFloat()
        for (g in listOf(maxV / 4, maxV / 2, maxV * 3 / 4)) { c.drawLine(40f, y(g), width.toFloat(), y(g), grid); c.drawText("${g.toInt()}", 2f, y(g) + 8f, axis) }
        val step = (width - 40f) / 120
        values.forEachIndexed { i, v ->
            val x = 40f + (120 - values.size + i) * step
            if (v == null) c.drawLine(x, 10f, x, height - 8f, lost)
            else if (i > 0) values[i - 1]?.let { pv -> c.drawLine(x - step, y(pv), x, y(v), line) }
        }
    }
}
