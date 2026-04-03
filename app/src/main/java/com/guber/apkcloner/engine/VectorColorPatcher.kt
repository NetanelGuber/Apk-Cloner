package com.guber.apkcloner.engine

import android.graphics.ColorMatrix
import pxb.android.axml.Axml
import pxb.android.axml.AxmlReader
import pxb.android.axml.AxmlWriter
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.cos
import kotlin.math.sin

/**
 * Applies hue-rotation, saturation, and contrast adjustments to every ARGB
 * color attribute inside a set of VectorDrawable (binary AXML) entries in an
 * APK.  Non-color attributes (references, strings, booleans, dimensions …)
 * are left completely unchanged, so vector shapes/paths are preserved.
 *
 * The same BT.601 luminance-preserving ColorMatrix used by [IconPatcher] is
 * applied here so that vector-drawn icon colours shift identically to
 * bitmap-drawn ones and the result matches the in-app preview.
 *
 * Matching is done by filename (last path segment) so the same drawable is
 * patched across density directories and split APKs without needing to know
 * the full path in each APK.
 *
 * If a vector contains no inline ARGB colour attributes the file is NOT
 * rewritten at all, avoiding any risk of a spurious AXML round-trip
 * introducing binary differences.
 */
class VectorColorPatcher {

    // Android binary XML direct-color attribute types
    private val COLOR_TYPES = setOf(0x1c, 0x1d, 0x1e, 0x1f)

    fun patch(
        apkFile: File,
        hue: Float,
        saturation: Float,
        contrast: Float,
        vectorPaths: Set<String>   // exact paths from base-APK ARSC; matched by filename
    ) {
        if (vectorPaths.isEmpty()) return
        if (hue == 0f && saturation == 0f && contrast == 0f) return

        // Build the 4×5 ColorMatrix once — reused for every attribute in every file.
        val colorMatrix = buildColorMatrix(hue, saturation, contrast)
        val targetNames = vectorPaths.mapTo(mutableSetOf()) { it.substringAfterLast('/') }
        val tempFile = File(apkFile.parentFile, "${apkFile.name}.vec_tmp")

        ZipFile(apkFile).use { inZip ->
            ZipOutputStream(FileOutputStream(tempFile).buffered()).use { outZip ->
                for (entry in inZip.entries()) {
                    val name = entry.name
                    val bytes by lazy { inZip.getInputStream(entry).readBytes() }

                    val isTarget = name.endsWith(".xml") &&
                        name.substringAfterLast('/') in targetNames

                    if (isTarget) {
                        val patched = patchVectorColors(bytes, colorMatrix)
                        val e = ZipEntry(name).apply { method = ZipEntry.DEFLATED }
                        outZip.putNextEntry(e)
                        outZip.write(patched)
                    } else {
                        val outEntry = ZipEntry(name).apply {
                            method = entry.method
                            if (entry.method == ZipEntry.STORED) {
                                size = entry.size
                                compressedSize = entry.compressedSize
                                crc = entry.crc
                            }
                        }
                        outZip.putNextEntry(outEntry)
                        outZip.write(bytes)
                    }
                    outZip.closeEntry()
                }
            }
        }

        apkFile.delete()
        tempFile.renameTo(apkFile)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Parses [bytes] as binary AXML, transforms every inline ARGB colour
     * attribute using [colorMatrix], and returns the rewritten bytes.
     * Returns the original [bytes] unchanged if:
     *  • parsing/writing throws (malformed AXML), or
     *  • no colour attribute was actually different after the transform
     *    (avoids unnecessary binary diffs from a no-op round-trip).
     */
    private fun patchVectorColors(bytes: ByteArray, colorMatrix: FloatArray): ByteArray {
        return try {
            val axml = Axml()
            AxmlReader(bytes).accept(axml)
            var anyChanged = false
            for (node in axml.firsts) {
                if (transformNode(node, colorMatrix)) anyChanged = true
            }
            if (!anyChanged) return bytes   // nothing to do — skip the rewrite
            val writer = AxmlWriter()
            axml.accept(writer)
            writer.toByteArray()
        } catch (_: Exception) {
            bytes   // return original on any parse/write failure
        }
    }

    /** Recursively transforms inline ARGB colour attrs. Returns true if anything changed. */
    private fun transformNode(node: Axml.Node, colorMatrix: FloatArray): Boolean {
        var changed = false
        for (attr in node.attrs) {
            if (attr.type in COLOR_TYPES && attr.value is Int) {
                val original = attr.value as Int
                val transformed = applyMatrix(original, colorMatrix)
                if (transformed != original) {
                    attr.value = transformed
                    changed = true
                }
            }
        }
        for (child in node.children) {
            if (transformNode(child, colorMatrix)) changed = true
        }
        return changed
    }

    /**
     * Applies the pre-built 4×5 ColorMatrix to a single ARGB integer.
     * Channels [r, g, b, a] are in [0, 255]; alpha is preserved unchanged.
     */
    private fun applyMatrix(argb: Int, m: FloatArray): Int {
        val alpha = (argb ushr 24) and 0xFF
        if (alpha == 0) return argb
        val r = ((argb ushr 16) and 0xFF).toFloat()
        val g = ((argb ushr 8)  and 0xFF).toFloat()
        val b = ( argb          and 0xFF).toFloat()
        val a = alpha.toFloat()
        val nr = (m[0]*r + m[1]*g + m[2]*b + m[3]*a + m[4]  + 0.5f).toInt().coerceIn(0, 255)
        val ng = (m[5]*r + m[6]*g + m[7]*b + m[8]*a + m[9]  + 0.5f).toInt().coerceIn(0, 255)
        val nb = (m[10]*r + m[11]*g + m[12]*b + m[13]*a + m[14] + 0.5f).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    /**
     * Builds a 4×5 ColorMatrix (FloatArray[20]) combining hue-rotation,
     * saturation delta, and contrast delta.  Identical to the matrix produced
     * by [IconPatcher.buildColorFilter] — both patchers now produce the same
     * per-pixel output for the same input colour.
     */
    private fun buildColorMatrix(hue: Float, saturation: Float, contrast: Float): FloatArray {
        val cm = ColorMatrix()
        if (hue != 0f) {
            val rad = Math.toRadians(hue.toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
            cm.postConcat(ColorMatrix(floatArrayOf(
                lr + c*(1f-lr) + s*(-lr),    lg + c*(-lg) + s*(-lg),        lb + c*(-lb) + s*(1f-lb),   0f, 0f,
                lr + c*(-lr) + s*(0.143f),   lg + c*(1f-lg) + s*(0.140f),   lb + c*(-lb) + s*(-0.283f), 0f, 0f,
                lr + c*(-lr) + s*(-(1f-lr)), lg + c*(-lg) + s*(lg),         lb + c*(1f-lb) + s*(lb),    0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        if (saturation != 0f) {
            val sat = ColorMatrix()
            sat.setSaturation(1f + saturation)
            cm.postConcat(sat)
        }
        if (contrast != 0f) {
            val scale = 1f + contrast
            val t = (-0.5f * scale + 0.5f) * 255f
            cm.postConcat(ColorMatrix(floatArrayOf(
                scale, 0f,    0f,    0f, t,
                0f,    scale, 0f,    0f, t,
                0f,    0f,    scale, 0f, t,
                0f,    0f,    0f,    1f, 0f
            )))
        }
        return cm.array
    }
}
