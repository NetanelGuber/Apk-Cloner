package com.yourname.apkcloner.engine

import android.content.Context
import java.io.File

class ApkExtractor(private val context: Context) {

	data class ApkSet(
		val baseApk: File,
		val splitApks: List<File>
	)

	fun extract(packageName: String, destDir: File): ApkSet {
		val appInfo = context.packageManager.getApplicationInfo(packageName, 0)

		val destBase = File(destDir, "base.apk")
		File(appInfo.sourceDir).copyTo(destBase, overwrite = true)

		val destSplits = appInfo.splitSourceDirs
			?.mapIndexed { i, path ->
				val splitFile = File(path)
				val dest = File(destDir, "split_$i.apk")
				splitFile.copyTo(dest, overwrite = true)
				dest
			}
			?: emptyList()

		return ApkSet(destBase, destSplits)
	}
}
