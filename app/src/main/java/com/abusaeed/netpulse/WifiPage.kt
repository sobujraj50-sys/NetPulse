package com.abusaeed.netpulse

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.util.Locale

/** Wi-Fi tab: connected network details, nearby networks, channel graph, best channel, security audit. */
class WifiPage(private val ctx: Context, private val p: Palette) {

    data class Ap(
        val ssid: String, val bssid: String, val rssi: Int, val freq: Int, val channel: Int,
        val widthMhz: Int, val standard: String, val security: String, val wps: Boolean, val connected: Boolean
    )

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var connCard: LinearLayout
    private lateinit var auditBox: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var graph: ChannelGraphView
    private lateinit var bestTv: TextView
    private lateinit var countTv: TextView
    private lateinit var band24: TextView
    private lateinit var band5: TextView
    private var showBand5 = false
    private var lastScanReq = 0L
    private var aps: List<Ap> = emptyList()
    private var publicInfo: String? = null

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun mw() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun str(id: Int) = ctx.getString(id)

    private fun tv(s: CharSequence, size: Float, color: Int = p.text, bold: Boolean = false, g: Int = Gravity.START) = TextView(ctx).apply {
        text = s; textSize = size; setTextColor(color); gravity = g
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun chip(s: String, onClick: () -> Unit) = tv(s, 14f, Color.WHITE, true, Gravity.CENTER).apply {
        setPadding(dp(14), dp(7), dp(14), dp(7))
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xFF424242.toInt()) }
        setOnClickListener { onClick() }
    }

    fun build(): View {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(20)) }
        col.addView(tv(str(R.string.wifi_connected), 17f, p.text, true), mw())
        connCard = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.panel); setPadding(dp(12), dp(10), dp(12), dp(10)) }
        col.addView(connCard, mw().apply { topMargin = dp(6) })

        auditBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(auditBox, mw().apply { topMargin = dp(10) })

        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, dp(6)) }
        head.addView(tv(str(R.string.wifi_channels), 17f, p.text, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        band24 = chip("2.4 GHz") { showBand5 = false; render() }
        band5 = chip("5 GHz") { showBand5 = true; render() }
        head.addView(band24)
        head.addView(band5, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        col.addView(head, mw())
        graph = ChannelGraphView(ctx, p)
        col.addView(graph, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)))
        bestTv = tv("", 14f, p.accent, true).apply { setPadding(0, dp(6), 0, 0) }
        col.addView(bestTv, mw())

        val listHead = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(16), 0, dp(6)) }
        countTv = tv(str(R.string.wifi_nearby), 17f, p.text, true)
        listHead.addView(countTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        listHead.addView(chip("⟳ " + str(R.string.wifi_rescan)) { scan(force = true) })
        col.addView(listHead, mw())
        listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox, mw())
        col.addView(tv(str(R.string.wifi_scan_note), 12f, p.label).apply { setPadding(0, dp(10), 0, 0) }, mw())
        return ScrollView(ctx).apply { addView(col) }
    }

    /** Called when the tab is shown and on each engine tick while visible. */
    fun onVisibleTick() {
        if (System.currentTimeMillis() - lastScanReq > 30_000) scan(force = false)
        renderConnected()
    }

    @Suppress("DEPRECATION")
    private fun wm() = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun scan(force: Boolean) {
        lastScanReq = System.currentTimeMillis()
        try {
            val ok = wm().startScan() // throttled by Android (about 4 scans / 2 min)
            if (!ok && force) android.widget.Toast.makeText(ctx, R.string.wifi_scan_throttled, android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { }
        readResults()
        ui.postDelayed({ readResults() }, 4000)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun readResults() {
        val list: List<ScanResult> = try { wm().scanResults ?: emptyList() } catch (e: Exception) { emptyList() }
        val connected = try { wm().connectionInfo?.bssid } catch (e: Exception) { null }
        aps = list.map { r ->
            val caps = r.capabilities ?: ""
            Ap(
                ssid = r.SSID?.ifBlank { null } ?: str(R.string.wifi_hidden),
                bssid = r.BSSID ?: "",
                rssi = r.level, freq = r.frequency, channel = channelOf(r.frequency),
                widthMhz = when (r.channelWidth) {
                    ScanResult.CHANNEL_WIDTH_40MHZ -> 40; ScanResult.CHANNEL_WIDTH_80MHZ -> 80
                    ScanResult.CHANNEL_WIDTH_160MHZ, ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
                    5 -> 320 // CHANNEL_WIDTH_320MHZ (API 33)
                    else -> 20
                },
                standard = if (Build.VERSION.SDK_INT >= 30) standardName(r.wifiStandard) else "",
                security = securityOf(caps), wps = caps.contains("WPS"),
                connected = connected != null && r.BSSID.equals(connected, ignoreCase = true)
            )
        }.sortedByDescending { it.rssi }
        render()
    }

    companion object {
        fun channelOf(f: Int): Int = when {
            f == 2484 -> 14
            f in 2412..2472 -> (f - 2407) / 5
            f in 5150..5925 -> (f - 5000) / 5
            f in 5955..7115 -> (f - 5950) / 5
            else -> 0
        }

        fun bandOf(f: Int) = when {
            f < 3000 -> "2.4 GHz"; f < 5926 -> "5 GHz"; else -> "6 GHz"
        }

        fun standardName(s: Int) = when (s) {
            4 -> "Wi-Fi 4 (n)"; 5 -> "Wi-Fi 5 (ac)"; 6 -> "Wi-Fi 6 (ax)"; 7 -> "802.11ad"; 8 -> "Wi-Fi 7 (be)"
            1 -> "Legacy (a/b/g)"; else -> ""
        }

        fun securityOf(caps: String) = when {
            caps.contains("WEP") -> "WEP"
            caps.contains("SAE") && caps.contains("PSK") -> "WPA2/WPA3"
            caps.contains("SAE") -> "WPA3"
            caps.contains("EAP") -> "WPA2-Enterprise"
            caps.contains("WPA2") || caps.contains("RSN") -> "WPA2"
            caps.contains("WPA") -> "WPA"
            caps.contains("OWE") -> "OWE (Enhanced Open)"
            else -> "Open"
        }
    }

    private fun securityColor(s: String) = when (s) {
        "Open", "WEP" -> Sig.RED; "WPA" -> Sig.ORANGE; else -> Sig.GREEN
    }

    private fun rssiColor(r: Int) = when {
        r >= -55 -> Sig.GREEN; r >= -65 -> Sig.LIME; r >= -72 -> Sig.YELLOW; r >= -80 -> Sig.ORANGE; else -> Sig.RED
    }

    // ---------- connected network ----------
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun renderConnected() {
        if (!::connCard.isInitialized) return
        connCard.removeAllViews()
        val n = ActiveNet.get(ctx)
        val wi = try { wm().connectionInfo } catch (e: Exception) { null }
        if (n.type != ActiveNet.Type.WIFI || wi == null || wi.networkId == -1) {
            connCard.addView(tv(str(R.string.no_wifi), 15f, p.label), mw())
            return
        }
        val rows = ArrayList<Pair<String, String>>()
        val ssid = wi.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: "Wi-Fi"
        rows.add(str(R.string.wifi_name) to ssid)
        rows.add("BSSID" to (wi.bssid ?: "--"))
        val f = wi.frequency
        rows.add(str(R.string.wifi_band) to "${bandOf(f)} • ${str(R.string.wifi_channel)} ${channelOf(f)} • $f MHz")
        rows.add(str(R.string.wifi_signal) to "${wi.rssi} dBm")
        val link = if (Build.VERSION.SDK_INT >= 29) "↓ ${wi.rxLinkSpeedMbps} / ↑ ${wi.txLinkSpeedMbps} Mbps" else "${wi.linkSpeed} Mbps"
        rows.add(str(R.string.wifi_link) to link)
        if (Build.VERSION.SDK_INT >= 30) standardName(wi.wifiStandard).ifBlank { null }?.let { rows.add(str(R.string.wifi_standard) to it) }
        aps.firstOrNull { it.connected }?.let { rows.add(str(R.string.wifi_security) to it.security); rows.add(str(R.string.wifi_width) to "${it.widthMhz} MHz") }
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val lp = cm.getLinkProperties(cm.activeNetwork)
            lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.let { rows.add(str(R.string.wifi_ip) to it.address.hostAddress.orEmpty()) }
            lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.let { rows.add(str(R.string.wifi_gateway) to it.hostAddress.orEmpty()) }
            lp?.dnsServers?.takeIf { it.isNotEmpty() }?.let { d -> rows.add("DNS" to d.joinToString(", ") { it.hostAddress.orEmpty() }) }
            if (lp?.isPrivateDnsActive == true) rows.add(str(R.string.wifi_private_dns) to (lp.privateDnsServerName ?: "ON"))
        } catch (e: Exception) { }
        publicInfo?.let { rows.add(str(R.string.wifi_public) to it) }

        rows.forEach { (k, v) ->
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, dp(3)) }
            r.addView(tv(k, 14f, p.label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val color = if (k == str(R.string.wifi_signal)) rssiColor(wi.rssi) else p.text
            r.addView(tv(v, 14f, color, g = Gravity.END), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f))
            connCard.addView(r, mw())
        }
        if (publicInfo == null) {
            connCard.addView(chip(str(R.string.wifi_show_public)) { fetchPublic() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
    }

    private fun fetchPublic() {
        publicInfo = "…"
        renderConnected()
        Thread {
            val r = try {
                val c = URL("https://speed.cloudflare.com/meta").openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 6000
                val j = org.json.JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                c.disconnect()
                j.optString("clientIp", "--") + " • " + j.optString("asOrganization", "")
            } catch (e: Exception) { "--" }
            ui.post { publicInfo = r; renderConnected() }
        }.start()
    }

    // ---------- list, graph, audit ----------
    fun render() {
        if (!::listBox.isInitialized) return
        val on = 0xFF00796B.toInt(); val off = 0xFF424242.toInt()
        (band24.background as? GradientDrawable)?.setColor(if (!showBand5) on else off)
        (band5.background as? GradientDrawable)?.setColor(if (showBand5) on else off)
        val bandAps = aps.filter { if (showBand5) it.freq in 4900..7200 else it.freq < 3000 }
        graph.aps = bandAps
        graph.band5 = showBand5
        graph.invalidate()
        bestTv.text = bestChannelText(bandAps)

        countTv.text = ctx.getString(R.string.wifi_nearby_fmt, aps.size)
        listBox.removeAllViews()
        if (aps.isEmpty()) listBox.addView(tv(str(R.string.wifi_none_found), 14f, p.label), mw())
        aps.forEachIndexed { i, a ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(if (a.connected) p.rowServing else if (i % 2 == 0) p.panel else p.panel2)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            val l1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            l1.addView(tv((if (a.connected) "● " else "") + a.ssid, 15f, p.text, true).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            l1.addView(tv("${a.rssi} dBm", 15f, rssiColor(a.rssi), true))
            row.addView(l1, mw())
            val l2 = "${bandOf(a.freq)} • ch ${a.channel} • ${a.widthMhz} MHz" + (if (a.standard.isNotBlank()) " • ${a.standard}" else "")
            row.addView(tv(l2, 12f, p.label), mw())
            val sec = android.text.SpannableStringBuilder(a.bssid + "  •  ")
            sec.append(a.security + (if (a.wps) " + WPS" else ""), android.text.style.ForegroundColorSpan(securityColor(a.security)), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            row.addView(tv(sec, 12f, p.label), mw())
            listBox.addView(row, mw())
        }
        renderAudit()
    }

    private fun bestChannelText(bandAps: List<Ap>): String {
        if (bandAps.isEmpty()) return ""
        fun power(r: Int) = Math.pow(10.0, (r + 100) / 10.0)
        return if (!showBand5) {
            val scores = listOf(1, 6, 11).associateWith { ch ->
                bandAps.filter { !it.connected && kotlin.math.abs(it.channel - ch) < 5 }.sumOf { power(it.rssi) * (5 - kotlin.math.abs(it.channel - ch)) / 5.0 }
            }
            val best = scores.minByOrNull { it.value }!!.key
            ctx.getString(R.string.wifi_best_fmt, "2.4 GHz", best)
        } else {
            val candidates = listOf(36, 40, 44, 48, 149, 153, 157, 161)
            val best = candidates.minByOrNull { ch -> bandAps.filter { !it.connected && kotlin.math.abs(it.channel - ch) < 4 * (it.widthMhz / 20).coerceAtLeast(1) }.sumOf { power(it.rssi) } }!!
            ctx.getString(R.string.wifi_best_fmt, "5 GHz", best)
        }
    }

    private fun renderAudit() {
        auditBox.removeAllViews()
        val items = ArrayList<Pair<Int, String>>() // severity, text
        val mine = aps.firstOrNull { it.connected }
        mine?.let {
            when (it.security) {
                "Open" -> items.add(2 to str(R.string.audit_open_mine))
                "WEP" -> items.add(2 to str(R.string.audit_wep_mine))
                "WPA" -> items.add(1 to str(R.string.audit_wpa_mine))
            }
            if (it.wps) items.add(1 to str(R.string.audit_wps_mine))
            if (it.security == "WPA3" || it.security == "WPA2/WPA3") items.add(0 to str(R.string.audit_wpa3_mine))
        }
        // same name, different security nearby = possible fake hotspot ("evil twin")
        aps.groupBy { it.ssid }.filter { (name, list) ->
            name != str(R.string.wifi_hidden) && list.map { it.security }.distinct().size > 1 && list.any { it.security == "Open" }
        }.keys.forEach { items.add(2 to ctx.getString(R.string.audit_twin_fmt, it)) }
        val openCount = aps.count { it.security == "Open" && !it.connected }
        if (openCount > 0) items.add(1 to ctx.getString(R.string.audit_open_nearby_fmt, openCount))
        if (items.isEmpty()) return
        auditBox.addView(tv(str(R.string.audit_title), 17f, p.text, true).apply { setPadding(0, dp(4), 0, dp(4)) }, mw())
        items.sortedByDescending { it.first }.forEach { (sev, text) ->
            val icon = when (sev) { 0 -> "✅"; 1 -> "⚠️"; else -> "❌" }
            auditBox.addView(tv("$icon  $text", 14f, p.text).apply {
                setBackgroundColor(p.panel); setPadding(dp(12), dp(8), dp(12), dp(8))
            }, mw().apply { bottomMargin = 2 })
        }
    }
}

/** Channel graph: each network drawn as a curve over its channel span, height = signal. */
class ChannelGraphView(ctx: Context, private val p: Palette) : View(ctx) {
    var aps: List<WifiPage.Ap> = emptyList()
    var band5 = false
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.label; textSize = 24f }
    private val grid = Paint().apply { color = p.graphGrid; strokeWidth = 1f }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val colors = intArrayOf(0xFF42A5F5.toInt(), 0xFFEF5350.toInt(), 0xFF66BB6A.toInt(), 0xFFFFCA28.toInt(),
        0xFFAB47BC.toInt(), 0xFF26C6DA.toInt(), 0xFFFF7043.toInt(), 0xFF8D6E63.toInt(), 0xFFEC407A.toInt(), 0xFF9CCC65.toInt())

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawColor(p.graphBg)
        val left = 50f; val bottom = height - 30f; val top = 10f; val right = width - 10f
        val (minCh, maxCh) = if (band5) 32 to 170 else -1 to 16
        fun x(ch: Double) = left + ((ch - minCh) / (maxCh - minCh) * (right - left)).toFloat()
        fun y(r: Int) = top + ((-30f - r.coerceIn(-100, -30)) / 70f) * (bottom - top)
        for (r in listOf(-40, -60, -80, -100)) {
            c.drawLine(left, y(r), right, y(r), grid)
            c.drawText("$r", 4f, y(r) + 8f, axis)
        }
        val ticks = if (band5) listOf(36, 52, 100, 116, 132, 149, 165) else (1..13).toList()
        ticks.forEach { c.drawText("$it", x(it.toDouble()) - 10f, height - 4f, axis) }
        aps.sortedBy { it.rssi }.forEachIndexed { i, a ->
            val half = (a.widthMhz / 5.0) / 2.0 // channel units: 5 MHz per channel
            val x0 = x(a.channel - half); val x1 = x(a.channel + half); val yTop = y(a.rssi)
            val path = Path().apply {
                moveTo(x0, bottom)
                cubicTo(x0 + (x1 - x0) * 0.15f, yTop, x1 - (x1 - x0) * 0.15f, yTop, x1, bottom)
            }
            val col = if (a.connected) 0xFFFFFFFF.toInt() else colors[i % colors.size]
            fill.color = (col and 0x00FFFFFF) or 0x33000000
            stroke.color = col
            stroke.strokeWidth = if (a.connected) 6f else 3f
            c.drawPath(path, fill)
            c.drawPath(path, stroke)
            label.color = col
            c.drawText(a.ssid.take(12), x(a.channel.toDouble()), yTop + 26f, label)
        }
    }
}
