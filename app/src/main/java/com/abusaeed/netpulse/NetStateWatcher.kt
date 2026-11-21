package com.abusaeed.netpulse

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi

/**
 * Tracks what the status-bar icon would show (4G+, 5G NSA, 5G+) and the
 * carrier-aggregation component carriers.
 */
class NetStateWatcher(private val ctx: Context) {

    /** TelephonyDisplayInfo override type: 0 none, 1 LTE_CA, 2 LTE_ADV_PRO, 3 NR_NSA, 4 NR_MMWAVE, 5 NR_ADVANCED */
    var overrideType = 0
        private set

    /** Bandwidth (kHz) of each component carrier in use. */
    var ccBandwidthsKhz: IntArray = IntArray(0)
        private set

    private var tm: TelephonyManager? = null
    private var callback: Any? = null

    fun attach(t: TelephonyManager) {
        detach()
        tm = t
        overrideType = 0
        try {
            callback = when {
                Build.VERSION.SDK_INT >= 31 -> Api31.register(ctx, t) { overrideType = it }
                Build.VERSION.SDK_INT == 30 -> Api30.register(t) { overrideType = it }
                else -> null
            }
        } catch (e: Exception) {
            callback = null
        }
    }

    fun detach() {
        val t = tm ?: return
        val cb = callback
        try {
            if (cb != null) {
                if (Build.VERSION.SDK_INT >= 31) Api31.unregister(t, cb) else Api30.unregister(t, cb)
            }
        } catch (e: Exception) { }
        callback = null
    }

    /** Polls ServiceState for carrier aggregation info (needs phone + location permission). */
    @SuppressLint("MissingPermission")
    fun poll() {
        val t = tm ?: return
        ccBandwidthsKhz = try {
            t.serviceState?.cellBandwidths?.filter { it > 0 }?.toIntArray() ?: IntArray(0)
        } catch (e: Exception) {
            IntArray(0)
        }
    }

    /** Short network label: 2G, 3G, 4G, 4G+ (LTE-CA), 5G NSA, 5G SA, 5G+ ... */
    fun label(tech: String?): String {
        val cc = ccBandwidthsKhz.size
        return when (overrideType) {
            1 -> "4G+ (LTE-CA)"
            2 -> "4.5G (LTE-A Pro)"
            3 -> "5G NSA"
            4, 5 -> "5G+ (NR Advanced)"
            else -> when (tech) {
                "NR" -> "5G SA"
                "LTE" -> if (cc > 1) "4G+ (LTE-CA)" else "4G LTE"
                "WCDMA" -> "3G"
                "GSM" -> "2G"
                "CDMA" -> "CDMA"
                else -> "--"
            }
        }
    }

    /** e.g. "3 CC: 20 + 15 + 10 MHz" */
    fun caText(): String {
        val cc = ccBandwidthsKhz
        if (cc.isEmpty()) return "--"
        val parts = cc.joinToString(" + ") { k -> if (k % 1000 == 0) "${k / 1000}" else String.format(java.util.Locale.US, "%.1f", k / 1000.0) }
        return if (cc.size == 1) "No CA (1 carrier, $parts MHz)" else "${cc.size} CC: $parts MHz"
    }

    @RequiresApi(31)
    private object Api31 {
        @SuppressLint("MissingPermission")
        fun register(ctx: Context, t: TelephonyManager, onType: (Int) -> Unit): Any {
            val cb = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
                override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                    onType(telephonyDisplayInfo.overrideNetworkType)
                }
            }
            t.registerTelephonyCallback(ctx.mainExecutor, cb)
            return cb
        }

        fun unregister(t: TelephonyManager, cb: Any) {
            t.unregisterTelephonyCallback(cb as TelephonyCallback)
        }
    }

    @RequiresApi(30)
    @Suppress("DEPRECATION")
    private object Api30 {
        @SuppressLint("MissingPermission")
        fun register(t: TelephonyManager, onType: (Int) -> Unit): Any {
            val l = object : PhoneStateListener() {
                override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                    onType(telephonyDisplayInfo.overrideNetworkType)
                }
            }
            t.listen(l, PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED)
            return l
        }

        fun unregister(t: TelephonyManager, cb: Any) {
            t.listen(cb as PhoneStateListener, PhoneStateListener.LISTEN_NONE)
        }
    }
}
