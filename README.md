# APK Cloner

Android tool that clones installed apps (or imported APK/APKM/XAPK files) into independently-runnable copies with a different package name — no root required.

**Version:** 0.4.2 · **Min SDK:** 21 (Android 5.0) · **Target SDK:** 28

---

## Features

- Clone any installed app or import `.apk` / `.apkm` / `.xapk` files
- Three clone strategies:
  - **Standard** — patches manifest, resources, and metadata only (fastest, works for most apps)
  - **Deep Clone** — also patches all DEX bytecode (handles apps that hardcode their own package name)
  - **Dual-DEX Shim** — injects a compatibility shim DEX instead of rewriting bytecode (safer for complex apps)
- **Package Name Shim** — overrides `getPackageName()` at runtime for apps that cache their package name
- **Native lib patching** — binary C-string replacement in `.so` files
- **SDK override** — lower min/target SDK to bypass API restrictions
- **Icon color customisation** — adjust hue, saturation, and contrast of the cloned app's launcher icon; live preview in the clone dialog
- Save output as `.apk` (single) or `.apkm` bundle (splits) to storage, or install directly via `PackageInstaller`
- Saved `.apkm` bundles include `info.json` metadata and `icon.png` so file managers display the app icon
- Dark mode support

### Clone Management (v0.4.0)

- **Clones tab** — two-tab layout ("All Apps" / "Clones") for instant access to cloned apps without scrolling
- **Update detection** — on startup each clone is compared against its original app's version; an UPDATE button appears inline when a newer version is available
- **One-tap update** — tapping UPDATE reopens the clone dialog with every setting from the original clone pre-filled (icon adjustments, DEX strategy, SDK overrides, custom package name, etc.) so the update uses identical settings automatically
- **Persistent clone settings** — all clone configuration is saved to internal storage per clone and recalled whenever an update is triggered
- **In-app self-update** — on Wi-Fi, the app checks [GitHub Releases](https://github.com/NetanelGuber/Apk-Cloner/releases) on startup; if a newer version is available a dialog shows the changelog with options to install the update or skip it until the next release
- **Auto-refresh** — app list reloads automatically after returning from any clone or update operation

## How It Works

```
Source APK
    │
    ├─ Extract base + split APKs
    ├─ Patch AndroidManifest.xml  (binary AXML)
    ├─ Patch resources.arsc       (binary ARSC)
    ├─ Patch icon colors          (bitmaps + vector XMLs + ARSC color entries)
    ├─ DEX work                   (deep clone / dual-DEX / pkg shim)
    ├─ Patch res/*.xml            (binary AXML)
    ├─ Patch assets/              (text files)
    ├─ Patch META-INF/            (kotlin_module, services)
    ├─ Patch .so libs             (C-string replacement)
    ├─ Assemble ZIP               (strip old signatures)
    ├─ 4-byte / 4096-byte align
    └─ V1 + V2 + V3 sign         (per-clone RSA-2048 keystore)
         │
         └─ Install via PackageInstaller or save to Downloads
```

### Icon Color Patching

Three complementary layers ensure full coverage across all icon styles:

| Layer | Handles |
|---|---|
| `IconPatcher` | PNG / WebP bitmap mipmaps |
| `VectorColorPatcher` | Inline ARGB values in VectorDrawable binary XML |
| `ResourcePatcher.patchIconLayerColors` | `@color/` resource entries in `resources.arsc` (simple colors and `ColorStateList`) |

All three layers apply the same BT.601 luminance-preserving hue-rotation matrix so that bitmap and vector portions of an adaptive icon shift identically and match the live preview.

## Building

Requirements: Android Studio / Gradle, JDK 17–22

```bash
./gradlew assembleDebug
```

Install to connected device:

```bash
./gradlew installDebug
```

## Permissions

| Permission | Purpose |
|---|---|
| `QUERY_ALL_PACKAGES` | Enumerate installed apps for the app list |
| `REQUEST_INSTALL_PACKAGES` | Install cloned APKs via PackageInstaller |
| `REQUEST_DELETE_PACKAGES` | Allow uninstall prompts |
| `READ_EXTERNAL_STORAGE` | Import APK files on Android < 10 |
| `WRITE_EXTERNAL_STORAGE` | Save output APKs on Android < 10 |
| `INTERNET` | Check GitHub Releases for app updates |
| `ACCESS_NETWORK_STATE` | Detect Wi-Fi before checking for updates |

## Project Structure

```
app/src/main/java/com/guber/apkcloner/
├── engine/
│   ├── CloneEngine.kt          # Orchestrates the full clone pipeline
│   ├── CloneSettings.kt        # Clone configuration data class
│   ├── ApkExtractor.kt         # Copies APKs from installed app
│   ├── FileApkParser.kt        # Parses .apk / .apkm / .xapk from SAF URI
│   ├── ManifestPatcher.kt      # Binary AXML manifest patching
│   ├── ResourcePatcher.kt      # ARSC resource patching + icon color patching
│   ├── DexPatcher.kt           # dexlib2-based DEX bytecode patching
│   ├── DualDexShimGenerator.kt # Generates shim DEX for dual-DEX mode
│   ├── PackageNameShimGenerator.kt  # Overrides getPackageName() at runtime
│   ├── IconPatcher.kt          # Hue/saturation/contrast on PNG/WebP icon bitmaps
│   ├── VectorColorPatcher.kt   # Hue/saturation/contrast on VectorDrawable binary XML
│   ├── XmlResourcePatcher.kt   # Patches res/*.xml binary files
│   ├── AssetsPatcher.kt        # Patches text files in assets/
│   ├── MetadataPatcher.kt      # Patches META-INF/ files
│   ├── NativeLibPatcher.kt     # Binary C-string patching in .so files
│   ├── ApkAssembler.kt         # Rebuilds the ZIP
│   ├── ZipAligner.kt           # 4-byte / 4096-byte alignment
│   ├── ApkSigner.kt            # V1+V2+V3 signing via apksig
│   ├── ApkInstaller.kt         # PackageInstaller session API
│   └── InstallResultReceiver.kt
├── ui/
│   ├── MainActivity.kt         # App list, file picker, clone settings dialog
│   ├── CloneProgressActivity.kt
│   └── AppListAdapter.kt
└── util/
    ├── FileUtils.kt                 # Work dir management, space checks
    ├── KeystoreUtils.kt             # BouncyCastle keystore/cert generation
    ├── PackageUtils.kt              # Installed app enumeration + update detection
    ├── CloneSettingsRepository.kt   # Persists/restores per-clone settings as JSON
    └── UpdateChecker.kt             # GitHub Releases API client + semver comparison

axml/                           # Forked pxb.android AXML/ARSC parser+writer (Java)
```

## Key Dependencies

| Library | Purpose |
|---|---|
| `com.android.tools.build:apksig:8.4.0` | APK signing (V1/V2/V3) |
| `org.bouncycastle:bcprov-jdk15on:1.70` | Keystore & cert generation |
| `org.smali:dexlib2:2.5.2` | DEX read/write for deep clone |
| `kotlinx-coroutines-android:1.7.3` | Async clone pipeline |
| AndroidX Lifecycle, Material Components | UI |

## Limitations

- Apps with certificate pinning will fail to connect to their servers after cloning (the clone has a different signing key)
- Apps that verify their own package integrity at runtime may crash or refuse to run
- System apps and apps with `sharedUserId` cannot be cloned
- Deep clone is slow on large APKs — DEX patching rewrites every string in every DEX file
