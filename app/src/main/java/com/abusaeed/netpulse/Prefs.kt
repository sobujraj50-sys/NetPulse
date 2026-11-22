package com.abusaeed.netpulse

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/** App settings. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    /** Refresh interval in seconds: 1, 2 or 5. */
    var intervalSec: Int
        get() = sp.getInt("interval", 1)
        set(v) = sp.edit().putInt("interval", v).apply()

    /** false = km, km/h, m ; true = miles, mph, ft */
    var imperial: Boolean
        get() = sp.getBoolean("imperial", false)
        set(v) = sp.edit().putBoolean("imperial", v).apply()

    /** 0 = standard, 1 = topographic */
    var mapStyle: Int
        get() = sp.getInt("map_style", 0)
        set(v) = sp.edit().putInt("map_style", v).apply()

    /** 0 = follow system, 1 = dark, 2 = light */
    var theme: Int
        get() = sp.getInt("theme", 1)
        set(v) = sp.edit().putInt("theme", v).apply()

    var keepScreenOn: Boolean
        get() = sp.getBoolean("keep_screen", true)
        set(v) = sp.edit().putBoolean("keep_screen", v).apply()

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    /** Simple view for everyday users (big signal meter, plain words). */
    var simpleMode: Boolean
        get() = sp.getBoolean("simple_mode", false)
        set(v) = sp.edit().putBoolean("simple_mode", v).apply()

    /** Live dBm in the status bar. */
    var statusBar: Boolean
        get() = sp.getBoolean("status_bar", false)
        set(v) = sp.edit().putBoolean("status_bar", v).apply()

    /** Monthly mobile data limit in MB (0 = no limit) and the day the month starts. */
    var dataLimitMb: Int
        get() = sp.getInt("data_limit_mb", 0)
        set(v) = sp.edit().putInt("data_limit_mb", v).apply()

    var billingDay: Int
        get() = sp.getInt("billing_day", 1)
        set(v) = sp.edit().putInt("billing_day", v).apply()

    /** e.g. "2026-11:80" — last data alert sent this cycle */
    var dataAlertState: String
        get() = sp.getString("data_alert_state", "") ?: ""
        set(v) = sp.edit().putString("data_alert_state", v).apply()

    /** Automatic speed tests: every N hours (0 = off), Wi-Fi only */
    var autoSpeedHours: Int
        get() = sp.getInt("auto_speed_h", 0)
        set(v) = sp.edit().putInt("auto_speed_h", v).apply()

    var autoSpeedWifiOnly: Boolean
        get() = sp.getBoolean("auto_speed_wifi", true)
        set(v) = sp.edit().putBoolean("auto_speed_wifi", v).apply()

    /** Run ping + short speed tests while drive-test logging. */
    var driveTests: Boolean
        get() = sp.getBoolean("drive_tests", false)
        set(v) = sp.edit().putBoolean("drive_tests", v).apply()

    /** Notify on fake-tower warning signs. */
    var secAlerts: Boolean
        get() = sp.getBoolean("sec_alerts", true)
        set(v) = sp.edit().putBoolean("sec_alerts", v).apply()

    fun ussd(key: String, default: String): String = sp.getString("ussd_$key", default) ?: default
    fun setUssd(key: String, v: String) = sp.edit().putString("ussd_$key", v).apply()

    // ---------- alerts ----------
    var alertsOn: Boolean
        get() = sp.getBoolean("alerts_on", false)
        set(v) = sp.edit().putBoolean("alerts_on", v).apply()

    /** Weak-signal threshold in dBm (alert when level drops below). */
    var alertWeakDbm: Int
        get() = sp.getInt("alert_weak", -110)
        set(v) = sp.edit().putInt("alert_weak", v).apply()

    var alertWeak: Boolean
        get() = sp.getBoolean("alert_weak_on", true)
        set(v) = sp.edit().putBoolean("alert_weak_on", v).apply()

    var alertNoService: Boolean
        get() = sp.getBoolean("alert_nosvc", true)
        set(v) = sp.edit().putBoolean("alert_nosvc", v).apply()

    var alertTechChange: Boolean
        get() = sp.getBoolean("alert_tech", false)
        set(v) = sp.edit().putBoolean("alert_tech", v).apply()

    var alertSound: Boolean
        get() = sp.getBoolean("alert_sound", true)
        set(v) = sp.edit().putBoolean("alert_sound", v).apply()

    var alertVibrate: Boolean
        get() = sp.getBoolean("alert_vibrate", true)
        set(v) = sp.edit().putBoolean("alert_vibrate", v).apply()

    /** Warned once that the speed test uses mobile data. */
    var speedDataWarned: Boolean
        get() = sp.getBoolean("speed_warned", false)
        set(v) = sp.edit().putBoolean("speed_warned", v).apply()

    // ---------- unit formatting ----------
    fun distance(meters: Double): String =
        if (imperial) String.format(Locale.US, "%.2f mi", meters / 1609.344)
        else String.format(Locale.US, "%.2f km", meters / 1000)

    fun speed(mps: Float): String =
        if (imperial) String.format(Locale.US, "%.1f mph", mps * 2.23694)
        else String.format(Locale.US, "%.1f km/h", mps * 3.6)

    fun height(meters: Double): String =
        if (imperial) "${(meters * 3.28084).toInt()} ft" else "${meters.toInt()} m"
}
