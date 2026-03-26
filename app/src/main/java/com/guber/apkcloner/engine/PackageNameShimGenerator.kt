package com.guber.apkcloner.engine

import android.content.Context
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.builder.MutableMethodImplementation
import org.jf.dexlib2.builder.instruction.BuilderInstruction10x
import org.jf.dexlib2.builder.instruction.BuilderInstruction11x
import org.jf.dexlib2.builder.instruction.BuilderInstruction21c
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import java.io.File

/**
 * Generates a DEX file containing a single Application subclass that overrides
 * getPackageName() to return the original package name. This allows apps that
 * call getApplicationContext().getPackageName() (Unity's typical path) to receive
 * the original package name even though the clone has a different package.
 *
 * The generated class:
 *   class PkgShimApp extends <originalAppClass|Application> {
 *       public String getPackageName() { return "com.original.package"; }
 *   }
 *
 * The manifest android:name attribute is updated to point at this class.
 */
class PackageNameShimGenerator(
	private val context: Context,
	private val originalPackageName: String,
	private val minSdk: Int,
	private val originalAppClass: String?
) {

	companion object {
		const val SHIM_CLASS = "com.guber.apkcloner.shim.PkgShimApp"
		private const val SHIM_DESCRIPTOR = "Lcom/guber/apkcloner/shim/PkgShimApp;"
	}

	fun generate(): ByteArray {
		val parentDescriptor = if (originalAppClass != null)
			"L${originalAppClass.replace('.', '/')};"
		else
			"Landroid/app/Application;"

		val shimClass = buildShimClass(parentDescriptor)
		val opcodes = Opcodes.forApi(minSdk)
		val dexFile = ImmutableDexFile(opcodes, listOf(shimClass))
		val tempFile = File(context.cacheDir, "pkgshim_${System.currentTimeMillis()}.dex")
		return try {
			DexFileFactory.writeDexFile(tempFile.absolutePath, dexFile)
			tempFile.readBytes()
		} finally {
			tempFile.delete()
		}
	}

	private fun buildShimClass(parentDescriptor: String): ImmutableClassDef {
		// Constructor: invoke-direct {p0}, <parent>.<init>()V; return-void
		val superInitRef = ImmutableMethodReference(parentDescriptor, "<init>", emptyList<String>(), "V")
		val ctorImpl = MutableMethodImplementation(1)
		ctorImpl.addInstruction(BuilderInstruction35c(Opcode.INVOKE_DIRECT, 1, 0, 0, 0, 0, 0, superInitRef))
		ctorImpl.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
		val constructor = ImmutableMethod(
			SHIM_DESCRIPTOR, "<init>", emptyList(), "V",
			AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
			emptySet(), emptySet(), ctorImpl
		)

		// getPackageName(): const-string v0, originalPackageName; return-object v0
		val getPkgImpl = MutableMethodImplementation(1)
		getPkgImpl.addInstruction(
			BuilderInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(originalPackageName))
		)
		getPkgImpl.addInstruction(BuilderInstruction11x(Opcode.RETURN_OBJECT, 0))
		val getPackageName = ImmutableMethod(
			SHIM_DESCRIPTOR, "getPackageName", emptyList(), "Ljava/lang/String;",
			AccessFlags.PUBLIC.value,
			emptySet(), emptySet(), getPkgImpl
		)

		return ImmutableClassDef(
			SHIM_DESCRIPTOR,
			AccessFlags.PUBLIC.value,
			parentDescriptor,
			emptyList<String>(),
			null,
			emptySet(),
			emptyList(),
			emptyList(),
			listOf(constructor),         // direct methods: <init> only
			listOf(getPackageName)        // virtual methods: public overrides
		)
	}
}
