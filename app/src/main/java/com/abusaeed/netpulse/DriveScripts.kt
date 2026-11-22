package com.abusaeed.netpulse

import android.os.SystemClock

/**
 * Optional tests during a drive test: a latency check every 10 s and a 5-second
 * download every 2 minutes. Results go into the CSV log next to the signal data.
 */
object DriveScripts {
    @Volatile private var ping: Double? = null
    @Volatile private var pingAt = 0L
    @Volatile private var dl: Double? = null
    @Volatile private var dlAt = 0L
    @Volatile private var pingBusy = false
    @Volatile private var dlBusy = false
    private var nextPing = 0L
    private var nextDl = 0L

    fun tick() {
        if (!Prefs.driveTests) return
        val now = SystemClock.elapsedRealtime()
        if (now >= nextPing && !pingBusy) {
            nextPing = now + 10_000
            pingBusy = true
            Thread { ping = NetUtil.httpRtt(); pingAt = SystemClock.elapsedRealtime(); pingBusy = false }.start()
        }
        if (now >= nextDl && !dlBusy) {
            nextDl = now + 120_000
            dlBusy = true
            Thread {
                dl = NetUtil.downloadFor("https://speed.cloudflare.com/__down?bytes=50000000", 5000).first
                dlAt = SystemClock.elapsedRealtime(); dlBusy = false
            }.start()
        }
    }

    /** Values measured in the last few seconds (each is written to the log once). */
    fun takePing(): Double? {
        val v = ping ?: return null
        if (SystemClock.elapsedRealtime() - pingAt > 12_000) return null
        ping = null
        return v
    }

    fun takeDl(): Double? {
        val v = dl ?: return null
        if (SystemClock.elapsedRealtime() - dlAt > 15_000) return null
        dl = null
        return v
    }

    fun reset() { ping = null; dl = null; nextPing = 0L; nextDl = 0L }
}
