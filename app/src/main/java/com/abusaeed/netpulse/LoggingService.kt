package com.abusaeed.netpulse

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Foreground service that keeps drive-test logging alive with the screen off. */
class LoggingService : Service() {

    companion object {
        private const val CHANNEL = "logging"
        private const val NOTIF_ID = 42
        private const val ACTION_STOP = "com.abusaeed.netpulse.STOP_LOGGING"

        var running = false
            private set

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, LoggingService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, LoggingService::class.java).setAction(ACTION_STOP))
        }
    }

    private var lastNotify = 0L
    private val listener: () -> Unit = {
        val now = System.currentTimeMillis()
        if (now - lastNotify > 2000) {
            lastNotify = now
            try {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, build())
            } catch (e: Exception) { }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Monitor.init(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (!running) {
                // started only to stop: satisfy the foreground contract, then quit
                tryStartForeground()
            }
            stopSelf()
            return START_NOT_STICKY
        }
        if (!tryStartForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            Monitor.acquire()
            Monitor.addListener(listener)
            if (!Monitor.logging) Monitor.startLogging()
        }
        return START_NOT_STICKY
    }

    private fun tryStartForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIF_ID, build(), type)
        true
    } catch (e: Exception) {
        false
    }

    override fun onDestroy() {
        if (running) {
            running = false
            Monitor.removeListener(listener)
            Monitor.stopLogging()
            Monitor.release()
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun build(): android.app.Notification {
        val s = Monitor.snap
        val c = s?.serving
        val title = getString(R.string.notif_title) + " • " + Monitor.logger.samples + " " + getString(R.string.samples).lowercase()
        val text = if (s == null) getString(R.string.hint_waiting) else buildString {
            append(Monitor.opName(s)).append(" • ").append(Monitor.net.label(c?.tech))
            append(" • ").append(c?.level?.let { "$it dBm" } ?: "--")
            c?.let { append(" • ").append(Monitor.dir.name(it) ?: Monitor.cellIdText(it)) }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LoggingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
