package com.abusaeed.netpulse

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Locale

/**
 * "Why is my internet slow?" — checks signal, signal quality, latency, packet loss,
 * DNS and a short speed sample, then explains the likely cause in plain words.
 */
object Diagnose {

    /** sev: 0 = OK, 1 = warning, 2 = problem */
    data class Finding(val sev: Int, val title: Int, val detail: Int, val args: List<Any> = emptyList())

    data class Report(
        val findings: List<Finding>, val pingMs: Double?, val lossPct: Int?,
        val dnsMs: Double?, val mbps: Double?, val net: ActiveNet.Info
    )

    interface Listener {
        fun onStep(step: Int)
        fun onDone(r: Report)
    }

    private const val BASE = "https://speed.cloudflare.com"

    fun run(ctx: Context, l: Listener) {
        val net = ActiveNet.get(ctx)
        val cc = Monitor.net.ccBandwidthsKhz.size
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val dataSaver = try { cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED } catch (e: Exception) { false }
        val main = Handler(Looper.getMainLooper())
        Thread {
            val f = ArrayList<Finding>()
            main.post { l.onStep(R.string.diag_step_signal) }
            if (net.type == ActiveNet.Type.NONE) {
                f.add(Finding(2, R.string.f_noconn_t, R.string.f_noconn_d))
                main.post { l.onDone(Report(f, null, null, null, null, net)) }
                return@Thread
            }
            var signalOk = true
            if (net.type == ActiveNet.Type.MOBILE) {
                val c = net.cellData
                val lv = c?.level
                val lte = c?.tech == "LTE" || c?.tech == "NR"
                if (c?.tech == "WCDMA" || c?.tech == "GSM") {
                    f.add(Finding(1, R.string.f_old_tech_t, R.string.f_old_tech_d, listOf(if (c?.tech == "GSM") "2G" else "3G")))
                }
                if (lv != null) {
                    val weak = if (lte) -115 else -105
                    val fair = if (lte) -105 else -95
                    when {
                        lv < weak -> { f.add(Finding(2, R.string.f_sig_weak_t, R.string.f_sig_weak_d, listOf(lv))); signalOk = false }
                        lv < fair -> f.add(Finding(1, R.string.f_sig_fair_t, R.string.f_sig_fair_d, listOf(lv)))
                        else -> f.add(Finding(0, R.string.f_sig_good_t, R.string.f_sig_good_d, listOf(lv)))
                    }
                }
                val q = c?.qual
                if (lte && q != null && (lv == null || lv >= -110)) {
                    when {
                        q < -15 -> { f.add(Finding(2, R.string.f_busy_t, R.string.f_busy_d, listOf(q))); signalOk = false }
                        q < -12 -> f.add(Finding(1, R.string.f_busy_t, R.string.f_busy_d, listOf(q)))
                    }
                }
                val snr = c?.snr
                if (snr != null && snr < 0) f.add(Finding(1, R.string.f_noise_t, R.string.f_noise_d, listOf(String.format(Locale.US, "%.1f", snr))))
                if (cc > 1) f.add(Finding(0, R.string.f_ca_t, R.string.f_ca_d, listOf(cc)))
            } else if (net.type == ActiveNet.Type.WIFI) {
                val r = net.level
                if (r != null) {
                    when {
                        r < -75 -> { f.add(Finding(2, R.string.f_wifi_weak_t, R.string.f_wifi_weak_d, listOf(r))); signalOk = false }
                        r < -67 -> f.add(Finding(1, R.string.f_wifi_fair_t, R.string.f_wifi_fair_d, listOf(r)))
                        else -> f.add(Finding(0, R.string.f_wifi_good_t, R.string.f_sig_good_d, listOf(r)))
                    }
                    if (net.tech.contains("2.4") && r > -60) f.add(Finding(1, R.string.f_wifi_24_t, R.string.f_wifi_24_d))
                }
                net.linkMbps?.let { if (it < 20) f.add(Finding(1, R.string.f_wifi_link_t, R.string.f_wifi_link_d, listOf(it))) }
            }
            if (dataSaver) f.add(Finding(1, R.string.f_datasaver_t, R.string.f_datasaver_d))

            // latency & loss
            main.post { l.onStep(R.string.diag_step_ping) }
            val rtts = ArrayList<Double>()
            var fails = 0
            for (i in 0 until 9) {
                try {
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    val c = URL("$BASE/__down?bytes=0").openConnection() as HttpURLConnection
                    c.connectTimeout = 3000; c.readTimeout = 3000; c.useCaches = false
                    c.inputStream.use { it.readBytes() }
                    if (i > 0) rtts.add((SystemClock.elapsedRealtimeNanos() - t0) / 1e6)
                } catch (e: Exception) {
                    if (i > 0) fails++
                }
            }
            val ping = rtts.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
            val loss = (fails * 100) / 8
            if (ping == null) {
                f.add(Finding(2, R.string.f_noconn_t, R.string.f_noconn_d))
                main.post { l.onDone(Report(f.sortedByDescending { it.sev }, null, 100, null, null, net)) }
                return@Thread
            }
            when {
                ping > 150 -> f.add(Finding(2, R.string.f_ping_high_t, R.string.f_ping_high_d, listOf(ping.toInt())))
                ping > 80 -> f.add(Finding(1, R.string.f_ping_high_t, R.string.f_ping_high_d, listOf(ping.toInt())))
                else -> f.add(Finding(0, R.string.f_ping_good_t, R.string.f_ping_good_d, listOf(ping.toInt())))
            }
            if (loss > 0) f.add(Finding(if (loss >= 25) 2 else 1, R.string.f_loss_t, R.string.f_loss_d, listOf(loss)))

            // DNS (a random name, so the answer can't come from a cache)
            main.post { l.onStep(R.string.diag_step_dns) }
            val dns = ArrayList<Double>()
            for (i in 0 until 3) {
                val t0 = SystemClock.elapsedRealtimeNanos()
                try { InetAddress.getAllByName("np${System.nanoTime() % 1000000}x$i.cloudflare.com") } catch (e: Exception) { }
                dns.add((SystemClock.elapsedRealtimeNanos() - t0) / 1e6)
            }
            val dnsMs = dns.sorted()[1]
            if (dnsMs > 300) f.add(Finding(1, R.string.f_dns_t, R.string.f_dns_d, listOf(dnsMs.toInt())))

            // short speed sample
            main.post { l.onStep(R.string.diag_step_speed) }
            var mbps: Double? = null
            try {
                val c = URL("$BASE/__down?bytes=25000000").openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 5000; c.useCaches = false
                val start = SystemClock.elapsedRealtime()
                var bytes = 0L
                var warmAt = 0L
                var warmBytes = 0L
                c.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (SystemClock.elapsedRealtime() - start < 4500) {
                        val n = input.read(buf)
                        if (n < 0) break
                        bytes += n
                        if (warmAt == 0L && SystemClock.elapsedRealtime() - start > 1000) { warmAt = SystemClock.elapsedRealtime(); warmBytes = bytes }
                    }
                }
                c.disconnect()
                val now = SystemClock.elapsedRealtime()
                mbps = if (warmAt > 0 && now > warmAt) (bytes - warmBytes) * 8 / 1e6 / ((now - warmAt) / 1000.0)
                else bytes * 8 / 1e6 / ((now - start).coerceAtLeast(1) / 1000.0)
            } catch (e: Exception) { }
            mbps?.let { m ->
                val txt = String.format(Locale.US, "%.1f", m)
                when {
                    m < 1 -> f.add(Finding(2, R.string.f_speed_low_t, R.string.f_speed_low_d, listOf(txt)))
                    m < 5 -> f.add(Finding(1, R.string.f_speed_low_t, R.string.f_speed_low_d, listOf(txt)))
                    else -> f.add(Finding(0, R.string.f_speed_good_t, R.string.f_speed_good_d, listOf(txt)))
                }
                if (m < 5 && signalOk && ping < 120 && loss == 0) f.add(Finding(2, R.string.f_network_side_t, R.string.f_network_side_d))
            }
            val sorted = f.sortedByDescending { it.sev }
            main.post { l.onDone(Report(sorted, ping, loss, dnsMs, mbps, net)) }
        }.start()
    }
}
