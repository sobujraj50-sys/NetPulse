package com.abusaeed.netpulse

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi

internal fun Int.ok(): Int? = if (this == Int.MAX_VALUE || this == Int.MIN_VALUE) null else this
internal fun Long.ok(): Long? = if (this == Long.MAX_VALUE || this == Long.MIN_VALUE) null else this
private fun Int?.inRange(lo: Int, hi: Int): Int? = if (this != null && this in lo..hi) this else null

data class CellData(
    val tech: String,
    val registered: Boolean,
    val mcc: String?,
    val mnc: String?,
    val lac: Int?,          // LAC or TAC
    val cellId: Long?,      // full CI / ECI / NCI
    val node: Long?,        // eNB / gNB / RNC
    val localCid: Long?,
    val pci: Int?,          // PCI / PSC / BSIC
    val arfcn: Int?,
    val band: String?,
    val dl: Double?,
    val ul: Double?,
    val bw: Double?,
    val level: Int?,        // RSRP / RSCP / RSSI (dBm)
    val qual: Int?,         // RSRQ / EcNo (dB)
    val snr: Double?,
    val rssi: Int?,
    val ta: Int?,
    val pciLabel: String,
    val areaLabel: String,
    val nodeLabel: String,
) {
    fun plmn(): String? = if (mcc != null && mnc != null) mcc + mnc else null
}

data class Snapshot(
    val operatorName: String,
    val operatorCode: String,
    val serving: CellData?,
    val otherRegistered: List<CellData>,
    val neighbors: List<CellData>,
    val total: Int,
)

class CellReader(private val ctx: Context) {
    data class SimSlot(val subId: Int, val slot: Int, val name: String)

    private val baseTm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    var tm: TelephonyManager = baseTm
        private set
    var subs: List<SimSlot> = emptyList()
        private set
    var subIndex = 0
        private set

    private var cached: List<CellInfo>? = null
    private var cachedAt = 0L

    @SuppressLint("MissingPermission")
    fun loadSubs() {
        subs = try {
            val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            sm.activeSubscriptionInfoList?.map {
                SimSlot(it.subscriptionId, it.simSlotIndex, (it.carrierName ?: it.displayName ?: "SIM").toString())
            }?.sortedBy { it.slot } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        selectSub(subIndex.coerceAtMost(maxOf(0, subs.size - 1)))
    }

    private fun selectSub(i: Int) {
        subIndex = i
        tm = subs.getOrNull(i)?.let {
            try { baseTm.createForSubscriptionId(it.subId) } catch (e: Exception) { baseTm }
        } ?: baseTm
        cached = null
    }

    fun nextSub() {
        if (subs.size > 1) selectSub((subIndex + 1) % subs.size)
    }

    fun simLabel(): String = subs.getOrNull(subIndex)?.let { "SIM${it.slot + 1}" } ?: "SIM"
    fun simName(): String = subs.getOrNull(subIndex)?.name ?: ""

    @SuppressLint("MissingPermission")
    fun requestUpdate() {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                tm.requestCellInfoUpdate(ctx.mainExecutor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        cached = cellInfo
                        cachedAt = SystemClock.elapsedRealtime()
                    }
                })
            } catch (e: Exception) { /* ignore */ }
        }
    }

    @SuppressLint("MissingPermission")
    fun snapshot(): Snapshot {
        val c = cached
        val raw: List<CellInfo> = if (c != null && c.isNotEmpty() && SystemClock.elapsedRealtime() - cachedAt < 4000) c
        else try { tm.allCellInfo ?: emptyList() } catch (e: Exception) { emptyList() }

        val cells = raw.mapNotNull { CellParser.parse(it) }
        val op = try { tm.networkOperator ?: "" } catch (e: Exception) { "" }
        val opName = try { tm.networkOperatorName ?: "" } catch (e: Exception) { "" }
        val registered = cells.filter { it.registered }
        val serving = registered.firstOrNull { it.plmn() == op }
            ?: registered.firstOrNull { it.plmn() == null }
            ?: registered.getOrNull(subIndex)
            ?: registered.firstOrNull()
        val others = registered.filter { it !== serving }
        val neighbors = cells.filter { !it.registered }.sortedByDescending { it.level ?: -999 }
        return Snapshot(opName, op, serving, others, neighbors, cells.size)
    }
}

object CellParser {
    fun parse(info: CellInfo): CellData? = try {
        when {
            info is CellInfoLte -> lte(info)
            Build.VERSION.SDK_INT >= 29 && NrParser.isNr(info) -> NrParser.parse(info)
            info is CellInfoWcdma -> wcdma(info)
            info is CellInfoGsm -> gsm(info)
            info is CellInfoCdma -> cdma(info)
            else -> null
        }
    } catch (e: Throwable) {
        null
    }

    @Suppress("DEPRECATION")
    private fun lte(info: CellInfoLte): CellData {
        val id = info.cellIdentity
        val s = info.cellSignalStrength
        val ci = id.ci.ok()?.toLong()
        val earfcn = id.earfcn.ok()
        val calc = earfcn?.let { Bands.lte(it) }
        val reportedBand = if (Build.VERSION.SDK_INT >= 30) id.bands.firstOrNull() else null
        val band = reportedBand?.let { Bands.lteLabel(it) } ?: calc?.band
        val bw = if (Build.VERSION.SDK_INT >= 28) id.bandwidth.ok()?.let { it / 1000.0 } else null
        val mcc = if (Build.VERSION.SDK_INT >= 28) id.mccString else id.mcc.ok()?.toString()
        val mnc = if (Build.VERSION.SDK_INT >= 28) id.mncString else id.mnc.ok()?.toString()?.padStart(2, '0')
        val rssi = if (Build.VERSION.SDK_INT >= 29) s.rssi.ok().inRange(-120, -20) else null
        return CellData(
            "LTE", info.isRegistered, mcc, mnc, id.tac.ok(), ci,
            ci?.shr(8), ci?.and(0xFF), id.pci.ok(), earfcn, band, calc?.dl, calc?.ul, bw,
            s.rsrp.ok().inRange(-160, -30), s.rsrq.ok().inRange(-40, 20),
            s.rssnr.ok()?.let { it / 10.0 }, rssi, s.timingAdvance.ok().inRange(0, 1300),
            "PCI", "TAC", "eNB"
        )
    }

    @Suppress("DEPRECATION")
    private fun wcdma(info: CellInfoWcdma): CellData {
        val id = info.cellIdentity
        val s = info.cellSignalStrength
        val cid = id.cid.ok()?.toLong()
        val uarfcn = id.uarfcn.ok()
        val calc = uarfcn?.let { Bands.umts(it) }
        val mcc = if (Build.VERSION.SDK_INT >= 28) id.mccString else id.mcc.ok()?.toString()
        val mnc = if (Build.VERSION.SDK_INT >= 28) id.mncString else id.mnc.ok()?.toString()?.padStart(2, '0')
        val ecno = if (Build.VERSION.SDK_INT >= 30) s.ecNo.ok().inRange(-30, 0) else null
        return CellData(
            "WCDMA", info.isRegistered, mcc, mnc, id.lac.ok(), cid,
            cid?.shr(16), cid?.and(0xFFFF), id.psc.ok(), uarfcn, calc?.band, calc?.dl, calc?.ul, 5.0,
            s.dbm.ok().inRange(-130, -20), ecno, null, null, null,
            "PSC", "LAC", "RNC"
        )
    }

    @Suppress("DEPRECATION")
    private fun gsm(info: CellInfoGsm): CellData {
        val id = info.cellIdentity
        val s = info.cellSignalStrength
        val cid = id.cid.ok()?.toLong()
        val arfcn = id.arfcn.ok()
        val calc = arfcn?.let { Bands.gsm(it) }
        val mcc = if (Build.VERSION.SDK_INT >= 28) id.mccString else id.mcc.ok()?.toString()
        val mnc = if (Build.VERSION.SDK_INT >= 28) id.mncString else id.mnc.ok()?.toString()?.padStart(2, '0')
        return CellData(
            "GSM", info.isRegistered, mcc, mnc, id.lac.ok(), cid,
            null, cid, id.bsic.ok(), arfcn, calc?.band, calc?.dl, calc?.ul, 0.2,
            s.dbm.ok().inRange(-120, -20), null, null, null, s.timingAdvance.ok().inRange(0, 219),
            "BSIC", "LAC", "BTS"
        )
    }

    private fun cdma(info: CellInfoCdma): CellData {
        val id = info.cellIdentity
        val s = info.cellSignalStrength
        return CellData(
            "CDMA", info.isRegistered, null, null, id.networkId.ok(), id.basestationId.ok()?.toLong(),
            id.systemId.ok()?.toLong(), id.basestationId.ok()?.toLong(), null, null, null, null, null, 1.25,
            s.cdmaDbm.ok().inRange(-130, -20), s.cdmaEcio.ok()?.let { it / 10 }, null, null, null,
            "PN", "NID", "SID"
        )
    }
}

@RequiresApi(29)
object NrParser {
    fun isNr(info: CellInfo): Boolean = info is CellInfoNr

    fun parse(info: CellInfo): CellData {
        val n = info as CellInfoNr
        val id = n.cellIdentity as CellIdentityNr
        val s = n.cellSignalStrength as CellSignalStrengthNr
        val nci = id.nci.ok()
        val arfcn = id.nrarfcn.ok()
        val calc = arfcn?.let { Bands.nr(it) }
        val reported = if (Build.VERSION.SDK_INT >= 30) id.bands.firstOrNull()?.let { "n$it" } else null
        return CellData(
            "NR", n.isRegistered, id.mccString, id.mncString, id.tac.ok(), nci,
            nci?.shr(12), nci?.and(0xFFF), id.pci.ok(), arfcn, reported ?: calc?.band, calc?.dl, calc?.ul, null,
            s.ssRsrp.ok().inRange(-160, -30), s.ssRsrq.ok().inRange(-45, 20),
            s.ssSinr.ok()?.toDouble(), null, null,
            "PCI", "TAC", "gNB"
        )
    }
}
