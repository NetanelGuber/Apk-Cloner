package com.guber.apkcloner.engine

class AssetsPatcher(
	private val oldPkg: String,
	private val newPkg: String
) {
	private val oldPath = oldPkg.replace('.', '/')
	private val newPath = newPkg.replace('.', '/')

	private val textExtensions = setOf(
		"json", "xml", "html", "htm", "js", "css", "txt", "cfg", "conf",
		"properties", "yaml", "yml", "toml", "ini", "csv", "md", "svg"
	)

	fun shouldPatch(entryName: String): Boolean {
		if (!entryName.startsWith("assets/")) return false
		val ext = entryName.substringAfterLast('.', "").lowercase()
		return ext in textExtensions
	}

	fun patch(bytes: ByteArray): ByteArray? {
		return try {
			val text = bytes.toString(Charsets.UTF_8)
			if (!text.contains(oldPkg) && !text.contains(oldPath)) return null
			text.replace(oldPkg, newPkg).replace(oldPath, newPath).toByteArray(Charsets.UTF_8)
		} catch (_: Exception) {
			null
		}
	}
}
