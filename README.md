# SpektraLab for Android

[简体中文](README.zh-CN.md) · **English**

An unofficial Android port of [SpektraLab](https://github.com/JamesQiu2005/SpektraLab), built on Andrea Volpato’s [spektrafilm](https://github.com/andreavolpato/spektrafilm) film-process model. Import a photograph or take one, develop it with film and paper profiles, and save the result locally. Processing runs on the device; no account or cloud upload is required. The current app interface is primarily Chinese; this documentation is bilingual.

**[Download the latest APK](https://github.com/Knonoa/SpektraLabAndroid/releases/latest)** · [1.2 release notes](SpektraDroid/docs/releases/1.2.md) · [RAW validation](SpektraDroid/docs/RAW_VALIDATION.md)

## What it does

- **Film and paper simulation:** 25 film stocks and 9 papers, exposure and enlarger controls, grain, halation, scattering, development couplers and scan sharpening. Native C++20 processing with a Kotlin / Jetpack Compose editor.
- **Film Edge:** 135, half-frame, XPan and 120 formats; gates, perforations, stock markings, frame numbers, fog and light leaks. Borders enter the negative’s exposure and development pipeline.
- **Date Back:** LCD-style or dot-matrix dates, plus available EXIF shooting data such as shutter speed, aperture and exposure compensation. The stock name appears on the film edge.
- **Capture page:** CameraX viewfinder, live film preview, flash and zoom; camera inventory shows public logical/physical cameras and their reported capabilities. Supported physical lenses can be requested explicitly.
- **Phone camera processing:** offers manufacturer CameraX extensions where exposed, with a Camera2 HDR scene-mode path on compatible devices. The system-camera button opens the phone’s camera and imports its returned image. Available modes depend on the device and what its camera app returns.
- **RAW and TIFF import:** DNG, Sony ARW, Nikon NEF, Canon CR2/CR3 and classic TIFF/TIF, alongside ordinary image imports. Native linear float images feed the film engine; an 8-bit display bitmap is not the RAW processing source.
- **Editing and output:** progressive 640/1600 px editor previews, zoom and split comparison, saved adjustment settings, cancellable JPEG export to `Pictures/SpektraLab`, with selectable output sizes up to a 4096 px source long edge. Film borders can enlarge the final canvas.

## Install

Android **8.0 / API 26 or later**. The universal APK contains **arm64-v8a, armeabi-v7a and x86_64**. Download the APK from [Releases](https://github.com/Knonoa/SpektraLabAndroid/releases), then allow installation from your browser or file manager when Android asks.

**Signing and upgrades:** from 1.3 on, releases are signed (in GitHub Actions) with the same key as 1.1, so **1.1 updates in place**. 1.2 used a different key: **1.2 users back up any settings they need and uninstall once** before installing 1.3 (uninstalling removes app settings/cache). Existing `.dev` / `.usb` developer builds are separate applications. See [signing](SpektraDroid/signing/README.md).

Version 1.2 uses application ID `com.spektralab.android`, version code `3`, with code shrinking and resource shrinking enabled. Camera permission is requested when opening capture; Android 8–9 also require storage permission to save exports.

## Use

1. Open a photo with the document picker, or share/open it from a gallery. RAW providers sometimes classify files as generic binary data; the picker accepts that type too.
2. Choose a film and paper; adjust exposure, enlarger settings and effects. Long-press to see the original, or use split comparison.
3. Open the **片边** tab for Film Edge and Date Back. Borders crop the image centrally to the selected gate.
4. Open **拍摄** to take a photo, optionally with live film preview. Use **系统相机** for modes available in the phone’s own camera application. The finished capture returns to the editor.
5. Export a JPEG to the system gallery.

## Verified RAW support and limits

Real public RAW samples were downloaded, SHA-256 checked against their source indexes, decoded at **640 and 1024 px**, processed through the float film engine and written as JPEG on an **arm64 Android 15 / API 35 emulator**:

| Camera | Tested sample mode |
|---|---|
| Sony A7R V | Reduced-size lossless-compressed RAW |
| Sony A7R VI | APS-C HQ ARW6 and full-frame lossless RAW |
| Sony A1 | Full-frame compressed RAW |
| Sony A1 II | Full-frame lossless Small RAW |
| Sony A7 V | APS-C HQ ARW6 |
| Sony A7 IV | APS-C uncompressed RAW |
| Nikon Z8 | HE-family high-quality compression |
| Nikon Z9 / Z6 III | HE-family compression |

All 10 samples from these 9 cameras passed, as did 5 additional ARW/NEF/CR2/CR3/DNG regression samples. The debug test suite passed **10 tests, zero failures and zero skips**. Full results, modes, timings and checksums are in [RAW_VALIDATION.md](SpektraDroid/docs/RAW_VALIDATION.md) and [raw-fixtures.json](SpektraDroid/docs/raw-fixtures.json). Release-package validation is recorded in [the 1.2 notes](SpektraDroid/docs/releases/1.2.md).

- Tested modes do not establish support for every RAW compression, crop, burst or orientation combination. Full-resolution / 4096 px RAW film export and device-specific performance have not been validated across these cameras.
- LibRaw `0.23.0-Snapshot202611` is pinned to an immutable official development snapshot for new Sony formats and Nikon HE/HE*. It is not a stable LibRaw release.
- RAW conversion uses LibRaw’s **16-bit linear compatible output**, then float32 resizing, colour conversion and film processing. Integer demosaicing and that policy’s highlight clipping still apply. Display and JPEG output are 8-bit sRGB; 16-bit TIFF export is not implemented.
- Large RAW files still require substantial memory. A full-frame A7R VI sample exposed an out-of-memory problem; releasing decoder buffers before bounded float allocation fixed the tested import path. Low-memory devices remain a separate validation target.
- Android exposes only the cameras and modes the phone manufacturer permits. Live film preview is unavailable in manufacturer extension sessions; HAL HDR can still support it. HDR/night behaviour and preview-to-capture agreement vary by phone. The app does not embed Google Camera or implement a manual multi-photo HDR merger.
- Half-frame pairs and an interactive crop tool are not implemented. This Android port uses a native CPU engine, so desktop GPU performance does not apply.

## Build

Open **`SpektraDroid/`** in Android Studio, or use the Gradle wrapper. Requirements: JDK 17, Android SDK 35, Build Tools 34.0.0, NDK `27.2.12479018`, CMake `3.22.1`, and Git. Gradle 8.7 and AGP 8.5.2 are specified in the project. Set `ANDROID_HOME` or create a local `local.properties` with `sdk.dir=…`.

```bash
git clone https://github.com/Knonoa/SpektraLabAndroid.git
cd SpektraLabAndroid/SpektraDroid
bash tools/setup_libraw.sh
./gradlew assembleDebug
```

`setup_libraw.sh` fetches and verifies LibRaw commit `0b87f5c907d7ee0e7641dd1b6a6c9140b9451922`. Override its location with `SPEKTRALAB_LIBRAW_SOURCE` only when pointing at the same clean commit. Engine tables and fonts are included; Python is unnecessary for ordinary builds or on the phone.

Debug output: `app/build/outputs/apk/debug/app-debug.apk`, package `com.spektralab.android.dev`. On macOS, `tools/build_install_mac.command` also builds and installs through adb. Signed release builds require private signing configuration; follow [RELEASING.md](SpektraDroid/docs/RELEASING.md).

```bash
./gradlew connectedDebugAndroidTest
```

Synthetic TIFF/DNG fixtures are included. External real-camera tests skip without developer-provided RAW samples; use `rawFixtureDir` and `rawFixtureCount` as described in the [validation guide](SpektraDroid/docs/RAW_VALIDATION.md) to require an exact sample count. Do not interpret a skipped fixture test as camera compatibility.

## Repository

| Path | Purpose |
|---|---|
| `SpektraDroid/app/src/main/java/` | Android editor, camera, state and image import/export |
| `SpektraDroid/app/src/main/cpp/` | Android engine, JNI, film edges and linear-image adapter |
| `SpektraDroid/app/src/main/assets/` | Baked engine data, fonts and licence texts |
| `SpektraDroid/app/src/androidTest/` | JNI, precision, RAW/TIFF and camera-inventory tests |
| `SpektraDroid/tools/` | Dependency setup, asset generation and parity tools |
| `engine/` | Shared upstream engine resources and native RAW/TIFF readers |
| `modern_UI/`, `windows_UI/`, `desktop/` | Retained upstream platform code; not the Android application |

Android development is on this repository’s `main` branch. Contributions should use focused commits, keep attribution/licences intact and state the checks actually run. Native border generation is documented in [HANDOFF.md](SpektraDroid/docs/HANDOFF.md); change its assembly sources rather than editing generated `overscan.cpp` directly.

## Credits, licences and name

- [spektrafilm](https://github.com/andreavolpato/spektrafilm), Andrea Volpato: film-process model, measured colour profiles and reference implementation. Additional black-and-white profiles: Hanze Qiu.
- [SpektraLab](https://github.com/JamesQiu2005/SpektraLab): native reference engine, Film Edge and Date Back. [Knonoa/SpektraLabAndroid](https://github.com/Knonoa/SpektraLabAndroid): Android port.
- Application and engine: **GPL-3.0-or-later**, see [LICENSE.txt](LICENSE.txt). Film/paper profiles and derived LUT data: **CC BY-SA 4.0**, with [upstream data terms](license/SPEKTRAFILM_LICENSE.txt).
- [LibRaw](https://github.com/LibRaw/LibRaw): LGPL-2.1 or CDDL; this distribution uses LGPL-2.1. TeX Gyre Heros: GUST Font License; DejaVu: its included font licence. stb: public-domain / MIT terms. AndroidX: Apache-2.0. Notices and texts are included in the APK assets and source tree.

This is an **unofficial Android fork**, with no claim of endorsement by the upstream authors. SpektraLab’s name and icon have separate upstream trademark terms; the source/data licences do not grant trademark rights. See the [upstream project](https://github.com/JamesQiu2005/SpektraLab#trademark) before redistributing a renamed or branded derivative.
