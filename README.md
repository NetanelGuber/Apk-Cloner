# APK Cloner

Android tool that clones installed apps (or imported APK/APKM/XAPK files) into independently-runnable copies with a different package name — no root required.

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
- Save output APK/ZIP to storage or install directly via `PackageInstaller`
- Dark mode support

## How It Works

```
Source APK
    │
    ├─ Extract base + split APKs
    ├─ Patch AndroidManifest.xml  (binary AXML)
    ├─ Patch resources.arsc       (binary ARSC)
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

## Building

Requirements: Android Studio / Gradle, JDK 17+

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

## Project Structure

```
app/src/main/java/com/guber/apkcloner/
├── engine/
│   ├── CloneEngine.kt          # Orchestrates the full clone pipeline
│   ├── CloneSettings.kt        # Clone configuration data class
│   ├── ApkExtractor.kt         # Copies APKs from installed app
│   ├── FileApkParser.kt        # Parses .apk / .apkm / .xapk from SAF URI
│   ├── ManifestPatcher.kt      # Binary AXML manifest patching
│   ├── ResourcePatcher.kt      # ARSC resource patching
│   ├── DexPatcher.kt           # dexlib2-based DEX bytecode patching
│   ├── DualDexShimGenerator.kt # Generates shim DEX for dual-DEX mode
│   ├── PackageNameShimGenerator.kt  # Overrides getPackageName() at runtime
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
    ├── FileUtils.kt            # Work dir management, space checks
    ├── KeystoreUtils.kt        # BouncyCastle keystore/cert generation
    └── PackageUtils.kt         # Installed app enumeration

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
