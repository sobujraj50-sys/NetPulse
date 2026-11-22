package com.abusaeed.netpulse

import android.location.Location
import java.util.Locale

/** "Which operator is best here?" — ranks operators by your own measurements near you. */
class AreaActivity : BaseScreen() {
    override val titleRes = R.string.hub_area

    override fun buildContent() {
        val loc = Monitor.freshLoc()
        if (loc == null) { note(str(R.string.waiting_gps)); return }
        val radius = 500f
        val d = FloatArray(1)
        fun near(lat: Double?, lon: Double?, r: Float): Boolean {
            if (lat == null || lon == null) return false
            Location.distanceBetween(loc.latitude, loc.longitude, lat, lon, d)
            return d[0] <= r
        }
        val samples = HistoryStore.samples(0).filter { near(it.lat, it.lon, radius) }
        val speeds = SpeedTest.history(this).filter {
            it["connection"] == "Mobile" && near(it["lat"]?.toDoubleOrNull(), it["lon"]?.toDoubleOrNull(), 1000f)
        }
        note(getString(R.string.area_intro_fmt, samples.size, speeds.size))

        data class Rank(val op: String, val color: Int, val avg: Double?, val good: Int, val coverage: Int, val n: Int, val down: Double?)
        val ops = (samples.map { it.op } + speeds.mapNotNull { it["operator"] }).distinct()
        val ranks = ops.map { op ->
            val list = samples.filter { it.op == op }
            val lv = list.mapNotNull { it.level }
            val good = list.count { val w = Sig.wordRes(it.tech, it.level); w == R.string.q_excellent || w == R.string.q_good }
            val down = speeds.filter { it["operator"] == op }.mapNotNull { it["download_mbps"]?.toDoubleOrNull() }
            Rank(op, list.firstOrNull()?.let { Operators.fromCode(it.plmn)?.color } ?: p.text,
                lv.takeIf { it.isNotEmpty() }?.average(),
                if (list.isEmpty()) 0 else good * 100 / list.size,
                if (list.isEmpty()) 0 else lv.size * 100 / list.size,
                list.size, down.takeIf { it.isNotEmpty() }?.average())
        }.sortedWith(compareByDescending<Rank> { it.n >= 5 }.thenByDescending { it.avg ?: -999.0 }.thenByDescending { it.down ?: 0.0 })

        if (ranks.isEmpty()) { note(str(R.string.area_no_data)); return }
        ranks.forEachIndexed { i, r ->
            val c = card()
            val medal = when (i) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "•" }
            c.addView(tv("$medal  ${r.op}", 18f, r.color, true), mw())
            kvRow(c, str(R.string.hist_avg), r.avg?.let { "${it.toInt()} dBm" } ?: "--")
            kvRow(c, str(R.string.hist_good), "${r.good}%", Sig.GREEN)
            kvRow(c, str(R.string.area_coverage), "${r.coverage}%")
            kvRow(c, str(R.string.speed_down), r.down?.let { String.format(Locale.US, "%.1f Mbps", it) } ?: "--")
            kvRow(c, str(R.string.area_samples), r.n.toString())
            if (r.n < 5) c.addView(tv(str(R.string.area_low_data), 12f, Sig.ORANGE), mw())
        }
        note(str(R.string.area_note))
    }
}
