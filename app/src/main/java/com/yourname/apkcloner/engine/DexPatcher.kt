package com.yourname.apkcloner.engine

import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.builder.MutableMethodImplementation
import org.jf.dexlib2.builder.instruction.BuilderInstruction21c
import org.jf.dexlib2.builder.instruction.BuilderInstruction31c
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Field
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.MethodImplementation
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.iface.value.EncodedValue
import org.jf.dexlib2.iface.value.StringEncodedValue
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableField
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodParameter
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.immutable.value.ImmutableStringEncodedValue
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DexPatcher {

	fun patchApk(apkFile: File, oldPackageName: String, newPackageName: String) {
		val zipFile = ZipFile(apkFile)
		val dexEntries = zipFile.entries().toList().filter {
			it.name.matches(Regex("classes\\d*\\.dex"))
		}

		if (dexEntries.isEmpty()) {
			zipFile.close()
			return
		}

		val oldPath = oldPackageName.replace('.', '/')
		val newPath = newPackageName.replace('.', '/')
		val patchedDexFiles = mutableMapOf<String, File>()

		try {
			for (entry in dexEntries) {
				val dexBytes = zipFile.getInputStream(entry).readBytes()
				val tempDex = File(apkFile.parentFile, "in_${entry.name}")
				try {
					tempDex.writeBytes(dexBytes)
					val outFile = File(apkFile.parentFile, "out_${entry.name}")
					patchDexFile(tempDex, outFile, oldPackageName, newPackageName, oldPath, newPath)
					patchedDexFiles[entry.name] = outFile
				} finally {
					tempDex.delete()
				}
				// Help GC between large DEX files
				System.gc()
			}

			zipFile.close()
			replaceDexEntries(apkFile, patchedDexFiles)
		} finally {
			patchedDexFiles.values.forEach { it.delete() }
		}
	}

	private fun patchDexFile(
		dexFile: File,
		outFile: File,
		oldPkg: String,
		newPkg: String,
		oldPath: String,
		newPath: String
	) {
		val opcodes = Opcodes.forApi(28)
		val dex = DexFileFactory.loadDexFile(dexFile, opcodes)

		val patchedClasses = dex.classes.map { classDef ->
			if (classNeedsPatching(classDef, oldPkg, oldPath)) {
				patchClass(classDef, oldPkg, newPkg, oldPath, newPath)
			} else {
				classDef
			}
		}

		val patchedDex = ImmutableDexFile(opcodes, patchedClasses)
		DexFileFactory.writeDexFile(outFile.absolutePath, patchedDex)
	}

	private fun classNeedsPatching(classDef: ClassDef, oldPkg: String, oldPath: String): Boolean {
		if (classDef.type.contains(oldPath)) return true
		if (classDef.superclass?.contains(oldPath) == true) return true
		if (classDef.interfaces.any { it.contains(oldPath) }) return true

		for (field in classDef.fields) {
			if (field.type.contains(oldPath)) return true
			val init = field.initialValue
			if (init is StringEncodedValue && (init.value.contains(oldPkg) || init.value.contains(oldPath))) return true
		}

		for (method in classDef.methods) {
			if (method.returnType.contains(oldPath)) return true
			if (method.parameters.any { it.type.contains(oldPath) }) return true
			if (methodImplHasMatchingStrings(method.implementation, oldPkg, oldPath)) return true
		}

		return false
	}

	private fun methodImplHasMatchingStrings(
		impl: MethodImplementation?,
		oldPkg: String,
		oldPath: String
	): Boolean {
		if (impl == null) return false
		for (instr in impl.instructions) {
			val op = instr.opcode
			if (op != Opcode.CONST_STRING && op != Opcode.CONST_STRING_JUMBO) continue
			val ref = (instr as? ReferenceInstruction)?.reference as? StringReference ?: continue
			val str = ref.string
			if (str.contains(oldPkg) || str.contains(oldPath)) return true
		}
		return false
	}

	private fun patchClass(
		classDef: ClassDef,
		oldPkg: String,
		newPkg: String,
		oldPath: String,
		newPath: String
	): ClassDef {
		val newType = rewriteType(classDef.type, oldPath, newPath)
		val newSuperclass = classDef.superclass?.let { rewriteType(it, oldPath, newPath) }
		val newInterfaces = classDef.interfaces.map { rewriteType(it, oldPath, newPath) }

		val newStaticFields = classDef.staticFields.map { patchField(it, oldPkg, newPkg, oldPath, newPath) }
		val newInstanceFields = classDef.instanceFields.map { patchField(it, oldPkg, newPkg, oldPath, newPath) }
		val newDirectMethods = classDef.directMethods.map { patchMethod(it, oldPkg, newPkg, oldPath, newPath) }
		val newVirtualMethods = classDef.virtualMethods.map { patchMethod(it, oldPkg, newPkg, oldPath, newPath) }

		return ImmutableClassDef(
			newType,
			classDef.accessFlags,
			newSuperclass,
			newInterfaces,
			classDef.sourceFile,
			classDef.annotations,
			newStaticFields,
			newInstanceFields,
			newDirectMethods,
			newVirtualMethods
		)
	}

	private fun patchField(
		field: Field,
		oldPkg: String,
		newPkg: String,
		oldPath: String,
		newPath: String
	): ImmutableField {
		val newDefiningClass = rewriteType(field.definingClass, oldPath, newPath)
		val newFieldType = rewriteType(field.type, oldPath, newPath)
		val newInitialValue = field.initialValue?.let { patchEncodedValue(it, oldPkg, newPkg, oldPath, newPath) }

		return ImmutableField(
			newDefiningClass,
			field.name,
			newFieldType,
			field.accessFlags,
			newInitialValue,
			field.annotations,
			field.hiddenApiRestrictions
		)
	}

	private fun patchEncodedValue(
		value: EncodedValue,
		oldPkg: String,
		newPkg: String,
		oldPath: String,
		newPath: String
	): EncodedValue {
		if (value is StringEncodedValue) {
			val str = value.value
			if (str.contains(oldPkg) || str.contains(oldPath)) {
				return ImmutableStringEncodedValue(
					str.replace(oldPkg, newPkg).replace(oldPath, newPath)
				)
			}
		}
		return value
	}

	private fun patchMethod(
		method: Method,
		oldPkg: String,
		newPkg: String,
		oldPath: String,
		newPath: String
	): Method {
		val defNeedsRewrite = method.definingClass.contains(oldPath)
		val retNeedsRewrite = method.returnType.contains(oldPath)
		val paramsNeedRewrite = method.parameters.any { it.type.contains(oldPath) }
		val implNeedsPatching = methodImplHasMatchingStrings(method.implementation, oldPkg, oldPath)

		if (!defNeedsRewrite && !retNeedsRewrite && !paramsNeedRewrite && !implNeedsPatching) {
			return method
		}

		val newDefiningClass = if (defNeedsRewrite) rewriteType(method.definingClass, oldPath, newPath) else method.definingClass
		val newReturnType = if (retNeedsRewrite) rewriteType(method.returnType, oldPath, newPath) else method.returnType
		val newParams = if (paramsNeedRewrite) {
			method.parameters.map {
				ImmutableMethodParameter(rewriteType(it.type, oldPath, newPath), it.annotations, it.name)
			}
		} else {
			method.parameters
		}
		val newImpl = if (implNeedsPatching) {
			patchMethodImpl(method.implementation!!, oldPkg, newPkg, oldPath, newPath)
		} else {
			method.implementation
		}

		return ImmutableMethod(
			newDefiningClass,
			method.name,
			newParams,
			newReturnType,
			method.accessFlags,
			method.annotations,
			method.hiddenApiRestrictions,
			newImpl
		)
	}

	private fun patchMethodImpl(
		impl: MethodImplementation,
		oldPkg: String,
		newPkg: String,
		oldPath: String,
		newPath: String
	): MethodImplementation {
		val mutableImpl = MutableMethodImplementation(impl)

		for (i in 0 until mutableImpl.instructions.size) {
			val instruction = mutableImpl.instructions[i]
			val opcode = instruction.opcode

			if (opcode != Opcode.CONST_STRING && opcode != Opcode.CONST_STRING_JUMBO) continue

			val ref = (instruction as ReferenceInstruction).reference as? StringReference ?: continue
			val originalStr = ref.string
			if (!originalStr.contains(oldPkg) && !originalStr.contains(oldPath)) continue

			val patchedStr = originalStr.replace(oldPkg, newPkg).replace(oldPath, newPath)
			val newRef = ImmutableStringReference(patchedStr)
			val regA = (instruction as OneRegisterInstruction).registerA

			val newInstr = if (opcode == Opcode.CONST_STRING) {
				BuilderInstruction21c(opcode, regA, newRef)
			} else {
				BuilderInstruction31c(opcode, regA, newRef)
			}
			mutableImpl.replaceInstruction(i, newInstr)
		}

		return mutableImpl
	}

	private fun rewriteType(type: String, oldPath: String, newPath: String): String {
		return if (type.contains(oldPath)) {
			type.replace(oldPath, newPath)
		} else {
			type
		}
	}

	private fun replaceDexEntries(apkFile: File, patchedDexFiles: Map<String, File>) {
		val tempApk = File(apkFile.parent, "temp_dex_patched.apk")

		ZipInputStream(FileInputStream(apkFile).buffered()).use { zin ->
			ZipOutputStream(FileOutputStream(tempApk).buffered()).use { zout ->
				val seen = mutableSetOf<String>()
				var entry = zin.nextEntry
				while (entry != null) {
					val name = entry.name
					if (!seen.add(name)) {
						zin.closeEntry()
						entry = zin.nextEntry
						continue
					}

					if (patchedDexFiles.containsKey(name)) {
						val patchedFile = patchedDexFiles[name]!!
						val newEntry = ZipEntry(name)
						newEntry.method = ZipEntry.DEFLATED
						zout.putNextEntry(newEntry)
						patchedFile.inputStream().buffered().use { it.copyTo(zout) }
					} else {
						val newEntry = ZipEntry(name)
						newEntry.method = entry.method
						if (entry.method == ZipEntry.STORED) {
							newEntry.size = entry.size
							newEntry.compressedSize = entry.compressedSize
							newEntry.crc = entry.crc
						}
						zout.putNextEntry(newEntry)
						zin.copyTo(zout)
					}
					zout.closeEntry()
					zin.closeEntry()
					entry = zin.nextEntry
				}
			}
		}

		apkFile.delete()
		tempApk.renameTo(apkFile)
	}
}
