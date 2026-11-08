# USB monitor and full-size RAW validation / 联机与全尺寸实测

2026-11-07. Development changes after v1.2, not a published release.
Android 15 / API 35 arm64 emulator, 10 GiB configured RAM (guest MemTotal
10,177,452 KiB), Apple silicon host. Timing is emulator-specific.

**No physical Sony camera was connected.** Protocol fixtures and demo frames
validate application behaviour, not USB electrical interoperability, firmware
compatibility, real stream rate, RAW+JPEG burst transfer or camera LCD mirroring.

## Full-size real RAW / 真实 RAW 全尺寸

Nine downloaded CC0 raw.pixls.us samples, complete-file SHA-256 verified against
the source index. Provenance, exact compression/crop mode, byte counts and hashes:
[full-raw-fixtures.json](full-raw-fixtures.json). RAW files remain outside Git.

Each sample: native metadata → advisory memory guard → maxEdge=0 decode →
assert original decoded dimensions → product-default film processing (including
spatial effects and grain) → quality 95 JPEG → read back JPEG dimensions.
No embedded JPEG source, resize, Film Edge gate crop, or OOM size retry.

| Sony model suffix | Decoded / JPEG pixels | Decode seconds | Total seconds | JPEG MB |
|---|---|---|---|---|
| 1 | 8660 × 5784 | 8.60 | 30.66 | 29.33 |
| 1M2 | 8660 × 5784 | 8.73 | 31.45 | 28.27 |
| 6700 | 6240 × 4168 | 4.46 | 15.01 | 12.54 |
| 7M3 | 6024 × 4024 | 4.11 | 13.79 | 13.06 |
| 7M4 | 7028 × 4688 | 5.60 | 19.51 | 17.90 |
| 7M5 | 7028 × 4688 | 8.27 | 22.91 | 13.53 |
| 7RM3 | 7968 × 5320 | 7.24 | 25.05 | 19.34 |
| 7RM4 | 9568 × 6376 | 10.11 | 37.61 | 21.68 |
| 7RM5 | 9564 × 6376 | 10.71 | 37.46 | 30.86 |

**9/9 passed**, one fixture test, 233.719 seconds, no skips. The test records
native heap *after* rendering; those numbers are not peak memory measurements.
The guard is advisory, and Android may terminate an app under memory pressure.
Full size means the decoder's oriented active image, excluding sensor margins;
it can differ from the manufacturer's JPEG dimensions. In-camera crop/reduced
RAW modes retain only their original pixels and cannot be restored to full frame.

## Protocol and media / 协议与媒体

`TetherTest` covers:

- Fragmented/coalesced USB containers, partial writes, zero-length USB packet termination, response/transaction/size
  errors and truncated streams.
- Sony old/new property datasets, 2024+ secondary enums and live JPEG offset
  with a false marker in the proprietary header.
- Handshake, card+PC/original settings, photo-ready protection, streamed original
  bytes and setting restoration on orderly disconnect.
- 480p and 720p MP4 with/without parameter overlay: 18 moving frames each,
  monotonic timestamps and one H.264 track, no audio. Decoding verifies motion,
  filtered colour and the chosen overlay.
- Demo photo reception, readable saved images, stop/finalize and reconnect.
- Synthetic full DNG dimensions, unsafe-memory refusal and editor original-size
  export while retaining the user's Film Edge setting.

UI inspection: USB entry, fixed preview with scrolling settings, live demo,
parameter/filter switches, recording, returning from background with saved-video
message, simulated shutter, received-photo editor entry, portrait/landscape layouts and scrollable original-size
export dialog. Demo is debug-only.

## Repeat / 复现

From `SpektraDroid/`, with SDK, JDK 17, pinned LibRaw and a high-memory emulator:

```sh
./gradlew assembleDebug assembleDebugAndroidTest lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r \
  -e class com.spektralab.android.TetherTest \
  com.spektralab.android.dev.test/androidx.test.runner.AndroidJUnitRunner
```

Place the nine full RAW files inside the app's readable cache on the test emulator,
then require the exact count (missing arguments skip this opt-in class; an
explicit directory with a missing or bad sample fails):

```sh
adb shell am instrument -w -r \
  -e class com.spektralab.android.FullRawExportTest \
  -e fullRawDir /data/user/0/com.spektralab.android.dev/cache/full-raw-fixtures \
  -e fullRawCount 9 \
  com.spektralab.android.dev.test/androidx.test.runner.AndroidJUnitRunner
```

The existing 15-fixture RAW regression uses `NativeImportTest` with
`rawFixtureDir` and `rawFixtureCount=15`; see [RAW_VALIDATION.md](RAW_VALIDATION.md).

## Build and test results / 构建与测试结果

- Debug: native engine/import plus tether tests passed **17/17** in 148.644 s,
  no skips; all **15/15** external RAW fixtures passed. After adding the editor
  full-size integration check and USB zero-packet regression, final debug
  tether checks passed **9/9** in 5.419 s. The zero-packet regression was first
  observed failing before the fix.
- Release: universal three-ABI APK, R8 and resource shrinking build succeeded.
  Native engine/import checks and **15/15** real RAW fixtures passed; independent
  test-boundary shrink issues were repaired with API keep rules, and the final
  tether suite passed **9/9** in 5.424 s, no skips.
- Debug and release lint: **zero errors**, 17 warnings each (including conservative
  usable-space checks and existing project warnings).
- The opt-in full-size nine-sample test above passed independently.

## Physical validation still required / 实体测试待办

For every requested model/firmware and Android phone/cable pair: permission,
connect/reconnect, preview dimensions/rate/latency, camera shutter and continuous
RAW+JPEG transfer, both recording overlay choices, clean feed availability,
background/disconnect finalization, low-storage errors, and camera settings
after orderly disconnect or cable removal. Check card originals remain intact.
