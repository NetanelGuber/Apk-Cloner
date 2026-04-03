package com.guber.apkcloner.engine

import android.graphics.ColorMatrix
import pxb.android.arsc.ArscParser
import pxb.android.arsc.ArscWriter
import pxb.android.arsc.BagValue
import pxb.android.arsc.Value
import java.io.File
import java.util.AbstractMap
import java.util.zip.ZipFile
import kotlin.math.cos
import kotlin.math.sin

class ResourcePatcher {

	private companion object {
		const val TYPE_STRING = 0x03
		// Android ARSC color value types
		val COLOR_TYPES = setOf(0x1c, 0x1d, 0x1e, 0x1f)

		fun extractArsc(apkFile: File): ByteArray? {
			ZipFile(apkFile).use { zip ->
				val entry = zip.getEntry("resources.arsc") ?: return null
				return zip.getInputStream(entry).readBytes()
			}
		}
	}

	/**
	 * Returns every file path (e.g. "res/mipmap-xxhdpi-v4/ic_launcher.png") that
	 * resources.arsc maps to any of the given resource IDs.  Used by IconPatcher to
	 * know exactly which APK entries to recolour, regardless of where they live.
	 */
	fun collectIconFilePaths(arscBytes: ByteArray, iconResIds: Set<Int>): Set<String> {
		if (iconResIds.isEmpty()) return emptySet()
		val pkgs = ArscParser(arscBytes).parse()
		val paths = mutableSetOf<String>()
		for (pkg in pkgs) {
			for ((typeId, type) in pkg.types) {
				for (config in type.configs) {
					for ((entryKey, entry) in config.resources) {
						val thisResId = ((pkg.id and 0xFF) shl 24) or
							((typeId and 0xFF) shl 16) or
							(entryKey and 0xFFFF)
						if (thisResId !in iconResIds) continue
						val v = entry.value
						if (v is Value && v.type == TYPE_STRING) {
							val raw = v.raw ?: continue
							if (raw.startsWith("res/")) paths.add(raw)
						}
					}
				}
			}
		}
		return paths
	}

	/**
	 * Transforms any direct ARGB color values in resources.arsc whose resource ID
	 * is in [layerResIds] (icon background/foreground layer color resources).
	 * Returns [arscBytes] unchanged if no matching color entries are found.
	 */
	fun patchIconLayerColors(
		arscBytes: ByteArray,
		layerResIds: Set<Int>,
		hue: Float,
		saturation: Float,
		contrast: Float
	): ByteArray {
		if (layerResIds.isEmpty()) return arscBytes
		val pkgs = ArscParser(arscBytes).parse()
		// Build the ColorMatrix once — same formula as IconPatcher and VectorColorPatcher
		// so all three patchers produce identical per-pixel results.
		val colorMatrix = buildColorMatrix(hue, saturation, contrast)
		var changed = false
		for (pkg in pkgs) {
			for ((typeId, type) in pkg.types) {
				for (config in type.configs) {
					for ((entryKey, entry) in config.resources) {
						val thisResId = ((pkg.id and 0xFF) shl 24) or
							((typeId and 0xFF) shl 16) or (entryKey and 0xFFFF)
						if (thisResId !in layerResIds) continue
						val v = entry.value
						when {
							v is Value && v.type in COLOR_TYPES -> {
								val transformed = applyMatrix(v.data, colorMatrix)
								if (transformed != v.data) {
									entry.value = Value(v.type, transformed, null)
									changed = true
								}
							}
							// Color state list (e.g. <selector> with per-state colors).
							// Use index-based replacement to avoid Map.Entry.setValue Kotlin
							// interop issues — the underlying list is a mutable ArrayList.
							v is BagValue -> {
								for (i in v.map.indices) {
									val mv = v.map[i].value
									if (mv.type in COLOR_TYPES) {
										val transformed = applyMatrix(mv.data, colorMatrix)
										if (transformed != mv.data) {
											v.map[i] = AbstractMap.SimpleEntry(
												v.map[i].key,
												Value(mv.type, transformed, null)
											)
											changed = true
										}
									}
								}
							}
						}
					}
				}
			}
		}
		return if (changed) ArscWriter(pkgs).toByteArray() else arscBytes
	}

	/**
	 * Applies a pre-built 4×5 ColorMatrix to a single ARGB integer.
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

	/** Builds a 4×5 ColorMatrix identical to [IconPatcher.buildColorFilter]. */
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

	fun patch(
		arscBytes: ByteArray,
		oldPackageName: String,
		newPackageName: String,
		cloneLabel: String?,
		labelResId: Int? = null
	): ByteArray {
		val pkgs = ArscParser(arscBytes).parse()

		for (pkg in pkgs) {
			// Patch the package name in the package chunk header
			if (pkg.name == oldPackageName) {
				pkg.name = newPackageName
			}

			// Walk all types → configs → entries → values
			// pkg.types is TreeMap<Int, Type> keyed by type ID
			for ((typeId, type) in pkg.types) {
				for (config in type.configs) {
					for ((entryKey, entry) in config.resources) {
						// Reconstruct the full resource ID to match against manifest label ref (BUG-5)
						val thisResId = ((pkg.id and 0xFF) shl 24) or ((typeId and 0xFF) shl 16) or (entryKey and 0xFFFF)
						val isLabelEntry = labelResId != null && thisResId == labelResId

						when (val value = entry.value) {
							is Value -> {
								patchValue(value, oldPackageName, newPackageName, cloneLabel, isLabelEntry)
							}
							is BagValue -> {
								for (mapEntry in value.map) {
									patchValue(mapEntry.value, oldPackageName, newPackageName, cloneLabel, isLabelEntry)
								}
							}
						}
					}
				}
			}
		}

		return ArscWriter(pkgs).toByteArray()
	}

	private fun patchValue(
		value: Value,
		oldPkg: String,
		newPkg: String,
		cloneLabel: String?,
		isLabelEntry: Boolean
	) {
		if (value.type != TYPE_STRING || value.raw == null) return

		// Apply both operations independently (BUG-6): package substitution and label
		// appending are sequential steps, not mutually exclusive branches.
		var updated = value.raw
		if (updated.contains(oldPkg)) {
			updated = updated.replaceBounded(oldPkg, newPkg)
		}
		if (cloneLabel != null && isLabelEntry) {
			updated = cloneLabel
		}
		if (updated != value.raw) {
			value.raw = updated
		}
	}

}
