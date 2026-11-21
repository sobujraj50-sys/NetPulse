package com.abusaeed.netpulse

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var p: Palette
    private lateinit var list: LinearLayout

    private val languages = listOf(
        "" to R.string.lang_system,
        "en" to R.string.lang_en,
        "bn" to R.string.lang_bn,
        "hi" to R.string.lang_hi,
        "ar" to R.string.lang_ar,
        "in" to R.string.lang_in,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Monitor.init(this)
        p = Palette((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.header)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(12), dp(10))
        }
        top.addView(text("←", 24f, Color.WHITE).apply {
            setPadding(dp(8), 0, dp(14), 0)
            contentDescription = "Back"
            setOnClickListener { finish() }
        })
        top.addView(text(getString(R.string.settings), 20f, Color.WHITE, true))
        root.addView(top)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(p.bg)
            addView(list)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        build()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun section(title: Int) {
        list.addView(text(getString(title), 13f, p.accent, true).apply {
            setPadding(dp(16), dp(18), dp(16), dp(6))
        })
    }

    private fun item(title: Int, value: String, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.panel)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(text(getString(title), 16f, p.text))
        if (value.isNotEmpty()) row.addView(text(value, 14f, p.label).apply { setPadding(0, dp(2), 0, 0) })
        list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 1
        })
    }

    private fun choose(title: Int, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), selected) { d, which ->
                d.dismiss()
                onPick(which)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun build() {
        list.removeAllViews()

        section(R.string.sec_display)
        val themes = listOf(getString(R.string.theme_system), getString(R.string.theme_dark), getString(R.string.theme_light))
        item(R.string.theme, themes[Prefs.theme]) {
            choose(R.string.theme, themes, Prefs.theme) {
                Prefs.theme = it
                App.applyTheme()
            }
        }
        val currentTag = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',').substringBefore('-')
        val langIndex = languages.indexOfFirst { it.first == currentTag || (it.first == "in" && currentTag == "id") }.coerceAtLeast(0)
        val langNames = languages.map { getString(it.second) }
        item(R.string.language, langNames[langIndex]) {
            choose(R.string.language, langNames, langIndex) {
                val tag = languages[it].first
                AppCompatDelegate.setApplicationLocales(
                    if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
                )
            }
        }
        val units = listOf(getString(R.string.units_metric), getString(R.string.units_imperial))
        item(R.string.units, units[if (Prefs.imperial) 1 else 0]) {
            choose(R.string.units, units, if (Prefs.imperial) 1 else 0) { Prefs.imperial = it == 1; build() }
        }

        section(R.string.sec_measurement)
        val intervals = listOf(1, 2, 5)
        val intervalNames = intervals.map { getString(R.string.every_n_sec, it) }
        val iIdx = intervals.indexOf(Prefs.intervalSec).coerceAtLeast(0)
        item(R.string.refresh_interval, intervalNames[iIdx]) {
            choose(R.string.refresh_interval, intervalNames, iIdx) { Prefs.intervalSec = intervals[it]; build() }
        }
        val styles = listOf(getString(R.string.map_standard), getString(R.string.map_topo))
        item(R.string.map_style, styles[Prefs.mapStyle.coerceIn(0, 1)]) {
            choose(R.string.map_style, styles, Prefs.mapStyle.coerceIn(0, 1)) { Prefs.mapStyle = it; build() }
        }
        item(R.string.keep_screen_on, getString(if (Prefs.keepScreenOn) R.string.on else R.string.off)) {
            Prefs.keepScreenOn = !Prefs.keepScreenOn; build()
        }
        item(R.string.set_api_key_btn, getString(if (Monitor.dir.apiKey.isBlank()) R.string.not_set else R.string.set_ok)) {
            val input = EditText(this).apply { setText(Monitor.dir.apiKey); setSingleLine(); hint = "pk.xxxxxxxxxxxx" }
            val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
            AlertDialog.Builder(this)
                .setTitle(R.string.api_key_title)
                .setMessage(R.string.api_key_msg)
                .setView(box)
                .setPositiveButton(R.string.save) { _, _ -> Monitor.dir.apiKey = input.text.toString(); build() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        section(R.string.sec_about)
        item(R.string.privacy_policy, "") {
            AlertDialog.Builder(this)
                .setTitle(R.string.privacy_policy)
                .setMessage(R.string.privacy_text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        item(R.string.permissions_settings, "") {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
        item(R.string.version, "NetPulse $version") { }
        list.addView(View(this), LinearLayout.LayoutParams(1, dp(24)))
    }
}
