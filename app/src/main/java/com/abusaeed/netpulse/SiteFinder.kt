package com.abusaeed.netpulse

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Estimates where a cell tower (site) is from your own measurements, like CellMapper:
 * every GPS fix + signal level of the serving cell is stored per site (eNB / gNB),
 * then a log-distance path-loss model (RSRP = P0 - 10·n·log10(d)) is fitted with a
 * grid search. Timing Advance, when the phone reports it, adds a distance constraint.
 */
object SiteFinder {

    data class Est(
        val lat: Double, val lon: Double, val radiusM: Double, val n: Double,
        val count: Int, val spreadM: Double, val quality: Int, val address: String?
    )

    private data class S(val lat: Double, val lon: Double, val level: Int, val ta: Int?)

    private const val MIN_POINTS = 6
    private const val MAX_POINTS = 600
    private const val MIN_MOVE_M = 15f

    private lateinit var dir: File
    private lateinit var app: Context
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val counts = HashMap<String, Int>()
    private val lastPos = HashMap<String, Location>()
    private val sinceEstimate = HashMap<String, Int>()
    private val cache = HashMap<String, Est>()
    private var loaded = false

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        dir = File(app.filesDir, "sites").apply { mkdirs() }
    }

    /** Site = the tower: eNB/gNB for LTE/NR, the cell for 2G/3G. */
    fun siteKey(c: CellData): String? {
        val mcc = c.mcc ?: return null
        val mnc = c.mnc ?: return null
        val id = when (c.tech) {
            "LTE", "NR" -> c.node
            else -> c.cellId
        } ?: return null
        return "${c.tech}_$mcc-${mnc}_$id"
    }

    fun siteLabel(c: CellData): String = when (c.tech) {
        "LTE" -> "eNB ${c.node ?: "--"}"
        "NR" -> "gNB ${c.node ?: "--"}"
        else -> "CI ${c.cellId ?: "--"}"
    }

    private fun file(key: String) = File(dir, "$key.csv")
    private fun prefs() = app.getSharedPreferences("site_estimates", Context.MODE_PRIVATE)

    private fun loadCache() {
        if (loaded) return
        loaded = true
        prefs().all.forEach { (k, v) -> parseEst(v.toString())?.let { cache[k] = it } }
    }

    private fun parseEst(v: String): Est? {
        val p = v.split('|')
        if (p.size < 7) return null
        return Est(
            p[0].toDoubleOrNull() ?: return null, p[1].toDoubleOrNull() ?: return null,
            p[2].toDoubleOrNull() ?: 0.0, p[3].toDoubleOrNull() ?: 0.0, p[4].toIntOrNull() ?: 0,
            p[5].toDoubleOrNull() ?: 0.0, p[6].toIntOrNull() ?: 0, p.getOrNull(7)?.ifBlank { null }
        )
    }

    private fun saveEst(key: String, e: Est) {
        val v = String.format(
            Locale.US, "%.6f|%.6f|%.0f|%.2f|%d|%.0f|%d|%s",
            e.lat, e.lon, e.radiusM, e.n, e.count, e.spreadM, e.quality, (e.address ?: "").replace('|', '/')
        )
        prefs().edit().putString(key, v).apply()
    }

    fun estimate(c: CellData): Est? {
        if (!::app.isInitialized) return null
        loadCache()
        return siteKey(c)?.let { cache[it] }
    }

    fun estimateByKey(key: String): Est? {
        if (!::app.isInitialized) return null
        loadCache(); return cache[key]
    }

    /** All estimated sites: key -> estimate. */
    fun all(): Map<String, Est> {
        if (!::app.isInitialized) return emptyMap()
        loadCache(); return HashMap(cache)
    }

    fun points(c: CellData): Int {
        val k = siteKey(c) ?: return 0
        return counts.getOrPut(k) {
            val f = file(k)
            if (f.exists()) f.readLines().count { it.isNotBlank() } else 0
        }
    }

    fun clearAll() {
        dir.listFiles()?.forEach { it.delete() }
        prefs().edit().clear().apply()
        cache.clear(); counts.clear(); lastPos.clear(); sinceEstimate.clear()
    }

    /** Called on every measurement. Stores a point when GPS is good and we have moved. */
    fun record(s: Snapshot, loc: Location?, onUpdated: () -> Unit) {
        if (!::app.isInitialized) return
        val c = s.serving ?: return
        val lv = c.level ?: return
        if (loc == null || loc.provider != LocationManager.GPS_PROVIDER) return
        if (!loc.hasAccuracy() || loc.accuracy > 30f) return
        if (System.currentTimeMillis() - loc.time > 5000) return
        val key = siteKey(c) ?: return
        val prev = lastPos[key]
        if (prev != null && prev.distanceTo(loc) < MIN_MOVE_M) return
        lastPos[key] = Location(loc)

        val ta = if (c.tech == "LTE") c.ta else null
        val line = String.format(Locale.US, "%.6f,%.6f,%d,%s\n", loc.latitude, loc.longitude, lv, ta?.toString() ?: "")
        val count = points(c) + 1
        counts[key] = count
        val since = (sinceEstimate[key] ?: 0) + 1
        sinceEstimate[key] = since
        // re-estimate at 6 points, then every 5 new points (every 20 once well sampled)
        val due = count >= MIN_POINTS && (count == MIN_POINTS || since >= (if (count > 100) 20 else 5))
        if (due) sinceEstimate[key] = 0
        exec.execute {
            try {
                file(key).appendText(line)
                if (due) {
                    val samples = readSamples(key)
                    val prevEst = cache[key]
                    val e = solve(samples) ?: return@execute
                    // only geocode again if the estimate moved noticeably
                    val addr = if (prevEst != null && dist(prevEst.lat, prevEst.lon, e.lat, e.lon) < 100 && prevEst.address != null)
                        prevEst.address else Monitor.dir.addressFor(e.lat, e.lon)
                    val withAddr = e.copy(address = addr)
                    main.post {
                        cache[key] = withAddr
                        saveEst(key, withAddr)
                        onUpdated()
                    }
                }
            } catch (e: Exception) { }
        }
    }

    private fun readSamples(key: String): List<S> {
        val f = file(key)
        if (!f.exists()) return emptyList()
        val all = f.readLines().mapNotNull { l ->
            val p = l.split(',')
            if (p.size < 3) null else {
                val lat = p[0].toDoubleOrNull(); val lon = p[1].toDoubleOrNull(); val lv = p[2].toIntOrNull()
                if (lat == null || lon == null || lv == null) null else S(lat, lon, lv, p.getOrNull(3)?.toIntOrNull())
            }
        }
        if (all.size <= MAX_POINTS) return all
        // keep an even spread of the history
        val step = all.size.toDouble() / MAX_POINTS
        return (0 until MAX_POINTS).map { all[(it * step).toInt()] }
    }

    private fun dist(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Float {
        val d = FloatArray(1)
        Location.distanceBetween(aLat, aLon, bLat, bLon, d)
        return d[0]
    }

    // ---------- the estimator ----------
    private fun solve(samples: List<S>): Est? {
        if (samples.size < MIN_POINTS) return null
        val lat0 = samples.map { it.lat }.average()
        val lon0 = samples.map { it.lon }.average()
        val kx = cos(Math.toRadians(lat0)) * 111_320.0
        val ky = 110_540.0
        val xs = DoubleArray(samples.size) { (samples[it].lon - lon0) * kx }
        val ys = DoubleArray(samples.size) { (samples[it].lat - lat0) * ky }
        val lv = DoubleArray(samples.size) { samples[it].level.toDouble() }
        val ta = samples.map { it.ta }

        // spread of the measurements
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val spread = sqrt((maxX - minX).pow(2) + (maxY - minY).pow(2))
        if (spread < 40) return null

        // start: power-weighted centroid (strong points pull harder)
        val maxLv = lv.max()
        val w = DoubleArray(samples.size) { 10.0.pow((lv[it] - maxLv) / 10.0) }
        val ws = w.sum()
        val cx0 = xs.indices.sumOf { xs[it] * w[it] } / ws
        val cy0 = ys.indices.sumOf { ys[it] * w[it] } / ws

        fun cost(cx: Double, cy: Double): DoubleArray {
            // regression of level vs log10(distance); returns [sse, n, a]
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
            val lx = DoubleArray(xs.size)
            for (i in xs.indices) {
                val d = max(sqrt((xs[i] - cx).pow(2) + (ys[i] - cy).pow(2)), 20.0)
                val x = log10(d)
                lx[i] = x
                sx += x; sy += lv[i]; sxx += x * x; sxy += x * lv[i]
            }
            val nPts = xs.size.toDouble()
            val den = nPts * sxx - sx * sx
            var b = if (abs(den) < 1e-9) -30.0 else (nPts * sxy - sx * sy) / den
            b = b.coerceIn(-50.0, -18.0) // path-loss exponent n between 1.8 and 5
            val a = (sy - b * sx) / nPts
            var sse = 0.0
            for (i in xs.indices) {
                val r = lv[i] - (a + b * lx[i])
                sse += r * r
                val t = ta[i]
                if (t != null) {
                    val dTa = (t + 0.5) * 78.12
                    val d = 10.0.pow(lx[i])
                    val e = (d - dTa) / 78.12
                    sse += 9.0 * e * e // one TA step (~78 m) ≈ 3 dB of error
                }
            }
            return doubleArrayOf(sse, -b / 10.0, a)
        }

        // coarse grid, then a fine grid around the best point
        val half = (2.0 * spread).coerceIn(1500.0, 8000.0)
        var best = doubleArrayOf(cx0, cy0)
        var bestC = cost(cx0, cy0)
        var step = half / 30
        for (pass in 0 until 2) {
            val gx = best[0]; val gy = best[1]
            val span = if (pass == 0) half else step * 3
            val st = if (pass == 0) step else step / 8
            var x = gx - span
            while (x <= gx + span) {
                var y = gy - span
                while (y <= gy + span) {
                    val c = cost(x, y)
                    if (c[0] < bestC[0]) { bestC = c; best = doubleArrayOf(x, y) }
                    y += st
                }
                x += st
            }
            step = st
        }

        // angular coverage around a point: a big gap = measured from one side only
        fun maxGap(px: Double, py: Double): Double {
            val br = xs.indices.map { (Math.toDegrees(atan2(ys[it] - py, xs[it] - px)) + 360) % 360 }.sorted()
            var g = 360.0 - br.last() + br.first()
            for (i in 1 until br.size) g = max(g, br[i] - br[i - 1])
            return g
        }

        var bx = best[0]; var by = best[1]
        var gap = maxGap(bx, by)
        val oneSided = gap > 190
        if (oneSided) {
            // e.g. only one straight road: the side of the road can't be known, so the
            // fitted point is unreliable; the power-weighted centroid is safer
            bx = cx0; by = cy0
            gap = maxGap(bx, by)
        }
        val n = bestC[1]
        val rms = sqrt(bestC[0] / samples.size)
        val dists = xs.indices.map { sqrt((xs[it] - bx).pow(2) + (ys[it] - by).pow(2)) }.sorted()
        val medD = dists[dists.size / 2]

        // distance error from level error: δd/d = ln(10)/(10n)·δP
        var radius = (ln(10.0) / (10 * n)) * rms * medD + step
        if (oneSided) radius = max(radius * 2.0, medD * 0.6)
        radius = radius.coerceIn(30.0, 5000.0)

        val quality = when {
            oneSided -> 0
            samples.size >= 30 && gap < 150 && radius < 300 -> 2
            samples.size >= 12 && radius < 800 -> 1
            else -> 0
        }
        return Est(
            lat0 + by / ky, lon0 + bx / kx, radius, n, samples.size,
            spread, quality, null
        )
    }

    fun qualityRes(q: Int) = when (q) {
        2 -> R.string.est_q_high
        1 -> R.string.est_q_med
        else -> R.string.est_q_low
    }

    @Suppress("unused")
    private fun clampLat(v: Double) = min(90.0, max(-90.0, v))
}
