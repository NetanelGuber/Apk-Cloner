package com.guber.apkcloner.engine

import pxb.android.arsc.ArscParser
import pxb.android.arsc.ArscWriter
import pxb.android.arsc.BagValue
import pxb.android.arsc.Value
import java.io.File
import java.util.zip.ZipFile

class ResourcePatcher {

	private companion object {
		const val TYPE_STRING = 0x03

		fun extractArsc(apkFile: File): ByteArray? {
			ZipFile(apkFile).use { zip ->
				val entry = zip.getEntry("resources.arsc") ?: return null
				return zip.getInputStream(entry).readBytes()
			}
		}
	}

	fun patch(
		arscBytes: ByteArray,
		oldPackageName: String,
		newPackageName: String,
		cloneLabel: String?,
		labelResId: Int? = null
	): ByteArray {
		val pkgs = ArscParser(arscBytes).parse()

		for (pkg in pkgs) {
			// Patch the package name in the package chunk header
			if (pkg.name == oldPackageName) {
				pkg.name = newPackageName
			}

			// Walk all types → configs → entries → values
			// pkg.types is TreeMap<Int, Type> keyed by type ID
			for ((typeId, type) in pkg.types) {
				for (config in type.configs) {
					for ((entryKey, entry) in config.resources) {
						// Reconstruct the full resource ID to match against manifest label ref (BUG-5)
						val thisResId = ((pkg.id and 0xFF) shl 24) or ((typeId and 0xFF) shl 16) or (entryKey and 0xFFFF)
						val isLabelEntry = labelResId != null && thisResId == labelResId

						when (val value = entry.value) {
							is Value -> {
								patchValue(value, oldPackageName, newPackageName, cloneLabel, isLabelEntry)
							}
							is BagValue -> {
								for (mapEntry in value.map) {
									patchValue(mapEntry.value, oldPackageName, newPackageName, cloneLabel, isLabelEntry)
								}
							}
						}
					}
				}
			}
		}

		return ArscWriter(pkgs).toByteArray()
	}

	private fun patchValue(
		value: Value,
		oldPkg: String,
		newPkg: String,
		cloneLabel: String?,
		isLabelEntry: Boolean
	) {
		if (value.type != TYPE_STRING || value.raw == null) return

		// Apply both operations independently (BUG-6): package substitution and label
		// appending are sequential steps, not mutually exclusive branches.
		var updated = value.raw
		if (updated.contains(oldPkg)) {
			updated = updated.replace(oldPkg, newPkg)
		}
		if (cloneLabel != null && isLabelEntry) {
			updated = "$updated $cloneLabel"
		}
		if (updated != value.raw) {
			value.raw = updated
		}
	}

}
