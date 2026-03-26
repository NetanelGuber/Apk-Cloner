package com.guber.apkcloner

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {
	override fun onCreate() {
		super.onCreate()
		val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
		val mode = prefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
		AppCompatDelegate.setDefaultNightMode(mode)
	}
}
