package com.abusaeed.netpulse

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import org.osmdroid.config.Configuration

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        applyTheme()
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        Monitor.init(this)
    }

    companion object {
        fun applyTheme() {
            AppCompatDelegate.setDefaultNightMode(
                when (Prefs.theme) {
                    1 -> AppCompatDelegate.MODE_NIGHT_YES
                    2 -> AppCompatDelegate.MODE_NIGHT_NO
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }
}
