package com.abusaeed.netpulse

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Speed test against Cloudflare's edge (speed.cloudflare.com):
 * ping + jitter, then multi-stream download and upload for a fixed time.
 */
class SpeedTest(private val listener: Listener) {

    enum class Phase { PING, DOWNLOAD, UPLOAD, DONE }

    interface Listener {
        /** [mbps] = live speed (or ping ms during PING), [progress] 0..1 of the whole test. */
        fun onProgress(phase: Phase, value: Double, progress: Float)
        fun onDone(result: Result?, error: String?)
    }

    data class Result(
        val time: Long, val pingMs: Double, val jitterMs: Double,
        val downMbps: Double, val upMbps: Double, val dataMb: Double,
        val operator: String, val network: String, val level: Int?, val cell: String,
        val lat: Double?, val lon: Double?
    )

    @Volatile
    var cancelled = false
        private set
    private val main = Handler(Looper.getMainLooper())

    fun cancel() { cancelled = true }

    fun start(operator: String, network: String, level: Int?, cell: String, lat: Double?, lon: Double?) {
        Thread {
            try {
                val (ping, jitter) = measurePing()
                if (cancelled) throw InterruptedException()
                val (down, downBytes) = measure(download = true, progressFrom = 0.15f)
                if (cancelled) throw InterruptedException()
                val (up, upBytes) = measure(download = false, progressFrom = 0.575f)
                if (cancelled) throw InterruptedException()
                val r = Result(
                    System.currentTimeMillis(), ping, jitter, down, up,
                    (downBytes + upBytes) / 1_000_000.0, operator, network, level, cell, lat, lon
                )
                main.post { listener.onProgress(Phase.DONE, 0.0, 1f); listener.onDone(r, null) }
            } catch (e: InterruptedException) {
                main.post { listener.onDone(null, null) }
            } catch (e: Exception) {
                main.post { listener.onDone(null, e.message ?: e.javaClass.simpleName) }
            }
        }.start()
    }

    private fun post(phase: Phase, value: Double, progress: Float) {
        main.post { listener.onProgress(phase, value, progress) }
    }

    // ---------- ping ----------
    private fun measurePing(): Pair<Double, Double> {
        val samples = ArrayList<Double>()
        for (i in 0 until 11) {
            if (cancelled) break
            val t0 = SystemClock.elapsedRealtimeNanos()
            val c = URL("$BASE/__down?bytes=0").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.useCaches = false
            c.inputStream.use { it.readBytes() }
            val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            if (i > 0) { // first request includes DNS + TLS handshake
                samples.add(ms)
                post(Phase.PING, ms, 0.15f * i / 10f)
            }
        }
        if (samples.isEmpty()) throw Exception("No response from server")
        val sorted = samples.sorted()
        val ping = sorted[sorted.size / 2]
        val jitter = if (samples.size < 2) 0.0 else
            samples.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average()
        return ping to jitter
    }

    // ---------- download / upload ----------
    private fun measure(download: Boolean, progressFrom: Float): Pair<Double, Long> {
        val phase = if (download) Phase.DOWNLOAD else Phase.UPLOAD
        val total = AtomicLong(0)
        val start = SystemClock.elapsedRealtime()
        val end = start + DURATION_MS
        val threads = (0 until STREAMS).map {
            Thread {
                try {
                    while (!cancelled && SystemClock.elapsedRealtime() < end && total.get() < MAX_BYTES) {
                        if (download) downloadOnce(total, end) else uploadOnce(total, end)
                    }
                } catch (e: Exception) { /* a stream failing is OK */ }
            }.also { it.start() }
        }
        // measure, ignoring the first WARMUP_MS (TCP slow start)
        var warmBytes = -1L
        var warmAt = 0L
        var lastBytes = 0L
        var lastAt = start
        while (!cancelled && SystemClock.elapsedRealtime() < end && total.get() < MAX_BYTES) {
            Thread.sleep(250)
            val now = SystemClock.elapsedRealtime()
            val b = total.get()
            if (warmBytes < 0 && now - start >= WARMUP_MS) { warmBytes = b; warmAt = now }
            val live = (b - lastBytes) * 8 / 1e6 / ((now - lastAt) / 1000.0)
            lastBytes = b; lastAt = now
            val frac = ((now - start).toFloat() / DURATION_MS).coerceIn(0f, 1f)
            post(phase, live, progressFrom + 0.425f * frac)
        }
        threads.forEach { it.join(3000) }
        val now = SystemClock.elapsedRealtime()
        val bytes = total.get()
        val mbps = if (warmBytes >= 0 && now > warmAt + 500) (bytes - warmBytes) * 8 / 1e6 / ((now - warmAt) / 1000.0)
        else bytes * 8 / 1e6 / (((now - start).coerceAtLeast(1)) / 1000.0)
        if (bytes == 0L && !cancelled) throw Exception(if (download) "Download failed" else "Upload failed")
        return mbps to bytes
    }

    private fun downloadOnce(total: AtomicLong, end: Long) {
        val c = URL("$BASE/__down?bytes=$CHUNK_DOWN").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000; c.useCaches = false
        try {
            c.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (!cancelled && SystemClock.elapsedRealtime() < end) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total.addAndGet(n.toLong())
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun uploadOnce(total: AtomicLong, end: Long) {
        val c = URL("$BASE/__up").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000; c.useCaches = false
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setFixedLengthStreamingMode(CHUNK_UP)
        try {
            c.outputStream.use { out ->
                var sent = 0
                while (sent < CHUNK_UP) {
                    if (cancelled || SystemClock.elapsedRealtime() >= end) break
                    val n = minOf(UP_BUF.size, CHUNK_UP - sent)
                    out.write(UP_BUF, 0, n)
                    sent += n
                    total.addAndGet(n.toLong())
                }
            }
            if (!cancelled && SystemClock.elapsedRealtime() < end) {
                c.inputStream.use { it.readBytes() }
            }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val BASE = "https://speed.cloudflare.com"
        private const val STREAMS = 4
        private const val DURATION_MS = 8_000L
        private const val WARMUP_MS = 1_500L
        private const val CHUNK_DOWN = 25_000_000
        private const val CHUNK_UP = 2_000_000
        private const val MAX_BYTES = 300_000_000L // safety cap per direction
        private val UP_BUF = Random.nextBytes(64 * 1024)

        // ---------- history ----------
        private fun file(ctx: Context) = File(ctx.filesDir, "speedtests.csv")
        private const val HEADER = "time,ping_ms,jitter_ms,download_mbps,upload_mbps,data_mb,operator,network,level_dbm,cell,lat,lon"

        fun save(ctx: Context, r: Result) {
            val f = file(ctx)
            val newFile = !f.exists()
            val t = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(r.time))
            fun q(s: String) = if (s.contains(',') || s.contains('"')) "\"" + s.replace("\"", "\"\"") + "\"" else s
            val line = listOf(
                t, String.format(Locale.US, "%.1f", r.pingMs), String.format(Locale.US, "%.1f", r.jitterMs),
                String.format(Locale.US, "%.2f", r.downMbps), String.format(Locale.US, "%.2f", r.upMbps),
                String.format(Locale.US, "%.1f", r.dataMb), q(r.operator), q(r.network),
                r.level?.toString() ?: "", q(r.cell),
                r.lat?.let { String.format(Locale.US, "%.6f", it) } ?: "",
                r.lon?.let { String.format(Locale.US, "%.6f", it) } ?: ""
            ).joinToString(",")
            f.appendText((if (newFile) "$HEADER\n" else "") + line + "\n")
        }

        /** Newest first. */
        fun history(ctx: Context): List<Map<String, String>> {
            val f = file(ctx)
            if (!f.exists()) return emptyList()
            return Exporter.readCsv(f).reversed()
        }

        fun historyFile(ctx: Context): File? = file(ctx).takeIf { it.exists() }

        fun clear(ctx: Context) { file(ctx).delete() }
    }
}
