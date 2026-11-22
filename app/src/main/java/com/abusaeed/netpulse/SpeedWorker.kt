package com.abusaeed.netpulse

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Automatic speed test every N hours (Settings in "Auto speed test & ISP report"). */
class SpeedWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        Monitor.init(applicationContext)
        val net = ActiveNet.get(applicationContext)
        if (net.type == ActiveNet.Type.NONE) return Result.success()
        if (Prefs.autoSpeedWifiOnly && net.type != ActiveNet.Type.WIFI) return Result.success()
        val r = SpeedTest.runBlocking(net, null, null) ?: return Result.success()
        try { SpeedTest.save(applicationContext, r) } catch (e: Exception) { }
        return Result.success()
    }

    companion object {
        private const val NAME = "auto_speed_test"

        fun schedule(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            val h = Prefs.autoSpeedHours
            if (h <= 0) { wm.cancelUniqueWork(NAME); return }
            val c = Constraints.Builder()
                .setRequiredNetworkType(if (Prefs.autoSpeedWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val req = PeriodicWorkRequestBuilder<SpeedWorker>(h.toLong(), TimeUnit.HOURS).setConstraints(c).build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
