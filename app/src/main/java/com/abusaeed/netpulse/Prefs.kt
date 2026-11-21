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
