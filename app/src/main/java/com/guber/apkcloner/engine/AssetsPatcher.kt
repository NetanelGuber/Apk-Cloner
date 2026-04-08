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
			val patched = text.replaceBounded(oldPkg, newPkg).replaceBounded(oldPath, newPath)
			if (patched == text) null else patched.toByteArray(Charsets.UTF_8)
		} catch (_: Exception) {
			null
		}
	}
}

/**
 * Replaces all occurrences of [old] in the receiver string where the character
 * immediately after the match is NOT alphanumeric and NOT underscore. This prevents
 * false-positive replacements when [old] is a strict prefix of a longer identifier
 * (e.g. replacing "com.example.app" must not corrupt "com.example.appcompat").
 */
internal fun String.replaceBounded(old: String, new: String): String {
	if (old.isEmpty() || !contains(old)) return this
	val sb = StringBuilder(length)
	var start = 0
	var idx = indexOf(old, start)
	while (idx >= 0) {
		val afterIdx = idx + old.length
		val beforeChar = getOrNull(idx - 1)
		val afterChar = getOrNull(afterIdx)
		val leftOk = beforeChar == null || (!beforeChar.isLetterOrDigit() && beforeChar != '_')
				|| beforeChar == 'L'  // Allow Java/Android type descriptor prefix (e.g. "Lcom/example/...")
		val rightOk = afterChar == null || (!afterChar.isLetterOrDigit() && afterChar != '_')
		if (leftOk && rightOk) {
			// Boundary check passed — safe to replace
			sb.append(this, start, idx).append(new)
			start = afterIdx
		} else {
			// Boundary check failed — skip this occurrence (advance one char to avoid infinite loop)
			sb.append(this, start, idx + 1)
			start = idx + 1
		}
		idx = indexOf(old, start)
	}
	sb.append(this, start, length)
	return sb.toString()
}
