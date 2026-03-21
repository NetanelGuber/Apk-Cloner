package com.yourname.apkcloner.engine

import android.content.Context
import com.yourname.apkcloner.util.FileUtils
import com.yourname.apkcloner.util.KeystoreUtils
import java.io.File
import java.util.zip.ZipFile

class CloneEngine(private val context: Context) {

	suspend fun clone(
		settings: CloneSettings,
		onProgress: suspend (step: String, percent: Int) -> Unit
	) {
		val workDir = FileUtils.getCloneWorkDir(context, settings.newPackageName)

		try {
			// ── Step 1: Extract APK ─────────────────────────────────── 10%
			onProgress("Extracting APK...", 5)
			val apkSet = ApkExtractor(context).extract(settings.sourcePackageName, workDir)
			val workingApk = apkSet.baseApk

			// Check available space
			FileUtils.checkAvailableSpace(
				context,
				FileUtils.estimateRequiredSpace(workingApk)
			)
			onProgress("APK extracted", 10)

			// ── Step 2: Patch Manifest ───────────────────────────────── 30%
			onProgress("Patching manifest...", 15)
			val manifestBytes = extractEntry(workingApk, "AndroidManifest.xml")
				?: throw IllegalStateException("No AndroidManifest.xml in APK")
			val patchedManifest = ManifestPatcher().patch(
				manifestBytes,
				settings.sourcePackageName,
				settings.newPackageName,
				settings.cloneLabel
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
					settings.cloneLabel
				)
			} else null
			onProgress("Resources patched", 50)

			// ── Step 4: DEX patching (deep clone) ───────────────────── 65%
			if (settings.deepClone) {
				onProgress("Patching DEX (deep clone)...", 55)
				DexPatcher().patchApk(
					workingApk,
					settings.sourcePackageName,
					settings.newPackageName
				)
				onProgress("DEX patched", 65)
			}

			// ── Step 5: Re-assemble base APK ────────────────────────── 75%
			onProgress("Assembling APK...", 68)
			val unsignedApk = File(workDir, "unsigned.apk")
			ApkAssembler().assemble(
				workingApk, patchedManifest, patchedArsc, unsignedApk,
				settings.sourcePackageName, settings.newPackageName,
				settings.patchNativeLibs
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
					val unsignedSplit = File(workDir, "unsigned_split_$i.apk")
					assembler.assembleSplit(
						splitApk,
						settings.sourcePackageName,
						settings.newPackageName,
						unsignedSplit,
						settings.patchNativeLibs
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
			FileUtils.cleanupWorkDir(workDir)
		}
	}

	private fun extractEntry(apkFile: File, entryName: String): ByteArray? {
		ZipFile(apkFile).use { zip ->
			val entry = zip.getEntry(entryName) ?: return null
			return zip.getInputStream(entry).readBytes()
		}
	}
}
