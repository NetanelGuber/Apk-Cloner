package com.guber.apkcloner.engine

import android.os.Build
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.builder.MutableMethodImplementation
import org.jf.dexlib2.builder.instruction.BuilderInstruction21c
import org.jf.dexlib2.builder.instruction.BuilderInstruction31c
import org.jf.dexlib2.iface.Annotation
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Field
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.MethodImplementation
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.iface.value.ArrayEncodedValue
import org.jf.dexlib2.iface.value.EncodedValue
import org.jf.dexlib2.iface.value.StringEncodedValue
import org.jf.dexlib2.iface.value.TypeEncodedValue
import org.jf.dexlib2.immutable.ImmutableAnnotation
import org.jf.dexlib2.immutable.ImmutableAnnotationElement
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableField
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodParameter
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.immutable.value.ImmutableArrayEncodedValue
import org.jf.dexlib2.immutable.value.ImmutableStringEncodedValue
import org.jf.dexlib2.immutable.value.ImmutableTypeEncodedValue
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DexPatcher {

	fun patchApk(apkFile: File, oldPackageName: String, newPackageName: String, minSdk: Int) {
		val oldPath = oldPackageName.replace('.', '/')
		val newPath = newPackageName.replace('.', '/')
		val patchedDexFiles = mutableMapOf<String, File>()

		try {
			ZipFile(apkFile).use { zipFile ->
				val dexEntries = zipFile.entries().toList().filter {
					it.name.matches(Regex("classes\\d*\\.dex"))
				}
				if (dexEntries.isEmpty()) return

				for (entry in dexEntries) {
					val dexBytes = zipFile.getInputStream(entry).readBytes()
					val tempDex = File(apkFile.parentFile, "in_${entry.name}")
					try {
						tempDex.writeBytes(dexBytes)
						val outFile = File(apkFile.parentFile, "out_${entry.name}")
						patchDexFile(tempDex, outFile, oldPackageName, newPackageName, oldPath, newPath, minSdk)
						patchedDexFiles[entry.name] = outFile
					} finally {
						tempDex.delete()
					}
					// Help GC between large DEX files
					System.gc()
				}
			} // zipFile closed here before replaceDexEntries reopens the file

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
		newPath: String,
		minSdk: Int
	) {
		val opcodes = Opcodes.forApi(maxOf(minSdk, Build.VERSION.SDK_INT))
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
		if (typeContainsPath(classDef.type, oldPath)) return true
		if (classDef.superclass?.let { typeContainsPath(it, oldPath) } == true) return true
		if (classDef.interfaces.any { typeContainsPath(it, oldPath) }) return true
		if (annotationsNeedPatching(classDef.annotations, oldPkg, oldPath)) return true

		for (field in classDef.fields) {
			if (typeContainsPath(field.type, oldPath)) return true
			val init = field.initialValue
			if (init != null && encodedValueNeedsPatching(init, oldPkg, oldPath)) return true
			if (annotationsNeedPatching(field.annotations, oldPkg, oldPath)) return true
		}

		for (method in classDef.methods) {
			if (typeContainsPath(method.returnType, oldPath)) return true
			if (method.parameters.any { typeContainsPath(it.type, oldPath) }) return true
			if (methodImplHasMatchingStrings(method.implementation, oldPkg, oldPath)) return true
			if (annotationsNeedPatching(method.annotations, oldPkg, oldPath)) return true
		}

		return false
	}

	private fun annotationsNeedPatching(
		annotations: Set<Annotation>,
		oldPkg: String,
		oldPath: String
	): Boolean {
		for (ann in annotations) {
			if (typeContainsPath(ann.type, oldPath)) return true
			for (elem in ann.elements) {
				if (encodedValueNeedsPatching(elem.value, oldPkg, oldPath)) return true
			}
		}
		return false
	}

	private fun encodedValueNeedsPatching(value: EncodedValue, oldPkg: String, oldPath: String): Boolean = when {
		value is StringEncodedValue -> value.value.contains(oldPkg) || value.value.contains(oldPath)
		value is TypeEncodedValue -> typeContainsPath(value.value, oldPath)
		value is ArrayEncodedValue -> value.value.any { encodedValueNeedsPatching(it, oldPkg, oldPath) }
		else -> false
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
			patchAnnotations(classDef.annotations, oldPkg, newPkg, oldPath, newPath),
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
		val newAnnotations = if (annotationsNeedPatching(field.annotations, oldPkg, oldPath)) {
			patchAnnotations(field.annotations, oldPkg, newPkg, oldPath, newPath)
		} else {
			field.annotations
		}

		return ImmutableField(
			newDefiningClass,
			field.name,
			newFieldType,
			field.accessFlags,
			newInitialValue,
			newAnnotations,
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
					str.replaceBounded(oldPkg, newPkg).replaceBounded(oldPath, newPath)
				)
			}
		}
		if (value is TypeEncodedValue) {
			val rewritten = rewriteType(value.value, oldPath, newPath)
			if (rewritten != value.value) return ImmutableTypeEncodedValue(rewritten)
		}
		if (value is ArrayEncodedValue) {
			val patchedElements = value.value.map { patchEncodedValue(it, oldPkg, newPkg, oldPath, newPath) }
			if (patchedElements != value.value) return ImmutableArrayEncodedValue(patchedElements)
		}
		return value
	}

	private fun patchAnnotations(
		annotations: Set<Annotation>,
		oldPkg: String,
		newPkg: String,
		oldPath: String,
		newPath: String
	): Set<ImmutableAnnotation> {
		return annotations.map { ann ->
			val newType = rewriteType(ann.type, oldPath, newPath)
			val newElems = ann.elements.map { elem ->
				val patched = patchEncodedValue(elem.value, oldPkg, newPkg, oldPath, newPath)
				if (patched !== elem.value) ImmutableAnnotationElement(elem.name, patched)
				else ImmutableAnnotationElement.of(elem)
			}
			ImmutableAnnotation(ann.visibility, newType, newElems)
		}.toSet()
	}

	private fun patchMethod(
		method: Method,
		oldPkg: String,
		newPkg: String,
		oldPath: String,
		newPath: String
	): Method {
		val defNeedsRewrite = typeContainsPath(method.definingClass, oldPath)
		val retNeedsRewrite = typeContainsPath(method.returnType, oldPath)
		val paramsNeedRewrite = method.parameters.any { typeContainsPath(it.type, oldPath) }
		val implNeedsPatching = methodImplHasMatchingStrings(method.implementation, oldPkg, oldPath)
		val annNeedsPatching = annotationsNeedPatching(method.annotations, oldPkg, oldPath)

		if (!defNeedsRewrite && !retNeedsRewrite && !paramsNeedRewrite && !implNeedsPatching && !annNeedsPatching) {
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
		val newAnnotations = if (annNeedsPatching) {
			patchAnnotations(method.annotations, oldPkg, newPkg, oldPath, newPath)
		} else {
			method.annotations
		}

		return ImmutableMethod(
			newDefiningClass,
			method.name,
			newParams,
			newReturnType,
			method.accessFlags,
			newAnnotations,
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

			val patchedStr = originalStr.replaceBounded(oldPkg, newPkg).replaceBounded(oldPath, newPath)
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

	// Boundary-aware type descriptor match: oldPath must be preceded by L or /
	// and followed by / or ; to avoid matching library package prefixes (BUG-2).
	private fun typeContainsPath(type: String, path: String): Boolean {
		var idx = type.indexOf(path)
		while (idx >= 0) {
			val before = if (idx > 0) type[idx - 1] else null
			val after = type.getOrNull(idx + path.length)
			if ((before == 'L' || before == '/') && (after == '/' || after == ';')) return true
			idx = type.indexOf(path, idx + 1)
		}
		return false
	}

	private fun rewriteType(type: String, oldPath: String, newPath: String): String {
		val sb = StringBuilder()
		var idx = type.indexOf(oldPath)
		var last = 0
		while (idx >= 0) {
			val before = if (idx > 0) type[idx - 1] else null
			val after = type.getOrNull(idx + oldPath.length)
			if ((before == 'L' || before == '/') && (after == '/' || after == ';')) {
				sb.append(type, last, idx).append(newPath)
				last = idx + oldPath.length
			}
			idx = type.indexOf(oldPath, idx + 1)
		}
		sb.append(type, last, type.length)
		return sb.toString()
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
						// DEX files must be STORED (uncompressed) for ART memory-mapping (BUG-10)
						val patchedBytes = patchedDexFiles[name]!!.readBytes()
						val crc = CRC32().also { it.update(patchedBytes) }
						val newEntry = ZipEntry(name)
						newEntry.method = ZipEntry.STORED
						newEntry.size = patchedBytes.size.toLong()
						newEntry.compressedSize = patchedBytes.size.toLong()
						newEntry.crc = crc.value
						zout.putNextEntry(newEntry)
						zout.write(patchedBytes)
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

		val backup = File(apkFile.parent, "${apkFile.name}.bak")
		apkFile.renameTo(backup)
		if (!tempApk.renameTo(apkFile)) {
			backup.renameTo(apkFile)
			throw IOException("Failed to replace DEX entries in ${apkFile.name}")
		}
		backup.delete()
	}
}
