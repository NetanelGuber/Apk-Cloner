package com.yourname.apkcloner.engine

import pxb.android.axml.Axml
import pxb.android.axml.AxmlReader
import pxb.android.axml.AxmlWriter
import pxb.android.axml.NodeVisitor
import java.io.File
import java.util.zip.ZipFile

class ManifestPatcher {

	companion object {
		// Tags whose android:name attribute is a Java class reference
		private val COMPONENT_TAGS = setOf(
			"application", "activity", "activity-alias",
			"service", "receiver", "provider"
		)

		fun extractManifest(apkFile: File): ByteArray {
			ZipFile(apkFile).use { zip ->
				val entry = zip.getEntry("AndroidManifest.xml")
					?: error("No AndroidManifest.xml in APK")
				return zip.getInputStream(entry).readBytes()
			}
		}

		data class ManifestPatchResult(val bytes: ByteArray, val labelResourceId: Int?)
	}

	// Captures the resource ID of android:label when it is a reference (BUG-5)
	private var capturedLabelResId: Int? = null

	fun patch(
		manifestBytes: ByteArray,
		oldPackageName: String,
		newPackageName: String,
		cloneLabel: String?,
		deepClone: Boolean = false
	): ManifestPatchResult {
		capturedLabelResId = null
		val axml = Axml()
		val reader = AxmlReader(manifestBytes)
		reader.accept(axml)

		for (node in axml.firsts) {
			patchNode(node, oldPackageName, newPackageName, cloneLabel, deepClone)
		}

		val writer = AxmlWriter()
		axml.accept(writer)
		return ManifestPatchResult(writer.toByteArray(), capturedLabelResId)
	}

	/**
	 * Convert a relative or short class name to an absolute one using the
	 * given package, so the name keeps pointing at the real DEX class even
	 * after the manifest package attribute changes.
	 */
	private fun resolveClassName(name: String, pkg: String): String {
		return when {
			name.startsWith(".") -> "$pkg$name"       // .Foo → com.example.app.Foo
			!name.contains(".")  -> "$pkg.$name"       // Foo  → com.example.app.Foo
			else                 -> name                // already absolute
		}
	}

	private fun patchNode(
		node: Axml.Node,
		oldPkg: String,
		newPkg: String,
		cloneLabel: String?,
		deepClone: Boolean
	) {
		val tagName = node.name ?: ""

		val attrsToRemove = mutableListOf<Axml.Node.Attr>()

		for (attr in node.attrs) {
			val attrName = attr.name ?: continue

			when {
				// <manifest package="...">
				tagName == "manifest" && attrName == "package" -> {
					attr.value = newPkg
				}

				// Remove sharedUserId to prevent signature mismatch failures
				tagName == "manifest" && attrName == "sharedUserId" -> {
					attrsToRemove.add(attr)
				}

				// Component android:name — resolve to absolute. When deepClone,
				// also update the package portion since DEX classes have been renamed.
				tagName in COMPONENT_TAGS && attrName == "name" -> {
					if (attr.type == NodeVisitor.TYPE_STRING && attr.value is String) {
						val resolved = resolveClassName(attr.value as String, oldPkg)
						attr.value = if (deepClone) resolved.replace(oldPkg, newPkg) else resolved
					}
				}

				// <activity-alias android:targetActivity="..."> — same treatment
				tagName == "activity-alias" && attrName == "targetActivity" -> {
					if (attr.type == NodeVisitor.TYPE_STRING && attr.value is String) {
						val resolved = resolveClassName(attr.value as String, oldPkg)
						attr.value = if (deepClone) resolved.replace(oldPkg, newPkg) else resolved
					}
				}

				// <application android:backupAgent="..."> — class reference
				tagName == "application" && attrName == "backupAgent" -> {
					if (attr.type == NodeVisitor.TYPE_STRING && attr.value is String) {
						val resolved = resolveClassName(attr.value as String, oldPkg)
						attr.value = if (deepClone) resolved.replace(oldPkg, newPkg) else resolved
					}
				}

				// <provider android:authorities="...">
				tagName == "provider" && attrName == "authorities" -> {
					val authorities = attr.value as? String ?: continue
					attr.value = authorities
						.split(";")
						.joinToString(";") { it.replace(oldPkg, newPkg) }
				}

				// <application android:label="..."> — always intercept this branch
				// (BUG-11: no cloneLabel != null condition; prevents fallthrough to
				// generic handler which would incorrectly mutate a literal label string)
				tagName == "application" && attrName == "label" -> {
					if (attr.type == NodeVisitor.TYPE_STRING && attr.value is String) {
						var v = attr.value as String
						if (v.contains(oldPkg)) v = v.replace(oldPkg, newPkg)
						if (cloneLabel != null) v = "$v $cloneLabel"
						attr.value = v
					} else {
						// Resource reference — capture the ID for ResourcePatcher (BUG-5)
						capturedLabelResId = attr.value as? Int
					}
				}

				// Patch any other attribute that contains the old package as a string.
				// Component class names were already handled above and won't reach here.
				attr.type == NodeVisitor.TYPE_STRING && attr.value is String -> {
					val strVal = attr.value as String
					if (strVal.contains(oldPkg)) {
						attr.value = strVal.replace(oldPkg, newPkg)
					}
				}
			}
		}

		// Remove marked attributes (sharedUserId)
		node.attrs.removeAll(attrsToRemove)

		// Recurse into children
		for (child in node.children) {
			patchNode(child, oldPkg, newPkg, cloneLabel, deepClone)
		}
	}
}
