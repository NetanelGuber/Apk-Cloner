# APK Cloner — Bug Report & Fix Plan

Generated: 2026-03-22

---

## CRITICAL BUGS (will cause crashes / broken clones)

---

### BUG-1 — Deep Clone: Manifest still references old package class paths after DEX renaming

**Files:** `ManifestPatcher.kt:88-91`, `CloneEngine.kt:56-63`
**Severity:** CRITICAL — cloned app crashes on launch whenever Deep Clone is enabled

**Root Cause:**

`ManifestPatcher` resolves ALL component class names (activity, service, receiver, provider, application) to absolute paths using the **old** package name and intentionally does NOT update them:

```kotlin
// ManifestPatcher.kt line 88-91
tagName in COMPONENT_TAGS && attrName == "name" -> {
    if (attr.type == NodeVisitor.TYPE_STRING && attr.value is String) {
        attr.value = resolveClassName(attr.value as String, oldPkg)  // resolved to OLD package
    }
}
```

The comment on lines 84-87 explains the reasoning:
> "Do NOT replace the package portion: the class name must stay unchanged since the DEX bytecode is not renamed."

That reasoning is only valid when **`deepClone = false`**. When `deepClone = true`, `DexPatcher` IS called (CloneEngine.kt line 58) and renames every class descriptor from `Lcom/old/package/SomeClass;` to `Lcom/new/package/SomeClass;`. After this rename:

- DEX contains: `com.new.package.MainActivity`
- Manifest says: `android:name="com.old.package.MainActivity"`

Android's ClassLoader uses the manifest entry to load the class. It looks for `com.old.package.MainActivity` in the DEX, finds nothing, and throws `ClassNotFoundException`. **Every activity, service, and the Application class fails to load.**

This affects every single component in the app: activities, services, broadcast receivers, content providers, and the custom Application class. The cloned app is permanently broken.

**What needs to change:**
`ManifestPatcher.patch()` needs a `deepClone: Boolean` parameter. When deep clone is active, component class names must be re-resolved using `newPkg` instead of `oldPkg`. The `CloneEngine` must pass this flag, and the `ManifestPatcher` must update class name resolution accordingly for all component tags and the `backupAgent` attribute.

---

### BUG-2 — Deep Clone: DexPatcher's substring matching incorrectly renames unrelated library classes

**File:** `DexPatcher.kt:96-99`, `DexPatcher.kt:279-285`
**Severity:** CRITICAL — corrupts the DEX of any cloned app whose package name is a prefix of a bundled library's package

**Root Cause:**

`classNeedsPatching` and `rewriteType` use `String.contains(oldPath)` without any word-boundary check:

```kotlin
// DexPatcher.kt line 97
if (classDef.type.contains(oldPath)) return true

// DexPatcher.kt line 280-284
private fun rewriteType(type: String, oldPath: String, newPath: String): String {
    return if (type.contains(oldPath)) {
        type.replace(oldPath, newPath)
    } else { type }
}
```

`oldPath` is simply `oldPackageName.replace('.', '/')`. If `oldPath = "com/example/app"`, this substring is also present in class paths like `"Lcom/example/appcompat/widget/Toolbar;"` (hypothetical, but any library that has a package starting with the same prefix). The `replace(oldPath, newPath)` call then corrupts these unrelated class descriptors, producing invalid DEX type strings like `Lclone/com/example/appcompat/widget/Toolbar;` which don't exist anywhere.

The same substring issue affects string constant patching — any string that happens to contain the old package name as a substring gets replaced, including:
- Third-party SDK application IDs / API keys embedded in DEX strings
- Firebase registration tokens
- URLs that happen to contain the package name as a path segment

**What needs to change:**
The type rewrite must check that the match is a **complete path segment boundary** (i.e., the match ends at `/` or `;` or is the full path). A correct check would verify `oldPath` is preceded by `L` or `/` and followed by `/` or `;` in the DEX type descriptor format. String constant replacement should ideally check for dot/slash word boundaries too, not just substring inclusion.

---

### BUG-3 — Deep Clone: Split APK DEX files are never patched

**Files:** `CloneEngine.kt:56-63`, `ApkAssembler.kt:123-196`
**Severity:** CRITICAL for split APKs with DEX in splits (dynamic feature modules)

**Root Cause:**

`DexPatcher.patchApk()` is called only on the **base APK** (`workingApk`):

```kotlin
// CloneEngine.kt line 58-63
DexPatcher().patchApk(
    workingApk,                    // only base APK
    settings.sourcePackageName,
    settings.newPackageName
)
```

The split APKs are processed afterward in `assembleSplit()` (CloneEngine.kt lines 94-100), but `assembleSplit` never invokes any DEX patcher — it only runs `XmlResourcePatcher`, `AssetsPatcher`, `MetadataPatcher`, and optionally `NativeLibPatcher`.

Apps using dynamic feature modules (common in games, large apps, apps published on the Play Store with on-demand delivery) have DEX files in their split APKs. These DEX files contain code that references the old package path as class descriptors. After a deep clone, the base APK's DEX is patched but split DEX is not, creating a mixed state: some classes are renamed to the new package, others still reference the old package. This causes `ClassNotFoundException` or `NoClassDefFoundError` at runtime when feature code tries to reference renamed base classes.

**What needs to change:**
`DexPatcher` must be invoked on each split APK file before (or during) `assembleSplit`, the same way it is for the base APK.

---

## HIGH SEVERITY BUGS (will cause malfunction in many real-world apps)

---

### BUG-4 — Deep Clone: Class and method annotations are not patched

**File:** `DexPatcher.kt:148-159`, `DexPatcher.kt:162-181`
**Severity:** HIGH — breaks apps using Dagger/Hilt, Retrofit, Room, or any annotation-driven framework with package-specific class paths

**Root Cause:**

When `patchClass` rebuilds a `ImmutableClassDef`, it passes `classDef.annotations` unchanged:

```kotlin
// DexPatcher.kt line 148-159
return ImmutableClassDef(
    newType,
    classDef.accessFlags,
    newSuperclass,
    newInterfaces,
    classDef.sourceFile,
    classDef.annotations,     // ← NOT patched
    newStaticFields,
    newInstanceFields,
    newDirectMethods,
    newVirtualMethods
)
```

Similarly, `patchField` passes `field.annotations` unchanged (line 175), and `patchMethod` passes `method.annotations` unchanged (line 239).

Annotation values in DEX can contain string values or class references (type annotations, annotation element values). If those values contain the old package path — e.g., a Dagger module annotation referencing a class in the old package, or a Retrofit `@Component` annotation — the mismatch between renamed classes and unchanged annotation references will cause the annotation processor or runtime reflection to fail.

**What needs to change:**
Each annotation set must be walked and any string or class-reference annotation elements that contain the old package must be patched the same way string constants are patched.

---

### BUG-5 — ResourcePatcher: Clone label not applied to apps using non-standard label resource keys

**Files:** `ResourcePatcher.kt:14`, `ResourcePatcher.kt:79`
**Severity:** HIGH — cloned app shows identical name to original for most real-world apps

**Root Cause:**

The ARSC clone-label logic only appends the clone suffix when the string resource's spec name matches one of three hardcoded keys:

```kotlin
// ResourcePatcher.kt line 14
val APP_LABEL_KEYS = setOf("app_name", "application_name", "app_label")

// ResourcePatcher.kt line 79
cloneLabel != null && specName != null && specName in APP_LABEL_KEYS -> {
    value.raw = "$raw $cloneLabel"
}
```

In practice, most large apps use `app_name`, but many apps name their label resource `label`, `title`, `name`, `app_title`, `application_label`, or other variants. These will never match, so the clone label is never appended in the ARSC.

The `ManifestPatcher` only appends the label when `android:label` is a **raw string** (TYPE_STRING). When it is a resource reference (the far more common case), the label's actual displayed text comes from the ARSC. If the ARSC string doesn't match the hardcoded key set, the label never changes and the user can't distinguish the clone from the original.

**What needs to change:**
The ARSC label patching should be driven by the resource IDs that the `android:label` attribute in the manifest references, not by hardcoded spec name strings. Alternatively, the key set should be expanded significantly. At minimum, the manifest patcher should communicate which resource ID the label attribute points to, and the ARSC patcher should mark that ID's string for label patching.

---

### BUG-6 — ResourcePatcher: `when` block excludes clone label for label resources that also contain the old package name

**File:** `ResourcePatcher.kt:73-82`
**Severity:** HIGH — clone label silently not applied whenever the app name string contains the package name

**Root Cause:**

The `patchValue` function's `when` block is exclusive — only the first matching branch executes:

```kotlin
// ResourcePatcher.kt line 73-82
when {
    raw.contains(oldPkg) -> {
        value.raw = raw.replace(oldPkg, newPkg)   // ← executed, clone label NOT appended
    }
    cloneLabel != null && specName != null && specName in APP_LABEL_KEYS -> {
        value.raw = "$raw $cloneLabel"             // ← never reached if first branch matched
    }
}
```

If an app's label resource string happens to contain the old package name (e.g., `"com.example.app — Premium"` or any similar branding string), the first branch replaces the package name but the second branch never executes. The cloned app's label will have the updated package name in the string but will be missing the clone suffix entirely.

**What needs to change:**
Both operations should apply independently. When the spec name matches `APP_LABEL_KEYS`, the clone label should ALWAYS be appended regardless of whether the string also needed a package name substitution. The two patches should be composed, not mutually exclusive.

---

## MEDIUM SEVERITY BUGS (resource leaks, session leaks, edge-case crashes)

---

### BUG-7 — DexPatcher: ZipFile is not closed when an exception occurs during DEX processing

**File:** `DexPatcher.kt:36-70`
**Severity:** MEDIUM — file descriptor leak; on low-resource devices can prevent subsequent file operations

**Root Cause:**

`ZipFile` is opened at line 36 and stored in a local variable. There are two explicit close calls (line 43 for the early-return path, and line 67 before `replaceDexEntries`), but neither is in a `try-finally` or `use` block:

```kotlin
// DexPatcher.kt line 36
val zipFile = ZipFile(apkFile)

// ... lots of code ...

try {
    for (entry in dexEntries) {
        val dexBytes = zipFile.getInputStream(entry).readBytes()  // could throw
        // ... patchDexFile can throw ...
    }
    zipFile.close()      // ← only closes on SUCCESS path
    replaceDexEntries(apkFile, patchedDexFiles)
} finally {
    patchedDexFiles.values.forEach { it.delete() }  // ← never closes zipFile on exception
}
```

If `patchDexFile()` or `zipFile.getInputStream()` throws, `zipFile` is never closed. The file descriptor leaks until GC finalises the `ZipFile` object.

**What needs to change:**
Wrap the entire body in `zipFile.use { ... }` (Kotlin's `Closeable.use`) so it is always closed, or restructure with an explicit `try-finally`.

---

### BUG-8 — ApkInstaller: PackageInstaller session not abandoned on write failure

**File:** `ApkInstaller.kt:42-71`, `ApkInstaller.kt:73-110`
**Severity:** MEDIUM — orphaned install sessions accumulate; system shows "incomplete download" in settings

**Root Cause:**

In both `install()` and `installMultiApk()`, the session is opened but there is no error handling around the write loop:

```kotlin
// ApkInstaller.kt line 50-70 (install())
val sessionId = installer.createSession(params)
val session = installer.openSession(sessionId)

session.openWrite("base", 0, signedApk.length()).use { output ->
    FileInputStream(signedApk).use { input ->
        input.copyTo(output)      // ← can throw IOException
        session.fsync(output)
    }
}
// ... commit and close ...
session.close()
```

If `copyTo` or `fsync` throws, the code exits via exception. Neither `session.abandon()` nor `session.close()` is called. The `PackageInstaller` session remains open in the system. Repeated failures accumulate orphaned sessions that appear in system settings under "Downloaded apps" or similar, and eat system resources.

**What needs to change:**
Wrap the session operations in `try-catch` (or use a `use`-style helper). On any exception, call `session.abandon()` before rethrowing.

---

### BUG-9 — CloneEngine: Storage space check runs AFTER extraction, wasting the space it's trying to protect

**File:** `CloneEngine.kt:20-27`
**Severity:** MEDIUM — the space check is functionally useless; runs after the APK is already on disk

**Root Cause:**

```kotlin
// CloneEngine.kt line 20-27
val apkSet = ApkExtractor(context).extract(settings.sourcePackageName, workDir)
val workingApk = apkSet.baseApk

// Check available space — but extraction already happened above!
FileUtils.checkAvailableSpace(
    context,
    FileUtils.estimateRequiredSpace(workingApk)
)
```

By the time `checkAvailableSpace` runs, the APK has already been copied to the work directory, consuming the very space being checked. If space is insufficient, the check throws `InsufficientStorageException` and the `finally` block cleans up the work dir — but the extraction already failed or succeeded. The check provides no protection against out-of-space errors during the subsequent (larger) operations (DEX patching, assembling, signing).

Also, the 3× estimate (`estimateRequiredSpace` = `apkFile.length() * 3`) ignores that split APKs also need space. If an app has 10 splits each 50 MB, only the base APK size is used in the estimate.

**What needs to change:**
The space check should run BEFORE extraction, using the known size of the source APK's directory (from `ApplicationInfo.sourceDir` file size plus `splitSourceDirs` sizes) multiplied by the safety factor. The estimate should account for all splits.

---

### BUG-10 — DexPatcher: Patched DEX entries written as DEFLATED instead of STORED

**File:** `DexPatcher.kt:303-307`
**Severity:** MEDIUM — can cause installation failure or AOT compilation errors on some Android versions

**Root Cause:**

When `replaceDexEntries` rebuilds the APK with patched DEX files, it forces DEFLATED compression on every patched DEX entry:

```kotlin
// DexPatcher.kt line 303-307
val newEntry = ZipEntry(name)
newEntry.method = ZipEntry.DEFLATED   // ← forced DEFLATED regardless of original
zout.putNextEntry(newEntry)
patchedFile.inputStream().buffered().use { it.copyTo(zout) }
```

Android requires DEX files to be STORED (uncompressed) in APKs since API 23 for the memory-mapping optimization used by ART. Compressed DEX causes the system to extract the DEX to a private data directory during installation, which:
- Doubles the storage footprint of the installed app
- Can cause installation to fail on devices with full `/data` partitions
- Prevents ART's profile-guided compilation from working correctly on some OEMs

The original APK's DEX entries are almost certainly STORED; the patcher should preserve that.

**What needs to change:**
Preserve the original compression method from the source entry. Since DEX files should be STORED, use `ZipEntry.STORED` (and set size + CRC) for patched DEX entries.

---

### BUG-11 — ManifestPatcher: `android:label` on application tag only patched in ARSC; manifest label skipped when `cloneLabel == null`

**File:** `ManifestPatcher.kt:116-121`
**Severity:** LOW-MEDIUM — edge case, but if the application's `android:label` is a literal string and cloneLabel is null, it falls through to the generic string-replacement handler which could incorrectly alter it

**Root Cause:**

```kotlin
// ManifestPatcher.kt line 116-121
tagName == "application" && attrName == "label" && cloneLabel != null -> {
    if (attr.type == NodeVisitor.TYPE_STRING && attr.value is String) {
        attr.value = "${attr.value} $cloneLabel"
    }
}
```

When `cloneLabel == null`, this branch does not match. The `when` block's Kotlin semantics mean the next matching branch runs — which is the catch-all generic string replacement handler:

```kotlin
attr.type == NodeVisitor.TYPE_STRING && attr.value is String -> {
    val strVal = attr.value as String
    if (strVal.contains(oldPkg)) {
        attr.value = strVal.replace(oldPkg, newPkg)
    }
}
```

If the app's label string is a literal like `"My App (com.example.app)"`, the old package name gets replaced but the intent was just to preserve it unchanged. The label attribute reaches the generic handler when it shouldn't affect it. This is a minor data-flow issue.

---

## LOW SEVERITY BUGS (incomplete features, UX issues)

---

### BUG-12 — MainActivity: Clone is not re-launched after granting install permission

**File:** `MainActivity.kt:189-197`
**Severity:** LOW — poor UX; user must re-select the app after granting permission

**Root Cause:**

When the user doesn't have install permission, `startCloning` redirects to the permission settings screen (line 178) and returns without saving the `CloneSettings`. After the user grants permission and returns, `onActivityResult` only shows a toast:

```kotlin
// MainActivity.kt line 189-197
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == REQUEST_INSTALL_PERMISSION) {
        val installer = ApkInstaller(this)
        if (installer.canInstallPackages()) {
            Toast.makeText(this, "Permission granted. Select an app to clone.", Toast.LENGTH_SHORT).show()
            // ← CloneSettings was never saved; cannot restart the clone
        }
    }
}
```

The user must go back and select the app again from scratch. The `CloneSettings` that was configured in the dialog is lost.

**What needs to change:**
Save the pending `CloneSettings` as a field before redirecting to permission settings, and resume `startCloning` in `onActivityResult` if permission was granted.

---

### BUG-13 — CloneProgressActivity: Install result is lost if user navigates away before installation completes

**File:** `CloneProgressActivity.kt:77-79`, `CloneProgressActivity.kt:132-140`
**Severity:** LOW — UX issue; user loses visibility of success/failure if they press back

**Root Cause:**

The `installReceiver` is registered in `onCreate` and unregistered in `onDestroy`. When the user presses "Cancel" (or back), `finish()` is called, which triggers `onDestroy`, which unregisters the receiver. If installation completes after this point, the `ACTION_INSTALL_STATUS` broadcast has no listener. The user never sees whether the installation succeeded or failed.

The cancel button does not attempt to cancel the `PackageInstaller` session either — it just abandons the UI. The install continues in the background silently.

**What needs to change:**
Either keep the `CloneProgressViewModel` alive with a foreground notification for the install phase, or show a warning before cancelling during installation that the install will still proceed. At minimum, the broadcast should be caught at the application level (or in a persistent receiver) and shown as a notification.

---

### BUG-14 — DexPatcher: `Opcodes.forApi(28)` is hardcoded regardless of target app's actual API level

**File:** `DexPatcher.kt:81`
**Severity:** LOW — theoretical correctness issue; practically safe for API 21-34 range

**Root Cause:**

```kotlin
// DexPatcher.kt line 81
val opcodes = Opcodes.forApi(28)
```

The opcode set used to interpret the source DEX should ideally match the DEX's actual target API, not a hardcoded 28. If an app targets a very old API (pre-ART) or a future API with new opcodes, using the wrong opcode set could misinterpret instructions. In practice, the ART opcode set has been stable from API 21 to 35+, so this causes no real-world issues today, but it's a correctness concern.

**What needs to change:**
Extract the minimum SDK from the manifest (already parsed) or from the APK's `minSdkVersion` and pass it to `Opcodes.forApi()`.

---

### BUG-15 — CloneEngine: `FileUtils.cleanupWorkDir` in `finally` runs even on install exception, potentially masking original error

**File:** `CloneEngine.kt:119-121`
**Severity:** LOW — exception swallowing risk

**Root Cause:**

```kotlin
// CloneEngine.kt line 119-121
} finally {
    FileUtils.cleanupWorkDir(workDir)
}
```

`deleteRecursively()` inside `cleanupWorkDir` can itself throw an exception (e.g., permission denied on a locked file on some Android versions). If it throws while a previous exception is already propagating, the original exception is **swallowed** and the cleanup exception is reported instead. The error dialog shows a confusing cleanup error rather than the real cloning failure.

**What needs to change:**
Wrap `cleanupWorkDir` in a try-catch that swallows or logs its own exceptions silently, so the original propagating exception is preserved.

---

### BUG-16 — ApkExtractor: No validation that split APK paths are valid before copying

**File:** `ApkExtractor.kt:19-26`
**Severity:** LOW — edge case on some OEM/rooted devices where `splitSourceDirs` contains stale paths

**Root Cause:**

```kotlin
// ApkExtractor.kt line 19-26
val destSplits = appInfo.splitSourceDirs
    ?.mapIndexed { i, path ->
        val splitFile = File(path)
        val dest = File(destDir, "split_$i.apk")
        splitFile.copyTo(dest, overwrite = true)  // throws if path doesn't exist
        dest
    }
    ?: emptyList()
```

On some devices, `splitSourceDirs` can contain paths to ODEX/VDEX files or temporary installation paths that no longer exist. `File.copyTo()` throws `NoSuchFileException` in that case, which propagates up as an unhandled cloning error. The error message would be confusing to the user.

**What needs to change:**
Filter out split paths where `File(path).exists()` is false (or where the file isn't a valid ZIP), and log a warning. Only include valid APK splits in the result.

---

## HALF-FINISHED FEATURES

---

### HALF-FINISHED-1 — Clone label only partially implemented

**Files:** `ResourcePatcher.kt`, `ManifestPatcher.kt`

The clone label feature is implemented in two separate places with different coverage:
- `ManifestPatcher` appends the label only if the manifest's `android:label` attribute is a raw string (rare)
- `ResourcePatcher` appends the label only for resources named `app_name`, `application_name`, or `app_label` (incomplete key set)

Most modern apps use a resource reference for `android:label`, and many use a resource key name that is not in the hardcoded set. The result is that for the vast majority of real-world apps, the clone label is **never visible** to the user — the cloned app shows the exact same name as the original, making it indistinguishable on the launcher.

The intended behaviour (showing "MyApp Clone" in the launcher) is not achieved reliably.

---

### HALF-FINISHED-2 — Deep Clone is effectively non-functional end-to-end (see BUG-1 + BUG-2 + BUG-3 + BUG-4)

**Files:** `DexPatcher.kt`, `ManifestPatcher.kt`, `CloneEngine.kt`

The deep clone feature attempts to rename the package identity throughout the DEX bytecode so the cloned app truly behaves as a separate instance. However, the combination of the four critical/high bugs above means:

- Apps crash immediately on launch (BUG-1: manifest/DEX class path mismatch)
- Library classes are silently corrupted (BUG-2: substring matching)
- Split DEX files are not patched (BUG-3)
- Annotation-driven frameworks break (BUG-4)

Deep clone in its current form is **not usable** and will always produce a broken clone. It needs a coordinated fix across ManifestPatcher, DexPatcher, and CloneEngine before it can work.

---

### HALF-FINISHED-3 — Native library patching is present but disabled by default with a warning

**File:** `dialog_clone_settings.xml` (checkbox with warning text), `NativeLibPatcher.kt`

The `NativeLibPatcher` implements binary C-string patching of `.so` files. The UI shows a warning that this is risky and can corrupt the binary. The implementation is conservative (skips occurrences where the new string is too long), but patching native binaries by simple byte-substitution is inherently fragile:

- It does not account for RELA/REL relocations that may reference the patched strings
- It does not update any ELF section checksums or hash tables
- It cannot extend strings (new package prefix makes the string longer, which is the common case)

This feature is incomplete and will silently skip most native string replacements when the new package name is longer (as it always is with the `clone.` prefix), making it effectively a no-op for the most common case.

---

## SUMMARY TABLE

| # | Bug | File | Severity | Impact |
|---|-----|------|----------|--------|
| 1 | Deep clone: manifest class paths not updated | ManifestPatcher.kt:88 | CRITICAL | App crashes on launch with deep clone |
| 2 | Substring matching corrupts unrelated classes | DexPatcher.kt:97,280 | CRITICAL | DEX corruption on many apps |
| 3 | Split APK DEX not patched in deep clone | CloneEngine.kt:56 | CRITICAL | Feature modules broken |
| 4 | Annotations not patched in deep clone | DexPatcher.kt:148 | HIGH | Annotation frameworks break |
| 5 | Clone label missing for non-standard resource keys | ResourcePatcher.kt:14 | HIGH | Clone indistinguishable from original |
| 6 | Clone label skipped when label also has old pkg | ResourcePatcher.kt:73 | HIGH | Clone label silently not applied |
| 7 | ZipFile not closed on exception in DexPatcher | DexPatcher.kt:36 | MEDIUM | File descriptor leak |
| 8 | PackageInstaller session not abandoned on error | ApkInstaller.kt:50 | MEDIUM | Orphaned sessions accumulate |
| 9 | Space check runs after extraction | CloneEngine.kt:20 | MEDIUM | Check is ineffective |
| 10 | Patched DEX entries forced to DEFLATED | DexPatcher.kt:305 | MEDIUM | Install/AOT issues on API 23+ |
| 11 | Application label fallthrough when cloneLabel=null | ManifestPatcher.kt:116 | LOW | Minor data-flow issue |
| 12 | Clone not resumed after permission grant | MainActivity.kt:189 | LOW | Poor UX |
| 13 | Install result lost if user presses Cancel | CloneProgressActivity.kt:77 | LOW | UX issue |
| 14 | Opcodes hardcoded to API 28 | DexPatcher.kt:81 | LOW | Theoretical correctness |
| 15 | cleanupWorkDir in finally can swallow original error | CloneEngine.kt:119 | LOW | Wrong error shown |
| 16 | No validation of split APK paths | ApkExtractor.kt:22 | LOW | Confusing errors on bad splits |
