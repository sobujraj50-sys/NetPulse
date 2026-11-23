package com.abusaeed.netpulse

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Cell names (set by the user, stored on the phone) and tower location/address
 * (looked up from OpenCelliD, reverse-geocoded with Android's Geocoder, cached).
 */
class CellDirectory(private val ctx: Context) {

    data class Tower(val lat: Double, val lon: Double, val range: Int?, val address: String?)

    private val names = ctx.getSharedPreferences("cell_names", Context.MODE_PRIVATE)
    private val towers = ctx.getSharedPreferences("cell_towers", Context.MODE_PRIVATE)
    private val settings = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val pending = HashSet<String>()
    private val failedAt = HashMap<String, Long>()

    /** Last lookup error (bad key, no internet, ...) to show to the user. */
    var lastError: String? = null
        private set

    /** Address of the user's own position. */
    var myAddress: String? = null
        private set
    private var myAddrLoc: Location? = null
    private var myAddrAt = 0L
    private var myAddrBusy = false

    var apiKey: String
        get() = settings.getString("ocid_key", "") ?: ""
        set(v) {
            settings.edit().putString("ocid_key", v.trim()).apply()
            lastError = null
            failedAt.clear()
            // forget "not found" results so they are retried with the new key
            val e = towers.edit()
            towers.all.forEach { (k, v2) -> if (v2.toString().startsWith("NF")) e.remove(k) }
            e.apply()
        }

    fun key(c: CellData): String? {
        if (c.cellId == null || c.lac == null || c.mcc == null || c.mnc == null) return null
        return "${c.tech}:${c.mcc}-${c.mnc}:${c.lac}:${c.cellId}"
    }

    // ---------- names ----------
    /** Name typed by the user, else the confirmed / matched site from the imported list. */
    fun name(c: CellData): String? = userName(c) ?: SiteDb.siteFor(c)?.name

    fun userName(c: CellData): String? = key(c)?.let { names.getString(it, null) }

    fun setName(c: CellData, name: String) {
        val k = key(c) ?: return
        if (name.isBlank()) names.edit().remove(k).apply() else names.edit().putString(k, name.trim()).apply()
    }

    fun nameCount(): Int = names.all.size

    // ---------- towers ----------
    /** Tower position: the user's site list first (exact), then OpenCelliD. */
    fun tower(c: CellData): Tower? {
        SiteDb.siteFor(c)?.let { s ->
            if (!s.lat.isNaN()) return Tower(s.lat, s.lon, null, listOf(s.address, s.area).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null })
        }
        return ocidTower(c)
    }

    fun ocidTower(c: CellData): Tower? = key(c)?.let { towerByKey(it) }

    fun towerByKey(k: String): Tower? {
        val v = towers.getString(k, null) ?: return null
        if (v.startsWith("NF")) return null
        val p = v.split('|')
        val lat = p.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lon = p.getOrNull(1)?.toDoubleOrNull() ?: return null
        return Tower(lat, lon, p.getOrNull(2)?.toIntOrNull(), p.getOrNull(3)?.ifBlank { null })
    }

    fun notFound(c: CellData): Boolean = key(c)?.let { towers.getString(it, null)?.startsWith("NF") } == true

    fun isLooking(c: CellData): Boolean = key(c)?.let { it in pending } == true

    /** Starts a background lookup if needed; [onDone] runs on the main thread. */
    fun lookup(c: CellData, onDone: () -> Unit) {
        val k = key(c) ?: return
        if (apiKey.isBlank() || k in pending) return
        val cached = towers.getString(k, null)
        if (cached != null) {
            if (!cached.startsWith("NF")) return
            val t = cached.removePrefix("NF").toLongOrNull() ?: 0L
            if (System.currentTimeMillis() - t < 24 * 3600_000L) return // retry "not found" once a day
        }
        failedAt[k]?.let { if (System.currentTimeMillis() - it < 60_000) return }
        pending.add(k)
        val key = apiKey
        exec.execute {
            var result: String? = null
            var err: String? = null
            try {
                val found = fetch(key, c)
                result = if (found == null) "NF${System.currentTimeMillis()}" else {
                    val addr = geocode(found.first, found.second) ?: ""
                    "${found.first}|${found.second}|${found.third ?: ""}|${addr.replace('|', '/')}"
                }
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
            main.post {
                pending.remove(k)
                if (result != null) {
                    towers.edit().putString(k, result).apply()
                    lastError = null
                } else {
                    failedAt[k] = System.currentTimeMillis()
                    lastError = err
                }
                onDone()
            }
        }
    }

    /** Returns lat, lon, range — or null when the cell is not in the database. */
    private fun fetch(key: String, c: CellData): Triple<Double, Double, Int?>? {
        val radio = when (c.tech) { "LTE" -> "LTE"; "WCDMA" -> "UMTS"; "GSM" -> "GSM"; "NR" -> "NR"; else -> "CDMA" }
        val mnc = c.mnc?.toIntOrNull() ?: c.mnc
        val url = "https://opencellid.org/cell/get?key=" + URLEncoder.encode(key, "UTF-8") +
            "&mcc=${c.mcc}&mnc=$mnc&lac=${c.lac}&cellid=${c.cellId}&radio=$radio&format=json"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            val code = conn.responseCode
            val body = (if (code < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val lower = body.lowercase(Locale.US)
            if ("invalid" in lower && ("key" in lower || "token" in lower)) throw Exception("OpenCelliD API key is invalid")
            if (code == 401 || code == 403) throw Exception("OpenCelliD API key was refused ($code)")
            if ("limit" in lower && ("exceed" in lower || "daily" in lower)) throw Exception("OpenCelliD daily limit reached")
            // JSON response
            try {
                val j = JSONObject(body)
                if (j.has("lat") && j.has("lon")) {
                    val lat = j.getDouble("lat"); val lon = j.getDouble("lon")
                    if (lat == 0.0 && lon == 0.0) return null
                    return Triple(lat, lon, if (j.has("range")) j.optInt("range") else null)
                }
                val err = j.optString("error", j.optString("message", ""))
                val low = err.lowercase(Locale.US)
                if ("token" in low || "key" in low || "unauthor" in low || "denied" in low)
                    throw Exception("OpenCelliD API key problem: $err")
                if ("limit" in low) throw Exception("OpenCelliD daily limit reached")
                if (err.isNotBlank() && "not found" !in low && "no cell" !in low && "no match" !in low)
                    throw Exception("OpenCelliD: $err")
                if (j.has("error") || j.has("code")) return null
            } catch (e: org.json.JSONException) { /* maybe XML */ }
            // XML response fallback
            val lat = Regex("lat=\"(-?[0-9.]+)\"").find(body)?.groupValues?.get(1)?.toDoubleOrNull()
            val lon = Regex("lon=\"(-?[0-9.]+)\"").find(body)?.groupValues?.get(1)?.toDoubleOrNull()
            val range = Regex("range=\"([0-9]+)\"").find(body)?.groupValues?.get(1)?.toIntOrNull()
            if (lat != null && lon != null && !(lat == 0.0 && lon == 0.0)) return Triple(lat, lon, range)
            if (code >= 500) throw Exception("OpenCelliD server error $code")
            return null
        } finally {
            conn.disconnect()
        }
    }

    private val plusCode = Regex("^[23456789CFGHJMPQRVWX]{2,8}\\+[23456789CFGHJMPQRVWX]{0,3},?\\s*")

    /** Blocking reverse geocode (call from a background thread). */
    fun addressFor(lat: Double, lon: Double): String? = geocode(lat, lon)

    @Suppress("DEPRECATION")
    private fun geocode(lat: Double, lon: Double): String? = try {
        if (!Geocoder.isPresent()) null
        else Geocoder(ctx, Locale.ENGLISH).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { a ->
            if (a.maxAddressLineIndex >= 0)
                (0..a.maxAddressLineIndex).joinToString(", ") { a.getAddressLine(it) }.replace(plusCode, "").trim()
            else listOfNotNull(a.subLocality, a.locality, a.subAdminArea, a.adminArea).joinToString(", ")
        }
    } catch (e: Exception) {
        null
    }

    /** Updates [myAddress] when the user moved >150 m or every 2 minutes. */
    fun updateMyAddress(loc: Location?, onDone: () -> Unit) {
        if (loc == null || myAddrBusy) return
        val prev = myAddrLoc
        val now = System.currentTimeMillis()
        if (prev != null && prev.distanceTo(loc) < 150 && now - myAddrAt < 120_000) return
        myAddrBusy = true
        myAddrAt = now
        myAddrLoc = loc
        exec.execute {
            val a = geocode(loc.latitude, loc.longitude)
            main.post {
                myAddrBusy = false
                if (a != null) myAddress = a
                onDone()
            }
        }
    }
}
