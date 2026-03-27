# APK Cloner Bug Report

Exhaustive codebase analysis performed 2026-03-26. Every source file read in full; two-pass analysis applied.

---

### Bug 1: FileApkParser leaks output streams when extracting archives
**File:** `engine/FileApkParser.kt` — Lines: 54, 58, 110, 114
**Severity:** Critical
**Status:** ✅ FIXED (2026-03-26) — All 4 extraction sites in `parseApkm()` and `parseXapk()` now nest both streams in `use {}`, matching the existing correct pattern in `copyUriToFile()`.
**Description:** When extracting APK files from APKM/XAPK archives, `dest.outputStream()` creates a `FileOutputStream` that is never closed. Only the zip input stream is wrapped in `use {}`. The output stream is leaked every time an entry is extracted. This leaks file descriptors (Android has a per-process limit of ~1024) and, more critically, the OS may not flush all data to disk — meaning extracted APK files can be silently truncated or corrupted, causing downstream clone failures with misleading errors.
**Trigger condition:** Clone any app from an APKM or XAPK file that contains 2+ APK entries. Each extraction leaks one `FileOutputStream`.
**Suggested fix:** Nest both streams in `use {}`:
```kotlin
zip.getInputStream(entry).use { input ->
	dest.outputStream().use { output -> input.copyTo(output) }
}
```
The correct pattern already exists in `copyUriToFile()` on line 141. Apply it to all four extraction sites.

---

### Bug 2: Install result broadcast lost during configuration changes
**File:** `ui/CloneProgressActivity.kt` — Lines: 100-122
**Severity:** High
**Status:** ✅ FIXED (2026-03-26) — `CloneProgressViewModel` changed to `AndroidViewModel`. The `BroadcastReceiver` is now created as a private field of the ViewModel, registered in `init {}` using `application.registerReceiver()` (survives config changes), and unregistered in `onCleared()`. All receiver code removed from the Activity. There is no longer any gap between Activity instances during which the broadcast can be lost.
**Description:** The `BroadcastReceiver` that listens for install completion (`ACTION_INSTALL_STATUS`) is registered per-Activity instance in `onCreate()` and unregistered in `onDestroy()`. During a configuration change (screen rotation, dark mode toggle, language change), the old Activity is destroyed and a new one created. There is a time gap between `unregisterReceiver()` in the old instance and `registerReceiver()` in the new one. If the PackageInstaller completes the install during this gap, the `STATUS_SUCCESS` broadcast is delivered to no receiver and is permanently lost. The ViewModel's `done` LiveData never becomes `true`, leaving the user stuck on a progress screen with no indication that the clone was actually installed successfully.
**Trigger condition:** Rotate the device (or trigger any config change) while the install step is in progress. The shorter the install time and the slower the Activity recreation, the more likely the race.
**Suggested fix:** Move the broadcast receiver into the `CloneProgressViewModel`. Register it in `init {}` using the `applicationContext` (which survives config changes) and unregister in `onCleared()`. Post results to LiveData from the receiver. Alternatively, use `ProcessLifecycleOwner` or a sticky broadcast.

---

### Bug 3: ZipAligner reads entire APK into memory
**File:** `engine/ZipAligner.kt` — Line: 21
**Severity:** High
**Status:** ✅ FIXED (2026-03-26) — `align()` completely rewritten to use `RandomAccessFile` for random-access reads. Only the EOCD tail (≤65 KB) and the central directory (typically ≤1 MB) are loaded into heap. File data is streamed entry-by-entry through a reused 64 KB `copyBuf` via `raf.readFully()` + `out.write()`. Peak memory is now proportional to the largest single entry, not the entire APK.
**Description:** `val bytes = input.readBytes()` loads the entire unsigned APK into a single `ByteArray`. For large apps (games with assets, apps with embedded ML models), APKs can easily exceed 100-200 MB. Combined with the ByteBuffer wrap and the output being written simultaneously, peak memory usage approaches 3x the APK size. On devices with limited heap (even with `android:largeHeap="true"`, typical max is 256-512 MB), this causes `OutOfMemoryError`. The `largeHeap` flag in the manifest helps but doesn't solve the problem for genuinely large APKs.
**Trigger condition:** Clone any app whose APK exceeds ~80-100 MB on a device with a 256 MB heap limit. Common examples: games with large assets, media apps, apps with bundled native libraries.
**Suggested fix:** Implement a streaming two-pass aligner: first pass reads the central directory (which is at the end of the file and is small), second pass streams local file headers and data entry-by-entry from `RandomAccessFile`, writing aligned entries directly to the output. This keeps memory usage proportional to the largest single entry, not the entire file.

---

### Bug 4: MetadataPatcher corrupts binary .kotlin_module files
**File:** `engine/MetadataPatcher.kt` — Lines: 20-24
**Severity:** High
**Status:** ✅ FIXED (2026-03-26) — `.kotlin_module` entries now use byte-level in-place replacement (`patchBinary()`), which is lossless for binary data. If old and new names differ in length (unsafe to patch binary length-prefix fields), the file is left untouched. `.properties` and `services/` entries continue to use the UTF-8 text path. `ApkAssembler` updated to pass the entry name to `patch()` at both call sites.
**Description:** `.kotlin_module` files use a binary protobuf-like format, not plain text. The patcher reads the binary bytes as UTF-8 text with `bytes.toString(Charsets.UTF_8)`, performs string replacement, and writes back with `toByteArray(Charsets.UTF_8)`. This roundtrip is **lossy for binary data**: any byte sequence that is not valid UTF-8 (e.g., `0x80`, `0xFE`, `0xFF`) is silently replaced by the Unicode replacement character U+FFFD, which encodes as 3 bytes (`0xEF 0xBF 0xBD`). This changes the file size and corrupts the binary structure. The corrupted `.kotlin_module` file can cause `kotlin-reflect` to crash at runtime or Kotlin serialization to fail.
**Trigger condition:** Clone any Kotlin app (the vast majority of modern Android apps). The `META-INF/*.kotlin_module` file almost always contains the app's package name embedded in binary data alongside non-UTF-8 bytes.
**Suggested fix:** Use byte-level replacement for `.kotlin_module` files (similar to `NativeLibPatcher`'s approach — find the byte pattern of the old package name and replace in-place). Only use the text-based approach for `.properties` and `services/` files which are genuinely plain text. Add a check: if `old.length == new.length`, do direct byte array replacement; otherwise, fall back to the current approach only for known-text formats.

---

### Bug 5: DualDexShimGenerator misses split-declared components when cloning from file
**File:** `engine/DualDexShimGenerator.kt` — Lines: 37-39
**Severity:** High
**Status:** ✅ FIXED (2026-03-26) — Added `splitApkPaths: List<String> = emptyList()` parameter to `DualDexShimGenerator`. After collecting components from the base APK, a loop calls `pm.getPackageArchiveInfo()` on each split path and merges their activities/services/receivers/providers into `componentClasses`. `CloneEngine` updated to pass `apkSet.splitApks.map { it.absolutePath }` when cloning from file.
**Description:** When `sourceApkPath` is non-null (file-based clone from APKM/XAPK), the generator calls `pm.getPackageArchiveInfo(sourceApkPath, flags)` which only reads the single base APK file. Components (Activities, Services, Receivers, Providers) declared in split APK manifests are not returned. The generator creates no shim classes for these components. Meanwhile, `ApkAssembler.assembleSplit()` patches the split manifest to rename these component class names to the new package. At runtime, Android tries to load the renamed class (e.g., `com.new.pkg.FeatureActivity`) but no such class exists — the shim was never generated and the original class still has the old name. This causes `ClassNotFoundException` crashes whenever any split-declared component is launched.
**Trigger condition:** Use DualDex mode to clone an app from file (APKM/XAPK) that has Activities or Services declared in split APK manifests (common in apps with dynamic feature modules).
**Suggested fix:** When `sourceApkPath` is set, also iterate through the split APK files (pass them as a parameter) and call `getPackageArchiveInfo()` on each split to collect their components. Merge all component lists before generating shim classes. Alternatively, parse the split manifests directly using the axml library.

---

### Bug 6: Naive substring replacement corrupts unrelated package references
**File:** Multiple — `ManifestPatcher.kt`:177, `ResourcePatcher.kt`:77-78, `DexPatcher.kt`:331, `AssetsPatcher.kt`:25, `XmlResourcePatcher.kt`:47
**Severity:** High
**Status:** ✅ FIXED (2026-03-26) — Added `internal fun String.replaceBounded(old, new)` in `AssetsPatcher.kt` (visible to the whole engine package). Only replaces a match when the character immediately after is not alphanumeric and not `_`, preventing prefix-of-longer-identifier false matches. Applied to all 7 sites in `ManifestPatcher.kt`, 4 in `XmlResourcePatcher.kt`, 4 in `DexPatcher.kt`, 1 in `ResourcePatcher.kt`, and 2 in `AssetsPatcher.kt`.
**Description:** The codebase uses `String.replace(oldPkg, newPkg)` throughout, which replaces ALL occurrences of the old package name as a plain substring with no boundary checks. If the old package name is a prefix of another package/string, the replacement corrupts the longer string. For example, if the source app's package is `com.mycompany.myapp` and the APK contains references to `com.mycompany.myapputil` (a utility library), the replacement produces `com.mycompany.myapqutil` — an invalid package that doesn't exist. This affects manifest attributes, ARSC string resources, DEX string constants, asset files, and XML resources. The DexPatcher's type descriptor matching (`typeContainsPath`/`rewriteType`) IS boundary-aware, but the string constant patching in `patchMethodImpl()` and all other patchers use unbounded replacement.
**Trigger condition:** Clone any app whose package name is a strict prefix of another identifier referenced in its code or resources. Example: app package `com.example.app` referencing library package `com.example.appcompat` or ContentProvider authority `com.example.app_provider`.
**Suggested fix:** For each replacement site, verify the character immediately after the match is either end-of-string, a non-alphanumeric-non-underscore character (like `.`, `/`, `;`, `:`, `"`, whitespace), or another delimiter appropriate for the context. For the ManifestPatcher's generic handler, DexPatcher's string constants, and text-based patchers, add a boundary check helper.

---

### Bug 7: DexPatcher uses minSdk for opcode interpretation
**File:** `engine/DexPatcher.kt` — Line: 84
**Severity:** High
**Status:** ✅ FIXED (2026-03-26) — Both `DexPatcher` and `DualDexShimGenerator` now use `Opcodes.forApi(maxOf(minSdk, Build.VERSION.SDK_INT))`, ensuring dexlib2 recognises all opcodes the current device can execute regardless of the app's declared minSdk.
**Description:** `Opcodes.forApi(minSdk)` tells dexlib2 to use the opcode set available at the app's minimum SDK level. However, DEX files can contain opcodes from any API level — the `minSdk` only indicates the oldest device the app supports, not the newest opcodes used. Apps commonly use `const-method-handle` and `const-method-type` (API 26+) even with minSdk 21, because the D8/R8 compiler emits them when targeting Java 8+. If `minSdk < 26` and the DEX contains these opcodes, dexlib2 may fail to parse them or misinterpret surrounding instructions, causing either a crash during patching or a silently corrupted DEX file.
**Trigger condition:** Deep-clone any app with `minSdk < 26` that uses Java 8+ features (lambdas, method references) compiled with D8/R8. This is extremely common in modern apps.
**Suggested fix:** Use `Opcodes.forApi(maxOf(minSdk, Build.VERSION.SDK_INT))` to ensure dexlib2 recognizes all opcodes that the current device can execute. Alternatively, use `Opcodes.getDefault()` which uses the latest known API level.

---

### Bug 8: ArscParser uses modulo instead of bitwise AND for package ID
**File:** `axml/.../arsc/ArscParser.java` — Line: 202
**Severity:** Medium
**Status:** ✅ FIXED (2026-03-26) — Changed `in.getInt() % 0xFF` to `in.getInt() & 0xFF`. The modulo form maps package ID 255 to 0; bitwise AND correctly preserves it as 255.
**Description:** `int pid = in.getInt() % 0xFF;` should be `in.getInt() & 0xFF`. The modulo operator `%` with `0xFF` (255) maps the value 255 to 0, while bitwise AND `& 0xFF` correctly preserves it as 255. For the standard app package ID (0x7F = 127), both produce 127. But for system resources (0x01) or shared library resources (potentially 0x00 or 0xFF), the modulo gives wrong results. Package ID 0xFF (used by some OEM overlays) would be parsed as 0, causing all resources in that package to be misattributed.
**Trigger condition:** Clone an app that uses resource overlays or shared libraries with non-standard package IDs (0xFF, 0x00). Rare in typical consumer apps but possible with OEM-specific or enterprise apps.
**Suggested fix:** Change line 202 to: `int pid = in.getInt() & 0xFF;`

---

### Bug 9: NativeLibPatcher can corrupt ELF binaries across string boundaries
**File:** `engine/NativeLibPatcher.kt` — Lines: 54-77
**Severity:** Medium
**Status:** ✅ FIXED (2026-03-26) — `replaceCStrings()` now validates every candidate match before writing. For each match: (1) the null terminator is located first; (2) `findPrevNull()` scans backward (max 4096 bytes) to find the C string's start; (3) `isValidCStringRegion()` confirms every byte from string-start to the null terminator is printable ASCII (0x20–0x7E), reliably rejecting false positives in binary ELF sections; (4) the replacement only proceeds if the new bytes fit within the confirmed space. Logic unified into a single branch — the old separate `new.size <= old.size` / `new.size > old.size` split is gone.
**Description:** When the new package name is longer than the old one, `replaceCStrings()` searches for the next null byte after the match to determine available space. In ELF binaries, null bytes are common in non-string data (e.g., padding, zero-initialized data, instruction encoding). `findNullTerminator()` may find a null byte that belongs to adjacent binary data rather than the actual string terminator. Writing the longer replacement string into this space overwrites binary data between the end of the real string and the found null byte, corrupting instructions, relocation tables, or other ELF structures. Additionally, even when the replacement fits, the null-padding between the end of the new string and the found null byte destroys any data that existed in that gap.
**Trigger condition:** Enable "Patch native libraries" and clone an app where the new package name is longer than the old one (not possible with the current auto-generator, but could happen if custom package names are added in the future). Even with same-length names, false positive matches in binary data can cause corruption.
**Suggested fix:** Parse the ELF section headers to identify `.rodata`, `.dynstr`, and other string sections. Only replace within these known string regions. Alternatively, at minimum, validate that the matched bytes are within a null-terminated region by scanning both forward AND backward to find the enclosing null terminators, confirming the match is a complete substring of a proper C string.

---

### Bug 10: DexPatcher.replaceDexEntries deletes APK before verifying rename
**File:** `engine/DexPatcher.kt` — Lines: 420-421
**Severity:** Medium
**Status:** ✅ FIXED (2026-03-26) — Uses backup-then-rename pattern: original APK is renamed to `.bak`, then temp is renamed to target. If the second rename fails, the backup is restored and an `IOException` is thrown with a clear message. The backup is deleted only after a confirmed successful rename.
**Description:** After building the temp APK with patched DEX entries, the code calls `apkFile.delete()` followed by `tempApk.renameTo(apkFile)`. If `renameTo()` fails (which can happen on some filesystems, when another process holds a handle, or under storage pressure), the original working APK is already deleted and the temp file retains its temp name. Subsequent pipeline steps (signing, alignment) operate on the original `apkFile` path, which no longer exists, causing a `FileNotFoundException` with a misleading error message. While the original installed app is untouched (this is a copy in the work dir), the clone operation fails with a confusing error.
**Trigger condition:** Storage pressure, concurrent file access, or filesystem quirks during the DEX replacement step. More likely on devices with slow/unreliable storage.
**Suggested fix:** Reverse the order — rename the original to a backup name, then rename temp to the target. If the second rename fails, restore the backup:
```kotlin
val backup = File(apkFile.parent, "backup.apk")
apkFile.renameTo(backup)
if (!tempApk.renameTo(apkFile)) {
	backup.renameTo(apkFile)
	throw IOException("Failed to replace DEX entries")
}
backup.delete()
```

---

### Bug 11: No collision detection for generated package name
**File:** `engine/CloneSettings.kt` — Lines: 19-33, `ui/MainActivity.kt` — Lines: 242-253
**Severity:** Medium
**Status:** ✅ FIXED (2026-03-26) — Added `generateUniquePackageName` and `isPackageInstalled` helpers to `MainActivity`. The helper generates the base candidate via `CloneSettings.generateNewPackageName()`, then checks `packageManager.getApplicationInfo()` in a loop; if taken, appends a numeric suffix (base2, base3, up to base100). The result is passed as `newPackageName` to the `CloneSettings` constructor.
**Description:** `generateNewPackageName()` deterministically produces a new package name by incrementing the last character of the source package. It never checks whether this name is already installed on the device. If the generated name collides with an existing app (whether another clone or a completely different app), the PackageInstaller will fail with a signature mismatch error ("Existing package ... signatures do not match newer version"). The error message is confusing and doesn't suggest the actual cause. Additionally, cloning the same app twice always generates the same target name, so the second clone overwrites the first (which may be intentional, but the user has no way to create truly independent clones).
**Trigger condition:** (a) An app already exists on the device whose package name equals the generated clone name. (b) The user clones the same app twice, expecting two independent clones.
**Suggested fix:** After generating the name, check `context.packageManager.getInstalledApplications()` or try `getApplicationInfo(newPkg)` to verify the name is available. If it collides, append a numeric suffix (e.g., `com.example.apq2`) or increment again. Expose the package name in the clone settings dialog so the user can customize it.

---

### Bug 12: AxmlParser crashes on non-standard start element flags
**File:** `axml/.../axml/AxmlParser.java` — Line: 183
**Severity:** Medium
**Status:** ✅ FIXED (2026-03-26) — The bare `throw new RuntimeException()` is replaced with a `System.err` warning. The `attributeSize` is extracted from the high 16 bits of the flag field and used to compute the actual per-attribute stride (`attrSizeInts`). All 6 attribute accessor methods updated from hardcoded `i * 5` to `i * attrSizeInts`, so APKs with non-standard attribute sizes parse correctly instead of crashing.
**Description:** `if (flag != 0x00140014) { throw new RuntimeException(); }` hard-codes the expected attribute chunk size/header bytes in XML start elements. While `0x00140014` is the standard value (attribute size = 20, header offset = 20), some APK build tools or protectors produce valid APKs with non-standard values (e.g., different attribute chunk sizes due to extended attribute formats or obfuscation). This causes an unrecoverable `RuntimeException` that crashes the entire clone operation for these APKs. The error message gives no context about what went wrong.
**Trigger condition:** Clone an app built with non-standard build tools, APK protectors (e.g., Bangcle, Jiagu), or heavily obfuscated apps that modify the binary XML format.
**Suggested fix:** Log a warning instead of throwing. The value at this position indicates attribute size and offset — if they differ from 0x0014, adjust the parsing accordingly (read attribute size from the field and use it for the loop stride). At minimum, replace the bare `RuntimeException` with a descriptive `IllegalStateException("Unsupported AXML attribute chunk: 0x${flag.toString(16)}")`.

---

### Bug 13: DexPatcher.patchEncodedValue misses TypeEncodedValue in field initial values
**File:** `engine/DexPatcher.kt` — Lines: 211-227
**Severity:** Medium
**Status:** ✅ FIXED (2026-03-26) — `patchEncodedValue()` now handles `TypeEncodedValue` (rewrites class descriptor) and `ArrayEncodedValue` (recurses into elements). A new `encodedValueNeedsPatching()` helper centralises the check logic; `annotationsNeedPatching()` and `classNeedsPatching()` both use it, so array-valued annotation parameters (e.g. `@JsonSubTypes`) and `Class<?>` field initializers are now correctly detected and patched. `patchAnnotations()` simplified to route all element values through `patchEncodedValue()`.
**Description:** `patchEncodedValue()` only handles `StringEncodedValue`. It ignores `TypeEncodedValue`, which represents class literal field initializers like `static final Class<?> CLAZZ = MyClass.class;`. In deep clone mode, the DEX class `Lcom/old/pkg/MyClass;` is renamed to `Lcom/new/pkg/MyClass;`, but the field's initial value still points to the old descriptor `Lcom/old/pkg/MyClass;`. At runtime, the class loader fails to resolve the old class name, causing `ClassNotFoundException`. Similarly, `annotationsNeedPatching()` (line 122) doesn't recurse into `ArrayEncodedValue`, so annotations with class-array parameters (e.g., `@JsonSubTypes({@Type(Foo.class), ...})`) are also missed.
**Trigger condition:** Deep-clone an app that has `static final Class<?>` fields or annotations with class-array parameters referencing classes in the app's own package.
**Suggested fix:** Extend `patchEncodedValue()` to handle `TypeEncodedValue`:
```kotlin
if (value is TypeEncodedValue) {
	val rewritten = rewriteType(value.value, oldPath, newPath)
	if (rewritten != value.value) return ImmutableTypeEncodedValue(rewritten)
}
```
Also extend `annotationsNeedPatching()` and `patchAnnotations()` to recurse into `ArrayEncodedValue` elements.

---

### Bug 14: App list will be empty when targetSdk is bumped to 30+
**File:** `app/src/main/AndroidManifest.xml`, `app/build.gradle`
**Severity:** Medium
**Status:** ✅ FIXED (2026-03-26) — Added `<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />` to `AndroidManifest.xml`. No-op at targetSdk 28 but future-proofs the app list when targetSdk is bumped to 30+.
**Description:** The app targets SDK 28, which exempts it from Android 11's package visibility restrictions. `PackageUtils.getInstalledApps()` calls `getInstalledApplications(GET_META_DATA)` which, on API 30+ devices with `targetSdk < 30`, still returns all apps. However, the manifest declares no `QUERY_ALL_PACKAGES` permission and no `<queries>` element. The moment `targetSdk` is bumped to 30+ (which Google Play requires periodically), the app list will show only the cloner itself and a handful of system packages. The app becomes non-functional. Additionally, `DualDexShimGenerator.generate()` and `CloneEngine.clone()` call `getApplicationInfo()` / `getPackageInfo()` which would also fail for non-visible packages.
**Trigger condition:** Bumping `targetSdk` to 30 or higher in `build.gradle`.
**Suggested fix:** Add to the manifest now (even at targetSdk 28, it's a no-op but future-proofs):
```xml
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
```
Or, for a more targeted approach, add a `<queries>` block with `<intent>` elements for the package categories the app needs to see.

---

### Bug 15: Split APK resources.arsc not patched in assembleSplit
**File:** `engine/ApkAssembler.kt` — Lines: 147-232
**Severity:** Medium
**Status:** ✅ FIXED (2026-03-26) — Added a `name == "resources.arsc"` case to `assembleSplit()`'s `when` block. Split ARSC bytes are now passed through `ResourcePatcher().patch()` with `cloneLabel=null` and `labelResId=null` (label renaming is only for the base APK). The patched bytes are written STORED (uncompressed) as required. A try/catch falls back to the raw bytes if the ARSC is malformed rather than crashing.
**Description:** `assembleSplit()` has no special handling for `resources.arsc`. If a split APK contains its own `resources.arsc` (which feature splits and some config splits do), the file is copied through without patching. String resources in the split's ARSC that reference the old package name (e.g., deep link URIs, custom authorities used in the split's ContentProviders, or string resources containing the package name) retain the old value. This can cause the clone's split-specific features to malfunction — deep links route to the original app instead of the clone, or ContentProvider queries return no results.
**Trigger condition:** Clone an app with feature-module split APKs that contain their own `resources.arsc` with package-name-bearing string resources. Most common in apps using Play Feature Delivery or dynamic feature modules.
**Suggested fix:** In `assembleSplit()`, add a check for `resources.arsc` entries and route them through `ResourcePatcher.patch()`, similar to how `assemble()` handles the base APK's ARSC at lines 61-71.

---

### Bug 16: ApkAssembler creates a new Regex object per ZIP entry
**File:** `engine/ApkAssembler.kt` — Line: 51
**Severity:** Low
**Status:** ✅ FIXED (2026-03-26) — Regex moved to `private companion object { val DEX_PATTERN = Regex("classes\\d*\\.dex") }` in `ApkAssembler`. All call sites now reference `DEX_PATTERN`.
**Description:** `name.matches(Regex("classes\\d*\\.dex"))` compiles a new `Regex` instance for every ZIP entry encountered during assembly. A typical APK contains 500-2000 entries. The regex compilation overhead (lexing, NFA/DFA construction) per-entry is small but measurable, and it creates garbage for the GC. On memory-constrained devices already handling large APKs, this adds unnecessary pressure.
**Trigger condition:** Always occurs. More noticeable for large APKs with many entries.
**Suggested fix:** Move the regex to a companion object constant:
```kotlin
private companion object {
	val DEX_PATTERN = Regex("classes\\d*\\.dex")
}
```
Then use `name.matches(DEX_PATTERN)`.

---

### Bug 17: Deprecated API usage throughout UI layer
**File:** `ui/MainActivity.kt` — Lines: 99, 291; `ui/CloneProgressActivity.kt` — Line: 47; `engine/InstallResultReceiver.kt` — Line: 16
**Severity:** Low
**Status:** ✅ FIXED (2026-03-26) — (a) `startActivityForResult`/`onActivityResult` in `MainActivity` replaced with two `ActivityResultLauncher` properties. (b) `getSerializableExtra(key)` in `CloneProgressActivity` replaced with API-33 type-safe version. (c) `getParcelableExtra<Intent>(key)` in `InstallResultReceiver` replaced with API-33 type-safe version.
**Description:** Several deprecated APIs are used: (a) `startActivityForResult()` / `onActivityResult()` in `MainActivity` — deprecated since `activity-ktx 1.2.0` in favor of `ActivityResultLauncher`. (b) `getSerializableExtra("settings")` in `CloneProgressActivity` — deprecated on API 33+ in favor of the type-safe `getSerializableExtra(name, Class)`. (c) `getParcelableExtra<Intent>(Intent.EXTRA_INTENT)` in `InstallResultReceiver` — deprecated on API 33+ in favor of `getParcelableExtra(name, Class)`. All currently functional because `targetSdk=28`, but will trigger lint warnings and could break in future Android versions if the deprecated methods are removed.
**Trigger condition:** Not a runtime bug currently. Will become relevant when targeting newer SDK versions.
**Suggested fix:** (a) Replace `startActivityForResult` with `registerForActivityResult(ActivityResultContracts.StartActivityForResult())`. (b) Use `if (Build.VERSION.SDK_INT >= 33) intent.getSerializableExtra(key, CloneSettings::class.java) else intent.getSerializableExtra(key) as? CloneSettings`. (c) Same pattern for `getParcelableExtra`.

---

### Bug 18: APK Signature Scheme V3 disabled
**File:** `engine/ApkSigner.kt` — Line: 33
**Severity:** Low
**Status:** ✅ FIXED (2026-03-26) — Changed `.setV3SigningEnabled(false)` to `.setV3SigningEnabled(true)` in `ApkSigner.kt`. apksig now emits V1+V2+V3 signatures; devices that do not understand V3 ignore it.
**Description:** `.setV3SigningEnabled(false)` explicitly disables V3 signing (APK Signature Scheme v3, introduced in Android 9). V3 provides key rotation capabilities and stronger verification. While V1+V2 signing is sufficient for installation on all supported devices (minSdk 21), some system-level verification paths on Android 9+ prefer V3. Disabling V3 means the clone cannot participate in key rotation if the app ever needs to update its signing key. Additionally, some strict enterprise MDM solutions or security scanners may flag the absence of V3 as a weaker signature.
**Trigger condition:** Always applies. The practical impact is minimal for most consumer use cases.
**Suggested fix:** Enable V3 signing: `.setV3SigningEnabled(true)`. There is no downside to enabling it — `apksig` will generate V1+V2+V3 signatures, and devices that don't understand V3 simply ignore it.

---
