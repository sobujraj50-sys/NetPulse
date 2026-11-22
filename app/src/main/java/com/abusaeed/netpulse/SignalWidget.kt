package com.abusaeed.netpulse

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Home-screen widget: operator, network type, signal level and quality. */
class SignalWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Monitor.init(ctx)
        if (Monitor.snap == null) try { Monitor.update() } catch (e: Exception) { }
        push(ctx, mgr, ids)
    }

    companion object {
        private var lastPush = 0L

        /** Called by the engine after each measurement (throttled). */
        fun maybeUpdate(ctx: Context) {
            val now = System.currentTimeMillis()
            if (now - lastPush < 10_000) return
            lastPush = now
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, SignalWidget::class.java))
                if (ids.isNotEmpty()) push(ctx, mgr, ids)
            } catch (e: Exception) { }
        }

        private fun push(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val s = Monitor.snap
            val c = s?.serving
            val v = RemoteViews(ctx.packageName, R.layout.widget_signal)
            val opInfo = Operators.find(c?.mcc, c?.mnc) ?: s?.let { Operators.fromCode(it.operatorCode) }
            v.setTextViewText(R.id.w_op, s?.let { Monitor.opName(it) } ?: ctx.getString(R.string.app_name))
            v.setTextColor(R.id.w_op, opInfo?.color ?: 0xFFFFFFFF.toInt())
            v.setTextViewText(R.id.w_net, Monitor.net.label(c?.tech) + "  " + Monitor.reader.simLabel())
            v.setTextViewText(R.id.w_level, c?.level?.let { "$it dBm" } ?: "--")
            val col = Sig.color(c?.tech, c?.level)
            v.setTextColor(R.id.w_level, col)
            v.setTextViewText(R.id.w_quality, ctx.getString(Sig.wordRes(c?.tech, c?.level)))
            v.setInt(R.id.w_bar, "setBackgroundColor", col)
            v.setTextViewText(R.id.w_time, SimpleDateFormat("HH:mm", Locale.US).format(Date()))
            val open = PendingIntent.getActivity(
                ctx, 5, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(R.id.w_root, open)
            mgr.updateAppWidget(ids, v)
        }
    }
}
