package com.guber.apkcloner.engine

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.builder.MutableMethodImplementation
import org.jf.dexlib2.builder.instruction.BuilderInstruction10x
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import java.io.File

class DualDexShimGenerator(
	private val context: Context,
	private val oldPackageName: String,
	private val newPackageName: String,
	private val minSdk: Int,
	private val sourceApkPath: String? = null,
	private val splitApkPaths: List<String> = emptyList()
) {

	@Suppress("DEPRECATION")
	fun generate(): ByteArray? {
		val pm = context.packageManager
		val flags = PackageManager.GET_ACTIVITIES or
			PackageManager.GET_SERVICES or
			PackageManager.GET_RECEIVERS or
			PackageManager.GET_PROVIDERS

		val packageInfo: android.content.pm.PackageInfo
		val appInfo: android.content.pm.ApplicationInfo

		if (sourceApkPath != null) {
			packageInfo = pm.getPackageArchiveInfo(sourceApkPath, flags)
				?: throw IllegalStateException(
					"Could not parse APK components for Dual DEX shim generation. " +
					"Try using Deep Clone instead."
				)
			appInfo = packageInfo.applicationInfo ?: return null
			appInfo.sourceDir = sourceApkPath
			appInfo.publicSourceDir = sourceApkPath
		} else {
			packageInfo = pm.getPackageInfo(oldPackageName, flags)
			appInfo = pm.getApplicationInfo(oldPackageName, 0)
		}

		val prefix = "$oldPackageName."
		val componentClasses = mutableSetOf<String>()

		appInfo.className?.takeIf { it.startsWith(prefix) }?.let { componentClasses.add(it) }
		appInfo.backupAgentName?.takeIf { it.startsWith(prefix) }?.let { componentClasses.add(it) }
		packageInfo.activities?.forEach { if (it.name.startsWith(prefix)) componentClasses.add(it.name) }
		packageInfo.services?.forEach { if (it.name.startsWith(prefix)) componentClasses.add(it.name) }
		packageInfo.receivers?.forEach { if (it.name.startsWith(prefix)) componentClasses.add(it.name) }
		packageInfo.providers?.forEach { if (it.name.startsWith(prefix)) componentClasses.add(it.name) }

		for (splitPath in splitApkPaths) {
			val splitInfo = pm.getPackageArchiveInfo(splitPath, flags) ?: continue
			splitInfo.activities?.forEach { if (it.name.startsWith(prefix)) componentClasses.add(it.name) }
			splitInfo.services?.forEach { if (it.name.startsWith(prefix)) componentClasses.add(it.name) }
			splitInfo.receivers?.forEach { if (it.name.startsWith(prefix)) componentClasses.add(it.name) }
			splitInfo.providers?.forEach { if (it.name.startsWith(prefix)) componentClasses.add(it.name) }
		}

		if (componentClasses.isEmpty()) return null

		val oldPath = oldPackageName.replace('.', '/')
		val newPath = newPackageName.replace('.', '/')

		val shimClasses = componentClasses.map { buildShimClass(it, oldPath, newPath) }

		val opcodes = Opcodes.forApi(maxOf(minSdk, Build.VERSION.SDK_INT))
		val dexFile = ImmutableDexFile(opcodes, shimClasses)
		val tempFile = File(context.cacheDir, "shim_${System.currentTimeMillis()}.dex")
		return try {
			DexFileFactory.writeDexFile(tempFile.absolutePath, dexFile)
			tempFile.readBytes()
		} finally {
			tempFile.delete()
		}
	}

	private fun buildShimClass(originalClass: String, oldPath: String, newPath: String): ImmutableClassDef {
		val originalDescriptor = "L${originalClass.replace('.', '/')};"
		val shimDescriptor = "L${originalClass.replace('.', '/').replace(oldPath, newPath)};"

		val methodRef = ImmutableMethodReference(originalDescriptor, "<init>", emptyList<String>(), "V")

		val impl = MutableMethodImplementation(1)
		impl.addInstruction(BuilderInstruction35c(Opcode.INVOKE_DIRECT, 1, 0, 0, 0, 0, 0, methodRef))
		impl.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))

		val constructor = ImmutableMethod(
			shimDescriptor,
			"<init>",
			emptyList(),
			"V",
			AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
			emptySet(),
			emptySet(),
			impl
		)

		return ImmutableClassDef(
			shimDescriptor,
			AccessFlags.PUBLIC.value,
			originalDescriptor,
			emptyList<String>(),
			null,
			emptySet(),
			emptyList(),
			emptyList(),
			listOf(constructor),
			emptyList()
		)
	}
}
