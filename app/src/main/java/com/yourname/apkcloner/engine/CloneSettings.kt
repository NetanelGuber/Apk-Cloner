package com.yourname.apkcloner.engine

import java.io.Serializable

data class CloneSettings(
	val sourcePackageName: String,
	val newPackageName: String = generateNewPackageName(sourcePackageName),
	val cloneLabel: String = "Clone",
	val deepClone: Boolean = false,
	val patchNativeLibs: Boolean = false
) : Serializable {

	companion object {
		fun generateNewPackageName(source: String): String {
			return "clone.$source"
		}
	}
}
