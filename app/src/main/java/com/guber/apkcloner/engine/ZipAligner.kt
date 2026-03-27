package com.guber.apkcloner.engine

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ZipAligner {

	private class CountingStream(private val out: OutputStream) : OutputStream() {
		var count = 0L
		override fun write(b: Int) { out.write(b); count++ }
		override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
		override fun flush() = out.flush()
		override fun close() = out.close()
	}

	fun align(input: File, output: File) {
		// 64 KB streaming buffer — reused for every file data copy to keep heap usage low
		val copyBuf = ByteArray(65536)

		RandomAccessFile(input, "r").use { raf ->
			val fileSize = raf.length()

			// ── Find EOCD ────────────────────────────────────────────────────────
			// The EOCD sits at the very end of the file. Its maximum size is
			// 22 + 65535 (max ZIP comment) = 65557 bytes.  We read only that tail
			// region, avoiding loading the entire APK into heap.
			val tailLen = minOf(fileSize, 65557L).toInt()
			val tailBytes = ByteArray(tailLen)
			raf.seek(fileSize - tailLen)
			raf.readFully(tailBytes)
			val eocdRelOff = findEocd(tailBytes)
				?: error("ZipAligner: EOCD not found in ${input.name}")
			val eocdAbsOff = fileSize - tailLen + eocdRelOff

			// ── Parse EOCD ───────────────────────────────────────────────────────
			val eocdBuf = ByteBuffer.wrap(tailBytes, eocdRelOff, tailLen - eocdRelOff)
				.order(ByteOrder.LITTLE_ENDIAN)
			eocdBuf.int                         // skip signature
			eocdBuf.short                       // skip diskNumber
			eocdBuf.short                       // skip startDisk
			eocdBuf.short                       // skip diskEntries
			val totalEntries = eocdBuf.short.toInt() and 0xFFFF
			eocdBuf.int                         // skip CD size field (computed from offsets instead)
			val cdOff = eocdBuf.int.toLong() and 0xFFFFFFFFL
			val commentLen = eocdBuf.short.toInt() and 0xFFFF
			val eocdComment = ByteArray(commentLen)
			if (commentLen > 0) eocdBuf.get(eocdComment)

			// ── Read central directory into memory ───────────────────────────────
			// The CD is typically kilobytes even for large APKs — safe to buffer.
			// We compute cdSize from file positions rather than the EOCD field,
			// which is more robust against tools that leave it incorrect.
			val cdSize = (eocdAbsOff - cdOff).toInt()
			check(cdSize >= 0) { "ZipAligner: invalid CD offset in ${input.name}" }
			val cdBytes = ByteArray(cdSize)
			raf.seek(cdOff)
			if (cdSize > 0) raf.readFully(cdBytes)
			val cdBuf = ByteBuffer.wrap(cdBytes).order(ByteOrder.LITTLE_ENDIAN)

			// ── Parse central directory ──────────────────────────────────────────
			data class CdEntry(
				val versionMadeBy: Short, val versionNeeded: Short,
				val flags: Short, val compression: Short,
				val modTime: Short, val modDate: Short,
				val crc: Int, val compressedSize: Int, val uncompressedSize: Int,
				val diskStart: Short, val internalAttrs: Short, val externalAttrs: Int,
				val localHeaderOffset: Int,
				val fileName: ByteArray, val extra: ByteArray, val comment: ByteArray
			)

			val cdEntries = ArrayList<CdEntry>(totalEntries)
			repeat(totalEntries) {
				check(cdBuf.int == 0x02014b50) { "Bad central directory signature" }
				val versionMadeBy    = cdBuf.short;  val versionNeeded  = cdBuf.short
				val flags            = cdBuf.short;  val compression    = cdBuf.short
				val modTime          = cdBuf.short;  val modDate        = cdBuf.short
				val crc              = cdBuf.int;    val compressedSize = cdBuf.int
				val uncompressedSize = cdBuf.int
				val fnLen            = cdBuf.short.toInt() and 0xFFFF
				val extraLen         = cdBuf.short.toInt() and 0xFFFF
				val cmtLen           = cdBuf.short.toInt() and 0xFFFF
				val diskStart        = cdBuf.short;  val internalAttrs  = cdBuf.short
				val externalAttrs    = cdBuf.int;    val localOff       = cdBuf.int
				val fileName = ByteArray(fnLen).also   { cdBuf.get(it) }
				val extra    = ByteArray(extraLen).also { cdBuf.get(it) }
				val comment  = ByteArray(cmtLen).also  { cdBuf.get(it) }
				cdEntries.add(CdEntry(
					versionMadeBy, versionNeeded, flags, compression, modTime, modDate,
					crc, compressedSize, uncompressedSize, diskStart, internalAttrs,
					externalAttrs, localOff, fileName, extra, comment
				))
			}

			// ── Write aligned entries ─────────────────────────────────────────────
			val out = CountingStream(FileOutputStream(output).buffered())
			try {
				val newOffsets = IntArray(totalEntries)

				for ((idx, cd) in cdEntries.withIndex()) {
					// Seek to and parse the local file header directly from source file
					val lhAbsOff = cd.localHeaderOffset.toLong() and 0xFFFFFFFFL
					raf.seek(lhAbsOff)
					val lhFixed = ByteArray(30)
					raf.readFully(lhFixed)
					val lhBuf = ByteBuffer.wrap(lhFixed).order(ByteOrder.LITTLE_ENDIAN)
					check(lhBuf.int == 0x04034b50) { "Bad local file header signature" }
					val lhVersionNeeded = lhBuf.short
					val lhFlags         = lhBuf.short.toInt() and 0xFFFF
					val lhCompression   = lhBuf.short.toInt() and 0xFFFF
					val lhModTime       = lhBuf.short
					val lhModDate       = lhBuf.short
					lhBuf.position(lhBuf.position() + 12)  // skip crc(4), compressedSize(4), uncompressedSize(4)
					val fnLen      = lhBuf.short.toInt() and 0xFFFF
					val lhExtraLen = lhBuf.short.toInt() and 0xFFFF
					// Read filename into a scratch array (we use cd.fileName as authoritative)
					val fnScratch = ByteArray(fnLen)
					if (fnLen > 0) raf.readFully(fnScratch)
					val lhExtra = ByteArray(lhExtraLen)
					if (lhExtraLen > 0) raf.readFully(lhExtra)
					// RAF is now positioned exactly at the start of the compressed data
					val dataAbsOff = lhAbsOff + 30L + fnLen + lhExtraLen

					newOffsets[idx] = out.count.toInt()

					// Calculate alignment padding for STORED (uncompressed) entries
					val newExtra = if (lhCompression == 0) {
						val align = alignment(String(cd.fileName, Charsets.UTF_8))
						val dataStart = (out.count + 30L + fnLen + lhExtraLen).toInt()
						val mod = dataStart % align
						val padding = if (mod == 0) 0 else align - mod
						if (padding == 0) lhExtra
						else ByteArray(lhExtraLen + padding).also { lhExtra.copyInto(it) }
					} else {
						lhExtra
					}

					// Write local file header
					// Clear data-descriptor flag (bit 3); sizes/crc come from CD (authoritative)
					val cleanFlags = (lhFlags and 0xFFF7).toShort()
					val lh = ByteBuffer.allocate(30 + fnLen + newExtra.size).order(ByteOrder.LITTLE_ENDIAN)
					lh.putInt(0x04034b50)
					lh.putShort(lhVersionNeeded)
					lh.putShort(cleanFlags)
					lh.putShort(lhCompression.toShort())
					lh.putShort(lhModTime)
					lh.putShort(lhModDate)
					lh.putInt(cd.crc)
					lh.putInt(cd.compressedSize)
					lh.putInt(cd.uncompressedSize)
					lh.putShort(fnLen.toShort())
					lh.putShort(newExtra.size.toShort())
					lh.put(cd.fileName)
					lh.put(newExtra)
					out.write(lh.array())

					// Stream compressed data directly from the source file — never buffers
					// more than copyBuf.size (64 KB) at a time, regardless of APK size.
					raf.seek(dataAbsOff)
					var remaining = cd.compressedSize
					while (remaining > 0) {
						val toRead = minOf(remaining, copyBuf.size)
						raf.readFully(copyBuf, 0, toRead)
						out.write(copyBuf, 0, toRead)
						remaining -= toRead
					}
				}

				// ── Write central directory (with updated local-header offsets) ────────
				val cdStartOffset = out.count.toInt()
				var newCdSize = 0

				for ((idx, cd) in cdEntries.withIndex()) {
					val entry = ByteBuffer.allocate(46 + cd.fileName.size + cd.extra.size + cd.comment.size)
						.order(ByteOrder.LITTLE_ENDIAN)
					entry.putInt(0x02014b50)
					entry.putShort(cd.versionMadeBy); entry.putShort(cd.versionNeeded)
					entry.putShort((cd.flags.toInt() and 0xFFF7).toShort()); entry.putShort(cd.compression)
					entry.putShort(cd.modTime);       entry.putShort(cd.modDate)
					entry.putInt(cd.crc)
					entry.putInt(cd.compressedSize)
					entry.putInt(cd.uncompressedSize)
					entry.putShort(cd.fileName.size.toShort())
					entry.putShort(cd.extra.size.toShort())
					entry.putShort(cd.comment.size.toShort())
					entry.putShort(cd.diskStart)
					entry.putShort(cd.internalAttrs)
					entry.putInt(cd.externalAttrs)
					entry.putInt(newOffsets[idx])
					entry.put(cd.fileName); entry.put(cd.extra); entry.put(cd.comment)
					val entryBytes = entry.array()
					out.write(entryBytes)
					newCdSize += entryBytes.size
				}

				// ── Write EOCD ───────────────────────────────────────────────────────
				val eocd = ByteBuffer.allocate(22 + eocdComment.size).order(ByteOrder.LITTLE_ENDIAN)
				eocd.putInt(0x06054b50)
				eocd.putShort(0); eocd.putShort(0)
				eocd.putShort(totalEntries.toShort()); eocd.putShort(totalEntries.toShort())
				eocd.putInt(newCdSize)
				eocd.putInt(cdStartOffset)
				eocd.putShort(eocdComment.size.toShort())
				eocd.put(eocdComment)
				out.write(eocd.array())
			} finally {
				out.close()
			}
		}
	}

	private fun alignment(fileName: String): Int {
		return if (fileName.endsWith(".so")) 4096 else 4
	}

	private fun findEocd(bytes: ByteArray): Int? {
		// Search backwards for EOCD signature 0x06054b50 (max comment = 65535 bytes).
		// When called with the tail buffer, bytes.size == tailLen, so the equation
		// i + 22 + commentLen == bytes.size correctly validates the EOCD position.
		var i = bytes.size - 22
		val limit = maxOf(0, bytes.size - 65557)
		while (i >= limit) {
			if (bytes[i]   == 0x50.toByte() && bytes[i+1] == 0x4B.toByte() &&
				bytes[i+2] == 0x05.toByte() && bytes[i+3] == 0x06.toByte()) {
				val cLen = ((bytes[i+21].toInt() and 0xFF) shl 8) or (bytes[i+20].toInt() and 0xFF)
				if (i + 22 + cLen == bytes.size) return i
			}
			i--
		}
		return null
	}
}
