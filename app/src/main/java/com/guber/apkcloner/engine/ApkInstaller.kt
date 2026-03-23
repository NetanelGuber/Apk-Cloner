package com.guber.apkcloner.engine

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.io.FileInputStream

class ApkInstaller(private val context: Context) {

	companion object {
		const val ACTION_INSTALL_RESULT = "com.guber.apkcloner.INSTALL_RESULT"
		const val ACTION_INSTALL_STATUS = "com.guber.apkcloner.INSTALL_STATUS"
		const val EXTRA_STATUS = "extra_status"
		const val EXTRA_PACKAGE = "extra_package"
	}

	fun canInstallPackages(): Boolean {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			context.packageManager.canRequestPackageInstalls()
		} else {
			true
		}
	}

	fun getInstallPermissionIntent(): Intent? {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			if (!context.packageManager.canRequestPackageInstalls()) {
				return Intent(
					Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
					Uri.parse("package:${context.packageName}")
				)
			}
		}
		return null
	}

	fun install(signedApk: File, packageName: String) {
		val installer = context.packageManager.packageInstaller
		val params = PackageInstaller.SessionParams(
			PackageInstaller.SessionParams.MODE_FULL_INSTALL
		)
		params.setAppPackageName(packageName)
		params.setSize(signedApk.length())

		val sessionId = installer.createSession(params)
		val session = installer.openSession(sessionId)

		try {
			session.openWrite("base", 0, signedApk.length()).use { output ->
				FileInputStream(signedApk).use { input ->
					input.copyTo(output)
					session.fsync(output)
				}
			}

			val intent = Intent(ACTION_INSTALL_RESULT).apply {
				setPackage(context.packageName)
				putExtra(EXTRA_PACKAGE, packageName)
			}
			val pendingIntent = PendingIntent.getBroadcast(
				context, sessionId, intent,
				PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
			)

			session.commit(pendingIntent.intentSender)
			session.close()
		} catch (e: Exception) {
			session.abandon()
			throw e
		}
	}

	fun installMultiApk(signedApks: List<File>, packageName: String) {
		val installer = context.packageManager.packageInstaller
		val params = PackageInstaller.SessionParams(
			PackageInstaller.SessionParams.MODE_FULL_INSTALL
		)
		params.setAppPackageName(packageName)

		var totalSize = 0L
		for (apk in signedApks) {
			totalSize += apk.length()
		}
		params.setSize(totalSize)

		val sessionId = installer.createSession(params)
		val session = installer.openSession(sessionId)

		try {
			for ((i, apk) in signedApks.withIndex()) {
				val name = if (i == 0) "base" else "split_$i"
				session.openWrite(name, 0, apk.length()).use { output ->
					FileInputStream(apk).use { input ->
						input.copyTo(output)
						session.fsync(output)
					}
				}
			}

			val intent = Intent(ACTION_INSTALL_RESULT).apply {
				setPackage(context.packageName)
				putExtra(EXTRA_PACKAGE, packageName)
			}
			val pendingIntent = PendingIntent.getBroadcast(
				context, sessionId, intent,
				PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
			)

			session.commit(pendingIntent.intentSender)
			session.close()
		} catch (e: Exception) {
			session.abandon()
			throw e
		}
	}
}
