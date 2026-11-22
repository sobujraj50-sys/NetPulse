package com.abusaeed.netpulse

import android.app.AppOpsManager
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Mobile / Wi-Fi data used, from Android's own counters (needs "Usage access"). */
object DataUsage {
    private var lastCheck = 0L

    fun hasAccess(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) { false }

    /** Bytes used (down + up) on mobile (wifi=false) or Wi-Fi between two times. */
    @Suppress("DEPRECATION")
    fun bytes(ctx: Context, wifi: Boolean, start: Long, end: Long): Long? = try {
        val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val b = nsm.querySummaryForDevice(if (wifi) ConnectivityManager.TYPE_WIFI else ConnectivityManager.TYPE_MOBILE, null, start, end)
        b.rxBytes + b.txBytes
    } catch (e: Exception) { null }

    fun dayStart(daysAgo: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Start of the current billing month. */
    fun cycleStart(): Long {
        val day = Prefs.billingDay.coerceIn(1, 28)
        val c = Calendar.getInstance()
        if (c.get(Calendar.DAY_OF_MONTH) < day) c.add(Calendar.MONTH, -1)
        c.set(Calendar.DAY_OF_MONTH, day)
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun fmt(bytes: Long?): String = when {
        bytes == null -> "--"
        bytes >= 1_000_000_000 -> String.format(Locale.US, "%.2f GB", bytes / 1e9)
        bytes >= 1_000_000 -> String.format(Locale.US, "%.0f MB", bytes / 1e6)
        else -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
    }

    /** Warns at 80% and 100% of the monthly mobile limit (checked every 10 minutes). */
    fun checkLimit(ctx: Context) {
        val limitMb = Prefs.dataLimitMb
        if (limitMb <= 0) return
        val now = System.currentTimeMillis()
        if (now - lastCheck < 600_000) return
        lastCheck = now
        if (!hasAccess(ctx)) return
        Thread {
            val used = bytes(ctx, false, cycleStart(), now) ?: return@Thread
            val pct = (used * 100 / (limitMb * 1_000_000L)).toInt()
            val cycle = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(cycleStart()))
            val level = if (pct >= 100) 100 else if (pct >= 80) 80 else 0
            val prev = Prefs.dataAlertState
            if (level > 0 && prev != "$cycle:$level" && !(prev == "$cycle:100" && level == 80)) {
                Prefs.dataAlertState = "$cycle:$level"
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Alerts.fire(ctx, "data$level", ctx.getString(R.string.data_alert_title),
                        ctx.getString(R.string.data_alert_text, pct, fmt(used), fmt(limitMb * 1_000_000L)))
                }
            }
        }.start()
    }
}
