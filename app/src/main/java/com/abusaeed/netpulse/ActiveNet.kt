package com.abusaeed.netpulse

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.telephony.SubscriptionManager

/**
 * Describes the connection the internet is actually going through right now:
 * Wi-Fi (network name + RSSI) or mobile data (the data SIM's operator, tech and signal).
 */
object ActiveNet {

    enum class Type { WIFI, MOBILE, ETHERNET, OTHER, NONE }

    data class Info(
        val type: Type,
        /** SSID for Wi-Fi, operator for mobile data */
        val name: String,
        /** "SIM2" for mobile data */
        val sim: String?,
        /** "4G LTE", "5G NSA"… for mobile, "Wi-Fi 5 GHz" for Wi-Fi */
        val tech: String,
        /** RSRP/RSCP for mobile, RSSI for Wi-Fi (dBm) */
        val level: Int?,
        val cell: String?,
        /** Wi-Fi link speed (Mbps) */
        val linkMbps: Int? = null,
        /** Full serving-cell data of the data SIM */
        val cellData: CellData? = null
    )

    @SuppressLint("MissingPermission")
    fun get(ctx: Context): Info {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = try { cm.getNetworkCapabilities(cm.activeNetwork) } catch (e: Exception) { null }
            ?: return Info(Type.NONE, "--", null, "--", null, null)
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> wifi(ctx)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> mobile(ctx)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Info(Type.ETHERNET, "Ethernet", null, "Ethernet", null, null)
            else -> Info(Type.OTHER, "--", null, "VPN/other", null, null)
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun wifi(ctx: Context): Info {
        var ssid: String? = null
        var rssi: Int? = null
        var band = "Wi-Fi"
        var link: Int? = null
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wi = wm.connectionInfo
            ssid = wi?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            rssi = wi?.rssi?.takeIf { it in -120..-1 }
            link = wi?.linkSpeed?.takeIf { it > 0 }
            val f = wi?.frequency ?: 0
            band = when {
                f in 2400..2500 -> "Wi-Fi 2.4 GHz"
                f in 4900..5900 -> "Wi-Fi 5 GHz"
                f in 5925..7125 -> "Wi-Fi 6 GHz"
                else -> "Wi-Fi"
            }
        } catch (e: Exception) { }
        return Info(Type.WIFI, ssid ?: "Wi-Fi", null, band, rssi, null, link)
    }

    private fun techLabel(tech: String?) = when (tech) {
        "LTE" -> "4G LTE"; "NR" -> "5G"; "WCDMA" -> "3G"; "GSM" -> "2G"; "CDMA" -> "CDMA"; else -> "--"
    }

    private fun mobile(ctx: Context): Info {
        val reader = Monitor.reader
        val dataSub = try { SubscriptionManager.getDefaultDataSubscriptionId() } catch (e: Exception) { -1 }
        val slot = reader.subs.firstOrNull { it.subId == dataSub }
        val tm = if (dataSub >= 0) reader.tmFor(dataSub) else reader.tm
        val plmn = try { tm.networkOperator ?: "" } catch (e: Exception) { "" }
        val netName = try { tm.networkOperatorName ?: "" } catch (e: Exception) { "" }
        val op = Operators.fromCode(plmn)?.name ?: netName.ifBlank { slot?.name ?: "--" }

        // that SIM's serving cell: the selected one, or the other SIM's registered cell
        val snap = Monitor.snap
        val cells = listOfNotNull(snap?.serving) + (snap?.otherRegistered ?: emptyList())
        val cell = cells.firstOrNull { it.plmn() == plmn } ?: if (slot == null) snap?.serving else null
        val selectedIsData = slot == null || slot.subId == reader.subs.getOrNull(reader.subIndex)?.subId
        val tech = if (selectedIsData) Monitor.net.label(cell?.tech) else techLabel(cell?.tech)
        return Info(
            Type.MOBILE, op, slot?.let { "SIM${it.slot + 1}" }, tech, cell?.level,
            cell?.let { Monitor.dir.name(it) ?: Monitor.cellIdText(it) }, null, cell
        )
    }
}
