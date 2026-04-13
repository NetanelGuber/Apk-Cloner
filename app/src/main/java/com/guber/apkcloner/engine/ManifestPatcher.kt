package com.guber.apkcloner.engine

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

		private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

		// Well-known android:* attribute resource IDs (from frameworks/base attrs_manifest.xml)
		private const val RES_NAME        = 0x01010003  // android:name
		private const val RES_AUTHORITIES = 0x01010018  // android:authorities
		private const val RES_EXPORTED    = 0x01010010  // android:exported
		private const val RES_INIT_ORDER  = 0x0101001a  // android:initOrder
		private const val RES_VALUE       = 0x01010024  // android:value (meta-data)

		/** Class name injected into the clone's manifest as the hook ContentProvider. */
		const val HOOK_PROVIDER_CLASS = "com.guber.apkcloner.hooks.HookEntryProvider"

		fun extractManifest(apkFile: File): ByteArray {
			ZipFile(apkFile).use { zip ->
				val entry = zip.getEntry("AndroidManifest.xml")
					?: error("No AndroidManifest.xml in APK")
				return zip.getInputStream(entry).readBytes()
			}
		}

		data class ManifestPatchResult(
			val bytes: ByteArray,
			val labelResourceId: Int?,
			val originalApplicationClass: String? = null,
			val iconResourceId: Int? = null,
			val roundIconResourceId: Int? = null
		)

		/**
		 * Data needed to inject signature-spoofing entries into the manifest.
		 * Produced by [SignatureCapture] and consumed by [ManifestPatcher.patch].
		 */
		data class SpoofingManifestData(
			val originalPackageName: String,
			val sigsBase64: String,
			val signingInfoBase64: String?
		)
	}

	// Captures the resource ID of android:label when it is a reference (BUG-5)
	private var capturedLabelResId: Int? = null
	// Captures android:icon and android:roundIcon resource IDs for icon patching
	private var capturedIconResId: Int? = null
	private var capturedRoundIconResId: Int? = null
	// Captures the original Application class name before it is replaced by the shim
	private var capturedAppClass: String? = null

	fun patch(
		manifestBytes: ByteArray,
		oldPackageName: String,
		newPackageName: String,
		cloneLabel: String?,
		deepClone: Boolean = false,
		overrideMinSdk: Int? = null,
		overrideTargetSdk: Int? = null,
		injectPackageShim: Boolean = false,
		spoofingData: SpoofingManifestData? = null
	): ManifestPatchResult {
		capturedLabelResId = null
		capturedIconResId = null
		capturedRoundIconResId = null
		capturedAppClass = null
		val axml = Axml()
		val reader = AxmlReader(manifestBytes)
		reader.accept(axml)

		for (node in axml.firsts) {
			patchNode(node, oldPackageName, newPackageName, cloneLabel, deepClone, injectPackageShim, overrideMinSdk, overrideTargetSdk, newPackageName, spoofingData)
		}

		val writer = AxmlWriter()
		axml.accept(writer)
		return ManifestPatchResult(writer.toByteArray(), capturedLabelResId, capturedAppClass, capturedIconResId, capturedRoundIconResId)
	}

	// ── Signature-spoofing manifest injection ────────────────────────────────

	/**
	 * Appends a ContentProvider and three meta-data children to the given
	 * [applicationNode] (which must be the `<application>` Axml.Node).
	 *
	 * Injected XML equivalent:
	 * ```xml
	 * <provider
	 *     android:name="com.guber.apkcloner.hooks.HookEntryProvider"
	 *     android:authorities="<newPkg>.cloner.hook"
	 *     android:exported="false"
	 *     android:initOrder="2147483647" />
	 *
	 * <meta-data android:name="spoofing.originalPackageName" android:value="<origPkg>" />
	 * <meta-data android:name="spoofing.originalSignatures"  android:value="<base64>" />
	 * <meta-data android:name="spoofing.originalSigningInfo" android:value="<base64>" />  <!-- optional -->
	 * ```
	 */
	private fun injectSpoofingNodes(
		applicationNode: Axml.Node,
		newPkg: String,
		data: SpoofingManifestData
	) {
		// 1. <provider> — HookEntryProvider
		val providerNode = Axml.Node()
		providerNode.ns   = null
		providerNode.name = "provider"
		providerNode.attrs.add(makeAttr("name",        RES_NAME,        NodeVisitor.TYPE_STRING,      HOOK_PROVIDER_CLASS))
		providerNode.attrs.add(makeAttr("authorities", RES_AUTHORITIES, NodeVisitor.TYPE_STRING,      "$newPkg.cloner.hook"))
		// TYPE_INT_BOOLEAN stores false as 0, true as 0xFFFFFFFF (-1) — use Int, not Boolean
		providerNode.attrs.add(makeAttr("exported",    RES_EXPORTED,    NodeVisitor.TYPE_INT_BOOLEAN, 0))
		providerNode.attrs.add(makeAttr("initOrder",   RES_INIT_ORDER,  NodeVisitor.TYPE_FIRST_INT,   Int.MAX_VALUE))
		applicationNode.children.add(providerNode)

		// 2. <meta-data> — original package name
		applicationNode.children.add(makeMetaNode("spoofing.originalPackageName", data.originalPackageName))

		// 3. <meta-data> — Parcel-serialised Signature[]
		applicationNode.children.add(makeMetaNode("spoofing.originalSignatures", data.sigsBase64))

		// 4. <meta-data> — Parcel-serialised SigningInfo (API 28+ only; absent for imported APKs)
		if (data.signingInfoBase64 != null) {
			applicationNode.children.add(makeMetaNode("spoofing.originalSigningInfo", data.signingInfoBase64))
		}
	}

	/** Creates a `<meta-data android:name=[name] android:value=[value] />` node. */
	private fun makeMetaNode(name: String, value: String): Axml.Node {
		val node = Axml.Node()
		node.ns   = null
		node.name = "meta-data"
		node.attrs.add(makeAttr("name",  RES_NAME,  NodeVisitor.TYPE_STRING, name))
		node.attrs.add(makeAttr("value", RES_VALUE, NodeVisitor.TYPE_STRING, value))
		return node
	}

	/** Creates a single [Axml.Node.Attr] with the given properties. */
	private fun makeAttr(name: String, resourceId: Int, type: Int, value: Any): Axml.Node.Attr {
		val attr = Axml.Node.Attr()
		attr.ns         = ANDROID_NS
		attr.name       = name
		attr.resourceId = resourceId
		attr.type       = type
		attr.value      = value
		return attr
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
		deepClone: Boolean,
		injectPackageShim: Boolean,
		overrideMinSdk: Int? = null,
		overrideTargetSdk: Int? = null,
		rootNewPkg: String = newPkg,
		spoofingData: SpoofingManifestData? = null
	) {
		val tagName = node.name ?: ""

		val attrsToRemove = mutableListOf<Axml.Node.Attr>()
		var foundAppName = false

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

				// <application android:name="..."> with package shim injection —
				// capture the original class and replace with shim. Must come before
				// the generic COMPONENT_TAGS handler below.
				tagName == "application" && attrName == "name" && injectPackageShim -> {
					foundAppName = true
					if (attr.type == NodeVisitor.TYPE_STRING && attr.value is String) {
						val resolved = resolveClassName(attr.value as String, oldPkg)
						capturedAppClass = if (deepClone) resolved.replaceBounded(oldPkg, newPkg) else resolved
						attr.value = PackageNameShimGenerator.SHIM_CLASS
					}
				}

				// Component android:name — resolve to absolute. When deepClone,
				// also update the package portion since DEX classes have been renamed.
				tagName in COMPONENT_TAGS && attrName == "name" -> {
					if (attr.type == NodeVisitor.TYPE_STRING && attr.value is String) {
						val resolved = resolveClassName(attr.value as String, oldPkg)
						attr.value = if (deepClone) resolved.replaceBounded(oldPkg, newPkg) else resolved
					}
				}

				// <activity-alias android:targetActivity="..."> — same treatment
				tagName == "activity-alias" && attrName == "targetActivity" -> {
					if (attr.type == NodeVisitor.TYPE_STRING && attr.value is String) {
						val resolved = resolveClassName(attr.value as String, oldPkg)
						attr.value = if (deepClone) resolved.replaceBounded(oldPkg, newPkg) else resolved
					}
				}

				// <application android:backupAgent="..."> — class reference
				tagName == "application" && attrName == "backupAgent" -> {
					if (attr.type == NodeVisitor.TYPE_STRING && attr.value is String) {
						val resolved = resolveClassName(attr.value as String, oldPkg)
						attr.value = if (deepClone) resolved.replaceBounded(oldPkg, newPkg) else resolved
					}
				}

				// <provider android:authorities="...">
				tagName == "provider" && attrName == "authorities" -> {
					val authorities = attr.value as? String ?: continue
					attr.value = authorities
						.split(";")
						.joinToString(";") { it.replaceBounded(oldPkg, newPkg) }
				}

				// <application android:icon="..."> / android:roundIcon — capture resource IDs
				tagName == "application" && attrName == "icon" -> {
					capturedIconResId = attr.value as? Int
				}

				tagName == "application" && attrName == "roundIcon" -> {
					capturedRoundIconResId = attr.value as? Int
				}

				// <application android:label="..."> — always intercept this branch
				// (BUG-11: no cloneLabel != null condition; prevents fallthrough to
				// generic handler which would incorrectly mutate a literal label string)
				tagName == "application" && attrName == "label" -> {
					if (attr.type == NodeVisitor.TYPE_STRING && attr.value is String) {
						var v = attr.value as String
						if (cloneLabel != null) v = cloneLabel
						else if (v.contains(oldPkg)) v = v.replaceBounded(oldPkg, newPkg)
						attr.value = v
					} else {
						// Resource reference — capture the ID for ResourcePatcher (BUG-5)
						capturedLabelResId = attr.value as? Int
					}
				}

				// <uses-sdk android:minSdkVersion="..." android:targetSdkVersion="...">
				tagName == "uses-sdk" && attrName == "minSdkVersion" && overrideMinSdk != null -> {
					attr.value = overrideMinSdk
				}

				tagName == "uses-sdk" && attrName == "targetSdkVersion" && overrideTargetSdk != null -> {
					attr.value = overrideTargetSdk
				}

				// Patch any other attribute that contains the old package as a string.
				// Component class names were already handled above and won't reach here.
				attr.type == NodeVisitor.TYPE_STRING && attr.value is String -> {
					val strVal = attr.value as String
					if (strVal.contains(oldPkg)) {
						attr.value = strVal.replaceBounded(oldPkg, newPkg)
					}
				}
			}
		}

		// Remove marked attributes (sharedUserId)
		node.attrs.removeAll(attrsToRemove)

		// If the <application> tag has no android:name attribute, inject the shim class
		if (tagName == "application" && injectPackageShim && !foundAppName) {
			val nameAttr = Axml.Node.Attr()
			nameAttr.ns = "http://schemas.android.com/apk/res/android"
			nameAttr.name = "name"
			nameAttr.resourceId = 16842755  // android:name = 0x01010003
			nameAttr.type = NodeVisitor.TYPE_STRING
			nameAttr.value = PackageNameShimGenerator.SHIM_CLASS
			node.attrs.add(nameAttr)
		}

		// Recurse into children (process existing children before we add new ones)
		for (child in node.children) {
			patchNode(child, oldPkg, newPkg, cloneLabel, deepClone, injectPackageShim, overrideMinSdk, overrideTargetSdk, rootNewPkg, spoofingData)
		}

		// Inject the signature-spoofing ContentProvider + meta-data AFTER recursion so
		// the newly added nodes are not themselves processed by patchNode (which would
		// mangle the originalPackageName meta-data value and the HookEntryProvider class).
		if (tagName == "application" && spoofingData != null) {
			injectSpoofingNodes(node, rootNewPkg, spoofingData)
		}
	}
}
