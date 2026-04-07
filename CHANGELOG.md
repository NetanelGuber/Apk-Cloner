# Changelog

All notable changes to APK Cloner are documented here.

---

## [0.4.5] - 2026-04-07

### Changed
- Update detection now uses **version name comparison only**. The previous two-step logic (check `versionCode` first, fall back to version name when it didn't increase) is replaced by a single left-to-right segment-by-segment version name comparison, which is consistent across all apps regardless of how their `versionCode` is managed.

---

## [0.4.4] - 2026-04-07

### Fixed
- Update detection now correctly identifies newer versions for apps that use CalVer-style version names where the build-number suffix can decrease across releases (e.g. `1.260420.20` vs `1.260330.27`). The check now falls back to a left-to-right segment-by-segment version name comparison when `versionCode` alone does not indicate an update.

---

## [0.4.3] - 2026-04-06

### Fixed

- Fixed native lib patcher as it could sometimes cause data corruption to file if it is exactly 1 byte longer than old package name.
- Fixed UI saying installation is in progress after pressing cancel on a save only clone.

---

## [0.4.2] - 2026-04-03

### Fixed
- Self-update check now runs on mobile data in addition to Wi-Fi.

---

## [0.4.1] - 2026-04-03

### Fixed
- "Open APK" button text now correctly centered.

---

## [0.4.0] - 2026-04-03

### Added
- **Clones tab** — the app list now has two tabs ("All Apps" / "Clones") so cloned apps can be viewed at a glance without scrolling through the full list.
- **Update detection** — on startup, the version code of each installed clone is compared against its original app. When the original has a newer version an UPDATE button appears inline next to the clone.
- **One-tap update with saved settings** — tapping UPDATE opens the clone dialog pre-filled with every setting used during the original clone (icon hue/saturation/contrast, DEX strategy, SDK overrides, custom package name, label, etc.), so updating is a single confirm instead of reconfiguring from scratch.
- **Persistent clone settings** — all clone configuration is automatically saved to internal storage (`filesDir/clone_settings/`) when a clone is created or updated, and recalled whenever an update is triggered.
- **In-app self-update** — when the device is on Wi-Fi, the app checks [GitHub Releases](https://github.com/NetanelGuber/Apk-Cloner/releases) on startup. If a newer version is found, a dialog shows the release changelog with "Update" (downloads and installs the APK) and "Skip" (suppresses the notification until the next release) options.
- **Auto-refresh** — the app list reloads automatically after returning from a clone or update operation.
- **Valid `.apkm` bundles** — split-APK saves now produce a proper `.apkm` archive containing `info.json` (package name, version, min API) and `icon.png` (extracted from the cloned APK), so file managers and APKMirror Installer display the app icon and metadata correctly.
- Search bar is now always visible in the toolbar (no longer collapses into the overflow menu).

### Changed
- Gradle wrapper upgraded from 8.7 → 8.9 (adds Java 22 support).

---

## [0.3.0] - 2025-03-??

### Added
- **Icon color customisation** — adjust hue, saturation, and contrast of the launcher icon with a live preview in the clone dialog.
- Three-layer icon patching: PNG/WebP bitmaps (`IconPatcher`), VectorDrawable binary XML (`VectorColorPatcher`), and `resources.arsc` color entries (`ResourcePatcher`).

---

## [0.2.0] - 2025-03-??

### Added
- **Deep Clone** — DEX bytecode patching for apps that hardcode their own package name.
- **Dual-DEX Shim** — alternative to Deep Clone; bundles a thin compatibility shim DEX alongside the original bytecode.
- **Package Name Shim** — runtime override of `getPackageName()` for apps that cache their package name.
- **Native lib patching** — binary C-string replacement in `.so` files.
- **SDK override** — independently override min SDK and target SDK in the clone manifest.
- Custom clone label and custom package name fields in the clone dialog.
- Install + Save mode: install the clone and save the APK to storage in one step.
- Expandable sections in the clone dialog (Compatibility / Manifest / Icon) to reduce visual clutter.
- Dark mode toggle.

---

## [0.1.3] - 2025-03-??

### Fixed
- 7 bug fixes (stability and compatibility improvements).

---

## [0.1.0] - 2025-03-??

### Added
- Initial release.
- Clone any installed app or imported `.apk` / `.apkm` / `.xapk` file into an independently-installable copy with a new package name — no root required.
- Standard clone strategy: manifest, resources, and metadata patching.
- Per-clone RSA-2048 keystore; V1 + V2 + V3 APK signing.
- Install via `PackageInstaller` or save to Downloads.
