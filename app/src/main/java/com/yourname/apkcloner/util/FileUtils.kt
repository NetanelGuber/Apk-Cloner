package com.yourname.apkcloner.util

import android.content.Context
import android.os.StatFs
import java.io.File

object FileUtils {

	fun getCloneWorkDir(context: Context, newPackageName: String): File {
		val workDir = File(context.cacheDir, "clone_workspace/$newPackageName")
		workDir.deleteRecursively()
		workDir.mkdirs()
		return workDir
	}

	fun cleanupWorkDir(workDir: File) {
		workDir.deleteRecursively()
	}

	fun checkAvailableSpace(context: Context, requiredBytes: Long) {
		val stat = StatFs(context.cacheDir.path)
		val available = stat.availableBytes
		if (available < requiredBytes) {
			throw InsufficientStorageException(
				"Need ${requiredBytes / (1024 * 1024)} MB but only ${available / (1024 * 1024)} MB available"
			)
		}
	}

	fun estimateRequiredSpace(apkFile: File): Long {
		return apkFile.length() * 3
	}
}

class InsufficientStorageException(message: String) : Exception(message)
