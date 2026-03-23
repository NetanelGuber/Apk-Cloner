package com.guber.apkcloner.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

object PackageUtils {

	data class AppInfo(
		val packageName: String,
		val label: String,
		val icon: Drawable,
		val sourceDir: String,
		val isSystemApp: Boolean
	)

	fun getInstalledApps(context: Context, includeSystem: Boolean = false): List<AppInfo> {
		val pm = context.packageManager
		return pm.getInstalledApplications(PackageManager.GET_META_DATA)
			.filter { includeSystem || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
			.map { appInfo ->
				AppInfo(
					packageName = appInfo.packageName,
					label = pm.getApplicationLabel(appInfo).toString(),
					icon = pm.getApplicationIcon(appInfo),
					sourceDir = appInfo.sourceDir,
					isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
				)
			}
			.sortedBy { it.label.lowercase() }
	}

	fun getAppLabel(context: Context, packageName: String): String {
		val pm = context.packageManager
		val appInfo = pm.getApplicationInfo(packageName, 0)
		return pm.getApplicationLabel(appInfo).toString()
	}
}
