package com.abusaeed.netpulse

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat

/** Shows the live signal level (dBm) as the status-bar icon. */
class StatusService : Service() {

    companion object {
        private const val CHANNEL = "status"
        private const val NOTIF_ID = 43
        private const val ACTION_STOP = "com.abusaeed.netpulse.STOP_STATUS"

        var running = false
            private set

        fun start(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, StatusService::class.java)) } catch (e: Exception) { }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, StatusService::class.java))
        }

        /** Number drawn as a monochrome status-bar icon, e.g. "95" for -95 dBm. */
        fun icon(level: Int?): IconCompat {
            val size = 96
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            }
            val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 28f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
            }
            val txt = level?.let { (-it).toString() } ?: "--"
            big.textSize = if (txt.length >= 3) 58f else 70f
            c.drawText(txt, size / 2f, 62f, big)
            c.drawText("dBm", size / 2f, 92f, small)
            return IconCompat.createWithBitmap(bmp)
        }
    }

    private var lastLevel: Int? = Int.MIN_VALUE
    private var lastText = ""
    private val listener: () -> Unit = { refresh() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Monitor.init(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, getString(R.string.notif_status_channel), NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.statusBar = false
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, NOTIF_ID, build(), type)
        } catch (e: Exception) {
            stopSelf(); return START_NOT_STICKY
        }
        if (!running) {
            running = true
            Monitor.acquire()
            Monitor.addListener(listener)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (running) {
            running = false
            Monitor.removeListener(listener)
            Monitor.release()
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun refresh() {
        val s = Monitor.snap
        val lv = s?.serving?.level
        val text = textFor(s)
        if (lv == lastLevel && text == lastText) return
        lastLevel = lv; lastText = text
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, build())
        } catch (e: Exception) { }
    }

    private fun textFor(s: Snapshot?): String {
        val c = s?.serving ?: return getString(R.string.hint_waiting)
        return Monitor.opName(s) + " • " + Monitor.net.label(c.tech) + " • " +
            (c.level?.let { "$it dBm" } ?: "--") + " • " + getString(Sig.wordRes(c.tech, c.level))
    }

    private fun build(): android.app.Notification {
        val s = Monitor.snap
        val c = s?.serving
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 4, Intent(this, StatusService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(icon(c?.level))
            .setContentTitle(c?.let { "${it.level ?: "--"} dBm  •  ${Monitor.reader.simLabel()}" } ?: getString(R.string.app_name))
            .setContentText(textFor(s))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
