package com.hisabbaki.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class HisabApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(Prefs.getThemeMode(this))
    }
}
