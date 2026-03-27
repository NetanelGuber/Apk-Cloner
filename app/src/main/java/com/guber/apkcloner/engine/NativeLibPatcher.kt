package com.guber.apkcloner.engine

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

	/**
	 * Replaces in-place all occurrences of [old] in [data] that are confirmed to be
	 * inside a valid null-terminated C string.
	 *
	 * Validation steps for each candidate match at position [pos]:
	 *   1. A null terminator must exist after the match (establishes available space).
	 *   2. Scanning backward finds the C string's start (nearest preceding null or
	 *      start of a bounded window).
	 *   3. Every byte from the string start to the null terminator must be printable
	 *      ASCII (0x20–0x7E). Package names are always printable ASCII; ELF binary
	 *      data (instructions, relocations, padding) will contain non-ASCII bytes,
	 *      so this check reliably rejects false positives in binary regions.
	 *   4. The new string must fit in the space up to (and including) the null.
	 */
	private fun replaceCStrings(data: ByteArray, old: ByteArray, new: ByteArray): Boolean {
		var pos = 0
		var replaced = false

		while (true) {
			pos = indexOf(data, old, pos)
			if (pos < 0) break

			// 1. Find the null terminator after this occurrence.
			val nullPos = findNullTerminator(data, pos + old.size)
			if (nullPos < 0) {
				// No null terminator found before end of file — skip.
				pos += old.size
				continue
			}
			val nullTermIdx = nullPos - 1  // actual index of the null byte

			// 2. Find the enclosing C string's start by scanning backward.
			val prevNull = findPrevNull(data, pos - 1)
			val stringStart = if (prevNull < 0) 0 else prevNull + 1

			// 3. Validate the entire region [stringStart, nullTermIdx) is printable ASCII.
			//    Rejects matches inside ELF binary sections where non-ASCII bytes appear.
			if (!isValidCStringRegion(data, stringStart, nullTermIdx)) {
				pos += old.size
				continue
			}

			// 4. Write replacement if it fits within the space up to (and including) the null.
			val totalSpace = nullPos - pos  // slots from pos through the null terminator
			if (new.size <= totalSpace) {
				System.arraycopy(new, 0, data, pos, new.size)
				// Zero out everything from end-of-new-string to (and including) the null,
				// ensuring the new string is properly terminated and no old bytes remain.
				for (i in new.size until totalSpace) data[pos + i] = 0
				replaced = true
				pos += new.size
			} else {
				// Not enough space to fit the longer replacement — skip this occurrence.
				pos += old.size
			}
		}

		return replaced
	}

	/**
	 * Returns the index AFTER the null byte (i.e. nullIdx + 1), or -1 if no null is found.
	 * Callers subtract 1 to get the null byte's own index when needed.
	 */
	private fun findNullTerminator(data: ByteArray, start: Int): Int {
		for (i in start until data.size) {
			if (data[i] == 0.toByte()) return i + 1
		}
		return -1
	}

	/**
	 * Scans backward from [startBefore] to find the nearest preceding null byte.
	 * Limits the scan to 4096 bytes to avoid traversing large binary regions.
	 * Returns the index of the null byte, or -1 if none is found within the limit.
	 */
	private fun findPrevNull(data: ByteArray, startBefore: Int): Int {
		val limit = maxOf(0, startBefore - 4096)
		var i = minOf(startBefore, data.size - 1)
		while (i >= limit) {
			if (data[i] == 0.toByte()) return i
			i--
		}
		return -1
	}

	/**
	 * Returns true if every byte in data[start..<nullPos] is printable ASCII (0x20–0x7E).
	 * [nullPos] is the index of the null terminator itself and is NOT included in the check.
	 */
	private fun isValidCStringRegion(data: ByteArray, start: Int, nullPos: Int): Boolean {
		for (i in start until nullPos) {
			val b = data[i].toInt() and 0xFF
			if (b < 0x20 || b > 0x7E) return false
		}
		return true
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
