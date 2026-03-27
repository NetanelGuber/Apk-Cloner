package com.guber.apkcloner.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

class InstallResultReceiver : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)

		when (status) {
			PackageInstaller.STATUS_PENDING_USER_ACTION -> {
					val confirmIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
					intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
				} else {
					@Suppress("DEPRECATION")
					intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
				}
				if (confirmIntent != null) {
					confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
					context.startActivity(confirmIntent)
				}
			}
			PackageInstaller.STATUS_SUCCESS -> {
				val localIntent = Intent(ApkInstaller.ACTION_INSTALL_STATUS).apply {
					setPackage(context.packageName)
					putExtra(PackageInstaller.EXTRA_STATUS, status)
					putExtra(
						ApkInstaller.EXTRA_PACKAGE,
						intent.getStringExtra(ApkInstaller.EXTRA_PACKAGE)
					)
				}
				context.sendBroadcast(localIntent)
			}
			else -> {
				val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
				val localIntent = Intent(ApkInstaller.ACTION_INSTALL_STATUS).apply {
					setPackage(context.packageName)
					putExtra(PackageInstaller.EXTRA_STATUS, status)
					putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, message)
					putExtra(
						ApkInstaller.EXTRA_PACKAGE,
						intent.getStringExtra(ApkInstaller.EXTRA_PACKAGE)
					)
				}
				context.sendBroadcast(localIntent)
			}
		}
	}
}
