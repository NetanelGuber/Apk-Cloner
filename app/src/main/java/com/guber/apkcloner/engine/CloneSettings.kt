package com.guber.apkcloner.engine

import java.io.Serializable

data class CloneSettings(
	val sourcePackageName: String,
	val newPackageName: String = generateNewPackageName(sourcePackageName),
	val cloneLabel: String = "Clone",
	val deepClone: Boolean = false,
	val dualDex: Boolean = false,
	val patchNativeLibs: Boolean = false,
	val pkgShim: Boolean = false,
	val overrideMinSdk: Int? = null,
	val overrideTargetSdk: Int? = null,
	val sourceApkPaths: List<String>? = null,
	val saveToStorage: Boolean = false,
	val installAfterBuild: Boolean = true,
	val saveLocationUri: String? = null
) : Serializable {

	companion object {
		fun generateNewPackageName(source: String): String {
			if (source.isEmpty()) return source
			val lastChar = source.last()
			val newChar = when {
				lastChar in 'a'..'y' -> lastChar + 1
				lastChar == 'z' -> 'a'
				lastChar in 'A'..'Y' -> lastChar + 1
				lastChar == 'Z' -> 'A'
				lastChar in '0'..'8' -> lastChar + 1
				lastChar == '9' -> '0'
				else -> 'x'
			}
			return source.dropLast(1) + newChar
		}
	}
}
