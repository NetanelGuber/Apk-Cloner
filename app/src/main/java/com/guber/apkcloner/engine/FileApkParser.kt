package com.guber.apkcloner.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.zip.ZipFile

class FileApkParser(private val context: Context) {

	data class FileApkInfo(
		val packageName: String,
		val appLabel: String,
		val baseApkPath: String,
		val splitApkPaths: List<String>
	)

	fun parse(uri: Uri, stagingDir: File): FileApkInfo {
		stagingDir.deleteRecursively()
		stagingDir.mkdirs()

		val fileName = getFileName(uri)
		val ext = fileName.substringAfterLast('.', "").lowercase()

		return when (ext) {
			"apk" -> parseApk(uri, stagingDir)
			"apkm" -> parseApkm(uri, stagingDir)
			"xapk" -> parseXapk(uri, stagingDir)
			else -> throw IllegalArgumentException("Unsupported file type: .$ext (expected .apk, .apkm, or .xapk)")
		}
	}

	private fun parseApk(uri: Uri, stagingDir: File): FileApkInfo {
		val destFile = File(stagingDir, "base.apk")
		copyUriToFile(uri, destFile)
		val (pkg, label) = readApkInfo(destFile)
		return FileApkInfo(pkg, label, destFile.absolutePath, emptyList())
	}

	private fun parseApkm(uri: Uri, stagingDir: File): FileApkInfo {
		val tempFile = File(stagingDir, "source.apkm")
		copyUriToFile(uri, tempFile)

		var baseApk: File? = null
		val splits = mutableListOf<File>()

		ZipFile(tempFile).use { zip ->
			val entries = zip.entries().asSequence()
				.filter { !it.isDirectory && it.name.endsWith(".apk") }
				.toList()
			for (entry in entries) {
				if (entry.name == "base.apk") {
					val dest = File(stagingDir, "base.apk")
					zip.getInputStream(entry).use { input ->
						dest.outputStream().use { output -> input.copyTo(output) }
					}
					baseApk = dest
				} else {
					val dest = File(stagingDir, "split_${splits.size}.apk")
					zip.getInputStream(entry).use { input ->
						dest.outputStream().use { output -> input.copyTo(output) }
					}
					splits.add(dest)
				}
			}
		}

		tempFile.delete()
		val base = baseApk ?: throw IllegalStateException("No base.apk found in .apkm archive")
		val (pkg, label) = readApkInfo(base)
		return FileApkInfo(pkg, label, base.absolutePath, splits.map { it.absolutePath })
	}

	private fun parseXapk(uri: Uri, stagingDir: File): FileApkInfo {
		val tempFile = File(stagingDir, "source.xapk")
		copyUriToFile(uri, tempFile)

		var packageNameHint: String? = null
		var baseFileNameHint: String? = null
		var baseApk: File? = null
		val splits = mutableListOf<File>()

		ZipFile(tempFile).use { zip ->
			zip.getEntry("manifest.json")?.let { entry ->
				val json = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
				packageNameHint = Regex(""""package_name"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
				// Find the split_apks entry whose "id" is "base" and read its "file" name.
				// Handle both key orderings: {"file":"...","id":"base"} and {"id":"base","file":"..."}
				val fileBeforeId = Regex(""""file"\s*:\s*"([^"]+)"[^\}]*"id"\s*:\s*"base"""").find(json)
				val idBeforeFile = Regex(""""id"\s*:\s*"base"[^\}]*"file"\s*:\s*"([^"]+)"""").find(json)
				baseFileNameHint = fileBeforeId?.groupValues?.get(1) ?: idBeforeFile?.groupValues?.get(1)
			}

			val apkEntries = zip.entries().asSequence()
				.filter { !it.isDirectory && it.name.endsWith(".apk") }
				.toList()

			// Determine base APK in priority order — do NOT use file size as a heuristic
			// because ABI config splits with large .so files are often bigger than the base.
			val baseEntryName = when {
				baseFileNameHint != null && apkEntries.any { it.name == baseFileNameHint } ->
					baseFileNameHint!!
				apkEntries.any { it.name == "base.apk" } ->
					"base.apk"
				packageNameHint != null && apkEntries.any { it.name == "$packageNameHint.apk" } ->
					"$packageNameHint.apk"
				else ->
					throw IllegalStateException("Could not identify base APK in .xapk archive")
			}

			for (entry in apkEntries) {
				if (entry.name == baseEntryName) {
					val dest = File(stagingDir, "base.apk")
					zip.getInputStream(entry).use { input ->
						dest.outputStream().use { output -> input.copyTo(output) }
					}
					baseApk = dest
				} else {
					val dest = File(stagingDir, "split_${splits.size}.apk")
					zip.getInputStream(entry).use { input ->
						dest.outputStream().use { output -> input.copyTo(output) }
					}
					splits.add(dest)
				}
			}
		}

		tempFile.delete()
		val base = baseApk ?: throw IllegalStateException("No base APK found in .xapk archive")
		val (pkg, label) = readApkInfo(base)
		return FileApkInfo(pkg, label, base.absolutePath, splits.map { it.absolutePath })
	}

	@Suppress("DEPRECATION")
	private fun readApkInfo(apkFile: File): Pair<String, String> {
		val pm = context.packageManager
		val pkgInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
			?: throw IllegalStateException("Could not parse APK manifest (file may be corrupt or unsupported)")
		val appInfo = pkgInfo.applicationInfo
			?: throw IllegalStateException("APK has no application info")
		appInfo.sourceDir = apkFile.absolutePath
		appInfo.publicSourceDir = apkFile.absolutePath
		val label = appInfo.loadLabel(pm).toString()
		return Pair(pkgInfo.packageName, label)
	}

	private fun copyUriToFile(uri: Uri, dest: File) {
		context.contentResolver.openInputStream(uri)?.use { input ->
			dest.outputStream().use { output -> input.copyTo(output) }
		} ?: throw IllegalStateException("Could not open selected file")
	}

	private fun getFileName(uri: Uri): String {
		context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
			if (cursor.moveToFirst()) {
				val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
				if (idx >= 0) {
					val name = cursor.getString(idx)
					if (!name.isNullOrBlank()) return name
				}
			}
		}
		val path = uri.lastPathSegment ?: return "unknown"
		return path.substringAfterLast('/').substringAfterLast(':')
	}
}
