package com.abusaeed.netpulse

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.TrafficStats
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The measurement engine. Runs while the screen is open OR the logging
 * service is running, so drive tests continue with the screen off.
 */
object Monitor {

    data class EventRow(
        val time: String, val event: String, val tech: String, val cell: String,
        val pci: String, val arfcn: String, val level: String, val qual: String, val color: Int
    )

    private lateinit var app: Context
    lateinit var reader: CellReader
        private set
    lateinit var dir: CellDirectory
        private set
    lateinit var logger: DriveLogger
        private set
    lateinit var net: NetStateWatcher
        private set

    var snap: Snapshot? = null
        private set
    private var lastLoc: Location? = null
    var dlKbps = 0.0
        private set
    var ulKbps = 0.0
        private set
    val events = ArrayList<EventRow>()
    val history = ArrayList<Pair<Int?, String?>>()
    val track = ArrayList<TrackPoint>()
    var servingSince = SystemClock.elapsedRealtime()
        private set

    private val ui = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    private var users = 0
    private var ticking = false
    private var lm: LocationManager? = null
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastTrafficAt = 0L
    private var lastServingKey: String? = null
    private var lastTech: String? = null
    const val HISTORY_MAX = 120

    val logging: Boolean get() = ::logger.isInitialized && logger.active

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        Prefs.init(app)
        reader = CellReader(app)
        dir = CellDirectory(app)
        logger = DriveLogger(app)
        net = NetStateWatcher(app)
    }

    // ---------- listeners / lifecycle ----------
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyListeners() { listeners.toList().forEach { try { it() } catch (e: Exception) { } } }

    /** Called by the screen (onStart) and by the logging service. */
    fun acquire() {
        users++
        if (users == 1) onPermissionsChanged()
        startTick()
    }

    fun release() {
        users = maxOf(0, users - 1)
        if (users == 0) stopTick()
    }

    fun hasPhonePerms(): Boolean = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE
    ).all { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED }

    fun hasLocPerm(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Re-reads SIMs and (re)starts location + network watchers. */
    fun onPermissionsChanged() {
        reader.loadSubs()
        net.attach(reader.tm)
        startLocation()
    }

    fun switchSim() {
        reader.nextSub()
        net.attach(reader.tm)
        history.clear(); events.clear(); lastServingKey = null
        update()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            try { update() } catch (e: Exception) { }
            ui.postDelayed(this, Prefs.intervalSec.coerceIn(1, 10) * 1000L)
        }
    }

    private fun startTick() {
        if (ticking) return
        ticking = true
        ui.post(tick)
    }

    private fun stopTick() {
        ticking = false
        ui.removeCallbacks(tick)
        try { lm?.removeUpdates(locListener) } catch (e: Exception) { }
        lm = null
        net.detach()
    }

    // ---------- logging ----------
    fun startLogging(): Boolean = try {
        logger.start()
        track.clear()
        notifyListeners()
        true
    } catch (e: Exception) {
        false
    }

    fun stopLogging() {
        logger.stop()
        notifyListeners()
    }

    // ---------- location ----------
    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val prev = lastLoc
            if (location.provider == LocationManager.NETWORK_PROVIDER && prev != null &&
                prev.provider == LocationManager.GPS_PROVIDER && location.time - prev.time < 10_000
            ) return
            lastLoc = location
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!hasLocPerm() || lm != null) return
        val m = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = m
        try {
            if (m.allProviders.contains(LocationManager.GPS_PROVIDER))
                m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper())
            if (m.allProviders.contains(LocationManager.NETWORK_PROVIDER))
                m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, locListener, Looper.getMainLooper())
            if (lastLoc == null) lastLoc = m.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: m.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) { }
    }

    fun freshLoc(): Location? = lastLoc?.takeIf { System.currentTimeMillis() - it.time < 15_000 }

    fun locationEnabled(): Boolean {
        val m = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= 28) m.isLocationEnabled
        else m.isProviderEnabled(LocationManager.GPS_PROVIDER) || m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    // ---------- main update ----------
    fun update() {
        if (!::app.isInitialized) return
        reader.requestUpdate()
        val s = reader.snapshot()
        snap = s
        net.poll()
        updateTraffic()
        trackEvents(s)
        history.add(s.serving?.level to s.serving?.tech)
        if (history.size > HISTORY_MAX) history.removeAt(0)
        addTrackPoint(s)
        if (hasPhonePerms()) Alerts.check(app, s)
        s.serving?.let { c -> dir.lookup(c) { notifyListeners() } }
        dir.updateMyAddress(freshLoc()) { notifyListeners() }
        if (logger.active) {
            val c = s.serving
            logger.log(
                s, freshLoc(), reader.simLabel(), dlKbps, ulKbps,
                c?.let { dir.name(it) }, c?.let { dir.tower(it)?.address },
                net.label(c?.tech), net.ccBandwidthsKhz.size
            )
        }
        notifyListeners()
    }

    private fun updateTraffic() {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val now = SystemClock.elapsedRealtime()
        if (lastTrafficAt > 0 && rx >= 0 && tx >= 0 && now > lastTrafficAt) {
            val dt = (now - lastTrafficAt) / 1000.0
            dlKbps = ((rx - lastRx) * 8 / 1000.0 / dt).coerceAtLeast(0.0)
            ulKbps = ((tx - lastTx) * 8 / 1000.0 / dt).coerceAtLeast(0.0)
        }
        lastRx = rx; lastTx = tx; lastTrafficAt = now
    }

    fun cellIdText(c: CellData): String =
        if (c.node != null && c.localCid != null) "${c.node}-${c.localCid}" else (c.cellId?.toString() ?: "--")

    private fun trackEvents(s: Snapshot) {
        val c = s.serving
        val key = c?.let { "${it.tech}|${it.cellId}|${it.pci}|${it.arfcn}" } ?: "NONE"
        if (key == lastServingKey) return
        val ev = when {
            lastServingKey == null -> "START"
            c == null -> "NO SVC"
            lastServingKey == "NONE" -> "SVC"
            lastTech != null && lastTech != c?.tech -> "IRAT"
            else -> "HO"
        }
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        events.add(
            0, EventRow(
                time, ev, c?.tech ?: "--", c?.let { cellIdText(it) } ?: "--",
                c?.pci?.toString() ?: "--", c?.arfcn?.toString() ?: "--",
                c?.level?.toString() ?: "--", c?.qual?.toString() ?: "--",
                Sig.color(c?.tech, c?.level)
            )
        )
        if (events.size > 60) events.removeAt(events.size - 1)
        lastServingKey = key
        lastTech = c?.tech
        servingSince = SystemClock.elapsedRealtime()
    }

    private fun addTrackPoint(s: Snapshot) {
        val loc = freshLoc() ?: return
        val last = track.lastOrNull()
        if (last != null) {
            val d = FloatArray(1)
            Location.distanceBetween(last.lat, last.lon, loc.latitude, loc.longitude, d)
            if (d[0] < 5f) return
        }
        track.add(TrackPoint(loc.latitude, loc.longitude, Sig.color(s.serving?.tech, s.serving?.level)))
        if (track.size > 5000) track.removeAt(0)
    }

    /** Known BD operator name; falls back to what the network broadcasts. */
    fun opName(s: Snapshot): String {
        val c = s.serving
        val known = (Operators.find(c?.mcc, c?.mnc) ?: Operators.fromCode(s.operatorCode))?.name
        val netName = s.operatorName.trim()
        return when {
            known == null -> netName.ifBlank { "--" }
            netName.isBlank() || netName.equals(known, ignoreCase = true) -> known
            else -> "$known / $netName"
        }
    }
}
