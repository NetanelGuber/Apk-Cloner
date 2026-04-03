package com.guber.apkcloner.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.cos
import kotlin.math.sin

/**
 * Applies hue-rotation, saturation, and contrast adjustments to the launcher
 * icon bitmaps inside an APK (zip) file.  The APK is rewritten atomically via
 * a temp file.  All non-bitmap entries (including adaptive-icon XML files) are
 * copied through unchanged.
 *
 * @param iconPaths  Exact APK entry paths of bitmap files to recolour
 *                   (PNG or WebP only — XML/vector entries must be excluded by
 *                   the caller).  When empty the patcher falls back to patching
 *                   all res/mipmap-* PNGs/WebPs as a heuristic.
 * @param hue        Hue rotation in degrees   (-180 … +180, 0 = no change)
 * @param saturation Saturation delta           (-1 … +1,    0 = no change)
 * @param contrast   Contrast delta             (-1 … +1,    0 = no change)
 */
class IconPatcher {

    fun patch(
        apkFile: File,
        hue: Float,
        saturation: Float,
        contrast: Float,
        iconPaths: Set<String> = emptySet()
    ) {
        if (hue == 0f && saturation == 0f && contrast == 0f) return

        val colorFilter = buildColorFilter(hue, saturation, contrast)
        val tempFile = File(apkFile.parentFile, "${apkFile.name}.icon_tmp")

        // Convert exact paths → filenames so the same icon file is matched across
        // all density directories and across split APKs whose ARSC we didn't read.
        // e.g. "res/drawable-xhdpi-v4/ic_launcher_foreground.png" → "ic_launcher_foreground.png"
        val iconFileNames = iconPaths.mapTo(mutableSetOf()) { it.substringAfterLast('/') }

        ZipFile(apkFile).use { inZip ->
            ZipOutputStream(FileOutputStream(tempFile).buffered()).use { outZip ->
                for (entry in inZip.entries()) {
                    val name = entry.name
                    val bytes by lazy { inZip.getInputStream(entry).readBytes() }

                    val shouldPatch = if (iconFileNames.isNotEmpty()) {
                        // Precise mode: match any bitmap entry whose filename is known
                        isBitmapPath(name) && name.substringAfterLast('/') in iconFileNames
                    } else {
                        isMipmapBitmap(name)       // heuristic fallback
                    }

                    if (shouldPatch) {
                        val patched = applyColorFilter(bytes, colorFilter)
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

    /** True for any PNG or WebP entry path. */
    private fun isBitmapPath(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".png") || lower.endsWith(".webp")
    }

    /** Heuristic fallback: true for res/mipmap-*.png / *.webp entries. */
    private fun isMipmapBitmap(name: String): Boolean {
        if (!name.startsWith("res/mipmap-")) return false
        return isBitmapPath(name)
    }

    /** Decode → apply color filter → re-encode as PNG. Returns original bytes on failure. */
    private fun applyColorFilter(bytes: ByteArray, filter: ColorMatrixColorFilter): ByteArray {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val dst = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dst)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = filter }
        canvas.drawBitmap(src, 0f, 0f, paint)
        src.recycle()
        val out = ByteArrayOutputStream()
        dst.compress(Bitmap.CompressFormat.PNG, 100, out)
        dst.recycle()
        return out.toByteArray()
    }

    private fun buildColorFilter(hue: Float, saturation: Float, contrast: Float): ColorMatrixColorFilter {
        val result = ColorMatrix()
        if (hue != 0f) result.postConcat(hueMatrix(hue))
        if (saturation != 0f) {
            val sat = ColorMatrix()
            sat.setSaturation(1f + saturation)
            result.postConcat(sat)
        }
        if (contrast != 0f) result.postConcat(contrastMatrix(contrast))
        return ColorMatrixColorFilter(result)
    }

    /** Luminance-preserving hue rotation (BT.601 luma weights, CSS/SVG spec). */
    private fun hueMatrix(degrees: Float): ColorMatrix {
        val rad = Math.toRadians(degrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
        return ColorMatrix(floatArrayOf(
            lr + c * (1f - lr) + s * (-lr),    lg + c * (-lg) + s * (-lg),        lb + c * (-lb) + s * (1f - lb), 0f, 0f,
            lr + c * (-lr) + s * (0.143f),      lg + c * (1f - lg) + s * (0.140f), lb + c * (-lb) + s * (-0.283f), 0f, 0f,
            lr + c * (-lr) + s * (-(1f - lr)),  lg + c * (-lg) + s * (lg),         lb + c * (1f - lb) + s * (lb),  0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
    }

    /** Contrast matrix: scales each channel around mid-gray (128). */
    private fun contrastMatrix(contrast: Float): ColorMatrix {
        val scale = 1f + contrast
        val translate = (-0.5f * scale + 0.5f) * 255f
        return ColorMatrix(floatArrayOf(
            scale, 0f,    0f,    0f, translate,
            0f,    scale, 0f,    0f, translate,
            0f,    0f,    scale, 0f, translate,
            0f,    0f,    0f,    1f, 0f
        ))
    }
}
