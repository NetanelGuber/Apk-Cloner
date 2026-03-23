package com.guber.apkcloner.engine

import pxb.android.axml.Axml
import pxb.android.axml.AxmlReader
import pxb.android.axml.AxmlWriter
import pxb.android.axml.NodeVisitor

class XmlResourcePatcher(
	private val oldPkg: String,
	private val newPkg: String
) {
	private val oldPath = oldPkg.replace('.', '/')
	private val newPath = newPkg.replace('.', '/')

	fun shouldPatch(entryName: String): Boolean {
		return entryName.startsWith("res/") && entryName.endsWith(".xml")
	}

	fun patch(xmlBytes: ByteArray): ByteArray? {
		return try {
			val axml = Axml()
			val reader = AxmlReader(xmlBytes)
			reader.accept(axml)

			var changed = false
			for (node in axml.firsts) {
				if (patchNode(node)) changed = true
			}

			if (!changed) return null

			val writer = AxmlWriter()
			axml.accept(writer)
			writer.toByteArray()
		} catch (_: Exception) {
			null
		}
	}

	private fun patchNode(node: Axml.Node): Boolean {
		var changed = false

		for (attr in node.attrs) {
			if (attr.type == NodeVisitor.TYPE_STRING && attr.value is String) {
				val str = attr.value as String
				if (str.contains(oldPkg) || str.contains(oldPath)) {
					attr.value = str.replace(oldPkg, newPkg).replace(oldPath, newPath)
					changed = true
				}
			}
		}

		val text = node.text
		if (text != null && (text.text.contains(oldPkg) || text.text.contains(oldPath))) {
			text.text = text.text.replace(oldPkg, newPkg).replace(oldPath, newPath)
			changed = true
		}

		for (child in node.children) {
			if (patchNode(child)) changed = true
		}

		return changed
	}
}
