package com.guber.apkcloner.engine

class MetadataPatcher(
	private val oldPkg: String,
	private val newPkg: String
) {
	private val oldPath = oldPkg.replace('.', '/')
	private val newPath = newPkg.replace('.', '/')

	fun shouldPatch(entryName: String): Boolean {
		if (entryName.startsWith("META-INF/")) {
			val name = entryName.substringAfter("META-INF/")
			if (name.endsWith(".kotlin_module")) return true
			if (name.startsWith("services/")) return true
			if (name.endsWith(".properties")) return true
		}
		return false
	}

	fun patch(bytes: ByteArray): ByteArray? {
		return try {
			val content = bytes.toString(Charsets.UTF_8)
			if (!content.contains(oldPkg) && !content.contains(oldPath)) return null
			content.replace(oldPkg, newPkg).replace(oldPath, newPath).toByteArray(Charsets.UTF_8)
		} catch (_: Exception) {
			null
		}
	}
}
