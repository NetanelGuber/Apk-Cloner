package com.guber.apkcloner.engine

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
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
		val bytes = input.readBytes()
		val eocdOff = findEocd(bytes) ?: error("ZipAligner: EOCD not found in ${input.name}")
		val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

		// ── Parse EOCD ───────────────────────────────────────────────────────
		buf.position(eocdOff + 10)
		val totalEntries = buf.short.toInt() and 0xFFFF
		buf.position(eocdOff + 16)
		val cdOff = buf.int
		buf.position(eocdOff + 20)
		val commentLen = buf.short.toInt() and 0xFFFF
		val eocdComment = bytes.copyOfRange(eocdOff + 22, eocdOff + 22 + commentLen)

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
		buf.position(cdOff)
		repeat(totalEntries) {
			check(buf.int == 0x02014b50) { "Bad central directory signature" }
			val versionMadeBy  = buf.short;  val versionNeeded = buf.short
			val flags          = buf.short;  val compression   = buf.short
			val modTime        = buf.short;  val modDate       = buf.short
			val crc            = buf.int;    val compressedSize = buf.int
			val uncompressedSize = buf.int
			val fnLen          = buf.short.toInt() and 0xFFFF
			val extraLen       = buf.short.toInt() and 0xFFFF
			val cmtLen         = buf.short.toInt() and 0xFFFF
			val diskStart      = buf.short;  val internalAttrs = buf.short
			val externalAttrs  = buf.int;    val localOff      = buf.int
			val fileName = ByteArray(fnLen).also  { buf.get(it) }
			val extra    = ByteArray(extraLen).also { buf.get(it) }
			val comment  = ByteArray(cmtLen).also  { buf.get(it) }
			cdEntries.add(CdEntry(
				versionMadeBy, versionNeeded, flags, compression, modTime, modDate,
				crc, compressedSize, uncompressedSize, diskStart, internalAttrs,
				externalAttrs, localOff, fileName, extra, comment
			))
		}

		// ── Write aligned entries ────────────────────────────────────────────
		val out = CountingStream(FileOutputStream(output))
		val newOffsets = IntArray(totalEntries)

		for ((idx, cd) in cdEntries.withIndex()) {
			// Parse local file header
			buf.position(cd.localHeaderOffset)
			check(buf.int == 0x04034b50) { "Bad local file header signature" }
			val lhVersionNeeded  = buf.short
			val lhFlags          = buf.short.toInt() and 0xFFFF
			val lhCompression    = buf.short.toInt() and 0xFFFF
			val lhModTime        = buf.short
			val lhModDate        = buf.short
			buf.position(buf.position() + 12) // skip crc(4) + compressedSize(4) + uncompressedSize(4)
			val fnLen            = buf.short.toInt() and 0xFFFF
			val lhExtraLen       = buf.short.toInt() and 0xFFFF
			buf.position(buf.position() + fnLen) // skip filename
			val lhExtra          = ByteArray(lhExtraLen).also { buf.get(it) }
			val dataPos          = buf.position()

			newOffsets[idx] = out.count.toInt()

			// Calculate alignment padding for STORED entries
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
			// Clear data-descriptor flag (bit 3) and fill sizes from CD (authoritative)
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

			// Write file data
			out.write(bytes, dataPos, cd.compressedSize)
		}

		// ── Write central directory (updated offsets) ────────────────────────
		val cdStartOffset = out.count.toInt()
		var newCdSize = 0

		for ((idx, cd) in cdEntries.withIndex()) {
			val cdBuf = ByteBuffer.allocate(46 + cd.fileName.size + cd.extra.size + cd.comment.size)
				.order(ByteOrder.LITTLE_ENDIAN)
			cdBuf.putInt(0x02014b50)
			cdBuf.putShort(cd.versionMadeBy); cdBuf.putShort(cd.versionNeeded)
			cdBuf.putShort((cd.flags.toInt() and 0xFFF7).toShort()); cdBuf.putShort(cd.compression)
			cdBuf.putShort(cd.modTime);       cdBuf.putShort(cd.modDate)
			cdBuf.putInt(cd.crc)
			cdBuf.putInt(cd.compressedSize)
			cdBuf.putInt(cd.uncompressedSize)
			cdBuf.putShort(cd.fileName.size.toShort())
			cdBuf.putShort(cd.extra.size.toShort())
			cdBuf.putShort(cd.comment.size.toShort())
			cdBuf.putShort(cd.diskStart)
			cdBuf.putShort(cd.internalAttrs)
			cdBuf.putInt(cd.externalAttrs)
			cdBuf.putInt(newOffsets[idx])
			cdBuf.put(cd.fileName); cdBuf.put(cd.extra); cdBuf.put(cd.comment)
			val cdBytes = cdBuf.array()
			out.write(cdBytes)
			newCdSize += cdBytes.size
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
		out.close()
	}

	private fun alignment(fileName: String): Int {
		return if (fileName.endsWith(".so")) 4096 else 4
	}

	private fun findEocd(bytes: ByteArray): Int? {
		// Search backwards for EOCD signature 0x06054b50 (max comment = 65535 bytes)
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
