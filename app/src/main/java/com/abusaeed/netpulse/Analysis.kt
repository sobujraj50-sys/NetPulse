package com.abusaeed.netpulse

import android.location.Location
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * RF analysis of a drive-test log: handovers (ping-pong, late, inter-RAT), pilot pollution,
 * PCI collision/confusion, band statistics, throughput vs signal and sector azimuths.
 */
object Analysis {

    data class Nb(val tech: String, val pci: Int?, val arfcn: Int?, val level: Int?)

    class S(m: Map<String, String>, fmt: SimpleDateFormat) {
        val t: Long = try { fmt.parse(m["time"] ?: "")?.time ?: 0L } catch (e: Exception) { 0L }
        val tech = m["tech"]?.ifBlank { null }
        val mcc = m["mcc"].orEmpty()
        val mnc = m["mnc"].orEmpty()
        val lac = m["lac_tac"].orEmpty()
        val cellId = m["cell_id"].orEmpty()
        val node = m["node"].orEmpty()
        val localCid = m["local_cid"].orEmpty()
        val pci = m["pci_psc"]?.toIntOrNull()
        val arfcn = m["arfcn"]?.toIntOrNull()
        val band = m["band"]?.ifBlank { null }
        val level = m["level_dbm"]?.toIntOrNull()
        val qual = m["qual_db"]?.toIntOrNull()
        val lat = m["lat"]?.toDoubleOrNull()
        val lon = m["lon"]?.toDoubleOrNull()
        val testDl = m["test_dl_mbps"]?.toDoubleOrNull()
        val name = m["cell_name"]?.ifBlank { null }
        val nbrs: List<Nb> = m["nbrs"].orEmpty().split('|').mapNotNull { e ->
            val p = e.split(':')
            if (p.size < 4) null else Nb(p[0], p[1].toIntOrNull(), p[2].toIntOrNull(), p[3].toIntOrNull())
        }
        val hasPos get() = lat != null && lon != null
        val label: String get() = name ?: if (node.isNotBlank() && localCid.isNotBlank()) "$node-$localCid" else cellId.ifBlank { "--" }
    }

    data class Ho(val t: Long, val from: String, val to: String, val fromLevel: Int?, val toLevel: Int?,
                  val pingPong: Boolean, val irat: Boolean, val late: Boolean, val lat: Double?, val lon: Double?)
    data class BandStat(val band: String, val share: Int, val avgLevel: Int?, val avgQual: Int?, val avgDl: Double?)
    data class Bucket(val range: String, val n: Int, val avgMbps: Double)
    data class Sector(val cell: String, val site: String, val siteLat: Double, val siteLon: Double,
                      val azimuth: Int, val spread: Int, val n: Int, val estimated: Boolean)

    data class Result(
        val samples: List<S>,
        val handovers: List<Ho>,
        val pollutedPct: Int,
        val polluted: List<S>,
        val pciIssues: List<String>,
        val bands: List<BandStat>,
        val buckets: List<Bucket>,
        val sectors: List<Sector>,
        val cellOrder: List<String>
    )

    fun load(f: File): List<S> {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return Exporter.readCsv(f).map { S(it, fmt) }
    }

    private fun weak(tech: String?) = if (tech == "LTE" || tech == "NR") -115 else -105

    fun analyze(f: File): Result {
        val s = load(f)

        // ---- handovers ----
        val hos = ArrayList<Ho>()
        var i = 1
        while (i < s.size) {
            val a = s[i - 1]; val b = s[i]
            if (a.cellId.isNotBlank() && b.cellId.isNotBlank() && a.cellId != b.cellId && b.t - a.t <= 10_000) {
                val before = s.subList(maxOf(0, i - 3), i).mapNotNull { it.level }
                val after = s.subList(i, minOf(s.size, i + 3)).mapNotNull { it.level }
                val fromL = before.takeIf { it.isNotEmpty() }?.average()?.toInt()
                val prev = hos.lastOrNull()
                val pp = prev != null && prev.from == b.label && prev.to == a.label && b.t - prev.t <= 10_000
                hos.add(Ho(b.t, a.label, b.label, fromL, after.takeIf { it.isNotEmpty() }?.average()?.toInt(),
                    pp, a.tech != b.tech, fromL != null && fromL < weak(a.tech), b.lat, b.lon))
            }
            i++
        }

        // ---- pilot pollution: good serving level but ≥3 same-tech cells within 6 dB ----
        val polluted = s.filter { x ->
            val lv = x.level ?: return@filter false
            lv >= -105 && x.nbrs.count { it.tech == x.tech && it.level != null && it.level >= lv - 6 } >= 3
        }
        val withNbr = s.count { it.nbrs.isNotEmpty() }
        val pollutedPct = if (withNbr == 0) 0 else polluted.size * 100 / withNbr

        // ---- PCI collision (neighbour with serving's PCI+ARFCN) & confusion (same PCI+ARFCN, different cells nearby) ----
        val issues = ArrayList<String>()
        s.filter { x -> x.pci != null && x.nbrs.any { it.tech == x.tech && it.pci == x.pci && it.arfcn == x.arfcn } }
            .distinctBy { it.cellId }.forEach { issues.add("Collision: cell ${it.label} (PCI ${it.pci}, ARFCN ${it.arfcn}) sees a neighbour with the same PCI") }
        s.filter { it.pci != null && it.arfcn != null && it.cellId.isNotBlank() && it.hasPos }
            .groupBy { "${it.tech}:${it.arfcn}:${it.pci}" }
            .forEach { (k, list) ->
                val cells = list.groupBy { it.cellId }
                if (cells.size < 2) return@forEach
                val centers = cells.map { (_, l) -> Triple(l.first().label, l.map { it.lat!! }.average(), l.map { it.lon!! }.average()) }
                val d = FloatArray(1)
                for (a in centers.indices) for (b in a + 1 until centers.size) {
                    Location.distanceBetween(centers[a].second, centers[a].third, centers[b].second, centers[b].third, d)
                    if (d[0] < 5000) issues.add("Confusion: ${centers[a].first} and ${centers[b].first} share ${k.substringAfter(':').replace(":", " / PCI ")} only ${(d[0] / 1000).let { String.format(Locale.US, "%.1f", it) }} km apart")
                }
            }

        // ---- bands ----
        val bands = s.filter { it.band != null }.groupBy { it.band!! }.map { (b, l) ->
            BandStat(b, l.size * 100 / s.size.coerceAtLeast(1), l.mapNotNull { it.level }.takeIf { it.isNotEmpty() }?.average()?.toInt(),
                l.mapNotNull { it.qual }.takeIf { it.isNotEmpty() }?.average()?.toInt(),
                l.mapNotNull { it.testDl }.takeIf { it.isNotEmpty() }?.average())
        }.sortedByDescending { it.share }

        // ---- throughput vs signal (10 dB buckets) ----
        val pairs = s.filter { it.testDl != null && it.level != null }.map { it.level!! to it.testDl!! }
        val buckets = pairs.groupBy { (lv, _) -> (lv / 10) * 10 }.toSortedMap(compareByDescending { it }).map { (b, l) ->
            Bucket("${b} … ${b - 9}", l.size, l.map { it.second }.average())
        }

        // ---- sector azimuths: power-weighted mean bearing from the site to where each cell serves ----
        val sectors = ArrayList<Sector>()
        s.filter { it.hasPos && it.level != null && it.cellId.isNotBlank() && it.tech != null }.groupBy { it.cellId }.forEach { (_, l) ->
            if (l.size < 10) return@forEach
            val x = l.first()
            val siteId = if (x.tech == "LTE" || x.tech == "NR") x.node else x.cellId
            val ocid = Monitor.dir.towerByKey("${x.tech}:${x.mcc}-${x.mnc}:${x.lac}:${x.cellId}")
            val est = SiteFinder.estimateByKey("${x.tech}_${x.mcc}-${x.mnc}_$siteId")
            val (sLat, sLon, estimated) = when {
                ocid != null -> Triple(ocid.lat, ocid.lon, false)
                est != null -> Triple(est.lat, est.lon, true)
                else -> return@forEach
            }
            var sx = 0.0; var sy = 0.0; var ws = 0.0
            val site = Location("s").apply { latitude = sLat; longitude = sLon }
            l.forEach { p ->
                val bearing = Math.toRadians(site.bearingTo(Location("p").apply { latitude = p.lat!!; longitude = p.lon!! }).toDouble())
                val w = Math.pow(10.0, (p.level!! + 140) / 20.0)
                sx += w * sin(bearing); sy += w * cos(bearing); ws += w
            }
            val az = ((Math.toDegrees(atan2(sx, sy)) + 360) % 360).toInt()
            val rlen = sqrt(sx * sx + sy * sy) / ws
            val spread = if (rlen >= 1) 0 else Math.toDegrees(sqrt(-2 * ln(rlen.coerceAtLeast(1e-6)))).toInt().coerceAtMost(180)
            sectors.add(Sector(x.label, if (x.tech == "LTE") "eNB ${x.node}" else if (x.tech == "NR") "gNB ${x.node}" else "CI ${x.cellId}",
                sLat, sLon, az, spread, l.size, estimated))
        }

        val order = s.filter { it.cellId.isNotBlank() }.groupingBy { it.label }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        return Result(s, hos, pollutedPct, polluted, issues.distinct(), bands, buckets, sectors, order)
    }

    /** Distinct colours for best-server maps. */
    val cellColors = intArrayOf(
        0xFF1E88E5.toInt(), 0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFFFDD835.toInt(), 0xFF8E24AA.toInt(),
        0xFF00ACC1.toInt(), 0xFFFB8C00.toInt(), 0xFF6D4C41.toInt(), 0xFFD81B60.toInt(), 0xFF7CB342.toInt(),
        0xFF3949AB.toInt(), 0xFF00897B.toInt()
    )
}
