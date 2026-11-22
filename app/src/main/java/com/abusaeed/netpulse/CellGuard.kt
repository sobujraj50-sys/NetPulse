package com.abusaeed.netpulse

import android.content.Context
import android.location.Location
import android.os.SystemClock
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Fake base station (IMSI-catcher / "stingray") warning signs. These are heuristics:
 * they flag unusual behaviour, they cannot prove an attack.
 *  - forced downgrade to 2G where 4G/5G was just available
 *  - a never-seen cell with an unusually strong signal
 *  - location/tracking area changes while you are standing still
 *  - a cell that lists no neighbours
 *  - invalid identifiers (CID 0/65535, PCI out of range, wrong network for the SIM)
 */
object CellGuard {

    data class Event(val t: Long, val sev: Int, val code: String, val cell: String, val detail: String, val lat: Double?, val lon: Double?)

    private lateinit var app: Context
    private lateinit var file: File
    private val exec = Executors.newSingleThreadExecutor()
    private var lastGood4gAt = 0L
    private var lastGood4gLoc: Location? = null
    private var lastKey: String? = null
    private var lastTac: Int? = null
    private var lastLoc: Location? = null
    private var lastLevel: Int? = null
    private var lastLevelAt = 0L
    private var noNbrSince = 0L
    private val recentCodes = HashMap<String, Long>()

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        file = File(app.filesDir, "security.csv")
    }

    private fun known() = app.getSharedPreferences("known_cells", Context.MODE_PRIVATE)

    fun knownCount(): Int = if (::app.isInitialized) known().all.size else 0

    private fun key(c: CellData) = "${c.tech}:${c.mcc}-${c.mnc}:${c.lac}:${c.cellId}"

    fun check(s: Snapshot, loc: Location?, inCall: Boolean) {
        if (!::app.isInitialized) return
        val c = s.serving ?: return
        val now = System.currentTimeMillis()
        val k = key(c)
        val lv = c.level
        val label = Monitor.dir.name(c) ?: Monitor.cellIdText(c)
        val prevLoc = lastLoc
        val stationary = loc != null && prevLoc != null && loc.distanceTo(prevLoc) < 50 && (!loc.hasSpeed() || loc.speed < 1f)

        // remember good 4G/5G coverage
        if ((c.tech == "LTE" || c.tech == "NR") && lv != null && lv >= -110) {
            lastGood4gAt = now; lastGood4gLoc = loc
        }

        val isNew = k != lastKey
        if (isNew) {
            val prefs = known()
            val seen = prefs.getString(k, null)
            val count = seen?.substringAfter('|')?.toIntOrNull() ?: 0

            // 1) forced 2G downgrade
            if (c.tech == "GSM" && !inCall && now - lastGood4gAt < 120_000) {
                val good = lastGood4gLoc
                val near = loc == null || good == null || loc.distanceTo(good) < 300
                if (near) add(2, "DOWNGRADE_2G", label, app.getString(R.string.sec_ev_downgrade), loc)
            }
            // 2) never-seen cell with an unusually strong signal
            val strong = if (c.tech == "GSM") -55 else -65
            if (count == 0 && lv != null && lv >= strong && knownCount() > 30) {
                add(1, "STRONG_NEW", label, app.getString(R.string.sec_ev_strong_new, lv), loc)
            }
            // 3) area code changed while standing still, onto an unknown cell
            if (stationary && lastTac != null && c.lac != null && c.lac != lastTac && count == 0) {
                add(1, "LAC_STILL", label, app.getString(R.string.sec_ev_lac, lastTac.toString(), c.lac.toString()), loc)
            }
            // 4) sudden big jump in level while standing still
            val pl = lastLevel
            if (stationary && pl != null && lv != null && lv - pl >= 25 && SystemClock.elapsedRealtime() - lastLevelAt < 3000) {
                add(0, "JUMP", label, app.getString(R.string.sec_ev_jump, pl, lv), loc)
            }
            // remember this cell
            val first = seen?.substringBefore('|') ?: (now / 1000).toString()
            prefs.edit().putString(k, "$first|${count + 1}").apply()
            lastKey = k
        }

        // 5) invalid identifiers
        val badCid = c.cellId == 0L || (c.tech == "GSM" && c.cellId == 65535L)
        val badPci = (c.tech == "LTE" && (c.pci ?: 0) > 503) || (c.tech == "NR" && (c.pci ?: 0) > 1007)
        if (badCid || badPci) add(1, "INVALID_ID", label, app.getString(R.string.sec_ev_invalid), loc)
        val simPlmn = try { Monitor.reader.tm.simOperator ?: "" } catch (e: Exception) { "" }
        val roaming = try { Monitor.reader.tm.isNetworkRoaming } catch (e: Exception) { false }
        val cellPlmn = c.plmn()
        if (!roaming && simPlmn.length >= 5 && cellPlmn != null && cellPlmn != simPlmn &&
            Operators.fromCode(simPlmn)?.name != Operators.fromCode(cellPlmn)?.name // Robi/Airtel share a network
        ) add(1, "WRONG_NETWORK", label, app.getString(R.string.sec_ev_wrong_net, cellPlmn, simPlmn), loc)

        // 6) no neighbour cells for a long time with a strong signal
        if (s.neighbors.isEmpty() && lv != null && lv > -90) {
            if (noNbrSince == 0L) noNbrSince = now
            else if (now - noNbrSince > 120_000) { add(0, "NO_NEIGHBOURS", label, app.getString(R.string.sec_ev_no_nbr), loc); noNbrSince = now }
        } else noNbrSince = 0L

        lastTac = c.lac
        lastLoc = loc?.let { Location(it) }
        lastLevel = lv
        lastLevelAt = SystemClock.elapsedRealtime()
    }

    private fun add(sev: Int, code: String, cell: String, detail: String, loc: Location?) {
        val now = System.currentTimeMillis()
        if (now - (recentCodes["$code|$cell"] ?: 0L) < 600_000) return // same warning at most every 10 min
        recentCodes["$code|$cell"] = now
        val line = String.format(Locale.US, "%d,%d,%s,%s,%s,%s,%s\n", now, sev, code, cell.replace(',', ' '), detail.replace(',', ';'),
            loc?.latitude?.toString() ?: "", loc?.longitude?.toString() ?: "")
        exec.execute { file.appendText(line) }
        if (sev >= 1 && Prefs.secAlerts) {
            Alerts.fire(app, "sec_$code", app.getString(R.string.sec_alert_title), "$cell: $detail")
        }
    }

    fun events(): List<Event> {
        if (!::app.isInitialized || !file.exists()) return emptyList()
        return file.readLines().mapNotNull { l ->
            val p = l.split(',')
            if (p.size < 7) null else Event(p[0].toLongOrNull() ?: 0, p[1].toIntOrNull() ?: 0, p[2], p[3], p[4], p[5].toDoubleOrNull(), p[6].toDoubleOrNull())
        }.sortedByDescending { it.t }
    }

    fun clear() {
        if (!::app.isInitialized) return
        exec.execute { file.delete() }
    }
}
