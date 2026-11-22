package com.abusaeed.netpulse

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Fake-tower warning signs + Wi-Fi security summary. */
class SecurityActivity : BaseScreen() {
    override val titleRes = R.string.hub_security

    override fun buildContent() {
        val now = System.currentTimeMillis()
        val ev = CellGuard.events()
        val recent = ev.filter { now - it.t < 7 * 86_400_000L }
        val worst = recent.maxOfOrNull { it.sev } ?: -1
        val c = card()
        c.addView(tv(when {
            recent.isEmpty() -> "✅  " + str(R.string.sec_ok)
            worst >= 2 -> "❌  " + getString(R.string.sec_bad_fmt, recent.size)
            else -> "⚠️  " + getString(R.string.sec_warn_fmt, recent.size)
        }, 17f, when { recent.isEmpty() -> Sig.GREEN; worst >= 2 -> Sig.RED; else -> Sig.ORANGE }, true), mw())
        c.addView(tv(getString(R.string.sec_known_fmt, CellGuard.knownCount()), 13f, p.label).apply { setPadding(0, dp(4), 0, 0) }, mw())
        note(str(R.string.sec_disclaimer))

        content.addView(button(str(R.string.sec_alerts) + ": " + str(if (Prefs.secAlerts) R.string.on else R.string.off), p.button) {
            Prefs.secAlerts = !Prefs.secAlerts; recreate()
        }, mw())

        section(str(R.string.sec_events))
        if (ev.isEmpty()) note(str(R.string.sec_no_events))
        val fmt = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
        ev.take(60).forEach { e ->
            val k = card()
            val icon = when (e.sev) { 2 -> "❌"; 1 -> "⚠️"; else -> "ℹ️" }
            k.addView(tv("$icon  ${fmt.format(Date(e.t))}  •  ${e.cell}", 14f, p.text, true), mw())
            k.addView(tv(e.detail, 13f, p.label), mw())
        }

        section(str(R.string.sec_wifi))
        wifiSummary()

        if (ev.isNotEmpty()) content.addView(button(str(R.string.hist_clear), p.button) {
            AlertDialog.Builder(this).setMessage(R.string.sec_clear_confirm)
                .setPositiveButton(R.string.remove) { _, _ -> CellGuard.clear(); recreate() }
                .setNegativeButton(R.string.cancel, null).show()
        }, mw().apply { topMargin = dp(12) })
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun wifiSummary() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val list = try { wm.scanResults ?: emptyList() } catch (e: Exception) { emptyList() }
        val connected = try { wm.connectionInfo?.bssid } catch (e: Exception) { null }
        val k = card()
        if (list.isEmpty()) { k.addView(tv(str(R.string.wifi_none_found), 13f, p.label), mw()); return }
        val sec = list.map { (it.SSID ?: "") to WifiPage.securityOf(it.capabilities ?: "") }
        val mine = list.firstOrNull { connected != null && it.BSSID.equals(connected, true) }
        mine?.let { m ->
            val s = WifiPage.securityOf(m.capabilities ?: "")
            kvRow(k, str(R.string.sec_your_wifi), "${m.SSID} • $s", if (s == "Open" || s == "WEP") Sig.RED else if (s == "WPA") Sig.ORANGE else Sig.GREEN)
        }
        val twins = sec.filter { it.first.isNotBlank() }.groupBy { it.first }
            .filter { (_, l) -> l.map { it.second }.distinct().size > 1 && l.any { it.second == "Open" } }.keys
        kvRow(k, str(R.string.sec_twins), if (twins.isEmpty()) "0" else twins.joinToString(), if (twins.isEmpty()) Sig.GREEN else Sig.RED)
        kvRow(k, str(R.string.sec_open_nearby), sec.count { it.second == "Open" }.toString())
        kvRow(k, str(R.string.sec_wep_nearby), sec.count { it.second == "WEP" }.toString())
        k.addView(tv(str(R.string.sec_wifi_more), 12f, p.label).apply { setPadding(0, dp(4), 0, 0) }, mw())
    }
}
