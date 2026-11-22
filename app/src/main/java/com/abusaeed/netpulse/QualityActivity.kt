package com.abusaeed.netpulse

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Connection quality (QoE): latency under load, call MOS, web, video, service speeds, IPv6/CGNAT/MTU. */
class QualityActivity : BaseScreen() {
    override val titleRes = R.string.hub_quality

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var results: LinearLayout
    private lateinit var startBtn: TextView
    @Volatile private var running = false
    private val report = StringBuilder()

    override fun buildContent() {
        note(str(R.string.qoe_intro))
        startBtn = button(str(R.string.qoe_start), 0xFF1565C0.toInt()) { start() }
        content.addView(startBtn, mw())
        status = tv("", 14f, p.label).apply { setPadding(0, dp(8), 0, dp(4)) }
        content.addView(status, mw())
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = android.view.View.GONE }
        content.addView(bar, mw())
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(results, mw())
    }

    override fun onPause() { super.onPause(); running = false }

    private fun step(textRes: Int, pct: Int) = ui.post { status.text = str(textRes); bar.progress = pct }

    /** sev 0 good / 1 so-so / 2 bad */
    private fun result(title: String, value: String, sev: Int, detail: String) = ui.post {
        val c = card(results)
        val icon = when (sev) { 0 -> "✅"; 1 -> "⚠️"; else -> "❌" }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(tv("$icon  $title", 15f, p.text, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(tv(value, 15f, when (sev) { 0 -> Sig.GREEN; 1 -> Sig.ORANGE; else -> Sig.RED }, true))
        c.addView(head, mw())
        if (detail.isNotBlank()) c.addView(tv(detail, 13f, p.label).apply { setPadding(0, dp(4), 0, 0) }, mw())
        report.append("$title: $value\n")
    }

    private fun start() {
        if (running) { running = false; return }
        running = true
        report.setLength(0)
        results.removeAllViews()
        bar.visibility = android.view.View.VISIBLE
        startBtn.text = str(R.string.speed_stop)
        val net = ActiveNet.get(this)
        report.append("NetPulse connection quality • ${net.name} (${net.tech})\n")
        Thread {
            try { runAll(net) } catch (e: Exception) { }
            ui.post {
                running = false
                bar.visibility = android.view.View.GONE
                status.text = str(R.string.qoe_done)
                startBtn.text = str(R.string.qoe_start)
                results.addView(button(str(R.string.qoe_share), p.button) {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report.toString())
                    }, str(R.string.qoe_share)))
                }, mw().apply { topMargin = dp(8) })
            }
        }.start()
    }

    private fun runAll(net: ActiveNet.Info) {
        if (net.type == ActiveNet.Type.NONE) { result(str(R.string.f_noconn_t), "", 2, str(R.string.f_noconn_d)); return }
        val icmp = NetUtil.icmpAvailable()
        val target = "1.1.1.1"
        fun latency(): Double? = if (icmp) NetUtil.icmp(target, 2).let { if (it.ok) it.ms else null } else NetUtil.httpRtt()

        // 1) idle latency, jitter, loss
        step(R.string.qoe_step_latency, 5)
        val idle = ArrayList<Double>(); var lost = 0
        repeat(20) { if (!running) return; val v = latency(); if (v == null) lost++ else idle.add(v); Thread.sleep(150) }
        if (idle.isEmpty()) { result(str(R.string.f_noconn_t), "", 2, str(R.string.f_noconn_d)); return }
        val idleMed = idle.sorted()[idle.size / 2]
        val jitter = if (idle.size < 2) 0.0 else idle.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average()
        val lossPct = lost * 100.0 / 20
        result(str(R.string.qoe_latency), String.format(Locale.US, "%.0f ms", idleMed), if (idleMed < 50) 0 else if (idleMed < 120) 1 else 2,
            String.format(Locale.US, "Jitter %.1f ms • Loss %.0f%% • %s", jitter, lossPct, if (icmp) "ICMP" else "HTTP"))

        // 2) bufferbloat: latency while the line is busy downloading
        step(R.string.qoe_step_load, 20)
        val busy = AtomicBoolean(true)
        val loaders = (0 until 3).map {
            Thread { while (busy.get() && running) NetUtil.downloadFor("https://speed.cloudflare.com/__down?bytes=50000000", 3000) }.also { it.start() }
        }
        Thread.sleep(1500)
        val loaded = ArrayList<Double>()
        repeat(16) { if (!running) return@repeat; latency()?.let { loaded.add(it) }; Thread.sleep(300) }
        busy.set(false); loaders.forEach { it.join(4000) }
        if (!running) return
        if (loaded.isNotEmpty()) {
            val loadMed = loaded.sorted()[loaded.size / 2]
            val inc = (loadMed - idleMed).coerceAtLeast(0.0)
            val grade = when { inc < 30 -> "A"; inc < 60 -> "B"; inc < 200 -> "C"; inc < 400 -> "D"; else -> "F" }
            result(str(R.string.qoe_bufferbloat), "$grade  (+${inc.toInt()} ms)", when (grade) { "A", "B" -> 0; "C" -> 1; else -> 2 },
                getString(R.string.qoe_bufferbloat_detail, idleMed.toInt(), loadMed.toInt()))
            // 3) call quality
            val mosIdle = NetUtil.mos(idleMed, jitter, lossPct)
            val mosLoad = NetUtil.mos(loadMed, jitter, lossPct)
            result(str(R.string.qoe_mos), String.format(Locale.US, "%.1f / 5", mosIdle), if (mosIdle >= 4.0) 0 else if (mosIdle >= 3.6) 1 else 2,
                getString(R.string.qoe_mos_detail, String.format(Locale.US, "%.1f", mosLoad)))
        }

        // 4) web browsing
        step(R.string.qoe_step_web, 45)
        val sites = listOf("https://www.google.com/", "https://www.facebook.com/", "https://www.youtube.com/",
            "https://www.wikipedia.org/", "https://www.prothomalo.com/", "https://www.daraz.com.bd/")
        val loads = ArrayList<Triple<String, Double, Double>>()
        for (url in sites) {
            if (!running) return
            val r: Triple<String, Double, Double>? = try {
                val t0 = SystemClock.elapsedRealtimeNanos()
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000; c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) NetPulse")
                val input = c.inputStream
                val ttfb = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                input.use { it.readBytes() }
                val total = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                c.disconnect()
                Triple(URL(url).host.removePrefix("www."), ttfb, total)
            } catch (e: Exception) { null }
            if (r != null) loads.add(r)
        }
        if (loads.isNotEmpty()) {
            val avg = loads.map { it.third }.average()
            result(str(R.string.qoe_web), String.format(Locale.US, "%.1f s", avg / 1000), if (avg < 1500) 0 else if (avg < 3500) 1 else 2,
                loads.joinToString("\n") { String.format(Locale.US, "%s  •  first byte %.0f ms  •  page %.1f s", it.first, it.second, it.third / 1000) } +
                    if (loads.size < sites.size) "\n" + getString(R.string.qoe_web_failed, sites.size - loads.size) else "")
        }

        // 5) video streaming estimate (sustained throughput + start-up time)
        step(R.string.qoe_step_video, 60)
        var firstChunkMs: Long? = null
        val vStart = SystemClock.elapsedRealtime()
        val (vMbps, _) = NetUtil.downloadFor("https://speed.cloudflare.com/__down?bytes=100000000", 8000) { b ->
            if (firstChunkMs == null && b >= 1_500_000) firstChunkMs = SystemClock.elapsedRealtime() - vStart
        }
        if (!running) return
        vMbps?.let { m ->
            val q = when { m >= 25 -> "4K"; m >= 8 -> "1080p"; m >= 5 -> "720p"; m >= 2.5 -> "480p"; m >= 1 -> "360p"; else -> "240p" }
            result(str(R.string.qoe_video), q, if (m >= 5) 0 else if (m >= 2.5) 1 else 2,
                getString(R.string.qoe_video_detail, String.format(Locale.US, "%.1f", m),
                    firstChunkMs?.let { String.format(Locale.US, "%.1f s", it / 1000.0) } ?: ">8 s"))
        }

        // 6) service speed comparison (indicator of traffic shaping / bad routes)
        step(R.string.qoe_step_services, 75)
        val cf = vMbps
        val (gMbps, _) = NetUtil.downloadFor("https://dl.google.com/chrome/install/googlechromestandaloneenterprise64.msi", 6000)
        if (!running) return
        if (cf != null && gMbps != null) {
            val ratio = gMbps / cf.coerceAtLeast(0.1)
            val sev = if (cf > 5 && ratio < 0.4) 2 else if (cf > 5 && ratio < 0.7) 1 else 0
            result(str(R.string.qoe_services), String.format(Locale.US, "%.0f%%", ratio * 100), sev,
                getString(R.string.qoe_services_detail, String.format(Locale.US, "%.1f", cf), String.format(Locale.US, "%.1f", gMbps)) +
                    if (sev > 0) "\n" + str(R.string.qoe_services_warn) else "")
        }

        // 7) IPv6, CGNAT, MTU
        step(R.string.qoe_step_features, 88)
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = try { cm.getLinkProperties(cm.activeNetwork) } catch (e: Exception) { null }
        val addrs = lp?.linkAddresses?.map { it.address } ?: emptyList()
        val v6 = NetUtil.hasGlobalIpv6(addrs) && NetUtil.ipv6Works()
        result("IPv6", str(if (v6) R.string.yes else R.string.no), if (v6) 0 else 1, str(if (v6) R.string.qoe_ipv6_yes else R.string.qoe_ipv6_no))

        val pub = NetUtil.publicInfo()
        val local4 = addrs.firstOrNull { it is Inet4Address }?.hostAddress
        var cgnat: Boolean? = null
        if (net.type == ActiveNet.Type.MOBILE && local4 != null && pub != null) {
            cgnat = local4 != pub.first && NetUtil.isPrivateOrCgnat(local4)
        } else if (icmp) {
            // on Wi-Fi: a carrier-grade NAT address in the first hops after the router
            cgnat = (2..5).any { ttl -> NetUtil.icmp("1.1.1.1", 1, ttl = ttl).from?.let { NetUtil.isCgnat(it) } == true }
        }
        cgnat?.let {
            result("CGNAT", str(if (it) R.string.yes else R.string.no), if (it) 1 else 0,
                (pub?.let { pi -> getString(R.string.qoe_public_fmt, pi.first, pi.second) + "\n" } ?: "") + str(if (it) R.string.qoe_cgnat_yes else R.string.qoe_cgnat_no))
        }

        if (icmp) {
            var lo = 1200; var hi = 1472; var best = 0
            while (lo <= hi && running) {
                val mid = (lo + hi) / 2
                if (NetUtil.icmp("1.1.1.1", 2, size = mid, dontFragment = true).ok) { best = mid; lo = mid + 1 } else hi = mid - 1
            }
            if (best > 0) {
                val mtu = best + 28
                result("MTU", "$mtu", if (mtu >= 1400) 0 else 1, str(if (mtu >= 1400) R.string.qoe_mtu_ok else R.string.qoe_mtu_low))
            }
        }
        step(R.string.qoe_done, 100)
    }
}
