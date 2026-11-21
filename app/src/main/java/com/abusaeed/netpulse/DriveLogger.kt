package com.abusaeed.netpulse

import android.content.Context
import android.location.Location
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes one CSV row per second while a drive test is running. */
class DriveLogger(private val ctx: Context) {
    var file: File? = null
        private set
    private var writer: BufferedWriter? = null
    var samples = 0
        private set
    var startMs = 0L
        private set
    var distanceM = 0.0
        private set
    private var prevLoc: Location? = null
    var minLevel: Int? = null
        private set
    var maxLevel: Int? = null
        private set
    private var sumLevel = 0L
    private var cntLevel = 0
    val avgLevel: Int? get() = if (cntLevel > 0) (sumLevel / cntLevel).toInt() else null
    val active: Boolean get() = writer != null

    fun dir(): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "logs").apply { mkdirs() }

    fun files(): List<File> =
        dir().listFiles()?.filter { it.name.endsWith(".csv") }?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun start() {
        if (active) return
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir(), "NetPulse_$ts.csv")
        file = f
        writer = f.bufferedWriter().also {
            it.write(
                "time,sim,operator,tech,mcc,mnc,lac_tac,cell_id,node,local_cid,pci_psc,arfcn,band," +
                    "dl_mhz,level_dbm,qual_db,snr_db,rssi_dbm,ta,lat,lon,speed_kmh,alt_m,acc_m," +
                    "dl_kbps,ul_kbps,neighbors,cell_name,tower_address,network,ca_carriers\n"
            )
        }
        samples = 0; startMs = System.currentTimeMillis(); distanceM = 0.0; prevLoc = null
        minLevel = null; maxLevel = null; sumLevel = 0; cntLevel = 0
    }

    fun stop() {
        try { writer?.flush(); writer?.close() } catch (e: Exception) { }
        writer = null
    }

    private fun q(s: String?): String {
        if (s == null) return ""
        return if (s.contains(',') || s.contains('"')) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    private fun n(v: Any?): String = when (v) {
        null -> ""
        is Double -> String.format(Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
        is Float -> String.format(Locale.US, "%.2f", v)
        else -> v.toString()
    }

    fun log(
        snap: Snapshot, loc: Location?, sim: String, dlKbps: Double, ulKbps: Double,
        cellName: String?, towerAddress: String?, netLabel: String, ccCount: Int
    ) {
        val w = writer ?: return
        val c = snap.serving
        if (loc != null) {
            prevLoc?.let { distanceM += it.distanceTo(loc) }
            prevLoc = loc
        }
        c?.level?.let {
            minLevel = minOf(minLevel ?: it, it)
            maxLevel = maxOf(maxLevel ?: it, it)
            sumLevel += it; cntLevel++
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val row = listOf(
            time, sim, q(Operators.find(c?.mcc, c?.mnc)?.name ?: snap.operatorName), c?.tech, c?.mcc, c?.mnc, n(c?.lac), n(c?.cellId), n(c?.node),
            n(c?.localCid), n(c?.pci), n(c?.arfcn), q(c?.band), n(c?.dl), n(c?.level), n(c?.qual), n(c?.snr),
            n(c?.rssi), n(c?.ta), n(loc?.latitude), n(loc?.longitude),
            loc?.let { if (it.hasSpeed()) n(it.speed * 3.6f) else "" },
            loc?.let { if (it.hasAltitude()) n(it.altitude) else "" },
            loc?.let { if (it.hasAccuracy()) n(it.accuracy) else "" },
            String.format(Locale.US, "%.1f", dlKbps), String.format(Locale.US, "%.1f", ulKbps),
            snap.neighbors.size.toString(), q(cellName), q(towerAddress), q(netLabel), ccCount.toString()
        ).joinToString(",") { it ?: "" }
        try {
            w.write(row); w.write("\n")
            samples++
            if (samples % 10 == 0) w.flush()
        } catch (e: Exception) {
            stop()
        }
    }
}
