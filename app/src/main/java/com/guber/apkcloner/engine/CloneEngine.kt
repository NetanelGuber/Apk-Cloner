package com.guber.apkcloner.engine

import android.content.Context
import android.os.Build
import com.guber.apkcloner.util.FileUtils
import com.guber.apkcloner.util.KeystoreUtils
import java.io.File
import java.util.zip.ZipFile

class CloneEngine(private val context: Context) {

	suspend fun clone(
		settings: CloneSettings,
		onProgress: suspend (step: String, percent: Int) -> Unit
	) {
		val workDir = FileUtils.getCloneWorkDir(context, settings.newPackageName)

		try {
			// ── Step 0: Pre-flight + Step 1: Acquire APKs ────────────────────────
			val minSdk: Int
			val apkSet: ApkExtractor.ApkSet

			if (settings.sourceApkPaths != null) {
				val paths = settings.sourceApkPaths
				val totalSourceSize = paths.sumOf { File(it).length() }
				FileUtils.checkAvailableSpace(context, totalSourceSize * 3)

				minSdk = readMinSdkFromApk(File(paths[0]))

				onProgress("Copying APK...", 5)
				val baseApk = File(paths[0]).copyTo(File(workDir, "base.apk"), overwrite = true)
				val splitApks = paths.drop(1).mapIndexed { i, path ->
					File(path).copyTo(File(workDir, "split_$i.apk"), overwrite = true)
				}
				apkSet = ApkExtractor.ApkSet(baseApk, splitApks)
				onProgress("APK copied", 10)

				// Clean up staging files now that they are copied into workDir
				try {
					paths.forEach { File(it).delete() }
					File(paths[0]).parentFile?.delete()
				} catch (_: Exception) {}
			} else {
				// ── Pre-flight space check ───────────────────────────────────────
				val appInfo = context.packageManager.getApplicationInfo(settings.sourcePackageName, 0)
				val totalSourceSize = File(appInfo.sourceDir).length() +
					(appInfo.splitSourceDirs?.sumOf { File(it).length() } ?: 0L)
				FileUtils.checkAvailableSpace(context, totalSourceSize * 3)

				minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
					appInfo.minSdkVersion
				} else {
					21
				}

				// ── Step 1: Extract APK ─────────────────────────────────── 10%
				onProgress("Extracting APK...", 5)
				apkSet = ApkExtractor(context).extract(settings.sourcePackageName, workDir)
				onProgress("APK extracted", 10)
			}

			val workingApk = apkSet.baseApk

			// ── Step 2: Patch Manifest ───────────────────────────────── 30%
			onProgress("Patching manifest...", 15)
			val manifestBytes = extractEntry(workingApk, "AndroidManifest.xml")
				?: throw IllegalStateException("No AndroidManifest.xml in APK")
			val patchComponentNames = settings.deepClone || settings.dualDex
			val manifestResult = ManifestPatcher().patch(
				manifestBytes,
				settings.sourcePackageName,
				settings.newPackageName,
				settings.cloneLabel,
				patchComponentNames,
				settings.overrideMinSdk,
				settings.overrideTargetSdk
			)
			onProgress("Manifest patched", 30)

			// ── Step 3: Patch ARSC ──────────────────────────────────── 50%
			onProgress("Patching resources...", 35)
			val arscBytes = extractEntry(workingApk, "resources.arsc")
			val patchedArsc = if (arscBytes != null) {
				ResourcePatcher().patch(
					arscBytes,
					settings.sourcePackageName,
					settings.newPackageName,
					settings.cloneLabel,
					manifestResult.labelResourceId
				)
			} else null
			onProgress("Resources patched", 50)

			// ── Step 4: DEX work ─────────────────────────────────────── 65%
			var shimDexBytes: ByteArray? = null
			when {
				settings.dualDex -> {
					onProgress("Generating compatibility shim...", 55)
					shimDexBytes = DualDexShimGenerator(
						context,
						settings.sourcePackageName,
						settings.newPackageName,
						minSdk,
						if (settings.sourceApkPaths != null) workingApk.absolutePath else null
					).generate()
					onProgress("Compatibility shim generated", 65)
				}
				settings.deepClone -> {
					onProgress("Patching DEX (deep clone)...", 55)
					DexPatcher().patchApk(
						workingApk,
						settings.sourcePackageName,
						settings.newPackageName,
						minSdk
					)
					onProgress("DEX patched", 65)
				}
			}

			// ── Step 5: Re-assemble base APK ────────────────────────── 75%
			onProgress("Assembling APK...", 68)
			val unsignedApk = File(workDir, "unsigned.apk")
			ApkAssembler().assemble(
				workingApk, manifestResult.bytes, patchedArsc, unsignedApk,
				settings.sourcePackageName, settings.newPackageName,
				settings.patchNativeLibs, shimDexBytes
			)
			onProgress("APK assembled", 75)

			// ── Step 6: Sign base APK ───────────────────────────────── 85%
			onProgress("Signing APK...", 78)
			val signedApk = File(workDir, "signed.apk")
			val keystoreDir = KeystoreUtils.getKeystoreDir(context)
			val keystoreFile = File(keystoreDir, "${settings.newPackageName}.jks")
			ApkSignerModule().sign(unsignedApk, signedApk, keystoreFile)
			onProgress("APK signed", 85)

			// ── Step 5b/6b: Handle split APKs ───────────────────────── 90%
			val allSignedApks = mutableListOf(signedApk)

			if (apkSet.splitApks.isNotEmpty()) {
				onProgress("Processing split APKs...", 87)
				val assembler = ApkAssembler()
				val signer = ApkSignerModule()

				for ((i, splitApk) in apkSet.splitApks.withIndex()) {
					if (settings.deepClone) {
						DexPatcher().patchApk(
							splitApk,
							settings.sourcePackageName,
							settings.newPackageName,
							minSdk
						)
					}

					val unsignedSplit = File(workDir, "unsigned_split_$i.apk")
					assembler.assembleSplit(
						splitApk,
						settings.sourcePackageName,
						settings.newPackageName,
						unsignedSplit,
						settings.patchNativeLibs,
						settings.deepClone || settings.dualDex
					)

					val signedSplit = File(workDir, "signed_split_$i.apk")
					signer.sign(unsignedSplit, signedSplit, keystoreFile)
					allSignedApks.add(signedSplit)
				}
				onProgress("Split APKs processed", 90)
			}

			// ── Step 7: Install ─────────────────────────────────────── 100%
			onProgress("Installing...", 93)
			val installer = ApkInstaller(context)
			if (allSignedApks.size == 1) {
				installer.install(signedApk, settings.newPackageName)
			} else {
				installer.installMultiApk(allSignedApks, settings.newPackageName)
			}
			onProgress("Done!", 100)

		} finally {
			try { FileUtils.cleanupWorkDir(workDir) } catch (_: Exception) { }
		}
	}

	@Suppress("DEPRECATION")
	private fun readMinSdkFromApk(apkFile: File): Int {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			try {
				val pkgInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
				pkgInfo?.applicationInfo?.let { return it.minSdkVersion }
			} catch (_: Exception) {}
		}
		return 21
	}

	private fun extractEntry(apkFile: File, entryName: String): ByteArray? {
		ZipFile(apkFile).use { zip ->
			val entry = zip.getEntry(entryName) ?: return null
			return zip.getInputStream(entry).readBytes()
		}
	}
}
