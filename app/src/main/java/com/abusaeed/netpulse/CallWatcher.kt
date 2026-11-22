package com.abusaeed.netpulse

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi

/** Listens for call start/end so call quality (and likely drops) can be logged. */
object CallWatcher {
    private var registered: Any? = null

    fun start(ctx: Context) {
        if (registered != null) return
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        registered = try {
            if (Build.VERSION.SDK_INT >= 31) Api31.register(ctx, tm) else Old.register(tm)
        } catch (e: Exception) { null }
    }

    private fun map(state: Int) = when (state) {
        TelephonyManager.CALL_STATE_OFFHOOK -> 2
        TelephonyManager.CALL_STATE_RINGING -> 1
        else -> 0
    }

    @RequiresApi(31)
    private object Api31 {
        @SuppressLint("MissingPermission")
        fun register(ctx: Context, tm: TelephonyManager): Any {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) { HistoryStore.onCallState(map(state)) }
            }
            tm.registerTelephonyCallback(ctx.mainExecutor, cb)
            return cb
        }
    }

    @Suppress("DEPRECATION")
    private object Old {
        fun register(tm: TelephonyManager): Any {
            val l = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) { HistoryStore.onCallState(map(state)) }
            }
            tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            return l
        }
    }
}
