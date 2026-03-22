package com.yourname.apkcloner.engine

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ApkAssembler {

	private var xmlPatcher: XmlResourcePatcher? = null
	private var assetsPatcher: AssetsPatcher? = null
	private var metadataPatcher: MetadataPatcher? = null
	private var nativeLibPatcher: NativeLibPatcher? = null

	fun assemble(
		sourceApk: File,
		patchedManifest: ByteArray,
		patchedArsc: ByteArray?,
		destApk: File,
		oldPackageName: String? = null,
		newPackageName: String? = null,
		patchNativeLibs: Boolean = false
	) {
		if (oldPackageName != null && newPackageName != null) {
			xmlPatcher = XmlResourcePatcher(oldPackageName, newPackageName)
			assetsPatcher = AssetsPatcher(oldPackageName, newPackageName)
			metadataPatcher = MetadataPatcher(oldPackageName, newPackageName)
			if (patchNativeLibs) {
				nativeLibPatcher = NativeLibPatcher(oldPackageName, newPackageName)
			}
		}

		ZipInputStream(FileInputStream(sourceApk).buffered()).use { zin ->
			ZipOutputStream(FileOutputStream(destApk).buffered()).use { zout ->
				val seen = mutableSetOf<String>()
				var entry = zin.nextEntry
				while (entry != null) {
					val name = entry.name

					if (isSignatureFile(name) || !seen.add(name)) {
						zin.closeEntry()
						entry = zin.nextEntry
						continue
					}

					val newEntry = ZipEntry(name)

					when {
						name == "AndroidManifest.xml" -> {
							newEntry.method = ZipEntry.DEFLATED
							zout.putNextEntry(newEntry)
							zout.write(patchedManifest)
						}
						name == "resources.arsc" -> {
							if (patchedArsc != null) {
								newEntry.method = ZipEntry.STORED
								newEntry.size = patchedArsc.size.toLong()
								newEntry.compressedSize = patchedArsc.size.toLong()
								newEntry.crc = calculateCrc32(patchedArsc)
								zout.putNextEntry(newEntry)
								zout.write(patchedArsc)
							} else {
								copyStoredEntry(entry, newEntry, zin, zout)
							}
						}
						else -> {
							val rawBytes = tryPatchEntry(name, entry, zin)
							if (rawBytes != null) {
								writeRawEntry(newEntry, entry, rawBytes, zout)
							} else {
								newEntry.method = entry.method
								if (entry.method == ZipEntry.STORED) {
									newEntry.size = entry.size
									newEntry.compressedSize = entry.compressedSize
									newEntry.crc = entry.crc
								}
								zout.putNextEntry(newEntry)
								zin.copyTo(zout)
							}
						}
					}
					zout.closeEntry()
					zin.closeEntry()
					entry = zin.nextEntry
				}
			}
		}
	}

	@Suppress("UNUSED_PARAMETER")
	private fun tryPatchEntry(name: String, entry: ZipEntry, zin: ZipInputStream): ByteArray? {
		val xp = xmlPatcher
		if (xp != null && xp.shouldPatch(name)) {
			val raw = zin.readBytes()
			val patched = xp.patch(raw)
			return patched ?: raw // return raw so caller uses writeRawEntry
		}

		val ap = assetsPatcher
		if (ap != null && ap.shouldPatch(name)) {
			val raw = zin.readBytes()
			return ap.patch(raw) ?: raw
		}

		val mp = metadataPatcher
		if (mp != null && mp.shouldPatch(name)) {
			val raw = zin.readBytes()
			return mp.patch(raw) ?: raw
		}

		val nlp = nativeLibPatcher
		if (nlp != null && nlp.shouldPatch(name)) {
			val raw = zin.readBytes()
			return nlp.patch(raw) ?: raw
		}

		return null // no patcher matched, caller does passthrough
	}

	fun assembleSplit(
		sourceSplitApk: File,
		oldPackageName: String,
		newPackageName: String,
		destSplitApk: File,
		patchNativeLibs: Boolean = false,
		deepClone: Boolean = false
	) {
		val xp = XmlResourcePatcher(oldPackageName, newPackageName)
		val ap = AssetsPatcher(oldPackageName, newPackageName)
		val mp = MetadataPatcher(oldPackageName, newPackageName)
		val nlp = if (patchNativeLibs) NativeLibPatcher(oldPackageName, newPackageName) else null

		ZipInputStream(FileInputStream(sourceSplitApk).buffered()).use { zin ->
			ZipOutputStream(FileOutputStream(destSplitApk).buffered()).use { zout ->
				val seen = mutableSetOf<String>()
				var entry = zin.nextEntry
				while (entry != null) {
					val name = entry.name

					if (isSignatureFile(name) || !seen.add(name)) {
						zin.closeEntry()
						entry = zin.nextEntry
						continue
					}

					val newEntry = ZipEntry(name)

					when {
						name == "AndroidManifest.xml" -> {
							val manifestBytes = zin.readBytes()
							val patchedManifest = ManifestPatcher().patch(
								manifestBytes, oldPackageName, newPackageName, null, deepClone
							).bytes
							newEntry.method = ZipEntry.DEFLATED
							zout.putNextEntry(newEntry)
							zout.write(patchedManifest)
						}
						xp.shouldPatch(name) -> {
							val raw = zin.readBytes()
							val patched = xp.patch(raw) ?: raw
							writeRawEntry(newEntry, entry, patched, zout)
						}
						ap.shouldPatch(name) -> {
							val raw = zin.readBytes()
							val patched = ap.patch(raw) ?: raw
							writeRawEntry(newEntry, entry, patched, zout)
						}
						mp.shouldPatch(name) -> {
							val raw = zin.readBytes()
							val patched = mp.patch(raw) ?: raw
							writeRawEntry(newEntry, entry, patched, zout)
						}
						nlp != null && nlp.shouldPatch(name) -> {
							val raw = zin.readBytes()
							val patched = nlp.patch(raw) ?: raw
							writeRawEntry(newEntry, entry, patched, zout)
						}
						else -> {
							newEntry.method = entry.method
							if (entry.method == ZipEntry.STORED) {
								newEntry.size = entry.size
								newEntry.compressedSize = entry.compressedSize
								newEntry.crc = entry.crc
							}
							zout.putNextEntry(newEntry)
							zin.copyTo(zout)
						}
					}
					zout.closeEntry()
					zin.closeEntry()
					entry = zin.nextEntry
				}
			}
		}
	}

	private fun writeRawEntry(
		newEntry: ZipEntry,
		original: ZipEntry,
		bytes: ByteArray,
		zout: ZipOutputStream
	) {
		newEntry.method = original.method
		if (original.method == ZipEntry.STORED) {
			newEntry.size = bytes.size.toLong()
			newEntry.compressedSize = bytes.size.toLong()
			newEntry.crc = calculateCrc32(bytes)
		}
		zout.putNextEntry(newEntry)
		zout.write(bytes)
	}

	private fun copyStoredEntry(
		original: ZipEntry,
		newEntry: ZipEntry,
		zin: ZipInputStream,
		zout: ZipOutputStream
	) {
		newEntry.method = ZipEntry.STORED
		newEntry.size = original.size
		newEntry.compressedSize = original.compressedSize
		newEntry.crc = original.crc
		zout.putNextEntry(newEntry)
		zin.copyTo(zout)
	}

	private fun isSignatureFile(name: String): Boolean {
		if (!name.startsWith("META-INF/")) return false
		val upper = name.uppercase()
		return upper.endsWith(".RSA") || upper.endsWith(".SF") ||
			upper.endsWith(".DSA") || upper.endsWith(".EC") ||
			upper == "META-INF/MANIFEST.MF"
	}

	private fun calculateCrc32(bytes: ByteArray): Long {
		val crc = CRC32()
		crc.update(bytes)
		return crc.value
	}
}
