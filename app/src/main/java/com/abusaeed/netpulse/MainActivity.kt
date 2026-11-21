package com.abusaeed.netpulse

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var p: Palette
    private val pages = arrayOfNulls<View>(6)
    private val tabButtons = ArrayList<TextView>()
    private var currentTab = 0

    // CELL
    private val fields = HashMap<String, TextView>()
    private lateinit var operatorTv: TextView
    private lateinit var netBadge: TextView
    private lateinit var hintTv: TextView
    private lateinit var graph: SignalGraphView
    private lateinit var servingTimeTv: TextView
    private lateinit var eventTable: LinearLayout
    private lateinit var simBtn: TextView
    private lateinit var nameTv: TextView
    private lateinit var towerAddrTv: TextView
    private lateinit var towerTv: TextView
    private lateinit var myAddrTv: TextView
    private var shownEventsTop: Monitor.EventRow? = null
    private var shownEventsCount = -1

    // NEI / INFO
    private lateinit var neiTable: LinearLayout
    private lateinit var neiCount: TextView
    private lateinit var infoTable: LinearLayout

    // MAP
    private var map: MapView? = null
    private lateinit var trackOverlay: TrackOverlay
    private lateinit var mapInfo: TextView
    private var follow = true
    private var mapStyleShown = -1

    // DRIVE
    private lateinit var driveBtn: TextView
    private lateinit var driveStatus: TextView
    private lateinit var logList: LinearLayout
    private var lastLoggingState: Boolean? = null
    private var pendingStartLogging = false
    private var permDenied = false

    private val perms = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE
    )
    private val reqPhone = 7
    private val reqNotif = 8

    private val listener: () -> Unit = { renderCurrent() }

    // ================= lifecycle =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Monitor.init(this)
        p = Palette(isDark())
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContentView(buildUi())
        selectTab(savedInstanceState?.getInt("tab") ?: 0)
        if (!Monitor.hasPhonePerms()) {
            if (!Prefs.onboarded) showPermissionIntro() else requestPerms()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", currentTab)
    }

    override fun onStart() {
        super.onStart()
        Monitor.acquire()
        Monitor.addListener(listener)
        renderCurrent()
    }

    override fun onDestroy() {
        speedTest?.cancel()
        super.onDestroy()
    }

    override fun onStop() {
        Monitor.removeListener(listener)
        Monitor.release()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        map?.onResume()
        applyMapStyle()
        renderCurrent()
    }

    override fun onPause() {
        super.onPause()
        map?.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqPhone) {
            permDenied = grantResults.any { it != PackageManager.PERMISSION_GRANTED }
            Monitor.onPermissionsChanged()
            Monitor.update()
        } else if (requestCode == reqNotif && pendingStartLogging) {
            startLoggingNow()
        }
    }

    private fun isDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun requestPerms() = ActivityCompat.requestPermissions(this, perms, reqPhone)

    private fun showPermissionIntro() {
        AlertDialog.Builder(this)
            .setTitle(R.string.perm_title)
            .setMessage(R.string.perm_msg)
            .setCancelable(false)
            .setPositiveButton(R.string.continue_) { _, _ -> Prefs.onboarded = true; requestPerms() }
            .setNegativeButton(R.string.not_now) { _, _ -> Prefs.onboarded = true }
            .show()
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    // ================= helpers =================

    private fun str(id: Int) = getString(id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun Any?.s(): String = this?.toString() ?: "--"
    private fun f1(d: Double?) = d?.let { String.format(Locale.US, "%.1f", it) } ?: "--"

    private fun tv(
        text: CharSequence = "", size: Float = 15f, color: Int = p.text,
        bold: Boolean = false, gravity: Int = Gravity.START
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        this.gravity = gravity
    }

    private fun kv(label: String, value: String, valueColor: Int = p.text): CharSequence {
        val sb = SpannableStringBuilder()
        sb.append("$label: ", ForegroundColorSpan(p.label), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(value, ForegroundColorSpan(valueColor), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun setKv(key: String, label: String, value: String, color: Int = p.text) {
        fields[key]?.text = kv(label, value, color)
    }

    private fun vbox(bg: Int? = null): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        bg?.let { setBackgroundColor(it) }
    }

    private fun mw() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun kvRow(vararg keys: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }
        keys.forEachIndexed { i, k ->
            val g = when {
                keys.size == 1 -> Gravity.START
                i == 0 -> Gravity.START
                i == keys.size - 1 -> Gravity.END
                else -> Gravity.CENTER_HORIZONTAL
            }
            val t = tv(kv(k.uppercase(Locale.US), "--"), 15f, gravity = g)
            t.setSingleLine(true)
            t.ellipsize = android.text.TextUtils.TruncateAt.END
            t.setAutoSizeTextTypeUniformWithConfiguration(10, 15, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            fields[k] = t
            row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return row
    }

    private fun tableRow(
        cells: List<String>, weights: List<Float>, bg: Int,
        textColor: Int = p.text, colorAt: Map<Int, Int> = emptyMap(), size: Float = 12f
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(p.border)
        }
        cells.forEachIndexed { i, text ->
            val t = tv(text, size, colorAt[i] ?: textColor, gravity = Gravity.CENTER).apply {
                setBackgroundColor(bg)
                setPadding(dp(2), dp(4), dp(2), dp(4))
                maxLines = 1
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weights[i])
            lp.setMargins(1, 1, 0, 0)
            row.addView(t, lp)
        }
        return row
    }

    private fun button(text: String, bg: Int, onClick: () -> Unit): TextView =
        tv(text, 16f, Color.WHITE, true, Gravity.CENTER).apply {
            background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(12).toFloat() }
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { onClick() }
        }

    private fun pill(text: String, bg: Int): TextView = tv(text, 13f, Color.WHITE, true, Gravity.CENTER).apply {
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(14).toFloat() }
        setPadding(dp(10), dp(5), dp(10), dp(5))
    }

    // ================= UI building =================

    private fun buildUi(): View {
        val root = vbox(p.header)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // top bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
        }
        top.addView(tv(str(R.string.app_name), 22f, 0xFF00E676.toInt(), true))
        netBadge = pill("--", 0xFF00695C.toInt())
        top.addView(netBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        simBtn = pill(Monitor.reader.simLabel() + " ⇄", 0xFF333333.toInt()).apply {
            setOnClickListener {
                if (Monitor.reader.subs.size < 2) {
                    Toast.makeText(this@MainActivity, R.string.only_one_sim, Toast.LENGTH_SHORT).show()
                } else {
                    Monitor.switchSim()
                    shownEventsCount = -1
                    Toast.makeText(this@MainActivity, "${Monitor.reader.simLabel()}: ${Monitor.reader.simName()}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        top.addView(simBtn)
        val gear = tv("⚙", 24f, Color.WHITE, gravity = Gravity.CENTER).apply {
            setPadding(dp(12), 0, dp(6), 0)
            contentDescription = str(R.string.settings)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        top.addView(gear)
        root.addView(top, mw())

        // pages
        val frame = FrameLayout(this).apply { setBackgroundColor(p.bg) }
        pages[0] = buildCellPage()
        pages[1] = buildNeiPage()
        pages[2] = buildMapPage()
        pages[3] = buildSpeedPage()
        pages[4] = buildInfoPage()
        pages[5] = buildDrivePage()
        pages.forEach { frame.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // bottom tabs
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(R.string.tab_cell, R.string.tab_nei, R.string.tab_map, R.string.tab_speed, R.string.tab_info, R.string.tab_drive).forEachIndexed { i, res ->
            val t = tv(str(res), 13f, Color.WHITE, false, Gravity.CENTER).apply {
                setPadding(0, dp(14), 0, dp(14))
                setSingleLine(true)
                setAutoSizeTextTypeUniformWithConfiguration(9, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                maxLines = 1
                setOnClickListener { selectTab(i) }
            }
            tabButtons.add(t)
            tabs.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tabs, mw())
        return root
    }

    private fun selectTab(i: Int) {
        currentTab = i
        pages.forEachIndexed { idx, v -> v?.visibility = if (idx == i) View.VISIBLE else View.GONE }
        tabButtons.forEachIndexed { idx, t ->
            t.setBackgroundColor(if (idx == i) 0xFF3D3D3D.toInt() else p.header)
            t.setTextColor(if (idx == i) 0xFF00E676.toInt() else 0xFFAAAAAA.toInt())
        }
        if (i == 5) renderLogList()
        if (i == 3) renderSpeedHistory()
        renderCurrent()
    }

    private fun renderCurrent() {
        if (!::driveBtn.isInitialized) return
        val snap = Monitor.snap
        val logging = Monitor.logging
        if (logging != lastLoggingState) {
            lastLoggingState = logging
            if (logging && Prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            renderLogList()
        }
        netBadge.text = Monitor.net.label(snap?.serving?.tech)
        simBtn.text = Monitor.reader.simLabel() + " ⇄"
        if (snap == null) {
            updateHint(null)
            return
        }
        when (currentTab) {
            0 -> renderCell(snap)
            1 -> renderNei(snap)
            2 -> renderMap(snap)
            3 -> renderSpeedContext(snap)
            4 -> renderInfo(snap)
            5 -> renderDrive()
        }
    }

    // ---------- CELL ----------
    private val evW = listOf(1.5f, 1.1f, 1.1f, 1.7f, 0.8f, 1.1f, 1.0f, 0.9f)

    private fun buildCellPage(): View {
        val wide = resources.configuration.screenWidthDp >= 600
        val col = vbox()
        operatorTv = tv("--", 20f, p.text, true, Gravity.CENTER).apply {
            setBackgroundColor(p.panel2)
            setPadding(0, dp(6), 0, dp(6))
        }
        col.addView(operatorTv, mw())
        hintTv = tv("", 13f, 0xFFE53935.toInt(), gravity = Gravity.CENTER).apply {
            visibility = View.GONE
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                when {
                    !Monitor.hasPhonePerms() ->
                        if (permDenied && !ActivityCompat.shouldShowRequestPermissionRationale(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION))
                            openAppSettings() else requestPerms()
                    !Monitor.locationEnabled() -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
        }
        col.addView(hintTv, mw())

        // name & address card
        val s0 = vbox(p.cardName).apply { setPadding(dp(10), dp(6), dp(10), dp(6)) }
        nameTv = tv(kv(str(R.string.cell_name), "--"), 16f, p.text, true).apply {
            setPadding(0, dp(2), 0, dp(4))
            setOnClickListener {
                val c = Monitor.snap?.serving
                if (c == null || Monitor.dir.key(c) == null) Toast.makeText(this@MainActivity, R.string.no_cell_id, Toast.LENGTH_SHORT).show()
                else editName(c)
            }
        }
        towerAddrTv = tv(kv(str(R.string.tower_address), "--"), 14f)
        towerTv = tv(kv(str(R.string.tower), "--"), 14f).apply {
            setPadding(0, dp(3), 0, dp(3))
            setOnClickListener { showTowerOnMap() }
        }
        myAddrTv = tv(kv(str(R.string.your_area), "--"), 14f)
        listOf(nameTv, towerAddrTv, towerTv, myAddrTv).forEach { s0.addView(it, mw()) }

        val s1 = vbox(p.panel).apply { setPadding(0, dp(4), 0, dp(4)) }
        s1.addView(kvRow("mcc", "mnc", "lac", "type"))
        s1.addView(kvRow("node", "cid", "pci"))
        s1.addView(kvRow("arfcn", "band", "bw"))
        s1.addView(kvRow("fdl", "ful"))
        s1.addView(kvRow("level", "qual", "snr"))
        s1.addView(kvRow("rssi", "ta"))
        s1.addView(kvRow("eci"))
        s1.addView(kvRow("net"))
        s1.addView(kvRow("ca"))

        graph = SignalGraphView(this, p)

        val s2 = vbox(p.panel2).apply { setPadding(0, dp(4), 0, dp(4)) }
        s2.addView(kvRow("lon", "lat"))
        s2.addView(kvRow("speed", "hdg", "acc"))
        s2.addView(kvRow("alt", "gps"))

        val s3 = vbox(p.panel).apply { setPadding(0, dp(4), 0, dp(4)) }
        s3.addView(kvRow("ul", "dl"))
        s3.addView(kvRow("data", "dstate"))

        servingTimeTv = tv("--", 15f, p.text, gravity = Gravity.CENTER).apply {
            setBackgroundColor(p.panel2); setPadding(0, dp(4), 0, dp(4))
        }
        val evHeader = tableRow(listOf("TIME", "EVENT", "TECH", "CELL", "PCI", "ARFCN", "LEVEL", "QUAL"), evW, p.header, Color.WHITE)
        eventTable = vbox()

        if (wide) {
            // tablet / landscape: two columns
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val left = vbox()
            val right = vbox()
            listOf(s0, s1, s2, s3).forEach { left.addView(it, mw()) }
            right.addView(graph, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
            right.addView(servingTimeTv, mw())
            right.addView(evHeader, mw())
            right.addView(eventTable, mw())
            row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
            col.addView(row, mw())
        } else {
            col.addView(s0, mw())
            col.addView(s1, mw())
            col.addView(graph, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)))
            col.addView(s2, mw())
            col.addView(s3, mw())
            col.addView(servingTimeTv, mw())
            col.addView(evHeader, mw())
            col.addView(eventTable, mw())
        }
        return ScrollView(this).apply { addView(col) }
    }

    private fun renderEvents() {
        val ev = Monitor.events
        if (ev.size == shownEventsCount && ev.firstOrNull() === shownEventsTop) return
        shownEventsCount = ev.size
        shownEventsTop = ev.firstOrNull()
        eventTable.removeAllViews()
        ev.take(40).forEach { e ->
            eventTable.addView(
                tableRow(
                    listOf(e.time, e.event, e.tech, e.cell, e.pci, e.arfcn, e.level, e.qual), evW,
                    p.panel, colorAt = mapOf(6 to e.color)
                ), mw()
            )
        }
    }

    private fun updateHint(snap: Snapshot?) {
        val msg = when {
            !Monitor.hasPhonePerms() -> str(R.string.hint_perm)
            !Monitor.locationEnabled() -> str(R.string.hint_location)
            snap == null || snap.total == 0 -> str(R.string.hint_waiting)
            else -> null
        }
        hintTv.visibility = if (msg == null) View.GONE else View.VISIBLE
        hintTv.text = msg ?: ""
    }

    private fun renderCell(snap: Snapshot) {
        updateHint(snap)
        val c = snap.serving
        val opInfo = Operators.find(c?.mcc, c?.mnc) ?: Operators.fromCode(snap.operatorCode)
        val code = snap.operatorCode
        operatorTv.text = str(R.string.operator) + ": " + Monitor.opName(snap) +
            (if (code.length >= 5) "  (${code.take(3)}-${code.drop(3)})" else "")
        operatorTv.setTextColor(opInfo?.color ?: p.text)
        renderNameAddress(c)

        setKv("mcc", "MCC", c?.mcc ?: code.take(3).ifBlank { "--" })
        setKv("mnc", "MNC", c?.mnc ?: (if (code.length > 3) code.drop(3) else "--"))
        setKv("lac", c?.areaLabel ?: "LAC", c?.lac.s())
        setKv("type", "Type", c?.tech ?: "--", p.accent)
        setKv("node", c?.nodeLabel ?: "NODE", c?.node.s())
        setKv("cid", "CID", c?.localCid.s())
        setKv("pci", c?.pciLabel ?: "PCI", c?.pci.s())
        setKv("ta", "TA", c?.ta.s())
        setKv("arfcn", "ARFCN", c?.arfcn.s())
        setKv("band", "BAND", c?.band ?: "--")
        setKv("bw", "BW", c?.bw?.let { "${f1(it)}MHz" } ?: "--")
        setKv("fdl", "F DL", c?.dl?.let { "${f1(it)} MHz" } ?: "--")
        setKv("ful", "F UL", c?.ul?.let { "${f1(it)} MHz" } ?: "--")
        val levelName = when (c?.tech) { "LTE" -> "RSRP"; "NR" -> "SS-RSRP"; "WCDMA" -> "RSCP"; else -> "LEVEL" }
        val qualName = when (c?.tech) { "LTE" -> "RSRQ"; "NR" -> "SS-RSRQ"; "WCDMA" -> "EcNo"; else -> "QUAL" }
        setKv("level", levelName, c?.level.s(), Sig.color(c?.tech, c?.level))
        setKv("qual", qualName, c?.qual.s())
        setKv("snr", "SNR", f1(c?.snr))
        setKv("rssi", "RSSI", c?.rssi.s())
        setKv("eci", if (c?.tech == "NR") "NCI" else if (c?.tech == "LTE") "ECI" else "CI", c?.cellId.s())
        setKv("net", str(R.string.network), Monitor.net.label(c?.tech), p.accent)
        setKv("ca", str(R.string.carrier_aggregation), Monitor.net.caText())

        val loc = Monitor.freshLoc()
        setKv("lon", str(R.string.longitude), loc?.let { String.format(Locale.US, "%.6f", it.longitude) } ?: "--")
        setKv("lat", str(R.string.latitude), loc?.let { String.format(Locale.US, "%.6f", it.latitude) } ?: "--")
        setKv("speed", str(R.string.speed), loc?.takeIf { it.hasSpeed() }?.let { Prefs.speed(it.speed) } ?: "--")
        setKv("hdg", "Hdg", loc?.takeIf { it.hasBearing() }?.let { "${it.bearing.toInt()}°" } ?: "--")
        setKv("acc", "Acc", loc?.takeIf { it.hasAccuracy() }?.let { Prefs.height(it.accuracy.toDouble()) } ?: "--")
        setKv("alt", str(R.string.altitude), loc?.takeIf { it.hasAltitude() }?.let { Prefs.height(it.altitude) } ?: "--")
        val (gpsState, gpsOk) = when {
            !Monitor.hasLocPerm() -> str(R.string.gps_noperm) to false
            !Monitor.locationEnabled() -> str(R.string.gps_off) to false
            loc == null -> str(R.string.gps_searching) to false
            loc.provider == LocationManager.GPS_PROVIDER -> str(R.string.gps_fix) to true
            else -> str(R.string.gps_network) to false
        }
        setKv("gps", "GPS", gpsState, if (gpsOk) Sig.GREEN else Sig.ORANGE)

        setKv("ul", "UL", "${f1(Monitor.ulKbps)} kbps")
        setKv("dl", "DL", "${f1(Monitor.dlKbps)} kbps")
        val (dtype, dstate) = dataInfo()
        setKv("data", str(R.string.data), dtype)
        setKv("dstate", str(R.string.state), dstate)

        val secs = (SystemClock.elapsedRealtime() - Monitor.servingSince) / 1000
        servingTimeTv.text = str(R.string.serving_time) + ": " + (if (secs >= 60) "${secs / 60}m ${secs % 60}s" else "${secs}s")
        graph.invalidate()
        renderEvents()
    }

    @SuppressLint("MissingPermission")
    private fun netTypeName(): String {
        val t = try { Monitor.reader.tm.dataNetworkType } catch (e: Exception) { return "--" }
        return when (t) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
            TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA -> "HSPA"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
            TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> "--"
            else -> "TYPE $t"
        }
    }

    private fun dataInfo(): Pair<String, String> {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = try { cm.getNetworkCapabilities(cm.activeNetwork) } catch (e: Exception) { null }
        val type = when {
            caps == null -> str(R.string.none)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile ${netTypeName()}"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "--"
        }
        val act = try {
            when (Monitor.reader.tm.dataActivity) {
                TelephonyManager.DATA_ACTIVITY_IN -> "IN"
                TelephonyManager.DATA_ACTIVITY_OUT -> "OUT"
                TelephonyManager.DATA_ACTIVITY_INOUT -> "IN/OUT"
                TelephonyManager.DATA_ACTIVITY_DORMANT -> "DORMANT"
                else -> "IDLE"
            }
        } catch (e: Exception) { "--" }
        return type to act
    }

    // ---------- name & address ----------
    private fun renderNameAddress(c: CellData?) {
        val dir = Monitor.dir
        if (c == null) {
            nameTv.text = kv(str(R.string.cell_name), "--")
            towerAddrTv.text = kv(str(R.string.tower_address), "--")
            towerTv.text = kv(str(R.string.tower), "--")
        } else {
            val name = dir.name(c)
            nameTv.text = kv(str(R.string.cell_name), name ?: str(R.string.not_set_tap), if (name != null) p.accent else p.label)
            val t = dir.tower(c)
            val tAddr = t?.address
            val addr = when {
                tAddr != null -> tAddr
                t != null -> str(R.string.no_street_address)
                dir.key(c) == null -> str(R.string.no_cell_id)
                dir.apiKey.isBlank() -> str(R.string.set_key_hint)
                dir.isLooking(c) -> str(R.string.looking_up)
                dir.notFound(c) -> str(R.string.tower_not_found)
                dir.lastError != null -> getString(R.string.error_fmt, dir.lastError)
                else -> str(R.string.looking_up)
            }
            towerAddrTv.text = kv(str(R.string.tower_address), addr, if (tAddr != null) p.text else p.label)
            val loc = Monitor.freshLoc()
            towerTv.text = if (t == null) kv(str(R.string.tower), "--") else {
                val sb = StringBuilder(String.format(Locale.US, "%.5f, %.5f", t.lat, t.lon))
                if (loc != null) {
                    val d = FloatArray(1)
                    Location.distanceBetween(loc.latitude, loc.longitude, t.lat, t.lon, d)
                    sb.append("  •  ").append(getString(R.string.away_fmt, Prefs.distance(d[0].toDouble())))
                }
                t.range?.let { if (it > 0) sb.append("  •  ±").append(Prefs.height(it.toDouble())) }
                sb.append("  ›")
                kv(str(R.string.tower), sb.toString())
            }
        }
        myAddrTv.text = kv(
            str(R.string.your_area),
            dir.myAddress ?: if (Monitor.freshLoc() == null) str(R.string.waiting_gps) else str(R.string.finding_address)
        )
    }

    private fun textDialog(title: String, message: String, initial: String, hint: String, onSave: (String) -> Unit, onRemove: (() -> Unit)? = null) {
        val input = EditText(this).apply {
            setText(initial)
            this.hint = hint
            setSingleLine()
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        val b = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ -> onSave(input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
        if (onRemove != null) b.setNeutralButton(R.string.remove) { _, _ -> onRemove() }
        b.show()
    }

    private fun editName(c: CellData) {
        textDialog(
            getString(R.string.name_title_fmt, c.tech, Monitor.cellIdText(c)), str(R.string.name_msg),
            Monitor.dir.name(c) ?: "", str(R.string.name_hint),
            onSave = { Monitor.dir.setName(c, it); renderCurrent() },
            onRemove = { Monitor.dir.setName(c, ""); renderCurrent() }
        )
    }

    private fun editApiKey() {
        textDialog(
            str(R.string.api_key_title), str(R.string.api_key_msg), Monitor.dir.apiKey, "pk.xxxxxxxxxxxx",
            onSave = { Monitor.dir.apiKey = it; renderCurrent() }
        )
    }

    private fun showTowerOnMap() {
        val c = Monitor.snap?.serving ?: return
        val t = Monitor.dir.tower(c) ?: return
        follow = false
        selectTab(2)
        map?.controller?.setZoom(16.0)
        map?.controller?.animateTo(GeoPoint(t.lat, t.lon))
    }

    // ---------- NEI ----------
    private val neiW = listOf(1.1f, 1.0f, 1.6f, 0.9f, 1.3f, 1.0f, 0.9f)

    private fun buildNeiPage(): View {
        val col = vbox()
        neiCount = tv("--", 17f, p.text, true, Gravity.CENTER).apply {
            setBackgroundColor(p.panel2); setPadding(0, dp(8), 0, dp(8))
        }
        col.addView(neiCount, mw())
        col.addView(tableRow(listOf("OP", "TECH", "CELL", "PCI", "ARFCN", "LEVEL", "QUAL"), neiW, p.header, Color.WHITE, size = 13f), mw())
        neiTable = vbox()
        col.addView(neiTable, mw())
        col.addView(tv(str(R.string.nei_legend), 12f, p.label, gravity = Gravity.CENTER).apply {
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }, mw())
        return ScrollView(this).apply { addView(col) }
    }

    private fun renderNei(snap: Snapshot) {
        neiCount.text = getString(R.string.nei_title_fmt, snap.neighbors.size)
        neiTable.removeAllViews()
        fun add(tag: String, c: CellData, bg: Int) {
            neiTable.addView(
                tableRow(
                    listOf(tag, c.tech, Monitor.dir.name(c) ?: (if (c.cellId != null) Monitor.cellIdText(c) else "--"),
                        c.pci.s(), c.arfcn.s(), c.level.s(), c.qual.s()),
                    neiW, bg, colorAt = mapOf(5 to Sig.color(c.tech, c.level)), size = 13f
                ), mw()
            )
        }
        fun opShort(c: CellData, fallback: String) = Operators.find(c.mcc, c.mnc)?.short ?: fallback
        snap.serving?.let { add(opShort(it, "S") + "●", it, p.rowServing) }
        snap.otherRegistered.forEach { add(opShort(it, "R"), it, p.rowOther) }
        snap.neighbors.forEach { add("N", it, p.panel) }
    }

    // ---------- MAP ----------
    private fun buildMapPage(): View {
        val fl = FrameLayout(this)
        val m = MapView(this)
        m.setMultiTouchControls(true)
        m.controller.setZoom(15.0)
        m.controller.setCenter(GeoPoint(23.8103, 90.4125))
        trackOverlay = TrackOverlay(Monitor.track)
        m.overlays.add(trackOverlay)
        map = m
        applyMapStyle()
        fl.addView(m, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        mapInfo = tv("", 13f, Color.WHITE).apply {
            setBackgroundColor(0xCC000000.toInt()); setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        fl.addView(mapInfo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lateinit var followBtn: TextView
        followBtn = button(str(R.string.follow_on), 0xDD00796B.toInt()) {
            follow = !follow
            followBtn.text = str(if (follow) R.string.follow_on else R.string.follow_off)
        }
        btns.addView(followBtn)
        val clearBtn = button(str(R.string.clear), 0xDD424242.toInt()) { Monitor.track.clear(); m.invalidate() }
        btns.addView(clearBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
        lp.setMargins(dp(12), dp(12), dp(12), dp(12))
        fl.addView(btns, lp)

        val sb = SpannableStringBuilder("LTE RSRP ")
        listOf(Sig.GREEN to "≥-85 ", Sig.LIME to "≥-95 ", Sig.YELLOW to "≥-105 ", Sig.ORANGE to "≥-115 ", Sig.RED to "<-115").forEach { (col, t) ->
            sb.append("●", ForegroundColorSpan(col), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(t)
        }
        val legend = tv(sb, 11f, Color.WHITE).apply {
            setBackgroundColor(0xCC000000.toInt()); setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val llp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
        llp.setMargins(dp(8), 0, 0, dp(80))
        fl.addView(legend, llp)
        return fl
    }

    private fun applyMapStyle() {
        val m = map ?: return
        if (mapStyleShown == Prefs.mapStyle) return
        mapStyleShown = Prefs.mapStyle
        m.setTileSource(if (Prefs.mapStyle == 1) TileSourceFactory.OpenTopo else TileSourceFactory.MAPNIK)
    }

    private fun renderMap(snap: Snapshot) {
        val m = map ?: return
        val loc = Monitor.freshLoc()
        trackOverlay.me = loc?.let { GeoPoint(it.latitude, it.longitude) }
        val c = snap.serving
        val tw = c?.let { Monitor.dir.tower(it) }
        trackOverlay.tower = tw?.let { GeoPoint(it.lat, it.lon) }
        trackOverlay.towerLabel = c?.let { Monitor.dir.name(it) ?: Monitor.cellIdText(it) }
        if (follow && loc != null) m.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
        mapInfo.text = buildString {
            append(Monitor.opName(snap)).append("  ").append(Monitor.net.label(c?.tech))
            append("\n").append(str(R.string.cell)).append(" ").append(c?.let { Monitor.cellIdText(it) } ?: "--")
                .append("  PCI ").append(c?.pci.s())
            c?.let { Monitor.dir.name(it) }?.let { append("\n").append(it) }
            append("\n").append(c?.level.s()).append(" dBm   ").append(str(R.string.points)).append(" ").append(Monitor.track.size)
            if (tw != null && loc != null) {
                val d = FloatArray(1)
                Location.distanceBetween(loc.latitude, loc.longitude, tw.lat, tw.lon, d)
                append("\n").append(str(R.string.tower)).append(" ").append(Prefs.distance(d[0].toDouble()))
            }
            if (loc == null) append("\n").append(str(R.string.waiting_gps))
        }
        m.invalidate()
    }

    // ---------- SPEED ----------
    private var speedTest: SpeedTest? = null
    private lateinit var speedBig: TextView
    private lateinit var speedPhase: TextView
    private lateinit var speedBar: android.widget.ProgressBar
    private lateinit var speedBtn: TextView
    private lateinit var speedCtx: TextView
    private lateinit var speedHist: LinearLayout
    private val speedVals = HashMap<String, TextView>()

    private fun buildSpeedPage(): View {
        val col = vbox().apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
        val card = vbox(p.panel).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        speedPhase = tv(str(R.string.speed_ready), 15f, p.label, gravity = Gravity.CENTER)
        speedBig = tv("--", 52f, p.text, true, Gravity.CENTER)
        val unit = tv("Mbps", 15f, p.label, gravity = Gravity.CENTER)
        speedBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 0
        }
        card.addView(speedPhase, mw())
        card.addView(speedBig, mw())
        card.addView(unit, mw())
        card.addView(speedBar, mw().apply { topMargin = dp(10) })
        col.addView(card, mw())

        // four result boxes
        val boxes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "ping" to R.string.speed_ping, "jitter" to R.string.speed_jitter,
            "down" to R.string.speed_down, "up" to R.string.speed_up
        ).forEachIndexed { i, (k, label) ->
            val b = vbox(p.panel2).apply {
                setPadding(dp(4), dp(10), dp(4), dp(10))
                gravity = Gravity.CENTER_HORIZONTAL
            }
            b.addView(tv(str(label), 12f, p.label, gravity = Gravity.CENTER).apply { maxLines = 1 }, mw())
            val v = tv("--", 18f, p.text, true, Gravity.CENTER).apply {
                setSingleLine(true)
                setAutoSizeTextTypeUniformWithConfiguration(11, 18, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }
            speedVals[k] = v
            b.addView(v, mw())
            b.addView(tv(if (i < 2) "ms" else "Mbps", 11f, p.label, gravity = Gravity.CENTER), mw())
            boxes.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(6)
            })
        }
        col.addView(boxes, mw().apply { topMargin = dp(10) })

        speedBtn = button(str(R.string.speed_start), 0xFF1565C0.toInt()) { toggleSpeed() }
        col.addView(speedBtn, mw().apply { topMargin = dp(12) })
        speedCtx = tv("", 13f, p.label).apply { setPadding(0, dp(8), 0, 0) }
        col.addView(speedCtx, mw())

        val histHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, dp(6))
        }
        histHead.addView(tv(str(R.string.speed_history), 16f, p.text, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        histHead.addView(tv(str(R.string.speed_share), 14f, p.accent, true).apply {
            setPadding(dp(10), dp(4), dp(4), dp(4))
            setOnClickListener {
                SpeedTest.historyFile(this@MainActivity)?.let { shareFile(it, "text/csv") }
                    ?: Toast.makeText(this@MainActivity, R.string.speed_no_history, Toast.LENGTH_SHORT).show()
            }
        })
        col.addView(histHead, mw())
        speedHist = vbox()
        col.addView(speedHist, mw())
        return ScrollView(this).apply { addView(col) }
    }

    private fun renderSpeedContext(snap: Snapshot) {
        if (speedTest != null) return
        val c = snap.serving
        speedCtx.text = getString(
            R.string.speed_context, "Cloudflare", Monitor.opName(snap),
            Monitor.net.label(c?.tech), c?.level?.let { "$it dBm" } ?: "--", dataInfo().first
        )
    }

    private fun renderSpeedHistory() {
        if (!::speedHist.isInitialized) return
        speedHist.removeAllViews()
        val h = SpeedTest.history(this)
        if (h.isEmpty()) {
            speedHist.addView(tv(str(R.string.speed_no_history), 14f, p.label), mw())
            return
        }
        h.take(40).forEachIndexed { i, r ->
            val line1 = "↓ ${r["download_mbps"]}   ↑ ${r["upload_mbps"]} Mbps   •   ${r["ping_ms"]} ms"
            val line2 = "${r["time"]}  •  ${r["operator"]}  ${r["network"]}  ${r["level_dbm"]?.let { if (it.isBlank()) "" else "$it dBm" }}"
            val t = tv("$line1\n$line2", 13f, p.text).apply {
                setBackgroundColor(if (i % 2 == 0) p.panel else p.panel2)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setLineSpacing(0f, 1.2f)
            }
            speedHist.addView(t, mw())
        }
    }

    private fun toggleSpeed() {
        speedTest?.let {
            it.cancel()
            speedBtn.text = str(R.string.speed_stopping)
            return
        }
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = try { cm.getNetworkCapabilities(cm.activeNetwork) } catch (e: Exception) { null }
        if (caps == null) {
            Toast.makeText(this, R.string.speed_no_internet, Toast.LENGTH_SHORT).show(); return
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !Prefs.speedDataWarned) {
            AlertDialog.Builder(this)
                .setTitle(R.string.speed_data_title)
                .setMessage(R.string.speed_data_msg)
                .setPositiveButton(R.string.continue_) { _, _ -> Prefs.speedDataWarned = true; startSpeed() }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        startSpeed()
    }

    private fun startSpeed() {
        listOf("ping", "jitter", "down", "up").forEach { speedVals[it]?.text = "--" }
        speedBig.text = "0"
        speedBar.progress = 0
        speedBtn.text = str(R.string.speed_stop)
        (speedBtn.background as? GradientDrawable)?.setColor(0xFFC62828.toInt())
        val snap = Monitor.snap
        val c = snap?.serving
        val loc = Monitor.freshLoc()
        val st = SpeedTest(object : SpeedTest.Listener {
            override fun onProgress(phase: SpeedTest.Phase, value: Double, progress: Float) {
                speedBar.progress = (progress * 1000).toInt()
                when (phase) {
                    SpeedTest.Phase.PING -> {
                        speedPhase.text = str(R.string.speed_ping)
                        speedBig.text = String.format(Locale.US, "%.0f", value)
                    }
                    SpeedTest.Phase.DOWNLOAD -> {
                        speedPhase.text = "↓ " + str(R.string.speed_down)
                        speedBig.text = String.format(Locale.US, "%.1f", value)
                    }
                    SpeedTest.Phase.UPLOAD -> {
                        speedPhase.text = "↑ " + str(R.string.speed_up)
                        speedBig.text = String.format(Locale.US, "%.1f", value)
                    }
                    SpeedTest.Phase.DONE -> {}
                }
            }

            override fun onDone(result: SpeedTest.Result?, error: String?) {
                speedTest = null
                speedBtn.text = str(R.string.speed_start)
                (speedBtn.background as? GradientDrawable)?.setColor(0xFF1565C0.toInt())
                when {
                    result != null -> {
                        speedVals["ping"]?.text = String.format(Locale.US, "%.0f", result.pingMs)
                        speedVals["jitter"]?.text = String.format(Locale.US, "%.1f", result.jitterMs)
                        speedVals["down"]?.text = String.format(Locale.US, "%.2f", result.downMbps)
                        speedVals["up"]?.text = String.format(Locale.US, "%.2f", result.upMbps)
                        speedBig.text = String.format(Locale.US, "%.1f", result.downMbps)
                        speedPhase.text = getString(R.string.speed_done_fmt, String.format(Locale.US, "%.0f", result.dataMb))
                        try { SpeedTest.save(this@MainActivity, result) } catch (e: Exception) { }
                        renderSpeedHistory()
                    }
                    error != null -> {
                        speedPhase.text = getString(R.string.error_fmt, error)
                        speedBig.text = "--"
                    }
                    else -> {
                        speedPhase.text = str(R.string.speed_cancelled)
                        speedBig.text = "--"
                        speedBar.progress = 0
                    }
                }
            }
        })
        speedTest = st
        st.start(
            snap?.let { Monitor.opName(it) } ?: "--", Monitor.net.label(c?.tech), c?.level,
            c?.let { Monitor.dir.name(it) ?: Monitor.cellIdText(it) } ?: "--",
            loc?.latitude, loc?.longitude
        )
    }

    // ---------- INFO ----------
    private fun buildInfoPage(): View {
        val col = vbox()
        val keyBtn = button(str(R.string.set_api_key_btn), p.button) { editApiKey() }
        col.addView(keyBtn, mw().apply { setMargins(dp(12), dp(10), dp(12), dp(6)) })
        infoTable = vbox().apply { setPadding(0, dp(4), 0, dp(4)) }
        col.addView(infoTable, mw())
        return ScrollView(this).apply { addView(col) }
    }

    @SuppressLint("MissingPermission")
    private fun renderInfo(snap: Snapshot) {
        val tm = Monitor.reader.tm
        val c = snap.serving
        fun safe(block: () -> Any?): String = try { block()?.toString()?.ifBlank { "--" } ?: "--" } catch (e: Exception) { "--" }
        val (dtype, dstate) = dataInfo()
        val yes = str(R.string.yes)
        val no = str(R.string.no)
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
        val rows = listOf(
            R.string.info_selected_sim to "${Monitor.reader.simLabel()} ${Monitor.reader.simName()}",
            R.string.info_active_sims to Monitor.reader.subs.joinToString { "SIM${it.slot + 1}: ${it.name}" }.ifBlank { "--" },
            R.string.info_network_operator to Monitor.opName(snap),
            R.string.info_network_code to snap.operatorCode.ifBlank { "--" },
            R.string.info_sim_operator to safe { tm.simOperatorName },
            R.string.info_sim_code to safe { tm.simOperator },
            R.string.info_country to safe { tm.networkCountryIso.uppercase(Locale.US) },
            R.string.network to Monitor.net.label(c?.tech),
            R.string.carrier_aggregation to Monitor.net.caText(),
            R.string.info_data_network to netTypeName(),
            R.string.info_data_connection to "$dtype ($dstate)",
            R.string.info_roaming to safe { if (tm.isNetworkRoaming) yes else no },
            R.string.info_serving_tech to (c?.tech ?: "--"),
            R.string.info_signal_quality to str(Sig.wordRes(c?.tech, c?.level)),
            R.string.cell_name to (c?.let { Monitor.dir.name(it) } ?: "--"),
            R.string.info_named_cells to Monitor.dir.nameCount().toString(),
            R.string.info_ocid_key to str(if (Monitor.dir.apiKey.isBlank()) R.string.not_set else R.string.set_ok),
            R.string.info_cells_visible to "${snap.total} (${snap.neighbors.size} N)",
            R.string.info_device to "${Build.MANUFACTURER} ${Build.MODEL}",
            R.string.info_android to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            R.string.info_app to "NetPulse $version",
        )
        infoTable.removeAllViews()
        rows.forEachIndexed { i, (k, v) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(if (i % 2 == 0) p.panel else p.bg)
                setPadding(dp(12), dp(9), dp(12), dp(9))
            }
            row.addView(tv(str(k), 15f, p.label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(tv(v, 15f, p.text, gravity = Gravity.END), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f))
            infoTable.addView(row, mw())
        }
    }

    // ---------- DRIVE ----------
    private fun buildDrivePage(): View {
        val col = vbox().apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
        col.addView(tv(str(R.string.drive_title), 20f, p.text, true), mw())
        col.addView(tv(str(R.string.drive_desc), 13f, p.label).apply { setPadding(0, dp(4), 0, dp(12)) }, mw())
        driveBtn = button(str(R.string.start_logging), 0xFF2E7D32.toInt()) { toggleDrive() }
        col.addView(driveBtn, mw())
        driveStatus = tv("", 15f, p.text).apply {
            setBackgroundColor(p.panel); setPadding(dp(12), dp(10), dp(12), dp(10))
            setLineSpacing(0f, 1.25f)
        }
        col.addView(driveStatus, mw().apply { topMargin = dp(12) })
        col.addView(tv(str(R.string.saved_logs), 16f, p.text, true).apply { setPadding(0, dp(16), 0, dp(6)) }, mw())
        logList = vbox()
        col.addView(logList, mw())
        return ScrollView(this).apply { addView(col) }
    }

    private fun toggleDrive() {
        if (Monitor.logging || LoggingService.running) {
            val name = Monitor.logger.file?.name
            LoggingService.stop(this)
            if (Monitor.logging) Monitor.stopLogging()
            name?.let { Toast.makeText(this, getString(R.string.saved_fmt, it), Toast.LENGTH_SHORT).show() }
            return
        }
        if (!Monitor.hasLocPerm()) {
            requestPerms(); return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !pendingStartLogging
        ) {
            pendingStartLogging = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), reqNotif)
            return
        }
        startLoggingNow()
    }

    private fun startLoggingNow() {
        pendingStartLogging = false
        try {
            LoggingService.start(this)
        } catch (e: Exception) {
            // service not allowed: log in the foreground only
            if (!Monitor.startLogging()) Toast.makeText(this, R.string.log_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun renderDrive() {
        val on = Monitor.logging
        val lg = Monitor.logger
        driveBtn.text = str(if (on) R.string.stop_logging else R.string.start_logging)
        (driveBtn.background as? GradientDrawable)?.setColor(if (on) 0xFFC62828.toInt() else 0xFF2E7D32.toInt())
        val dur = if (lg.startMs > 0) (System.currentTimeMillis() - lg.startMs) / 1000 else 0
        driveStatus.text = buildString {
            append(str(R.string.status)).append(": ").append(str(if (on) R.string.logging else R.string.stopped)).append('\n')
            append(str(R.string.file)).append(": ").append(lg.file?.name ?: "--").append('\n')
            append(str(R.string.duration)).append(": ")
                .append(String.format(Locale.US, "%02d:%02d:%02d", dur / 3600, (dur / 60) % 60, dur % 60)).append('\n')
            append(str(R.string.samples)).append(": ").append(lg.samples).append('\n')
            append(str(R.string.distance)).append(": ").append(Prefs.distance(lg.distanceM)).append('\n')
            append(str(R.string.level_mma)).append(": ").append(lg.minLevel.s()).append(" / ")
                .append(lg.avgLevel.s()).append(" / ").append(lg.maxLevel.s()).append(" dBm\n")
            append("GPS: ").append(str(if (Monitor.freshLoc() != null) R.string.gps_fix else R.string.gps_searching))
        }
    }

    private fun renderLogList() {
        if (!::logList.isInitialized) return
        logList.removeAllViews()
        val files = Monitor.logger.files()
        if (files.isEmpty()) {
            logList.addView(tv(str(R.string.no_logs), 14f, p.label), mw())
            return
        }
        files.take(30).forEachIndexed { i, f ->
            val t = tv("${f.name}   ${f.length() / 1024} KB", 14f, p.text).apply {
                setBackgroundColor(if (i % 2 == 0) p.panel else p.panel2)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener { logOptions(f) }
            }
            logList.addView(t, mw())
        }
    }

    private fun logOptions(f: File) {
        val items = arrayOf(
            str(R.string.exp_share_csv), str(R.string.exp_pdf), str(R.string.exp_kml), str(R.string.exp_gpx), str(R.string.exp_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> shareFile(f, "text/csv")
                    1 -> export(f, "application/pdf") { Exporter.pdf(this, it) }
                    2 -> export(f, "application/vnd.google-earth.kml+xml") { Exporter.kml(this, it) }
                    3 -> export(f, "application/gpx+xml") { Exporter.gpx(it) }
                    4 -> AlertDialog.Builder(this)
                        .setMessage(getString(R.string.exp_delete_confirm, f.name))
                        .setPositiveButton(R.string.exp_delete) { _, _ ->
                            if (Monitor.logging && Monitor.logger.file == f) {
                                Toast.makeText(this, R.string.exp_in_use, Toast.LENGTH_SHORT).show()
                            } else { f.delete(); renderLogList() }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    /** Builds an export file in the background, then opens the share sheet. */
    private fun export(src: File, mime: String, make: (File) -> File) {
        Toast.makeText(this, R.string.exp_working, Toast.LENGTH_SHORT).show()
        Thread {
            val result = try { Result.success(make(src)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                result.onSuccess { shareFile(it, mime) }
                    .onFailure { Toast.makeText(this, getString(R.string.share_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun shareFile(f: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, f.name))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.share_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }
}
