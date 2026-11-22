package com.abusaeed.netpulse

import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Low-level network measurements used by the tools and QoE tests (call from background threads). */
object NetUtil {

    data class PingReply(val ok: Boolean, val ms: Double?, val from: String?, val ttlExceeded: Boolean)

    /** One ICMP echo using the system ping binary (works without root on Android). */
    fun icmp(host: String, timeoutSec: Int = 2, ttl: Int? = null, size: Int? = null, dontFragment: Boolean = false): PingReply {
        val cmd = arrayListOf("ping", "-c", "1", "-W", timeoutSec.toString())
        ttl?.let { cmd += listOf("-t", it.toString()) }
        size?.let { cmd += listOf("-s", it.toString()) }
        if (dontFragment) cmd += listOf("-M", "do")
        cmd += host
        val t0 = SystemClock.elapsedRealtimeNanos()
        return try {
            val pr = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = pr.inputStream.bufferedReader().readText()
            pr.waitFor((timeoutSec + 2).toLong(), TimeUnit.SECONDS)
            pr.destroy()
            val wall = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            val time = Regex("time[=<]([0-9.]+)").find(out)?.groupValues?.get(1)?.toDoubleOrNull()
            val from = Regex("[Ff]rom ([0-9a-fA-F:.]+)").find(out)?.groupValues?.get(1)?.trimEnd(':')
            val exceeded = out.contains("Time to live exceeded", ignoreCase = true) || out.contains("TTL", ignoreCase = false) && out.contains("exceeded")
            when {
                time != null -> PingReply(true, time, from, false)
                exceeded -> PingReply(false, wall, from, true)
                else -> PingReply(false, null, from, false)
            }
        } catch (e: Exception) {
            PingReply(false, null, null, false)
        }
    }

    /** True when the system ping binary is usable on this phone. */
    fun icmpAvailable(): Boolean = icmp("1.1.1.1", 2).ok || icmp("8.8.8.8", 2).ok

    /** HTTP round-trip time (keep-alive reused after the first call). */
    fun httpRtt(url: String = "https://speed.cloudflare.com/__down?bytes=0", timeoutMs: Int = 3000): Double? = try {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.useCaches = false
        c.inputStream.use { it.readBytes() }
        (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
    } catch (e: Exception) { null }

    /** Raw DNS "A" query over UDP to a specific server; returns ms or null. */
    fun dnsQuery(server: String, name: String, timeoutMs: Int = 2000): Double? {
        return try {
            val id = Random.nextInt(0, 65535)
            val b = ByteArrayOutputStream()
            b.write(id shr 8); b.write(id and 0xFF)
            b.write(0x01); b.write(0x00) // recursion desired
            b.write(0); b.write(1); repeat(6) { b.write(0) }
            name.split('.').forEach { label -> b.write(label.length); b.write(label.toByteArray()) }
            b.write(0); b.write(0); b.write(1); b.write(0); b.write(1)
            val q = b.toByteArray()
            DatagramSocket().use { s ->
                s.soTimeout = timeoutMs
                val addr = InetAddress.getByName(server)
                val t0 = SystemClock.elapsedRealtimeNanos()
                s.send(DatagramPacket(q, q.size, addr, 53))
                val buf = ByteArray(512)
                val resp = DatagramPacket(buf, buf.size)
                s.receive(resp)
                val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                if (resp.length >= 2 && ((buf[0].toInt() and 0xFF) shl 8 or (buf[1].toInt() and 0xFF)) == id) ms else null
            }
        } catch (e: Exception) { null }
    }

    /** Resolve with the phone's own resolver; returns (ms, addresses). */
    fun resolve(host: String): Pair<Double, List<String>> {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val list = try { InetAddress.getAllByName(host).map { it.hostAddress.orEmpty() } } catch (e: Exception) { emptyList() }
        return (SystemClock.elapsedRealtimeNanos() - t0) / 1e6 to list
    }

    /** RFC1918 private or 100.64.0.0/10 carrier-grade NAT address. */
    fun isPrivateOrCgnat(ip: String): Boolean {
        val a = try { InetAddress.getByName(ip) } catch (e: Exception) { return false }
        if (a !is Inet4Address) return a.isSiteLocalAddress || a.isLinkLocalAddress
        val b = a.address.map { it.toInt() and 0xFF }
        return b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) || (b[0] == 100 && b[1] in 64..127)
    }

    fun isCgnat(ip: String): Boolean {
        val a = try { InetAddress.getByName(ip) } catch (e: Exception) { return false }
        if (a !is Inet4Address) return false
        val b = a.address.map { it.toInt() and 0xFF }
        return b[0] == 100 && b[1] in 64..127
    }

    /** Public IP + provider name from Cloudflare (unofficial endpoint, may stop working). */
    fun publicInfo(): Pair<String, String>? = try {
        val c = URL("https://speed.cloudflare.com/meta").openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 5000
        val j = org.json.JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        j.optString("clientIp", "") to j.optString("asOrganization", "")
    } catch (e: Exception) { null }

    /** Does an IPv6-only destination answer? (Cloudflare DNS by its IPv6 address.) */
    fun ipv6Works(): Boolean = try {
        val c = URL("https://[2606:4700:4700::1111]/cdn-cgi/trace").openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 4000
        c.inputStream.use { it.readBytes() }.isNotEmpty()
    } catch (e: Exception) { false }

    fun hasGlobalIpv6(addrs: List<InetAddress>) = addrs.any { it is Inet6Address && !it.isLinkLocalAddress && !it.isSiteLocalAddress && !it.isLoopbackAddress }

    /** Download for [ms] milliseconds; returns Mbps (after a short warm-up) and bytes. */
    fun downloadFor(url: String, ms: Long, onBytes: ((Long) -> Unit)? = null): Pair<Double?, Long> {
        var bytes = 0L
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 6000; c.useCaches = false
            val start = SystemClock.elapsedRealtime()
            var warmAt = 0L; var warmBytes = 0L
            c.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (SystemClock.elapsedRealtime() - start < ms) {
                    val n = input.read(buf)
                    if (n < 0) break
                    bytes += n
                    onBytes?.invoke(bytes)
                    if (warmAt == 0L && SystemClock.elapsedRealtime() - start > 1000) { warmAt = SystemClock.elapsedRealtime(); warmBytes = bytes }
                }
            }
            c.disconnect()
            val now = SystemClock.elapsedRealtime()
            val mbps = if (warmAt > 0 && now > warmAt + 300) (bytes - warmBytes) * 8 / 1e6 / ((now - warmAt) / 1000.0)
            else if (now > start) bytes * 8 / 1e6 / ((now - start) / 1000.0) else null
            mbps to bytes
        } catch (e: Exception) { (if (bytes > 0) 0.0 else null) to bytes }
    }

    /** ITU-T G.107 E-model (simplified): MOS from latency, jitter and loss. */
    fun mos(latencyMs: Double, jitterMs: Double, lossPct: Double): Double {
        val eff = latencyMs + 2 * jitterMs + 10
        var r = if (eff < 160) 93.2 - eff / 40 else 93.2 - (eff - 120) / 10
        r -= 2.5 * lossPct
        r = r.coerceIn(0.0, 100.0)
        return (1 + 0.035 * r + 0.000007 * r * (r - 60) * (100 - r)).coerceIn(1.0, 4.5)
    }
}
