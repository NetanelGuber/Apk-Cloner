package com.yourname.apkcloner.engine

import pxb.android.axml.Axml
import pxb.android.axml.AxmlReader
import pxb.android.axml.AxmlWriter
import pxb.android.axml.NodeVisitor
import java.io.File
import java.util.zip.ZipFile

class ManifestPatcher {

	fun patch(
		manifestBytes: ByteArray,
		oldPackageName: String,
		newPackageName: String,
		cloneLabel: String?
	): ByteArray {
		val axml = Axml()
		val reader = AxmlReader(manifestBytes)
		reader.accept(axml)

		for (node in axml.firsts) {
			patchNode(node, oldPackageName, newPackageName, cloneLabel)
		}

		val writer = AxmlWriter()
		axml.accept(writer)
		return writer.toByteArray()
	}

	private fun patchNode(
		node: Axml.Node,
		oldPkg: String,
		newPkg: String,
		cloneLabel: String?
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

				// <provider android:authorities="...">
				tagName == "provider" && attrName == "authorities" -> {
					val authorities = attr.value as? String ?: continue
					attr.value = authorities
						.split(";")
						.joinToString(";") { it.replace(oldPkg, newPkg) }
				}

				// <application android:label="..."> — append clone suffix
				tagName == "application" && attrName == "label" && cloneLabel != null -> {
					if (attr.type == NodeVisitor.TYPE_STRING && attr.value is String) {
						attr.value = "${attr.value} $cloneLabel"
					}
				}

				// Patch any other attribute that contains the old package as a string
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
			patchNode(child, oldPkg, newPkg, cloneLabel)
		}
	}

	companion object {
		fun extractManifest(apkFile: File): ByteArray {
			ZipFile(apkFile).use { zip ->
				val entry = zip.getEntry("AndroidManifest.xml")
					?: error("No AndroidManifest.xml in APK")
				return zip.getInputStream(entry).readBytes()
			}
		}
	}
}
