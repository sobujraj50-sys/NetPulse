package com.abusaeed.netpulse

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat

/**
 * Signal alerts: weak signal, no service and technology change (e.g. 4G -> 3G).
 * Checked on every measurement while the app is open or logging.
 */
object Alerts {
    private const val CHANNEL = "alerts"
    private const val COOLDOWN_MS = 30_000L
    private val lastFired = HashMap<String, Long>()
    private var weakActive = false
    private var noSvcActive = false
    private var lastTech: String? = null
    private var channelMade = false

    fun check(ctx: Context, s: Snapshot) {
        if (!Prefs.alertsOn) {
            weakActive = false; noSvcActive = false; lastTech = null
            return
        }
        val c = s.serving
        val name = Monitor.opName(s)

        // no service
        val noSvc = c == null
        if (Prefs.alertNoService && noSvc && !noSvcActive) {
            fire(ctx, "nosvc", ctx.getString(R.string.alert_nosvc_title), ctx.getString(R.string.alert_nosvc_text, name))
        }
        noSvcActive = noSvc

        // weak signal, with 3 dB hysteresis so it doesn't flap
        val lv = c?.level
        val thr = Prefs.alertWeakDbm
        if (lv != null) {
            if (lv < thr && !weakActive) {
                weakActive = true
                if (Prefs.alertWeak) fire(
                    ctx, "weak", ctx.getString(R.string.alert_weak_title),
                    ctx.getString(R.string.alert_weak_text, "${c?.tech ?: ""} $lv", thr)
                )
            } else if (lv >= thr + 3) {
                weakActive = false
            }
        }

        // technology change
        val tech = c?.tech
        val prev = lastTech
        if (tech != null) {
            if (Prefs.alertTechChange && prev != null && prev != tech) {
                fire(ctx, "tech", ctx.getString(R.string.alert_tech_title), ctx.getString(R.string.alert_tech_text, prev, tech))
            }
            lastTech = tech
        }
    }

    /** Plays the alert once so the user can hear/feel it from Settings. */
    fun test(ctx: Context) = signal(ctx)

    fun fire(ctx: Context, key: String, title: String, text: String) {
        val now = System.currentTimeMillis()
        if (now - (lastFired[key] ?: 0L) < COOLDOWN_MS) return
        lastFired[key] = now
        signal(ctx)
        notify(ctx, key.hashCode(), title, text)
    }

    private fun signal(ctx: Context) {
        if (Prefs.alertSound) {
            try {
                val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
                tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tg.release() }, 800)
            } catch (e: Exception) { }
        }
        if (Prefs.alertVibrate) {
            try {
                val vib: Vibrator = if (Build.VERSION.SDK_INT >= 31)
                    (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
                vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 220, 120, 220), -1))
            } catch (e: Exception) { }
        }
    }

    private fun notify(ctx: Context, id: Int, title: String, text: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!channelMade) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, ctx.getString(R.string.alert_channel), NotificationManager.IMPORTANCE_HIGH)
                )
                channelMade = true
            }
            val open = PendingIntent.getActivity(
                ctx, 2, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setSilent(true) // sound/vibration handled above, respecting the user's settings
                .setContentIntent(open)
                .build()
            nm.notify(1000 + (id and 0xFF), n)
        } catch (e: Exception) { }
    }
}
