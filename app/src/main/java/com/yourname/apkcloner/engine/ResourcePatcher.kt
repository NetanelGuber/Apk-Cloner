package com.yourname.apkcloner.engine

import pxb.android.arsc.ArscParser
import pxb.android.arsc.ArscWriter
import pxb.android.arsc.BagValue
import pxb.android.arsc.Value
import java.io.File
import java.util.zip.ZipFile

class ResourcePatcher {

	private companion object {
		const val TYPE_STRING = 0x03
		val APP_LABEL_KEYS = setOf("app_name", "application_name", "app_label")

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
		cloneLabel: String?
	): ByteArray {
		val pkgs = ArscParser(arscBytes).parse()

		for (pkg in pkgs) {
			// Patch the package name in the package chunk header
			if (pkg.name == oldPackageName) {
				pkg.name = newPackageName
			}

			// Walk all types → configs → entries → values
			for (type in pkg.types.values) {
				for (config in type.configs) {
					for ((_, entry) in config.resources) {
						val specName = entry.spec?.name

						when (val value = entry.value) {
							is Value -> {
								patchValue(value, oldPackageName, newPackageName, cloneLabel, specName)
							}
							is BagValue -> {
								for (mapEntry in value.map) {
									patchValue(mapEntry.value, oldPackageName, newPackageName, cloneLabel, specName)
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
		specName: String?
	) {
		if (value.type != TYPE_STRING || value.raw == null) return

		val raw = value.raw

		when {
			// Replace package name occurrences
			raw.contains(oldPkg) -> {
				value.raw = raw.replace(oldPkg, newPkg)
			}
			// Append clone label to app name strings
			cloneLabel != null && specName != null && specName in APP_LABEL_KEYS -> {
				value.raw = "$raw $cloneLabel"
			}
		}
	}

}
