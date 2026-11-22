package com.abusaeed.netpulse

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * Finder: a "Geiger counter" for signal (faster, higher beeps = stronger) to find the
 * best spot, plus a compass arrow pointing at the tower (OpenCelliD or estimated).
 */
class FinderActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var p: Palette
    private lateinit var bigTv: TextView
    private lateinit var wordTv: TextView
    private lateinit var trendTv: TextView
    private lateinit var bestTv: TextView
    private lateinit var srcMobile: TextView
    private lateinit var srcWifi: TextView
    private lateinit var beepBtn: TextView
    private lateinit var compass: CompassView
    private lateinit var towerTv: TextView

    private var useWifi = false
    private var beepOn = true
    private var level: Int? = null
    private var best: Int? = null
    private val recent = ArrayList<Int>()
    private val ui = Handler(Looper.getMainLooper())
    private var sensors: SensorManager? = null
    private var azimuth = 0f
    private var declination = 0f

    private val listener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Monitor.init(this)
        p = Palette((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(build())
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
    }

    override fun onResume() {
        super.onResume()
        Monitor.acquire()
        Monitor.addListener(listener)
        sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        ui.post(beeper)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        Monitor.removeListener(listener)
        Monitor.release()
        sensors?.unregisterListener(this)
        ui.removeCallbacks(beeper)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun chip(s: String, onClick: () -> Unit) = tv(s, 15f, Color.WHITE, true).apply {
        setPadding(dp(18), dp(8), dp(18), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xFF424242.toInt()) }
        setOnClickListener { onClick() }
    }

    private fun mw() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun build(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.header) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(12), dp(10))
        }
        top.addView(tv("←", 24f, Color.WHITE).apply { setPadding(dp(8), 0, dp(14), 0); setOnClickListener { finish() } })
        top.addView(tv(getString(R.string.finder_title), 19f, Color.WHITE, true))
        root.addView(top)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(p.bg); setPadding(dp(16), dp(12), dp(16), dp(20))
        }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        srcMobile = chip(getString(R.string.src_mobile)) { useWifi = false; resetBest(); refresh() }
        srcWifi = chip(getString(R.string.src_wifi)) { useWifi = true; resetBest(); refresh() }
        chips.addView(srcMobile)
        chips.addView(srcWifi, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        col.addView(chips, mw())

        bigTv = tv("--", 64f, p.text, true).apply { setPadding(0, dp(10), 0, 0) }
        col.addView(bigTv, mw())
        wordTv = tv("--", 22f, p.label, true)
        col.addView(wordTv, mw())
        trendTv = tv("", 17f, p.text).apply { setPadding(0, dp(6), 0, 0) }
        col.addView(trendTv, mw())
        bestTv = tv("", 15f, p.label)
        col.addView(bestTv, mw())

        beepBtn = chip(getString(R.string.geiger_on)) {
            beepOn = !beepOn
            beepBtn.text = getString(if (beepOn) R.string.geiger_on else R.string.geiger_off)
        }
        col.addView(beepBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12); gravity = Gravity.CENTER_HORIZONTAL
        })
        col.addView(tv(getString(R.string.finder_help), 14f, p.label).apply { setPadding(0, dp(8), 0, dp(14)) }, mw())

        compass = CompassView(this, p)
        col.addView(compass, LinearLayout.LayoutParams(dp(260), dp(260)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        towerTv = tv("", 15f, p.text).apply { setPadding(0, dp(10), 0, 0) }
        col.addView(towerTv, mw())
        col.addView(tv(getString(R.string.compass_calib), 12f, p.label).apply { setPadding(0, dp(8), 0, 0) }, mw())

        root.addView(ScrollView(this).apply { setBackgroundColor(p.bg); addView(col) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun resetBest() { best = null; recent.clear() }

    @Suppress("DEPRECATION")
    private fun wifiRssi(): Int? = try {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.connectionInfo?.rssi?.takeIf { it in -120..-1 && wm.connectionInfo?.networkId != -1 }
    } catch (e: Exception) { null }

    private fun refresh() {
        srcMobile.background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(if (!useWifi) 0xFF00796B.toInt() else 0xFF424242.toInt()) }
        srcWifi.background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(if (useWifi) 0xFF00796B.toInt() else 0xFF424242.toInt()) }

        val c = Monitor.snap?.serving
        level = if (useWifi) wifiRssi() else c?.level
        val lv = level
        val tech = if (useWifi) null else c?.tech
        if (lv == null) {
            bigTv.text = "--"
            wordTv.text = getString(if (useWifi) R.string.no_wifi else R.string.q_none)
            wordTv.setTextColor(p.label)
            trendTv.text = ""
        } else {
            bigTv.text = "$lv"
            bigTv.setTextColor(color(lv))
            wordTv.text = (if (useWifi) "Wi-Fi " else "${c?.tech ?: ""} ") + "dBm • " + getString(Sig.wordRes(tech, lv))
            wordTv.setTextColor(color(lv))
            recent.add(lv)
            if (recent.size > 12) recent.removeAt(0)
            if (best == null || lv > best!!) best = lv
            val old = recent.take(4).average()
            val now = recent.takeLast(3).average()
            trendTv.text = when {
                recent.size < 5 -> ""
                now - old >= 2 -> getString(R.string.trend_up)
                old - now >= 2 -> getString(R.string.trend_down)
                else -> getString(R.string.trend_flat)
            }
            trendTv.setTextColor(when { trendTv.text == getString(R.string.trend_up) -> Sig.GREEN; trendTv.text == getString(R.string.trend_down) -> Sig.RED; else -> p.label })
        }
        bestTv.text = best?.let { getString(R.string.best_fmt, it) } ?: ""

        // tower direction
        val loc = Monitor.freshLoc()
        loc?.let {
            declination = GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), System.currentTimeMillis()).declination
        }
        val t = c?.let { Monitor.dir.tower(it) }
        val e = if (t == null) c?.let { SiteFinder.estimate(it) } else null
        val target = when {
            t != null -> Triple(t.lat, t.lon, getString(R.string.tower_src_ocid))
            e != null -> Triple(e.lat, e.lon, getString(R.string.tower_src_est))
            else -> null
        }
        if (target == null || loc == null || useWifi) {
            compass.targetBearing = null
            towerTv.text = if (useWifi) "" else if (target == null) getString(R.string.tower_unknown) else getString(R.string.waiting_gps)
        } else {
            val dest = Location("t").apply { latitude = target.first; longitude = target.second }
            val bearing = (loc.bearingTo(dest) + 360) % 360
            compass.targetBearing = bearing
            val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            val card = dirs[(((bearing + 22.5f) % 360) / 45).toInt()]
            towerTv.text = getString(R.string.tower_dir_fmt, Prefs.distance(loc.distanceTo(dest).toDouble()), bearing.toInt(), card) + " " + target.third
        }
        compass.invalidate()
    }

    private fun color(lv: Int) = if (useWifi) when {
        lv >= -55 -> Sig.GREEN; lv >= -65 -> Sig.LIME; lv >= -72 -> Sig.YELLOW; lv >= -80 -> Sig.ORANGE; else -> Sig.RED
    } else Sig.color(Monitor.snap?.serving?.tech, lv)

    /** 0 (very weak) .. 1 (very strong) */
    private fun strength(): Double? {
        val lv = level ?: return null
        val (lo, hi) = if (useWifi) -90.0 to -40.0 else when (Monitor.snap?.serving?.tech) {
            "LTE", "NR" -> -125.0 to -75.0
            else -> -110.0 to -60.0
        }
        return ((lv - lo) / (hi - lo)).coerceIn(0.0, 1.0)
    }

    private val beeper = object : Runnable {
        override fun run() {
            val q = strength()
            val next = if (q == null) 1500L else (1300 - q * 1150).toLong()
            if (beepOn && q != null) beep(500 + q * 1100)
            ui.postDelayed(this, next)
        }
    }

    private fun beep(freq: Double) {
        try {
            val rate = 22050
            val n = rate * 60 / 1000
            val buf = ShortArray(n) { i ->
                val env = minOf(1.0, minOf(i, n - i) / (n * 0.15)) // soft edges, no clicks
                (sin(2 * PI * freq * i / rate) * env * 0.6 * Short.MAX_VALUE).toInt().toShort()
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(n * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(buf, 0, n)
            track.play()
            ui.postDelayed({ try { track.release() } catch (e: Exception) { } }, 300)
        } catch (e: Exception) { }
    }

    // ---------- compass ----------
    private val rot = FloatArray(9)
    private val ori = FloatArray(3)

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rot, event.values)
        SensorManager.getOrientation(rot, ori)
        val az = ((Math.toDegrees(ori[0].toDouble()).toFloat() + declination) + 360) % 360
        // smooth, handling wrap-around at 0/360
        var diff = az - azimuth
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360
        azimuth = (azimuth + diff * 0.15f + 360) % 360
        compass.azimuth = azimuth
        compass.invalidate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/** Compass dial (rotates with the phone) with an arrow pointing to the target bearing. */
class CompassView(ctx: Context, private val p: Palette) : View(ctx) {
    var azimuth = 0f
    var targetBearing: Float? = null
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = p.label }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f; color = p.label }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 40f; isFakeBoldText = true; color = p.text }
    private val north = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 44f; isFakeBoldText = true; color = 0xFFE53935.toInt() }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8E24AA.toInt() }
    private val center = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.text }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - 8f
        c.drawCircle(cx, cy, r, ring)
        c.save()
        c.rotate(-azimuth, cx, cy) // dial: north follows real north
        for (i in 0 until 72) {
            val len = if (i % 9 == 0) 26f else 12f
            c.drawLine(cx, cy - r, cx, cy - r + len, tick)
            c.rotate(5f, cx, cy)
        }
        c.drawText("N", cx, cy - r + 70f, north)
        c.drawText("E", cx + r - 52f, cy + 14f, text)
        c.drawText("S", cx, cy + r - 40f, text)
        c.drawText("W", cx - r + 52f, cy + 14f, text)
        c.restore()
        targetBearing?.let { b ->
            c.save()
            c.rotate(b - azimuth, cx, cy)
            val path = Path().apply {
                moveTo(cx, cy - r + 30f)
                lineTo(cx - 34f, cy + 10f)
                lineTo(cx - 10f, cy + 10f)
                lineTo(cx - 10f, cy + r * 0.45f)
                lineTo(cx + 10f, cy + r * 0.45f)
                lineTo(cx + 10f, cy + 10f)
                lineTo(cx + 34f, cy + 10f)
                close()
            }
            c.drawPath(path, arrow)
            c.restore()
        }
        c.drawCircle(cx, cy, 10f, center)
    }
}
