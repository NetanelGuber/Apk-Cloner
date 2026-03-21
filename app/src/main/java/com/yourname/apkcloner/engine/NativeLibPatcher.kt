package com.yourname.apkcloner.engine

class NativeLibPatcher(
	private val oldPkg: String,
	private val newPkg: String
) {
	private val oldPath = oldPkg.replace('.', '/')
	private val newPath = newPkg.replace('.', '/')

	fun shouldPatch(entryName: String): Boolean {
		return entryName.startsWith("lib/") && entryName.endsWith(".so")
	}

	fun patch(bytes: ByteArray): ByteArray? {
		val oldPkgBytes = oldPkg.toByteArray(Charsets.UTF_8)
		val oldPathBytes = oldPath.toByteArray(Charsets.UTF_8)

		val hasPkg = indexOf(bytes, oldPkgBytes) >= 0
		val hasPath = indexOf(bytes, oldPathBytes) >= 0

		if (!hasPkg && !hasPath) return null

		val result = bytes.copyOf()
		val newPkgBytes = newPkg.toByteArray(Charsets.UTF_8)
		val newPathBytes = newPath.toByteArray(Charsets.UTF_8)
		var patched = false

		if (hasPkg) {
			if (replaceCStrings(result, oldPkgBytes, newPkgBytes)) patched = true
		}
		if (hasPath) {
			if (replaceCStrings(result, oldPathBytes, newPathBytes)) patched = true
		}

		return if (patched) result else null
	}

	private fun replaceCStrings(data: ByteArray, old: ByteArray, new: ByteArray): Boolean {
		var pos = 0
		var replaced = false

		while (true) {
			pos = indexOf(data, old, pos)
			if (pos < 0) break

			if (new.size <= old.size) {
				// New string fits in old space — write and null-pad the rest
				System.arraycopy(new, 0, data, pos, new.size)
				for (i in new.size until old.size) {
					data[pos + i] = 0
				}
				replaced = true
				pos += old.size
			} else {
				// New string is longer — check available space up to next null byte
				val availableSpace = findNullTerminator(data, pos + old.size)
				val totalSpace = if (availableSpace >= 0) {
					availableSpace - pos
				} else {
					old.size // can't determine, don't risk it
				}

				if (new.size <= totalSpace) {
					// Enough room: write new string and null-pad remainder
					System.arraycopy(new, 0, data, pos, new.size)
					for (i in new.size until totalSpace) {
						data[pos + i] = 0
					}
					replaced = true
					pos += new.size
				} else {
					// Not enough room, skip this occurrence
					pos += old.size
				}
			}
		}

		return replaced
	}

	private fun findNullTerminator(data: ByteArray, start: Int): Int {
		for (i in start until data.size) {
			if (data[i] == 0.toByte()) return i + 1 // include the null
		}
		return -1
	}

	private fun indexOf(haystack: ByteArray, needle: ByteArray, start: Int = 0): Int {
		if (needle.size > haystack.size) return -1
		outer@ for (i in start..haystack.size - needle.size) {
			for (j in needle.indices) {
				if (haystack[i + j] != needle[j]) continue@outer
			}
			return i
		}
		return -1
	}
}
