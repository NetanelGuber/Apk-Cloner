package com.guber.apkcloner.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.guber.apkcloner.util.FileUtils
import com.guber.apkcloner.util.KeystoreUtils
import org.json.JSONObject
import pxb.android.axml.Axml
import pxb.android.axml.AxmlReader
import pxb.android.axml.NodeVisitor
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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
				settings.overrideTargetSdk,
				injectPackageShim = settings.pkgShim
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

			// Resolve the exact set of bitmap files to recolour:
			//  1. Collect all paths for the icon/roundIcon resource IDs from ARSC.
			//  2. Any of those paths that are adaptive-icon XML files (mipmap-anydpi-*)
			//     are NOT themselves bitmaps — parse them to extract the foreground/
			//     background layer resource IDs, then collect those paths from ARSC too.
			//  3. Keep only bitmap paths (PNG/WebP); XML files are passed through intact
			//     so ARSC references remain valid and Android doesn't show a placeholder.
			val iconResIds = setOfNotNull(manifestResult.iconResourceId, manifestResult.roundIconResourceId)
			// bitmapIconPaths — PNG/WebP files for IconPatcher
			// vectorIconPaths — VectorDrawable XMLs for VectorColorPatcher
			val bitmapIconPaths: Set<String>
			val vectorIconPaths: Set<String>
			var layerResIds = emptySet<Int>()
			if (arscBytes != null && iconResIds.isNotEmpty()) {
				val rp = ResourcePatcher()
				val directPaths = rp.collectIconFilePaths(arscBytes, iconResIds)

				// Parse any adaptive-icon XML entries to find their layer drawables
				val mutableLayerResIds = mutableSetOf<Int>()
				for (path in directPaths) {
					if (!path.endsWith(".xml")) continue
					val xmlBytes = extractEntry(workingApk, path) ?: continue
					mutableLayerResIds += parseAdaptiveIconLayerResIds(xmlBytes)
				}
				layerResIds = mutableLayerResIds

				val layerPaths = if (layerResIds.isNotEmpty()) {
					rp.collectIconFilePaths(arscBytes, layerResIds)
				} else emptySet()

				// Bitmap paths go to IconPatcher; layer XML paths go to VectorColorPatcher
				// (exclude the adaptive-icon wrapper XML itself — it has no color attrs)
				bitmapIconPaths = (directPaths + layerPaths).filterTo(mutableSetOf()) { isBitmapPath(it) }
				vectorIconPaths = layerPaths.filterTo(mutableSetOf()) { it.endsWith(".xml") }

				// Extend layerResIds with @color/ references found inside the vector
				// drawables.  Apps like Google Keep define ALL icon colors this way —
				// as color resources in the ARSC rather than as inline ARGB values.
				// Without this, VectorColorPatcher finds nothing to patch and the icon
				// appears unchanged.  Mixing (some inline + some @color/) also causes
				// the "icon looks incorrect" symptom, which this also fixes.
				if (vectorIconPaths.isNotEmpty()) {
					val vectorColorResIds = mutableSetOf<Int>()
					for (path in vectorIconPaths) {
						// Check the base APK first, then fall back to any split APK.
						val xmlBytes = extractEntry(workingApk, path)
							?: apkSet.splitApks.firstNotNullOfOrNull { extractEntry(it, path) }
							?: continue
						vectorColorResIds += collectColorRefsFromVector(xmlBytes)
					}
					if (vectorColorResIds.isNotEmpty()) {
						layerResIds = layerResIds + vectorColorResIds
					}
				}
			} else {
				bitmapIconPaths = emptySet()
				vectorIconPaths = emptySet()
			}

			// Patch icon layer colors that are direct ARSC integer values (e.g. background
			// colors defined as <color> in values.xml — invisible to file-based patchers).
			var finalArsc = patchedArsc
			if (layerResIds.isNotEmpty() &&
				(settings.iconHue != 0f || settings.iconSaturation != 0f || settings.iconContrast != 0f)) {
				val base = patchedArsc ?: arscBytes
				if (base != null) {
					val colorPatched = ResourcePatcher().patchIconLayerColors(
						base, layerResIds, settings.iconHue, settings.iconSaturation, settings.iconContrast
					)
					if (colorPatched !== base) finalArsc = colorPatched
				}
			}

			// ── Step 4: DEX work ─────────────────────────────────────── 65%
			val extraDexFiles = mutableListOf<ByteArray>()
			if (settings.pkgShim) {
				onProgress("Generating package name shim...", 52)
				val pkgShimDex = PackageNameShimGenerator(
					context,
					settings.sourcePackageName,
					minSdk,
					manifestResult.originalApplicationClass
				).generate()
				extraDexFiles.add(pkgShimDex)
			}
			when {
				settings.dualDex -> {
					onProgress("Generating compatibility shim...", 55)
					val dualDexBytes = DualDexShimGenerator(
						context,
						settings.sourcePackageName,
						settings.newPackageName,
						minSdk,
						if (settings.sourceApkPaths != null) workingApk.absolutePath else null,
						if (settings.sourceApkPaths != null) apkSet.splitApks.map { it.absolutePath } else emptyList()
					).generate()
					if (dualDexBytes != null) extraDexFiles.add(dualDexBytes)
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

			// ── Step 4b: Patch icon (if adjustments requested) ─────── 66%
			if (settings.iconHue != 0f || settings.iconSaturation != 0f || settings.iconContrast != 0f) {
				onProgress("Patching icon...", 66)
				IconPatcher().patch(workingApk, settings.iconHue, settings.iconSaturation, settings.iconContrast, bitmapIconPaths)
				VectorColorPatcher().patch(workingApk, settings.iconHue, settings.iconSaturation, settings.iconContrast, vectorIconPaths)
			}

			// ── Step 5: Re-assemble base APK ────────────────────────── 75%
			onProgress("Assembling APK...", 68)
			val unsignedApk = File(workDir, "unsigned.apk")
			ApkAssembler().assemble(
				workingApk, manifestResult.bytes, finalArsc, unsignedApk,
				settings.sourcePackageName, settings.newPackageName,
				settings.patchNativeLibs, extraDexFiles
			)
			val alignedApk = File(workDir, "aligned.apk")
			ZipAligner().align(unsignedApk, alignedApk)
			unsignedApk.delete()
			onProgress("APK assembled", 75)

			// ── Step 6: Sign base APK ───────────────────────────────── 85%
			onProgress("Signing APK...", 78)
			val signedApk = File(workDir, "signed.apk")
			val keystoreDir = KeystoreUtils.getKeystoreDir(context)
			val keystoreFile = File(keystoreDir, "${settings.newPackageName}.jks")
			ApkSignerModule().sign(alignedApk, signedApk, keystoreFile)
			alignedApk.delete()
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

					if (settings.iconHue != 0f || settings.iconSaturation != 0f || settings.iconContrast != 0f) {
						IconPatcher().patch(splitApk, settings.iconHue, settings.iconSaturation, settings.iconContrast, bitmapIconPaths)
						VectorColorPatcher().patch(splitApk, settings.iconHue, settings.iconSaturation, settings.iconContrast, vectorIconPaths)
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

					val alignedSplit = File(workDir, "aligned_split_$i.apk")
					ZipAligner().align(unsignedSplit, alignedSplit)
					unsignedSplit.delete()

					val signedSplit = File(workDir, "signed_split_$i.apk")
					signer.sign(alignedSplit, signedSplit, keystoreFile)
					alignedSplit.delete()
					allSignedApks.add(signedSplit)
				}
				onProgress("Split APKs processed", 90)
			}

			// ── Step 7a: Save to storage (if requested) ─────────────── 88-92%
			if (settings.saveToStorage) {
				onProgress("Saving APK to storage...", 88)
				saveApkToStorage(allSignedApks, settings.cloneLabel, settings.saveLocationUri)
				onProgress("APK saved to Downloads", 92)
			}

			// ── Step 7b: Install (if requested) ─────────────────────── 100%
			if (settings.installAfterBuild) {
				onProgress("Installing...", 93)
				val installer = ApkInstaller(context)
				if (allSignedApks.size == 1) {
					installer.install(signedApk, settings.newPackageName)
				} else {
					installer.installMultiApk(allSignedApks, settings.newPackageName)
				}
				onProgress("Done!", 100)
			} else {
				onProgress("Complete!", 100)
			}

		} finally {
			try { FileUtils.cleanupWorkDir(workDir) } catch (_: Exception) { }
		}
	}

	private fun saveApkToStorage(apks: List<File>, label: String, saveLocationUri: String?) {
		val safeName = label.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
		if (apks.size == 1) {
			writeToStorage(apks[0], "$safeName.apk", "application/vnd.android.package-archive", saveLocationUri)
		} else {
			// Bundle all splits into a valid .apkm archive (ZIP + info.json + icon.png)
			val tempZip = File(context.cacheDir, "save_temp_${System.currentTimeMillis()}.zip")
			try {
				val (infoJson, iconPng) = buildApkmMetadata(apks[0], label)
				zipApks(apks, tempZip, infoJson, iconPng)
				writeToStorage(tempZip, "$safeName.apkm", "application/octet-stream", saveLocationUri)
			} finally {
				tempZip.delete()
			}
		}
	}

	private fun buildApkmMetadata(baseApk: File, label: String): Pair<String, ByteArray?> {
		val pm = context.packageManager
		@Suppress("DEPRECATION")
		val pkgInfo = pm.getPackageArchiveInfo(baseApk.absolutePath, 0)
		val appInfo = pkgInfo?.applicationInfo?.also {
			it.sourceDir = baseApk.absolutePath
			it.publicSourceDir = baseApk.absolutePath
		}

		val pname = pkgInfo?.packageName ?: ""
		val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			pkgInfo?.longVersionCode ?: 0L
		} else {
			@Suppress("DEPRECATION") pkgInfo?.versionCode?.toLong() ?: 0L
		}
		val versionName = pkgInfo?.versionName ?: ""
		val minApi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			appInfo?.minSdkVersion ?: 1
		} else 1

		val infoJson = JSONObject().apply {
			put("apkm_version", "1")
			put("pname", pname)
			put("app_name", label)
			put("release_version", versionName)
			put("versioncode", versionCode.toString())
			put("min_api", minApi.toString())
		}.toString()

		val iconPng: ByteArray? = try {
			appInfo?.loadIcon(pm)?.let { drawableToPng(it) }
		} catch (_: Exception) { null }

		return Pair(infoJson, iconPng)
	}

	private fun drawableToPng(drawable: Drawable): ByteArray {
		val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
			drawable.bitmap
		} else {
			val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 192
			val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 192
			Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bm ->
				val canvas = Canvas(bm)
				drawable.setBounds(0, 0, canvas.width, canvas.height)
				drawable.draw(canvas)
			}
		}
		return ByteArrayOutputStream().also { out ->
			bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
		}.toByteArray()
	}

	private fun zipApks(files: List<File>, dest: File, infoJson: String? = null, iconPng: ByteArray? = null) {
		ZipOutputStream(dest.outputStream().buffered()).use { zip ->
			if (infoJson != null) {
				zip.putNextEntry(ZipEntry("info.json"))
				zip.write(infoJson.toByteArray(Charsets.UTF_8))
				zip.closeEntry()
			}
			if (iconPng != null) {
				zip.putNextEntry(ZipEntry("icon.png"))
				zip.write(iconPng)
				zip.closeEntry()
			}
			for ((index, file) in files.withIndex()) {
				val entryName = if (index == 0) "base.apk" else "split_$index.apk"
				zip.putNextEntry(ZipEntry(entryName))
				file.inputStream().use { it.copyTo(zip) }
				zip.closeEntry()
			}
		}
	}

	private fun writeToStorage(file: File, fileName: String, mimeType: String, saveLocationUri: String?) {
		if (saveLocationUri != null) {
			val treeUri = Uri.parse(saveLocationUri)
			val docId = DocumentsContract.getTreeDocumentId(treeUri)
			val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
			val fileUri = DocumentsContract.createDocument(
				context.contentResolver, parentDocUri, mimeType, fileName
			) ?: throw IllegalStateException("Failed to create '$fileName' in selected folder")
			context.contentResolver.openOutputStream(fileUri)?.use { out ->
				file.inputStream().use { it.copyTo(out) }
			}
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			val values = ContentValues().apply {
				put(MediaStore.Downloads.DISPLAY_NAME, fileName)
				put(MediaStore.Downloads.MIME_TYPE, mimeType)
				put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/APK Cloner")
			}
			val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
				?: throw IllegalStateException("Could not create file in Downloads: $fileName")
			context.contentResolver.openOutputStream(uri)?.use { out ->
				file.inputStream().use { it.copyTo(out) }
			}
		} else {
			@Suppress("DEPRECATION")
			val downloadsDir = File(
				Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
				"APK Cloner"
			)
			downloadsDir.mkdirs()
			file.copyTo(File(downloadsDir, fileName), overwrite = true)
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

	/** True for PNG and WebP file paths. */
	private fun isBitmapPath(path: String): Boolean {
		val lower = path.lowercase()
		return lower.endsWith(".png") || lower.endsWith(".webp")
	}

	companion object {
		/** Attribute names in VectorDrawable XML that carry color values. */
		private val VECTOR_COLOR_ATTR_NAMES = setOf(
			"fillColor", "strokeColor", "color", "tint",
			"startColor", "endColor", "centerColor", "solidColor"
		)
	}

	/**
	 * Parses a binary VectorDrawable AXML and returns every resource ID that
	 * appears as a TYPE_REFERENCE value on a known color-type attribute
	 * (fillColor, strokeColor, color, tint, startColor, endColor, centerColor,
	 * solidColor).  These are @color/<name> references whose actual color values
	 * live in resources.arsc and must be patched there rather than in the XML.
	 */
	private fun collectColorRefsFromVector(xmlBytes: ByteArray): Set<Int> {
		return try {
			val axml = Axml()
			AxmlReader(xmlBytes).accept(axml)
			val ids = mutableSetOf<Int>()
			fun walk(node: Axml.Node) {
				for (attr in node.attrs) {
					if (attr.type == NodeVisitor.TYPE_REFERENCE &&
						attr.name in VECTOR_COLOR_ATTR_NAMES &&
						attr.value is Int && (attr.value as Int) != 0
					) {
						ids += attr.value as Int
					}
				}
				node.children.forEach { walk(it) }
			}
			axml.firsts.forEach { walk(it) }
			ids
		} catch (_: Exception) { emptySet() }
	}

	/**
	 * Parses a binary adaptive-icon XML (AXML) and returns the resource IDs
	 * referenced by android:drawable attributes on the foreground/background nodes.
	 */
	private fun parseAdaptiveIconLayerResIds(xmlBytes: ByteArray): Set<Int> {
		return try {
			val axml = Axml()
			AxmlReader(xmlBytes).accept(axml)
			val ids = mutableSetOf<Int>()
			fun walk(node: Axml.Node) {
				for (attr in node.attrs) {
					if (attr.name == "drawable") {
						val v = attr.value
						if (v is Int && v != 0) ids += v
					}
				}
				node.children.forEach { walk(it) }
			}
			axml.firsts.forEach { walk(it) }
			ids
		} catch (_: Exception) { emptySet() }
	}
}
