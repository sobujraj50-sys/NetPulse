package com.abusaeed.netpulse

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.location.Location
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Turns a drive-test CSV into KML (Google Earth), GPX or a PDF report. */
object Exporter {

    // ---------- CSV ----------
    fun parseLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQ = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQ && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> inQ = !inQ
                ch == ',' && !inQ -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    fun readCsv(f: File): List<Map<String, String>> {
        val lines = f.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val head = parseLine(lines[0])
        return lines.drop(1).map { l ->
            val v = parseLine(l)
            head.indices.associate { head[it] to (v.getOrNull(it) ?: "") }
        }
    }

    private class Sample(m: Map<String, String>) {
        val time = m["time"] ?: ""
        val tech = m["tech"]?.ifBlank { null }
        val level = m["level_dbm"]?.toIntOrNull()
        val qual = m["qual_db"]?.toIntOrNull()
        val lat = m["lat"]?.toDoubleOrNull()
        val lon = m["lon"]?.toDoubleOrNull()
        val alt = m["alt_m"]?.toDoubleOrNull()
        val mcc = m["mcc"] ?: ""
        val mnc = m["mnc"] ?: ""
        val lac = m["lac_tac"] ?: ""
        val cellId = m["cell_id"] ?: ""
        val node = m["node"] ?: ""
        val localCid = m["local_cid"] ?: ""
        val pci = m["pci_psc"] ?: ""
        val arfcn = m["arfcn"] ?: ""
        val band = m["band"] ?: ""
        val operator = m["operator"] ?: ""
        val network = m["network"] ?: ""
        val name = m["cell_name"]?.ifBlank { null }
        val speed = m["speed_kmh"]?.toDoubleOrNull()
        val hasPos get() = lat != null && lon != null
        val cellLabel: String get() = name ?: if (node.isNotBlank() && localCid.isNotBlank()) "$node-$localCid" else cellId.ifBlank { "--" }
        val dirKey: String? get() = if (tech != null && cellId.isNotBlank() && lac.isNotBlank() && mcc.isNotBlank() && mnc.isNotBlank())
            "$tech:$mcc-$mnc:$lac:$cellId" else null
        val color get() = Sig.color(tech, level)
    }

    private fun load(f: File) = readCsv(f).map { Sample(it) }

    private fun outFile(src: File, ext: String): File =
        File(File(src.parentFile, "exports").apply { mkdirs() }, src.nameWithoutExtension + "." + ext)

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private val localFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private fun isoUtc(local: String): String? = try {
        val d = localFmt.parse(local) ?: return null
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d)
    } catch (e: Exception) { null }

    private val palette = listOf(Sig.GREEN, Sig.LIME, Sig.YELLOW, Sig.ORANGE, Sig.RED, Sig.GRAY)
    private val bucketNames = listOf("Excellent", "Good", "Fair", "Poor", "Bad", "No signal")
    private fun bucket(color: Int) = palette.indexOf(color).let { if (it < 0) 5 else it }

    /** ARGB -> KML aabbggrr */
    private fun kmlColor(c: Int) = String.format(
        Locale.US, "%02x%02x%02x%02x", Color.alpha(c), Color.blue(c), Color.green(c), Color.red(c)
    )

    // ---------- KML ----------
    fun kml(ctx: Context, src: File): File {
        val samples = load(src).filter { it.hasPos }
        if (samples.isEmpty()) throw Exception("This log has no GPS points")
        val out = outFile(src, "kml")
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n")
        sb.append("<name>").append(xml(src.nameWithoutExtension)).append("</name>\n")
        palette.forEachIndexed { i, c ->
            sb.append("<Style id=\"s$i\"><IconStyle><color>").append(kmlColor(c))
                .append("</color><scale>0.6</scale><Icon><href>http://maps.google.com/mapfiles/kml/shapes/shaded_dot.png</href></Icon></IconStyle>")
                .append("<LabelStyle><scale>0</scale></LabelStyle></Style>\n")
        }
        sb.append("<Style id=\"route\"><LineStyle><color>99ffffff</color><width>2</width></LineStyle></Style>\n")
        sb.append("<Style id=\"tower\"><IconStyle><color>ff3539e5</color><scale>1.2</scale><Icon><href>http://maps.google.com/mapfiles/kml/shapes/triangle.png</href></Icon></IconStyle></Style>\n")

        // route line
        sb.append("<Placemark><name>Route</name><styleUrl>#route</styleUrl><LineString><tessellate>1</tessellate><coordinates>\n")
        samples.forEach { sb.append(it.lon).append(',').append(it.lat).append(",0 ") }
        sb.append("\n</coordinates></LineString></Placemark>\n")

        // measurement points (thinned to 5000)
        val step = maxOf(1, samples.size / 5000)
        sb.append("<Folder><name>Signal (").append(samples.size).append(" samples)</name>\n")
        samples.filterIndexed { i, _ -> i % step == 0 }.forEach { s ->
            sb.append("<Placemark><styleUrl>#s").append(bucket(s.color)).append("</styleUrl>")
            sb.append("<description><![CDATA[")
                .append(xml("${s.time}\n${s.operator} ${s.tech ?: ""} ${s.network}\nCell ${s.cellLabel}  PCI ${s.pci}  ARFCN ${s.arfcn} ${s.band}\nLevel ${s.level ?: "--"} dBm  Qual ${s.qual ?: "--"} dB"))
                .append("]]></description>")
            isoUtc(s.time)?.let { sb.append("<TimeStamp><when>").append(it).append("</when></TimeStamp>") }
            sb.append("<Point><coordinates>").append(s.lon).append(',').append(s.lat).append(",0</coordinates></Point></Placemark>\n")
        }
        sb.append("</Folder>\n")

        // towers known from OpenCelliD lookups
        val towers = samples.mapNotNull { s -> s.dirKey?.let { k -> Monitor.dir.towerByKey(k)?.let { s to it } } }
            .distinctBy { it.first.dirKey }
        if (towers.isNotEmpty()) {
            sb.append("<Folder><name>Towers</name>\n")
            towers.forEach { (s, t) ->
                sb.append("<Placemark><name>").append(xml(s.cellLabel)).append("</name><styleUrl>#tower</styleUrl><description>")
                    .append(xml("${s.tech} ${s.operator}\n${t.address ?: ""}")).append("</description><Point><coordinates>")
                    .append(t.lon).append(',').append(t.lat).append(",0</coordinates></Point></Placemark>\n")
            }
            sb.append("</Folder>\n")
        }
        sb.append("</Document></kml>\n")
        out.writeText(sb.toString())
        return out
    }

    // ---------- GPX ----------
    fun gpx(src: File): File {
        val samples = load(src).filter { it.hasPos }
        if (samples.isEmpty()) throw Exception("This log has no GPS points")
        val out = outFile(src, "gpx")
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"NetPulse\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n<trk><name>")
            .append(xml(src.nameWithoutExtension)).append("</name><trkseg>\n")
        samples.forEach { s ->
            sb.append("<trkpt lat=\"").append(s.lat).append("\" lon=\"").append(s.lon).append("\">")
            s.alt?.let { sb.append("<ele>").append(it).append("</ele>") }
            isoUtc(s.time)?.let { sb.append("<time>").append(it).append("</time>") }
            sb.append("<desc>").append(xml("${s.tech ?: ""} ${s.level ?: "--"} dBm cell ${s.cellLabel}")).append("</desc>")
            sb.append("</trkpt>\n")
        }
        sb.append("</trkseg></trk>\n</gpx>\n")
        out.writeText(sb.toString())
        return out
    }

    // ---------- PDF ----------
    fun pdf(ctx: Context, src: File): File {
        val all = load(src)
        if (all.isEmpty()) throw Exception("This log is empty")
        val out = outFile(src, "pdf")
        val doc = PdfDocument()
        val W = 595
        val H = 842
        val M = 40f

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 20f; isFakeBoldText = true }
        val h2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00A152.toInt(); textSize = 14f; isFakeBoldText = true }
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202124.toInt(); textSize = 10.5f }
        val lab = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5F6368.toInt(); textSize = 10.5f }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5F6368.toInt(); textSize = 8.5f }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFD0D4D9.toInt(); strokeWidth = 1f }

        // ---- statistics ----
        val levels = all.mapNotNull { it.level }
        val withPos = all.filter { it.hasPos }
        var dist = 0.0
        val d = FloatArray(1)
        withPos.zipWithNext { a, b ->
            Location.distanceBetween(a.lat!!, a.lon!!, b.lat!!, b.lon!!, d)
            if (d[0] < 2000) dist += d[0]
        }
        var handovers = 0
        all.zipWithNext { a, b -> if (a.cellId.isNotBlank() && b.cellId.isNotBlank() && a.cellId != b.cellId) handovers++ }
        val techShare = all.groupingBy { it.tech ?: "No service" }.eachCount()
        val buckets = IntArray(6)
        all.forEach { buckets[bucket(it.color)]++ }
        val operators = all.map { it.operator }.filter { it.isNotBlank() }.distinct()
        val cells = all.filter { it.cellId.isNotBlank() }.groupBy { "${it.tech}|${it.cellId}" }

        // ---------- page 1 ----------
        val p1 = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
        val c = p1.canvas
        fill.color = 0xFF101010.toInt()
        c.drawRect(0f, 0f, W.toFloat(), 70f, fill)
        c.drawText("NetPulse — Drive Test Report", M, 44f, title)
        var y = 100f
        fun row(k: String, v: String) {
            c.drawText(k, M, y, lab); c.drawText(v, M + 150, y, txt); y += 16
        }
        val first = all.first().time
        val last = all.last().time
        val durSec = try { ((localFmt.parse(last)?.time ?: 0) - (localFmt.parse(first)?.time ?: 0)) / 1000 } catch (e: Exception) { 0L }
        c.drawText("Summary", M, y, h2); y += 20
        row("Log file", src.name)
        row("Start", first)
        row("End", last)
        row("Duration", String.format(Locale.US, "%02d:%02d:%02d", durSec / 3600, (durSec / 60) % 60, durSec % 60))
        row("Samples", "${all.size} (${withPos.size} with GPS)")
        row("Distance", Prefs.distance(dist))
        row("Operator(s)", operators.joinToString(", ").ifBlank { "--" })
        row("Technologies", techShare.entries.sortedByDescending { it.value }
            .joinToString("   ") { "${it.key} ${pct(it.value, all.size)}" })
        row("Handovers", handovers.toString())
        row("Unique cells", cells.size.toString())
        row("Level min / avg / max", if (levels.isEmpty()) "--" else "${levels.min()} / ${levels.average().toInt()} / ${levels.max()} dBm")
        row("Device", "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")

        // quality distribution bar
        y += 14
        c.drawText("Signal quality distribution", M, y, h2); y += 14
        val barW = W - 2 * M
        var x = M
        buckets.forEachIndexed { i, n ->
            if (n == 0) return@forEachIndexed
            val w = barW * n / all.size
            fill.color = palette[i]
            c.drawRect(x, y, x + w, y + 22, fill)
            x += w
        }
        y += 38
        var lx = M
        buckets.forEachIndexed { i, n ->
            fill.color = palette[i]
            c.drawRect(lx, y - 8, lx + 8, y, fill)
            val label = "${bucketNames[i]} ${pct(n, all.size)}"
            c.drawText(label, lx + 12, y, small)
            lx += 14 + small.measureText(label) + 14
        }
        y += 26

        // level over time chart
        c.drawText("Signal level over time (dBm)", M, y, h2); y += 10
        val chart = RectF(M + 24, y, W - M, y + 180)
        c.drawRect(chart, stroke)
        fun cy(v: Int) = chart.top + (-40f - v.coerceIn(-140, -40)) / 100f * chart.height()
        for (g in listOf(-60, -80, -100, -120)) {
            c.drawLine(chart.left, cy(g), chart.right, cy(g), stroke)
            c.drawText("$g", M, cy(g) + 3, small)
        }
        val bw = chart.width() / all.size
        all.forEachIndexed { i, s ->
            val lv = s.level ?: return@forEachIndexed
            fill.color = s.color
            val bx = chart.left + i * bw
            c.drawRect(bx, cy(lv), bx + maxOf(bw, 0.6f), chart.bottom, fill)
        }
        y = chart.bottom + 14
        c.drawText(first, chart.left, y, small)
        c.drawText(last, chart.right - small.measureText(last), y, small)
        c.drawText("Generated by NetPulse " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()), M, H - 24f, small)
        doc.finishPage(p1)

        // ---------- page 2 ----------
        val p2 = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 2).create())
        val c2 = p2.canvas
        y = 50f
        c2.drawText("Route (coloured by signal level)", M, y, h2); y += 10
        val box = RectF(M, y, W - M, y + 360)
        c2.drawRect(box, stroke)
        if (withPos.size >= 2) {
            val minLat = withPos.minOf { it.lat!! }
            val maxLat = withPos.maxOf { it.lat!! }
            val minLon = withPos.minOf { it.lon!! }
            val maxLon = withPos.maxOf { it.lon!! }
            val midLat = Math.toRadians((minLat + maxLat) / 2)
            val spanX = maxOf((maxLon - minLon) * Math.cos(midLat), 1e-6)
            val spanY = maxOf(maxLat - minLat, 1e-6)
            val scale = minOf((box.width() - 20) / spanX, (box.height() - 20) / spanY)
            val ox = box.left + (box.width() - spanX * scale).toFloat() / 2
            val oy = box.top + (box.height() - spanY * scale).toFloat() / 2
            fun px(s: Sample) = (ox + (s.lon!! - minLon) * Math.cos(midLat) * scale).toFloat()
            fun py(s: Sample) = (oy + (maxLat - s.lat!!) * scale).toFloat()
            withPos.forEach { s -> fill.color = s.color; c2.drawCircle(px(s), py(s), 2.6f, fill) }
            fill.color = 0xFF2979FF.toInt()
            c2.drawCircle(px(withPos.first()), py(withPos.first()), 5f, fill)
            c2.drawText("Start", px(withPos.first()) + 7, py(withPos.first()) + 3, small)
            fill.color = Color.BLACK
            c2.drawCircle(px(withPos.last()), py(withPos.last()), 5f, fill)
            c2.drawText("End", px(withPos.last()) + 7, py(withPos.last()) + 3, small)
            val kmWide = spanX * 111.32
            c2.drawText(String.format(Locale.US, "Area ≈ %.2f km wide", kmWide), box.left + 6, box.bottom - 6, small)
        } else {
            c2.drawText("No GPS points in this log", box.left + 10, box.top + 20, txt)
        }
        y = box.bottom + 30

        c2.drawText("Top cells", M, y, h2); y += 18
        val cols = floatArrayOf(M, M + 170, M + 220, M + 270, M + 330, M + 400, M + 460)
        listOf("Cell", "Tech", "PCI", "ARFCN", "Samples", "Avg dBm", "Share").forEachIndexed { i, h -> c2.drawText(h, cols[i], y, lab) }
        y += 6
        c2.drawLine(M, y, W - M, y, stroke)
        y += 14
        cells.entries.sortedByDescending { it.value.size }.take(18).forEach { (_, list) ->
            val s = list.first()
            val avg = list.mapNotNull { it.level }.takeIf { it.isNotEmpty() }?.average()?.toInt()
            val vals = listOf(
                s.cellLabel.take(28), s.tech ?: "--", s.pci, s.arfcn, list.size.toString(),
                avg?.toString() ?: "--", pct(list.size, all.size)
            )
            vals.forEachIndexed { i, v -> c2.drawText(v, cols[i], y, txt) }
            y += 15
        }
        c2.drawText("Generated by NetPulse", M, H - 24f, small)
        doc.finishPage(p2)

        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }

    private fun pct(n: Int, total: Int) = if (total == 0) "0%" else "${(n * 100.0 / total).toInt()}%"
}
