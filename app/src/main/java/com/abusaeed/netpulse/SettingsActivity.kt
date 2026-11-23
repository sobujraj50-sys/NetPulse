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

    private val ops = listOf("" to R.string.site_op_any, "47001" to 0, "47002" to 0, "47003" to 0, "47004" to 0, "47007" to 0)

    private fun opName(plmn: String): String =
        if (plmn.isEmpty()) getString(R.string.site_op_any) else Operators.fromCode(plmn)?.name ?: plmn

    private val pickFile = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) chooseOperator(uri)
    }

    private fun siteDbMenu() {
        val b = AlertDialog.Builder(this)
            .setTitle(R.string.site_db)
            .setMessage(R.string.site_db_help)
            .setPositiveButton(R.string.site_import) { _, _ -> pickFile.launch(arrayOf("*/*")) }
            .setNegativeButton(R.string.cancel, null)
        if (SiteDb.count > 0) b.setNeutralButton(R.string.site_clear) { _, _ ->
            AlertDialog.Builder(this)
                .setMessage(R.string.site_clear_confirm)
                .setPositiveButton(R.string.remove) { _, _ -> SiteDb.clear(); build() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        b.show()
    }

    private fun chooseOperator(uri: Uri) {
        val names = ops.map { opName(it.first) }
        choose(R.string.site_which_op, names, 0) { i -> runImport(uri, ops[i].first) }
    }

    private fun runImport(uri: Uri, plmn: String) {
        var display = "file"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val col = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && c.moveToFirst()) display = c.getString(col) ?: display
            }
        } catch (e: Exception) { }
        android.widget.Toast.makeText(this, R.string.site_importing, android.widget.Toast.LENGTH_SHORT).show()
        Thread {
            val msg = try {
                val r = SiteDb.importFile(uri, display, plmn)
                getString(R.string.site_import_ok_fmt, r.count, r.withLocation, r.sheet)
            } catch (e: Exception) {
                getString(R.string.site_import_fail_fmt, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                AlertDialog.Builder(this).setTitle(R.string.site_db).setMessage(msg).setPositiveButton(android.R.string.ok, null).show()
                build()
            }
        }.start()
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
        val views = listOf(getString(R.string.view_simple), getString(R.string.view_detailed))
        item(R.string.view_mode, views[if (Prefs.simpleMode) 0 else 1]) {
            choose(R.string.view_mode, views, if (Prefs.simpleMode) 0 else 1) { Prefs.simpleMode = it == 0; build() }
        }
        item(R.string.status_bar, getString(if (Prefs.statusBar) R.string.on else R.string.off) + " — " + getString(R.string.status_bar_desc)) {
            Prefs.statusBar = !Prefs.statusBar
            if (Prefs.statusBar) {
                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 10)
                if (Monitor.hasLocPerm()) StatusService.start(this)
                else { Prefs.statusBar = false; android.widget.Toast.makeText(this, R.string.hint_perm, android.widget.Toast.LENGTH_LONG).show() }
            } else StatusService.stop(this)
            build()
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

        section(R.string.sec_towers)
        val siteVal = if (SiteDb.count == 0) getString(R.string.site_db_empty)
            else getString(R.string.site_db_fmt, SiteDb.count, opName(SiteDb.plmn), SiteDb.linkCount())
        item(R.string.site_db, siteVal) { siteDbMenu() }
        item(R.string.est_clear, getString(R.string.est_count_fmt, SiteFinder.all().size)) {
            AlertDialog.Builder(this)
                .setMessage(R.string.est_clear_confirm)
                .setPositiveButton(R.string.remove) { _, _ -> SiteFinder.clearAll(); build() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        section(R.string.sec_alerts)
        fun onOff(b: Boolean) = getString(if (b) R.string.on else R.string.off)
        item(R.string.alerts_on, onOff(Prefs.alertsOn)) {
            Prefs.alertsOn = !Prefs.alertsOn
            if (Prefs.alertsOn && android.os.Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
            }
            build()
        }
        if (Prefs.alertsOn) {
            val levels = listOf(-100, -105, -110, -115, -120)
            val levelNames = levels.map { getString(R.string.alert_below_fmt, it) }
            val li = levels.indexOf(Prefs.alertWeakDbm).coerceAtLeast(0)
            item(R.string.alert_weak_on, onOff(Prefs.alertWeak)) { Prefs.alertWeak = !Prefs.alertWeak; build() }
            item(R.string.alert_threshold, levelNames[li]) {
                choose(R.string.alert_threshold, levelNames, li) { Prefs.alertWeakDbm = levels[it]; build() }
            }
            item(R.string.alert_nosvc_on, onOff(Prefs.alertNoService)) { Prefs.alertNoService = !Prefs.alertNoService; build() }
            item(R.string.alert_tech_on, onOff(Prefs.alertTechChange)) { Prefs.alertTechChange = !Prefs.alertTechChange; build() }
            item(R.string.alert_sound, onOff(Prefs.alertSound)) { Prefs.alertSound = !Prefs.alertSound; build() }
            item(R.string.alert_vibrate, onOff(Prefs.alertVibrate)) { Prefs.alertVibrate = !Prefs.alertVibrate; build() }
            item(R.string.alert_test, "") { Alerts.test(this) }
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
