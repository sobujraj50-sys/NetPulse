package com.abusaeed.netpulse

import android.content.Context
import android.location.Location
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Long-term history while NetPulse is running (screen open, status-bar mode or logging):
 * - signal samples once a minute for every registered SIM
 * - no-service periods (outages)
 * - calls, with signal at start/end and a "possible drop" flag
 */
object HistoryStore {

    data class Sample(val t: Long, val plmn: String, val op: String, val tech: String?, val level: Int?, val lat: Double?, val lon: Double?)
    data class Outage(val start: Long, val end: Long, val op: String)
    data class Call(val start: Long, val end: Long, val op: String, val startLevel: Int?, val endLevel: Int?, val minLevel: Int?, val techs: String, val possibleDrop: Boolean)

    private const val KEEP_DAYS = 8
    private lateinit var dir: File
    private val exec = Executors.newSingleThreadExecutor()
    private var lastSample = 0L
    private var outageStart = 0L
    private var outageOp = ""

    // call tracking
    private var inCall = false
    val inCallNow: Boolean get() = inCall
    private var callStart = 0L
    private var callOp = ""
    private var callStartLevel: Int? = null
    private var callMin: Int? = null
    private val callTechs = LinkedHashSet<String>()
    private var callEndedAt = 0L
    private var pendingCall: Call? = null

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = File(ctx.applicationContext.filesDir, "history").apply { mkdirs() }
        exec.execute { prune() }
    }

    private fun f(name: String) = File(dir, name)

    private fun opName(c: CellData) = Operators.find(c.mcc, c.mnc)?.name ?: (c.plmn() ?: "?")

    /** Called on each measurement by the engine. */
    fun onSnapshot(s: Snapshot, loc: Location?) {
        if (!::dir.isInitialized) return
        val now = System.currentTimeMillis()

        // samples, once a minute, for every registered SIM
        if (now - lastSample >= 60_000) {
            lastSample = now
            val cells = listOfNotNull(s.serving) + s.otherRegistered
            val lines = StringBuilder()
            if (cells.isEmpty()) lines.append(line(now, s.operatorCode, Operators.fromCode(s.operatorCode)?.name ?: s.operatorName, null, null, loc))
            cells.forEach { c -> lines.append(line(now, c.plmn() ?: "", opName(c), c.tech, c.level, loc)) }
            val text = lines.toString()
            exec.execute { f("samples.csv").appendText(text) }
        }

        // outages of the selected SIM
        val c = s.serving
        val op = Monitor.opName(s)
        if (c == null && Monitor.locationEnabled()) {
            if (outageStart == 0L) { outageStart = now; outageOp = op }
        } else if (outageStart != 0L) {
            val dur = now - outageStart
            if (dur >= 10_000) {
                val l = "$outageStart,$now,${outageOp.replace(',', ' ')}\n"
                exec.execute { f("outages.csv").appendText(l) }
            }
            outageStart = 0L
        }

        // call quality tracking
        if (inCall && c != null) {
            c.level?.let { lv -> callMin = minOf(callMin ?: lv, lv) }
            callTechs.add(c.tech)
        }
        pendingCall?.let { pc ->
            // within 5 s after a call: no service = the call was likely dropped
            if (now - callEndedAt <= 5_000) {
                if (c == null && !pc.possibleDrop) pendingCall = pc.copy(possibleDrop = true)
            } else {
                saveCall(pc); pendingCall = null
            }
        }
    }

    private fun line(t: Long, plmn: String, op: String, tech: String?, level: Int?, loc: Location?) =
        String.format(Locale.US, "%d,%s,%s,%s,%s,%s,%s\n", t / 1000, plmn, op.replace(',', ' '), tech ?: "",
            level?.toString() ?: "", loc?.let { String.format(Locale.US, "%.5f", it.latitude) } ?: "",
            loc?.let { String.format(Locale.US, "%.5f", it.longitude) } ?: "")

    /** state: 0 idle, 1 ringing, 2 off-hook */
    fun onCallState(state: Int) {
        val s = Monitor.snap
        val c = s?.serving
        val now = System.currentTimeMillis()
        if (state == 2 && !inCall) {
            inCall = true; callStart = now
            callOp = s?.let { Monitor.opName(it) } ?: "?"
            callStartLevel = c?.level; callMin = c?.level
            callTechs.clear(); c?.tech?.let { callTechs.add(it) }
        } else if (state == 0 && inCall) {
            inCall = false
            val endLevel = c?.level
            val weakThr = if (c?.tech == "LTE" || c?.tech == "NR") -118 else -108
            val drop = c == null || (endLevel != null && endLevel < weakThr)
            callEndedAt = now
            pendingCall = Call(callStart, now, callOp, callStartLevel, endLevel, callMin, callTechs.joinToString(">"), drop)
        }
    }

    private fun saveCall(c: Call) {
        val l = String.format(Locale.US, "%d,%d,%s,%s,%s,%s,%s,%d\n", c.start, c.end, c.op.replace(',', ' '),
            c.startLevel?.toString() ?: "", c.endLevel?.toString() ?: "", c.minLevel?.toString() ?: "", c.techs, if (c.possibleDrop) 1 else 0)
        exec.execute { f("calls.csv").appendText(l) }
    }

    // ---------- reading ----------
    fun samples(sinceMs: Long): List<Sample> {
        if (!::dir.isInitialized) return emptyList()
        val file = f("samples.csv")
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { l ->
            val p = l.split(',')
            if (p.size < 7) return@mapNotNull null
            val t = (p[0].toLongOrNull() ?: return@mapNotNull null) * 1000
            if (t < sinceMs) null
            else Sample(t, p[1], p[2], p[3].ifBlank { null }, p[4].toIntOrNull(), p[5].toDoubleOrNull(), p[6].toDoubleOrNull())
        }
    }

    fun outages(sinceMs: Long): List<Outage> {
        if (!::dir.isInitialized) return emptyList()
        val file = f("outages.csv")
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { l ->
            val p = l.split(',')
            val s = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val e = p.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            if (e < sinceMs) null else Outage(s, e, p.getOrNull(2) ?: "?")
        }.sortedByDescending { it.start }
    }

    fun calls(): List<Call> {
        if (!::dir.isInitialized) return emptyList()
        val file = f("calls.csv")
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { l ->
            val p = l.split(',')
            if (p.size < 8) return@mapNotNull null
            Call(p[0].toLongOrNull() ?: return@mapNotNull null, p[1].toLongOrNull() ?: 0, p[2], p[3].toIntOrNull(),
                p[4].toIntOrNull(), p[5].toIntOrNull(), p[6], p[7] == "1")
        }.sortedByDescending { it.start }
    }

    fun clear() {
        if (!::dir.isInitialized) return
        exec.execute { dir.listFiles()?.forEach { it.delete() } }
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        val file = f("samples.csv")
        if (file.exists() && file.length() > 600_000) {
            val keep = file.readLines().filter { (it.substringBefore(',').toLongOrNull() ?: 0) * 1000 >= cutoff }
            file.writeText(keep.joinToString("\n", postfix = if (keep.isEmpty()) "" else "\n"))
        }
    }
}
