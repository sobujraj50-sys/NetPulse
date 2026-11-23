package com.abusaeed.netpulse

import android.content.Context
import android.location.Location
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * The user's own site list (imported from an Excel/CSV file on the phone).
 * Sites are matched to the serving cell either directly (eNB / Cell ID column)
 * or by location: the nearest site to the tower position is suggested and the
 * user confirms it once. Nothing leaves the phone.
 */
object SiteDb {

    data class Site(
        val name: String, val address: String, val lat: Double, val lon: Double,
        val area: String, val node: Long?, val cellId: Long?
    )

    data class Match(val site: Site, val distM: Double)

    data class ImportResult(val count: Int, val withLocation: Int, val sheet: String)

    private lateinit var app: Context
    private var sites: List<Site> = emptyList()
    private var byNode: Map<Long, Site> = emptyMap()
    private var byCell: Map<Long, Site> = emptyMap()

    private fun file() = File(app.filesDir, "site_db.tsv")
    private fun prefs() = app.getSharedPreferences("site_db", Context.MODE_PRIVATE)
    private fun links() = app.getSharedPreferences("site_links", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        load()
    }

    val count: Int get() = sites.size

    /** PLMN ("47002") the list belongs to, or "" for any operator. */
    var plmn: String
        get() = prefs().getString("plmn", "") ?: ""
        set(v) { prefs().edit().putString("plmn", v).apply() }

    val sourceName: String get() = prefs().getString("source", "") ?: ""

    private fun applies(c: CellData): Boolean {
        if (sites.isEmpty()) return false
        val p = plmn
        return p.isEmpty() || c.plmn() == p || (c.mcc + (c.mnc?.padStart(2, '0') ?: "")) == p
    }

    // ---------- matching ----------

    /** Site linked to this cell: confirmed by the user, or an exact eNB / Cell ID match. */
    fun siteFor(c: CellData): Site? {
        if (!applies(c)) return null
        val k = SiteFinder.siteKey(c)
        if (k != null) links().getString(k, null)?.let { n -> sites.firstOrNull { it.name == n }?.let { return it } }
        c.cellId?.let { id -> byCell[id]?.let { return it } }
        if (c.tech == "LTE" || c.tech == "NR") c.node?.let { id -> byNode[id]?.let { return it } }
        return null
    }

    fun isConfirmed(c: CellData): Boolean = SiteFinder.siteKey(c)?.let { links().contains(it) } == true

    fun link(c: CellData, s: Site) {
        val k = SiteFinder.siteKey(c) ?: return
        links().edit().putString(k, s.name).apply()
    }

    fun unlink(c: CellData) {
        val k = SiteFinder.siteKey(c) ?: return
        links().edit().remove(k).apply()
    }

    fun search(q: String, limit: Int = 30): List<Site> {
        val t = q.trim().lowercase(Locale.ROOT)
        if (t.isEmpty()) return sites.take(limit)
        return sites.filter { it.name.lowercase(Locale.ROOT).contains(t) || it.address.lowercase(Locale.ROOT).contains(t) || it.area.lowercase(Locale.ROOT).contains(t) }.take(limit)
    }

    fun linkCount(): Int = links().all.size

    /**
     * Nearest sites to where the tower probably is (OpenCelliD position, then the
     * on-phone estimate, then — less reliable — the user's own position).
     * Returns the reference kind: 0 = tower position, 1 = estimate, 2 = your position.
     */
    fun nearest(c: CellData, limit: Int = 3): Pair<Int, List<Match>> {
        if (!applies(c)) return 0 to emptyList()
        val ocid = Monitor.dir.ocidTower(c)
        val est = if (ocid == null) SiteFinder.estimate(c) else null
        val me = Monitor.freshLoc()
        val (kind, lat, lon) = when {
            ocid != null -> Triple(0, ocid.lat, ocid.lon)
            est != null -> Triple(1, est.lat, est.lon)
            me != null -> Triple(2, me.latitude, me.longitude)
            else -> return 0 to emptyList()
        }
        val maxM = if (kind == 2) 5000.0 else 3000.0
        val d = FloatArray(1)
        val list = sites.asSequence().filter { !it.lat.isNaN() }.map {
            Location.distanceBetween(lat, lon, it.lat, it.lon, d)
            Match(it, d[0].toDouble())
        }.filter { it.distM <= maxM }.sortedBy { it.distM }.take(limit).toList()
        return kind to list
    }

    /** A suggestion that is clearly the right one (close, and well ahead of the next site). */
    fun confidentSuggestion(c: CellData): Match? {
        val (kind, list) = nearest(c, 2)
        if (kind == 2) return null
        val first = list.firstOrNull() ?: return null
        val second = list.getOrNull(1)
        val limit = if (kind == 0) 300.0 else 250.0
        return if (first.distM <= limit && (second == null || second.distM > first.distM * 2.5)) first else null
    }

    // ---------- storage ----------

    private fun load() {
        val f = file()
        sites = if (!f.exists()) emptyList() else try {
            f.readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 5 || p[0].isBlank()) null
                else Site(p[0], p[1], p[2].toDoubleOrNull() ?: Double.NaN, p[3].toDoubleOrNull() ?: Double.NaN,
                    p[4], p.getOrNull(5)?.toLongOrNull(), p.getOrNull(6)?.toLongOrNull())
            }
        } catch (e: Exception) { emptyList() }
        byNode = sites.filter { it.node != null }.associateBy { it.node!! }
        byCell = sites.filter { it.cellId != null }.associateBy { it.cellId!! }
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').replace(Regex("\\s+"), " ").trim()

    fun clear() {
        file().delete()
        links().edit().clear().apply()
        prefs().edit().clear().apply()
        load()
    }

    // ---------- import ----------

    /** Blocking — call from a background thread. Throws with a readable message on failure. */
    fun importFile(uri: Uri, displayName: String, opPlmn: String): ImportResult {
        val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw Exception("Could not open the file")
        if (bytes.size > 30_000_000) throw Exception("File is too large")
        val tables: List<Pair<String, List<List<String>>>> =
            if (bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) readXlsx(bytes)
            else listOf("CSV" to readCsv(String(bytes, Charsets.UTF_8).removePrefix("﻿")))

        var best: Pair<String, List<Site>>? = null
        for ((sheet, rows) in tables) {
            val parsed = parseTable(rows)
            if (parsed.isNotEmpty() && parsed.size > (best?.second?.size ?: 0)) best = sheet to parsed
        }
        val (sheet, list) = best ?: throw Exception(
            "No site list found. The file needs a header row with a site name column and LAT / LON (or eNB / Cell ID) columns."
        )
        file().writeText(list.joinToString("\n") {
            listOf(clean(it.name), clean(it.address), if (it.lat.isNaN()) "" else it.lat.toString(),
                if (it.lon.isNaN()) "" else it.lon.toString(), clean(it.area), it.node?.toString() ?: "", it.cellId?.toString() ?: ""
            ).joinToString("\t")
        } + "\n")
        prefs().edit().putString("plmn", opPlmn).putString("source", displayName).apply()
        load()
        return ImportResult(list.size, list.count { !it.lat.isNaN() }, sheet)
    }

    private fun norm(s: String) = s.uppercase(Locale.US).replace(Regex("[^A-Z0-9]"), "")

    private fun findCol(h: List<String>, exact: List<String>, contains: List<String> = emptyList()): Int {
        val n = h.map { norm(it) }
        exact.forEach { e -> n.indexOf(e).let { if (it >= 0) return it } }
        contains.forEach { e -> n.indexOfFirst { it.contains(e) }.let { if (it >= 0) return it } }
        return -1
    }

    private fun parseTable(rows: List<List<String>>): List<Site> {
        for (hi in 0 until minOf(rows.size, 15)) {
            val h = rows[hi]
            val cName = findCol(h, listOf("SITENAME", "SITE", "SITEID", "SITECODE", "BTSNAME", "BTS", "BTSID", "CELLNAME", "NAME"),
                listOf("SITENAME", "SITEID", "BTSNAME"))
            val cLat = findCol(h, listOf("LAT", "LATITUDE", "LATT"), listOf("LATITUDE"))
            val cLon = findCol(h, listOf("LON", "LONG", "LNG", "LONGITUDE", "LONGT"), listOf("LONGITUDE"))
            val cNode = findCol(h, listOf("ENB", "ENBID", "ENODEB", "ENODEBID", "GNB", "GNBID"), listOf("ENODEB", "ENBID"))
            val cCell = findCol(h, listOf("CI", "CELLID", "ECI", "ECGI", "NCI", "CID"))
            if (cName < 0 || ((cLat < 0 || cLon < 0) && cNode < 0 && cCell < 0)) continue
            val cAddr = findCol(h, listOf("ADDRESS", "SITEADDRESS", "ADDR", "LOCATION", "VILLAGE"), listOf("ADDRESS"))
            val cArea = findCol(h, listOf("TERRITORY", "THANA", "UPAZILA", "UPAZILLA", "AREA", "CLUSTER", "DISTRICT", "ZONE", "REGION"),
                listOf("TERRITORY", "THANA", "UPAZILA"))
            val out = ArrayList<Site>()
            for (r in rows.drop(hi + 1)) {
                val name = r.getOrNull(cName)?.trim().orEmpty()
                if (name.isEmpty() || name.length > 60) continue
                val lat = r.getOrNull(cLat)?.trim()?.toDoubleOrNull()
                val lon = r.getOrNull(cLon)?.trim()?.toDoubleOrNull()
                val ok = lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)
                val node = r.getOrNull(cNode)?.trim()?.toDoubleOrNull()?.toLong()
                val cell = r.getOrNull(cCell)?.trim()?.toDoubleOrNull()?.toLong()
                if (!ok && node == null && cell == null) continue
                out.add(Site(name, r.getOrNull(cAddr)?.trim().orEmpty(),
                    if (ok) lat!! else Double.NaN, if (ok) lon!! else Double.NaN,
                    r.getOrNull(cArea)?.trim().orEmpty(), node, cell))
            }
            if (out.isNotEmpty()) return out.distinctBy { it.name }
        }
        return emptyList()
    }

    // ---------- CSV ----------

    private fun readCsv(text: String): List<List<String>> {
        val delim = text.lineSequence().firstOrNull { it.isNotBlank() }?.let { l ->
            listOf(',', ';', '\t').maxByOrNull { d -> l.count { it == d } } ?: ','
        } ?: ','
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var q = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (q) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else q = false
                } else cell.append(ch)
            } else when (ch) {
                '"' -> q = true
                delim -> { row.add(cell.toString()); cell.setLength(0) }
                '\n' -> { row.add(cell.toString()); cell.setLength(0); rows.add(row); row = ArrayList() }
                '\r' -> {}
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows
    }

    // ---------- XLSX (no library: it is a zip of XML files) ----------

    private fun readXlsx(bytes: ByteArray): List<Pair<String, List<List<String>>>> {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val n = e.name
                if (n == "xl/sharedStrings.xml" || n == "xl/workbook.xml" || n == "xl/_rels/workbook.xml.rels" ||
                    (n.startsWith("xl/worksheets/") && n.endsWith(".xml"))) entries[n] = z.readBytes()
                e = z.nextEntry
            }
        }
        val shared = entries["xl/sharedStrings.xml"]?.let { readShared(it) } ?: emptyList()
        val names = sheetNames(entries)
        return entries.keys.filter { it.startsWith("xl/worksheets/") && !it.contains("/_rels/") }.sorted().map { path ->
            (names[path] ?: path.substringAfterLast('/').removeSuffix(".xml")) to readSheet(entries[path]!!, shared)
        }
    }

    private fun parser(b: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(ByteArrayInputStream(b), "UTF-8")
    }

    private fun readShared(b: ByteArray): List<String> {
        val out = ArrayList<String>()
        val p = parser(b)
        val sb = StringBuilder()
        var inSi = false
        var inT = false
        var inRph = false
        var ev = p.next()
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> { inSi = true; sb.setLength(0) }
                    "t" -> inT = true
                    "rPh" -> inRph = true
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "si" -> { inSi = false; out.add(sb.toString()) }
                    "t" -> inT = false
                    "rPh" -> inRph = false
                }
                XmlPullParser.TEXT -> if (inSi && inT && !inRph) sb.append(p.text)
            }
            ev = p.next()
        }
        return out
    }

    private fun sheetNames(entries: Map<String, ByteArray>): Map<String, String> {
        val wb = entries["xl/workbook.xml"] ?: return emptyMap()
        val rels = entries["xl/_rels/workbook.xml.rels"] ?: return emptyMap()
        val idToTarget = HashMap<String, String>()
        parser(rels).let { p ->
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                    val id = p.getAttributeValue(null, "Id") ?: continue
                    var t = p.getAttributeValue(null, "Target") ?: continue
                    t = t.removePrefix("/")
                    if (!t.startsWith("xl/")) t = "xl/$t"
                    idToTarget[id] = t
                }
            }
        }
        val out = HashMap<String, String>()
        parser(wb).let { p ->
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "sheet") {
                    val name = p.getAttributeValue(null, "name") ?: continue
                    val rid = p.getAttributeValue(null, "r:id") ?: continue
                    idToTarget[rid]?.let { out[it] = name }
                }
            }
        }
        return out
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else break }
        return n - 1
    }

    private fun readSheet(b: ByteArray, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val p = parser(b)
        var row: ArrayList<String>? = null
        var col = -1
        var type = ""
        val v = StringBuilder()
        var inV = false
        var ev = p.next()
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> row = ArrayList()
                    "c" -> {
                        val r = p.getAttributeValue(null, "r")
                        col = if (r != null) colIndex(r) else (row?.size ?: 0)
                        type = p.getAttributeValue(null, "t") ?: ""
                        v.setLength(0)
                    }
                    "v", "t" -> inV = true
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> inV = false
                    "c" -> row?.let { r ->
                        val raw = v.toString()
                        val value = when (type) {
                            "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            else -> raw
                        }
                        if (col in 0..500) {
                            while (r.size < col) r.add("")
                            if (r.size == col) r.add(value) else r[col] = value
                        }
                    }
                    "row" -> { row?.let { rows.add(it) }; row = null; if (rows.size > 50_000) return rows }
                }
                XmlPullParser.TEXT -> if (inV) v.append(p.text)
            }
            ev = p.next()
        }
        return rows
    }
}
