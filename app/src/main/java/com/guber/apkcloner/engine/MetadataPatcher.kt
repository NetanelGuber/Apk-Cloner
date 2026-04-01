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

	fun patch(bytes: ByteArray, entryName: String = ""): ByteArray? {
		return if (entryName.endsWith(".kotlin_module")) {
			patchBinary(bytes)
		} else {
			patchText(bytes)
		}
	}

	// Binary in-place replacement for .kotlin_module files.
	// These are binary protobuf-like files; a UTF-8 round-trip is lossy for non-UTF-8 bytes.
	// In-place replacement is only safe when the old and new byte sequences are the same length;
	// if they differ, we leave the file untouched rather than corrupt the length-prefix fields.
	private fun patchBinary(bytes: ByteArray): ByteArray? {
		val oldPkgBytes = oldPkg.toByteArray(Charsets.UTF_8)
		val newPkgBytes = newPkg.toByteArray(Charsets.UTF_8)
		val oldPathBytes = oldPath.toByteArray(Charsets.UTF_8)
		val newPathBytes = newPath.toByteArray(Charsets.UTF_8)

		val hasPkg = indexOf(bytes, oldPkgBytes) >= 0
		val hasPath = indexOf(bytes, oldPathBytes) >= 0
		if (!hasPkg && !hasPath) return null

		if (oldPkgBytes.size != newPkgBytes.size || oldPathBytes.size != newPathBytes.size) {
			// Length-changing replacement is unsafe in binary protobuf format — leave untouched
			return null
		}

		val result = bytes.copyOf()
		var patched = false
		if (hasPkg && replaceAllSameLength(result, oldPkgBytes, newPkgBytes)) patched = true
		if (hasPath && replaceAllSameLength(result, oldPathBytes, newPathBytes)) patched = true
		return if (patched) result else null
	}

	// Text-based replacement for plain-text formats (.properties, services/).
	private fun patchText(bytes: ByteArray): ByteArray? {
		return try {
			val content = bytes.toString(Charsets.UTF_8)
			if (!content.contains(oldPkg) && !content.contains(oldPath)) return null
			content.replaceBounded(oldPkg, newPkg).replaceBounded(oldPath, newPath).toByteArray(Charsets.UTF_8)
		} catch (_: Exception) {
			null
		}
	}

	private fun replaceAllSameLength(data: ByteArray, old: ByteArray, new: ByteArray): Boolean {
		var pos = 0
		var replaced = false
		while (true) {
			pos = indexOf(data, old, pos)
			if (pos < 0) break
			System.arraycopy(new, 0, data, pos, new.size)
			replaced = true
			pos += old.size
		}
		return replaced
	}

	private fun indexOf(haystack: ByteArray, needle: ByteArray, start: Int = 0): Int {
		if (needle.isEmpty() || needle.size > haystack.size) return -1
		outer@ for (i in start..haystack.size - needle.size) {
			for (j in needle.indices) {
				if (haystack[i + j] != needle[j]) continue@outer
			}
			return i
		}
		return -1
	}
}
