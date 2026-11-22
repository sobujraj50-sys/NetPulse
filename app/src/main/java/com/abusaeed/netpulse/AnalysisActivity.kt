package com.abusaeed.netpulse

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** RF analysis of one drive log: map (signal / best server / technology / throughput) + report. */
class AnalysisActivity : BaseScreen() {
    override val titleRes = R.string.an_title

    companion object { const val EXTRA_FILE = "file" }

    private var mode = 0
    private var result: Analysis.Result? = null
    private lateinit var map: MapView
    private lateinit var overlay: AnalysisOverlay
    private lateinit var modeBtns: List<android.widget.TextView>
    private lateinit var legend: android.widget.TextView
    private lateinit var report: LinearLayout

    override fun buildContent() {
        val f = intent.getStringExtra(EXTRA_FILE)?.let { File(it) }
            ?: Monitor.logger.files().firstOrNull { it != Monitor.logger.file || !Monitor.logging }
            ?: run { note(str(R.string.an_no_logs)); return }
        note(f.name)
        map = MapView(this).apply {
            setTileSource(if (Prefs.mapStyle == 1) TileSourceFactory.OpenTopo else TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
        }
        overlay = AnalysisOverlay()
        map.overlays.add(overlay)
        content.addView(map, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(320)))
        // allow the map to pan inside the scroll view
        map.setOnTouchListener { v, _ -> v.parent.requestDisallowInterceptTouchEvent(true); false }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        modeBtns = listOf(R.string.an_mode_signal, R.string.an_mode_server, R.string.an_mode_tech, R.string.an_mode_tput).mapIndexed { i, r ->
            button(str(r), p.button) { mode = i; draw() }.apply { textSize = 12f }.also {
                row.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(4) })
            }
        }
        content.addView(row, mw())
        legend = tv("", 12f, p.label).apply { setPadding(0, dp(4), 0, 0) }
        content.addView(legend, mw())
        report = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(report, mw())
        note(str(R.string.diag_running), report)

        Thread {
            val r = try { Analysis.analyze(f) } catch (e: Exception) { null }
            Handler(Looper.getMainLooper()).post {
                result = r
                if (r == null) { report.removeAllViews(); note(str(R.string.an_failed), report); return@post }
                fit(r); draw(); renderReport(r)
            }
        }.start()
    }

    override fun onResume() { super.onResume(); if (::map.isInitialized) map.onResume() }
    override fun onPause() { super.onPause(); if (::map.isInitialized) map.onPause() }

    private fun fit(r: Analysis.Result) {
        val pts = r.samples.filter { it.hasPos }
        if (pts.isEmpty()) return
        val box = BoundingBox(pts.maxOf { it.lat!! } + 0.002, pts.maxOf { it.lon!! } + 0.002, pts.minOf { it.lat!! } - 0.002, pts.minOf { it.lon!! } - 0.002)
        map.post { try { map.zoomToBoundingBox(box, false, dp(20)) } catch (e: Exception) { } }
    }

    private fun techColor(t: String?) = when (t) {
        "NR" -> 0xFF8E24AA.toInt(); "LTE" -> 0xFF43A047.toInt(); "WCDMA" -> 0xFFFB8C00.toInt(); "GSM" -> 0xFFE53935.toInt(); else -> Sig.GRAY
    }

    private fun tputColor(v: Double?) = when {
        v == null -> 0x00000000; v >= 20 -> Sig.GREEN; v >= 10 -> Sig.LIME; v >= 5 -> Sig.YELLOW; v >= 2 -> Sig.ORANGE; else -> Sig.RED
    }

    private fun draw() {
        val r = result ?: return
        modeBtns.forEachIndexed { i, b -> (b.background as? android.graphics.drawable.GradientDrawable)?.setColor(if (i == mode) 0xFF00796B.toInt() else p.button) }
        val order = r.cellOrder
        overlay.points = r.samples.filter { it.hasPos }.mapNotNull { s ->
            val c = when (mode) {
                1 -> order.indexOf(s.label).let { if (it < 0) Sig.GRAY else Analysis.cellColors[it % Analysis.cellColors.size] }
                2 -> techColor(s.tech)
                3 -> tputColor(s.testDl).takeIf { it != 0 } ?: return@mapNotNull null
                else -> Sig.color(s.tech, s.level)
            }
            Triple(s.lat!!, s.lon!!, c)
        }
        overlay.sectors = if (mode == 1) r.sectors.map { sec ->
            val idx = order.indexOf(sec.cell)
            sec to if (idx < 0) Sig.GRAY else Analysis.cellColors[idx % Analysis.cellColors.size]
        } else emptyList()
        overlay.hos = r.handovers.filter { it.lat != null }
        legend.text = when (mode) {
            1 -> order.take(12).mapIndexed { i, c -> "■ $c" }.joinToString("   ") + "\n" + str(R.string.an_legend_server)
            2 -> "■ 5G NR   ■ LTE   ■ 3G   ■ 2G"
            3 -> str(R.string.an_legend_tput)
            else -> str(R.string.an_legend_signal)
        }
        map.invalidate()
    }

    private fun renderReport(r: Analysis.Result) {
        report.removeAllViews()
        val s = r.samples
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

        section(str(R.string.an_ho), report)
        val c1 = card(report)
        val pp = r.handovers.count { it.pingPong }
        val late = r.handovers.count { it.late }
        val irat = r.handovers.count { it.irat }
        val durMin = ((s.lastOrNull()?.t ?: 0) - (s.firstOrNull()?.t ?: 0)) / 60000.0
        kvRow(c1, str(R.string.an_ho_total), "${r.handovers.size}" + if (durMin > 0) String.format(Locale.US, "  (%.1f / min)", r.handovers.size / durMin) else "")
        kvRow(c1, str(R.string.an_ho_pp), "$pp" + pct(pp, r.handovers.size), if (pp > 0) Sig.ORANGE else p.text)
        kvRow(c1, str(R.string.an_ho_late), "$late" + pct(late, r.handovers.size), if (late > 0) Sig.ORANGE else p.text)
        kvRow(c1, str(R.string.an_ho_irat), "$irat", if (irat > 0) Sig.ORANGE else p.text)
        r.handovers.takeLast(15).reversed().forEach { h ->
            val flags = listOfNotNull(if (h.pingPong) "PING-PONG" else null, if (h.late) "LATE" else null, if (h.irat) "IRAT" else null).joinToString(" ")
            c1.addView(tv("${fmt.format(Date(h.t))}  ${h.from} → ${h.to}  (${h.fromLevel ?: "--"} → ${h.toLevel ?: "--"} dBm) $flags", 12f,
                if (flags.isEmpty()) p.label else Sig.ORANGE), mw())
        }
        note(str(R.string.an_ho_note), report)

        section(str(R.string.an_pollution), report)
        val c2 = card(report)
        kvRow(c2, str(R.string.an_pollution_pct), "${r.pollutedPct}%", if (r.pollutedPct >= 10) Sig.RED else if (r.pollutedPct > 0) Sig.ORANGE else Sig.GREEN)
        c2.addView(tv(str(R.string.an_pollution_note), 12f, p.label), mw())

        section(str(R.string.an_pci), report)
        val c3 = card(report)
        if (r.pciIssues.isEmpty()) c3.addView(tv("✅ " + str(R.string.an_pci_none), 14f), mw())
        r.pciIssues.take(20).forEach { c3.addView(tv("⚠️ $it", 13f), mw()) }

        section(str(R.string.an_bands), report)
        val c4 = card(report)
        if (r.bands.isEmpty()) c4.addView(tv("--", 14f), mw())
        r.bands.forEach { b ->
            kvRow(c4, b.band, "${b.share}%  •  ${b.avgLevel ?: "--"} dBm  •  Q ${b.avgQual ?: "--"}" + (b.avgDl?.let { String.format(Locale.US, "  •  %.1f Mbps", it) } ?: ""))
        }

        section(str(R.string.an_tput), report)
        val c5 = card(report)
        if (r.buckets.isEmpty()) c5.addView(tv(str(R.string.an_tput_none), 13f, p.label), mw())
        val maxM = r.buckets.maxOfOrNull { it.avgMbps } ?: 1.0
        r.buckets.forEach { b ->
            c5.addView(tv(String.format(Locale.US, "%s dBm  →  %.1f Mbps  (%d)", b.range, b.avgMbps, b.n), 13f), mw())
            c5.addView(android.view.View(this).apply { setBackgroundColor(0xFF42A5F5.toInt()) },
                LinearLayout.LayoutParams(((b.avgMbps / maxM) * (resources.displayMetrics.widthPixels - dp(60))).toInt().coerceAtLeast(2), dp(6)))
        }

        section(str(R.string.an_sectors), report)
        val c6 = card(report)
        if (r.sectors.isEmpty()) c6.addView(tv(str(R.string.an_sectors_none), 13f, p.label), mw())
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        r.sectors.sortedBy { it.site }.forEach { sec ->
            val card = dirs[(((sec.azimuth + 22.5) % 360) / 45).toInt()]
            kvRow(c6, "${sec.site} • ${sec.cell}", "${sec.azimuth}° $card  ±${sec.spread}°  (${sec.n})" + if (sec.estimated) " ≈" else "")
        }
        note(str(R.string.an_sectors_note), report)
    }

    private fun pct(n: Int, t: Int) = if (t == 0) "" else "  (${n * 100 / t}%)"
}

/** Points + sector wedges + handover markers for the analysis map. */
class AnalysisOverlay : Overlay() {
    var points: List<Triple<Double, Double, Int>> = emptyList()
    var sectors: List<Pair<Analysis.Sector, Int>> = emptyList()
    var hos: List<Analysis.Ho> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wedge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wedgeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val site = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val hoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.BLACK }
    private val pt = Point()

    override fun draw(c: Canvas, osmv: MapView, shadow: Boolean) {
        if (shadow) return
        val pj = osmv.projection
        for ((lat, lon, col) in points) {
            pj.toPixels(GeoPoint(lat, lon), pt)
            paint.color = col
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 8f, paint)
        }
        for (h in hos) {
            pj.toPixels(GeoPoint(h.lat!!, h.lon!!), pt)
            hoPaint.color = if (h.pingPong || h.late) Sig.RED else Color.BLACK
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 13f, hoPaint)
        }
        for ((sec, col) in sectors) {
            pj.toPixels(GeoPoint(sec.siteLat, sec.siteLon), pt)
            val r = 140f
            val half = sec.spread.coerceIn(15, 60).toFloat()
            wedge.color = (col and 0x00FFFFFF) or 0x55000000
            wedgeLine.color = col
            val oval = RectF(pt.x - r, pt.y - r, pt.x + r, pt.y + r)
            // Canvas angles: 0° = east, clockwise; compass: 0° = north
            val start = sec.azimuth - 90f - half
            c.drawArc(oval, start, half * 2, true, wedge)
            c.drawArc(oval, start, half * 2, true, wedgeLine)
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 9f, site)
        }
    }
}
