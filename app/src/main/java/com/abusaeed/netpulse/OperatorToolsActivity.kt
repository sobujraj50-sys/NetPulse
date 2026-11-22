package com.abusaeed.netpulse

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** One-tap USSD codes per SIM (editable) + shortcuts to network settings. */
class OperatorToolsActivity : BaseScreen() {
    override val titleRes = R.string.hub_operator

    // default codes — operators change these sometimes, so each one can be edited (long-press)
    private val defaults = mapOf(
        "Grameenphone" to listOf("balance" to "*566#", "menu" to "*121#"),
        "Robi" to listOf("balance" to "*222#", "menu" to "*123#"),
        "Airtel" to listOf("balance" to "*778#", "menu" to "*121#"),
        "Banglalink" to listOf("balance" to "*124#", "menu" to "*121#"),
        "Teletalk" to listOf("balance" to "*152#"),
    )

    override fun buildContent() = render()

    private fun render() {
        content.removeAllViews()
        val subs = Monitor.reader.subs
        if (subs.isEmpty()) note(str(R.string.ops_no_sim))
        subs.forEach { sub ->
            val tm = Monitor.reader.tmFor(sub.subId)
            val code = try { tm.simOperator ?: "" } catch (e: Exception) { "" }
            val op = Operators.fromCode(code)?.name ?: sub.name
            section("SIM${sub.slot + 1} • $op")
            val c = card()
            val list = defaults[op] ?: emptyList()
            val custom = Prefs.ussd("${op}_custom", "")
            (list + if (custom.isNotBlank()) listOf("custom" to custom) else emptyList()).forEach { (kind, def) ->
                val key = "${op}_$kind"
                val ussd = Prefs.ussd(key, def)
                val label = when (kind) { "balance" -> str(R.string.ops_balance); "menu" -> str(R.string.ops_menu); else -> str(R.string.ops_custom) }
                val b = button("$label   $ussd", 0xFF00796B.toInt()) { dial(ussd) }
                b.setOnLongClickListener { edit(key, ussd); true }
                c.addView(b, mw().apply { bottomMargin = dp(6) })
            }
            c.addView(button("+ " + str(R.string.ops_add_custom), p.button) { edit("${op}_custom", custom) }, mw())
        }
        note(str(R.string.ops_note))

        section(str(R.string.ops_settings))
        val s = card()
        s.addView(button(str(R.string.ops_network_settings), p.button) {
            open(Intent(Settings.ACTION_DATA_ROAMING_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }, mw().apply { bottomMargin = dp(6) })
        s.addView(button(str(R.string.ops_radio_info), p.button) { openRadioInfo() }, mw().apply { bottomMargin = dp(6) })
        s.addView(button(str(R.string.ops_sim_settings), p.button) {
            open(Intent("android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS"), Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }, mw())
        note(str(R.string.ops_radio_note))
    }

    private fun dial(code: String) {
        try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(code)))) }
        catch (e: Exception) { Toast.makeText(this, code, Toast.LENGTH_LONG).show() }
    }

    private fun edit(key: String, current: String) {
        val input = EditText(this).apply { setText(current); hint = "*123#"; setSingleLine() }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this).setTitle(R.string.ops_edit_title).setView(box)
            .setPositiveButton(R.string.save) { _, _ -> Prefs.setUssd(key, input.text.toString().trim()); render() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun open(vararg intents: Intent) {
        for (i in intents) {
            try { startActivity(i); return } catch (e: ActivityNotFoundException) { } catch (e: SecurityException) { }
        }
        Toast.makeText(this, R.string.ops_not_available, Toast.LENGTH_SHORT).show()
    }

    /** Hidden "Phone info" screen (network mode lock etc.). Not every phone allows opening it. */
    private fun openRadioInfo() {
        val candidates = listOf(
            "com.android.phone" to "com.android.phone.settings.RadioInfo",
            "com.android.settings" to "com.android.settings.RadioInfo",
            "com.android.settings" to "com.android.settings.Settings\$RadioInfoActivity"
        )
        for ((pkg, cls) in candidates) {
            try { startActivity(Intent().setClassName(pkg, cls)); return } catch (e: Exception) { }
        }
        AlertDialog.Builder(this).setMessage(R.string.ops_radio_fallback).setPositiveButton(android.R.string.ok, null).show()
    }

    @Suppress("unused")
    private fun unusedLayout() = LinearLayout(this)
}
