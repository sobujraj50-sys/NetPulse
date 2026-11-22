package com.abusaeed.netpulse

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.net.wifi.WifiManager
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Indoor walk test: open a floor plan (photo/image) or use a grid, stand somewhere,
 * tap that spot on the plan — mobile signal and Wi-Fi RSSI are recorded there.
 * Shows a heatmap of either layer and can export it as an image.
 */
class IndoorActivity : BaseScreen() {
    override val titleRes = R.string.in_title

    data class Pt(val x: Float, val y: Float, val mobile: Int?, val wifi: Int?, val tech: String?)

    private lateinit var plan: PlanView
    private lateinit var layerBtn: android.widget.TextView
    private lateinit var info: android.widget.TextView
    private var showWifi = false
    private val pickPlan = 51

    override fun buildContent() {
        note(str(R.string.in_help))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(str(R.string.in_open_plan), p.button) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }, pickPlan)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        layerBtn = button(str(R.string.in_layer_mobile), 0xFF00796B.toInt()) {
            showWifi = !showWifi
            layerBtn.text = str(if (showWifi) R.string.in_layer_wifi else R.string.in_layer_mobile)
            plan.showWifi = showWifi; plan.invalidate()
        }
        row.addView(layerBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        content.addView(row, mw())

        plan = PlanView(this, p) { x, y -> addPoint(x, y) }
        content.addView(plan, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(460)).apply { topMargin = dp(8) })
        info = tv("", 13f, p.label)
        content.addView(info, mw())

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        row2.addView(button(str(R.string.in_undo), p.button) {
            if (plan.points.isNotEmpty()) { plan.points.removeAt(plan.points.size - 1); plan.invalidate(); updateInfo() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(button(str(R.string.in_export), 0xFF1565C0.toInt()) { export() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        content.addView(row2, mw())
        updateInfo()
    }

    override fun onResume() { super.onResume(); Monitor.acquire() }
    override fun onPause() { super.onPause(); Monitor.release() }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickPlan && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    plan.bitmap = BitmapFactory.decodeStream(input, null, opts)
                }
                plan.invalidate()
            } catch (e: Exception) {
                Toast.makeText(this, e.message ?: "", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun wifiRssi(): Int? = try {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.connectionInfo?.takeIf { it.networkId != -1 }?.rssi?.takeIf { it in -120..-1 }
    } catch (e: Exception) { null }

    private fun addPoint(x: Float, y: Float) {
        val c = Monitor.snap?.serving
        plan.points.add(Pt(x, y, c?.level, wifiRssi(), c?.tech))
        plan.invalidate()
        updateInfo()
    }

    private fun updateInfo() {
        val pts = plan.points
        val m = pts.mapNotNull { it.mobile }
        val w = pts.mapNotNull { it.wifi }
        info.text = getString(R.string.in_info_fmt, pts.size,
            if (m.isEmpty()) "--" else "${m.max()} / ${m.average().toInt()} / ${m.min()}",
            if (w.isEmpty()) "--" else "${w.max()} / ${w.average().toInt()} / ${w.min()}")
    }

    private fun export() {
        if (plan.width == 0) return
        val bmp = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
        plan.draw(Canvas(bmp))
        val dir = File(getExternalFilesDir(null) ?: filesDir, "logs/exports").apply { mkdirs() }
        val f = File(dir, "Indoor_" + (if (showWifi) "WiFi_" else "Mobile_") + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, f.name))
    }
}

/** Floor plan with measurement dots and an inverse-distance-weighted heatmap. */
class PlanView(ctx: Context, private val p: Palette, private val onTap: (Float, Float) -> Unit) : View(ctx) {
    var bitmap: Bitmap? = null
    val points = ArrayList<IndoorActivity.Pt>()
    var showWifi = false
    private val grid = Paint().apply { color = p.graphGrid }
    private val cell = Paint()
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.BLACK }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f; color = Color.BLACK; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDDFFFFFF.toInt() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        if (e.action == MotionEvent.ACTION_UP) onTap(e.x / width, e.y / height)
        return true
    }

    private fun colorOf(v: Int, tech: String?): Int = if (showWifi) when {
        v >= -55 -> Sig.GREEN; v >= -65 -> Sig.LIME; v >= -72 -> Sig.YELLOW; v >= -80 -> Sig.ORANGE; else -> Sig.RED
    } else Sig.color(tech, v)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawColor(Color.WHITE)
        bitmap?.let { b ->
            val scale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
            val w = b.width * scale; val h = b.height * scale
            c.drawBitmap(b, null, RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2), null)
        } ?: run {
            for (i in 1 until 10) {
                c.drawLine(width * i / 10f, 0f, width * i / 10f, height.toFloat(), grid)
                c.drawLine(0f, height * i / 10f, width.toFloat(), height * i / 10f, grid)
            }
        }
        val vals = points.mapNotNull { pt -> (if (showWifi) pt.wifi else pt.mobile)?.let { Triple(pt.x * width, pt.y * height, it to pt.tech) } }
        if (vals.size >= 2) {
            // inverse-distance-weighted heatmap on a coarse grid
            val step = 24
            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    var num = 0.0; var den = 0.0
                    var nearest = Double.MAX_VALUE
                    for ((px, py, v) in vals) {
                        val d2 = ((px - x) * (px - x) + (py - y) * (py - y)).toDouble().coerceAtLeast(1.0)
                        nearest = minOf(nearest, d2)
                        val w = 1.0 / d2
                        num += w * v.first; den += w
                    }
                    if (nearest < (width * 0.25) * (width * 0.25)) {
                        cell.color = (colorOf((num / den).toInt(), vals.first().third.second) and 0x00FFFFFF) or 0x66000000
                        c.drawRect(x.toFloat(), y.toFloat(), (x + step).toFloat(), (y + step).toFloat(), cell)
                    }
                    x += step
                }
                y += step
            }
        }
        for ((px, py, v) in vals) {
            dot.color = colorOf(v.first, v.second)
            c.drawCircle(px, py, 16f, dot)
            c.drawCircle(px, py, 16f, ring)
            val t = "${v.first}"
            val tw = label.measureText(t)
            c.drawRoundRect(px - tw / 2 - 6, py - 52, px + tw / 2 + 6, py - 22, 6f, 6f, labelBg)
            c.drawText(t, px, py - 28, label)
        }
    }
}
